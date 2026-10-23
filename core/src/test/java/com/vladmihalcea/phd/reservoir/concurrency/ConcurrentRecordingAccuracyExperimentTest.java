package com.vladmihalcea.phd.reservoir.concurrency;

import com.vladmihalcea.phd.reservoir.api.Clock;
import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.baseline.ExactQuantileBaseline;
import com.vladmihalcea.phd.reservoir.catalog.StrategyCatalog;
import com.vladmihalcea.phd.reservoir.framework.DropwizardExpDecay;
import com.vladmihalcea.phd.reservoir.framework.DropwizardLockFreeExpDecay;
import com.vladmihalcea.phd.reservoir.framework.DropwizardSlidingTimeWindow;
import com.vladmihalcea.phd.reservoir.framework.DropwizardUniform;
import com.vladmihalcea.phd.reservoir.util.CsvWriter;
import com.vladmihalcea.phd.reservoir.util.Proportions;
import com.vladmihalcea.phd.reservoir.util.Statistics;
import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Experiment V7 of the journal extension: the accuracy of the shared production recorders
 * ({@link StrategyCatalog#CONCURRENT_RECORDERS}) while thousands of concurrent requests record into them, live
 * and on the wall clock. The survey's accuracy experiments (E6 to E8) fed one recorder from one thread; V2
 * replayed captured traces offline. Here, 200 platform threads (a default Tomcat connector) or 1,000 or 10,000
 * platform or virtual threads each loop for {@code rs.live.seconds} (30 s): block for 1 ms in
 * {@link Thread#sleep(Duration)}, then record the next value of their own seeded stream.
 * <p>
 * The stream is a request-latency distribution with a timeout-level tail: a lognormal body (median 2 ms,
 * &sigma; = 0.5) and, with a probability from {@code rs.live.stallProbabilities} (0.2%, 0.05%, 0.01%, and 0.001%), a
 * stall drawn uniformly from 0.5 to 5 s. At 0.2% the p99.9 lies among the stalls and every fixed-size reservoir keeps
 * about two of them, so all recorders show one; the rarer stalls ask how often a recorder shows a stall at all, which
 * for a uniform reservoir of {@code k} samples out of {@code n} values with {@code s} stalls is
 * {@code 1 - C(n - s, k) / C(n, k)} ({@link Proportions#uniformReservoirHit}), about {@code 1 - exp(-k s / n)}. The
 * values are deterministic, so the ground truth needs no recording during the run: each client counts the values it
 * recorded, and the streams are regenerated afterwards into the baseline. The run therefore measures the recorder
 * alone.
 * <p>
 * The baseline is an HdrHistogram with five significant digits (a resolution of 0.001%), finer than every recorder
 * under test, so the three-digit HdrHistogram and Micrometer recorders are scored on their own resolution instead of
 * matching a baseline of the same precision; the max is exact.
 * <p>
 * Per recorder, load, stall probability, and repetition, it reports the relative error of p50, p99 and p99.9 against
 * the baseline, the reported max ({@link StrategyCatalog#reportedTop}) against the true max, whether a stall shows in
 * the reported max at all, the uniform-reservoir probability of showing one, the update rate, how many clients
 * progressed, and the retained sample count of the Dropwizard reservoirs, which reveals whether concurrent updates keep
 * the reservoir at its size. The summary has the share of the repetitions that showed a stall with its 95% Wilson
 * interval. Since the stream is stationary, the whole-run truth also scores the decaying and windowed recorders
 * ({@code metrics/ConcurrentAccuracy/}).
 *
 * @author Vlad Mihalcea
 */
public class ConcurrentRecordingAccuracyExperimentTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConcurrentRecordingAccuracyExperimentTest.class);

    private static final long SECONDS = Long.getLong("rs.live.seconds", 30);
    private static final int REPS = Integer.getInteger("rs.reps", 3);
    private static final Duration IO = Duration.ofMillis(1);
    private static final long BASE_SEED = 9_000L;

    static final double[] STALL_PROBABILITIES = Arrays.stream(
                    System.getProperty("rs.live.stallProbabilities", "0.002,0.0005,0.0001,0.00001").split(","))
            .map(String::trim).mapToDouble(Double::parseDouble).toArray();
    static final long STALL_MIN_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
    static final long STALL_MAX_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final double BODY_MU = Math.log(2.0 * 1_000_000);
    private static final double BODY_SIGMA = 0.5;

    private record Load(String kind, int clients) {
    }

    private static final Load[] LOADS = {
            new Load("platform", 200),
            new Load("platform", 1_000),
            new Load("virtual", 1_000),
            new Load("platform", 10_000),
            new Load("virtual", 10_000),
    };

    /** The next value of a client's stream: a lognormal body with rare timeout-level stalls. */
    static long next(SplittableRandom random, double stallProbability) {
        if (random.nextDouble() < stallProbability) {
            return random.nextLong(STALL_MIN_NANOS, STALL_MAX_NANOS);
        }
        return Math.max(1, (long) Math.exp(BODY_MU + BODY_SIGMA * random.nextGaussian()));
    }

    @Test
    public void measureConcurrentRecordingAccuracy() throws Exception {
        try (CsvWriter out = new CsvWriter(CsvWriter.metricsPath("ConcurrentAccuracy", "concurrent-accuracy.csv"),
                "recorder", "thread_kind", "clients", "stall_probability", "rep", "updates", "updates_per_sec",
                "clients_progressed", "true_p50_ns", "true_p99_ns", "true_p999_ns", "true_max_ns", "true_stalls",
                "p50_rel_error", "p99_rel_error", "p999_rel_error", "reported_max_ns", "max_ratio", "stall_visible",
                "uniform_model_visible", "retained_samples");
             CsvWriter summary = new CsvWriter(CsvWriter.metricsPath("ConcurrentAccuracy", "concurrent-accuracy-summary.csv"),
                     "recorder", "thread_kind", "clients", "stall_probability", "p99_rel_error", "p99_ci95",
                     "p999_rel_error", "p999_ci95", "max_ratio", "stall_visible_reps", "reps", "visible_ci95_low",
                     "visible_ci95_high", "uniform_model_visible", "true_stalls_mean", "updates_per_sec")) {
            int seedBlock = 0;
            for (double stallProbability : STALL_PROBABILITIES) {
                for (String name : StrategyCatalog.CONCURRENT_RECORDERS) {
                    for (Load load : LOADS) {
                        Statistics p99 = new Statistics();
                        Statistics p999 = new Statistics();
                        Statistics maxRatio = new Statistics();
                        Statistics rate = new Statistics();
                        Statistics model = new Statistics();
                        Statistics stalls = new Statistics();
                        int visible = 0;
                        for (int rep = 0; rep < REPS; rep++) {
                            // The same seeds for every recorder and load at one stall probability, other seeds at the
                            // next, so the probabilities do not share their stall positions.
                            long seed = BASE_SEED + 1_000L * rep + 100_000L * seedBlock;
                            SamplingStrategy recorder = StrategyCatalog.create(
                                    name, StrategyCatalog.DEFAULT_K, Clock.SYSTEM, StrategyCatalog.DEFAULT_SEED);
                            long[] counts = run(recorder, load, seed, stallProbability);
                            Truth truth = truth(counts, seed, stallProbability);

                            double estP50 = recorder.valueAtQuantile(0.5);
                            double estP99 = recorder.valueAtQuantile(0.99);
                            double estP999 = recorder.valueAtQuantile(0.999);
                            double estMax = StrategyCatalog.reportedTop(recorder);
                            boolean stallVisible = truth.stalls > 0 && estMax >= STALL_MIN_NANOS;
                            double uniform = Proportions.uniformReservoirHit(truth.count, truth.stalls,
                                    StrategyCatalog.DEFAULT_K);
                            long updates = truth.count;
                            int progressed = 0;
                            for (long count : counts) {
                                if (count > 0) {
                                    progressed++;
                                }
                            }
                            double e99 = ExactQuantileBaseline.relativeError(estP99, truth.p99);
                            double e999 = ExactQuantileBaseline.relativeError(estP999, truth.p999);
                            double ratio = estMax / truth.max;
                            out.writeRow(name, load.kind(), load.clients(), stallProbability, rep, updates,
                                    updates / (double) SECONDS, progressed, truth.p50, truth.p99, truth.p999,
                                    truth.max, truth.stalls, ExactQuantileBaseline.relativeError(estP50, truth.p50),
                                    e99, e999, estMax, ratio, stallVisible, uniform, retained(recorder));
                            p99.add(e99);
                            p999.add(e999);
                            maxRatio.add(ratio);
                            rate.add(updates / (double) SECONDS);
                            model.add(uniform);
                            stalls.add(truth.stalls);
                            if (stallVisible) {
                                visible++;
                            }
                        }
                        double[] ci = Proportions.wilson95(visible, REPS);
                        summary.writeRow(name, load.kind(), load.clients(), stallProbability, p99.mean(),
                                p99.ci95HalfWidth(), p999.mean(), p999.ci95HalfWidth(), maxRatio.mean(), visible, REPS,
                                ci[0], ci[1], model.mean(), stalls.mean(), rate.mean());
                        LOGGER.info("{} with {} {} clients, stalls {}: p99 error {}, p99.9 error {}, max ratio {}, "
                                        + "stall visible {}/{} (uniform model {}), {} updates/s",
                                name, load.clients(), load.kind(), stallProbability,
                                String.format("%.3f", p99.mean()), String.format("%.3f", p999.mean()),
                                String.format("%.3f", maxRatio.mean()), visible, REPS,
                                String.format("%.3f", model.mean()), String.format("%.0f", rate.mean()));
                    }
                }
                seedBlock++;
            }
        }
    }

    /** Runs the clients for {@link #SECONDS} and returns how many values each one recorded. */
    private static long[] run(SamplingStrategy recorder, Load load, long seed, double stallProbability)
            throws InterruptedException {
        long[] counts = new long[load.clients()];
        Thread[] threads = new Thread[load.clients()];
        CountDownLatch ready = new CountDownLatch(load.clients());
        CountDownLatch go = new CountDownLatch(1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SECONDS) + TimeUnit.SECONDS.toNanos(1);
        long[] stopAt = {0};
        Thread.Builder builder = "virtual".equals(load.kind())
                ? Thread.ofVirtual().name("vt-client-", 0)
                : Thread.ofPlatform().daemon(true).name("pt-client-", 0);
        for (int i = 0; i < load.clients(); i++) {
            final int client = i;
            threads[i] = builder.start(() -> {
                SplittableRandom random = new SplittableRandom(seed + client);
                long recorded = 0;
                ready.countDown();
                try {
                    go.await();
                    long end = stopAt[0];
                    while (System.nanoTime() < end) {
                        Thread.sleep(IO);
                        recorder.update(next(random, stallProbability));
                        recorded++;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                counts[client] = recorded;
            });
        }
        ready.await();
        stopAt[0] = System.nanoTime() + TimeUnit.SECONDS.toNanos(SECONDS);
        go.countDown();
        for (Thread thread : threads) {
            thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()) + 10_000));
        }
        return counts;
    }

    private record Truth(long count, double p50, double p99, double p999, long max, long stalls) {
    }

    /**
     * Regenerates every client's first {@code counts[i]} values. A histogram with five significant digits is
     * the baseline for the quantiles (0.001% resolution, below the resolution of every recorder under test), and the
     * max is exact.
     */
    private static Truth truth(long[] counts, long seed, double stallProbability) {
        Histogram histogram = new Histogram(1, STALL_MAX_NANOS * 2, 5);
        long max = 0;
        long stalls = 0;
        long total = 0;
        for (int client = 0; client < counts.length; client++) {
            SplittableRandom random = new SplittableRandom(seed + client);
            for (long i = 0; i < counts[client]; i++) {
                long value = next(random, stallProbability);
                histogram.recordValue(Math.min(value, STALL_MAX_NANOS * 2));
                max = Math.max(max, value);
                if (value >= STALL_MIN_NANOS) {
                    stalls++;
                }
            }
            total += counts[client];
        }
        return new Truth(total, histogram.getValueAtPercentile(50), histogram.getValueAtPercentile(99),
                histogram.getValueAtPercentile(99.9), max, stalls);
    }

    private static String retained(SamplingStrategy strategy) {
        if (strategy instanceof DropwizardExpDecay s) {
            return String.valueOf(s.retained());
        }
        if (strategy instanceof DropwizardLockFreeExpDecay s) {
            return String.valueOf(s.retained());
        }
        if (strategy instanceof DropwizardUniform s) {
            return String.valueOf(s.retained());
        }
        if (strategy instanceof DropwizardSlidingTimeWindow s) {
            return String.valueOf(s.retained());
        }
        return "";
    }
}
