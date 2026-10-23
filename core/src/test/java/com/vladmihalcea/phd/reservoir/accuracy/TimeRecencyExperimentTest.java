package com.vladmihalcea.phd.reservoir.accuracy;

import com.vladmihalcea.phd.reservoir.api.Clock;
import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.catalog.StrategyCatalog;
import com.vladmihalcea.phd.reservoir.util.CsvWriter;
import com.vladmihalcea.phd.reservoir.util.Normal;
import com.vladmihalcea.phd.reservoir.util.Statistics;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Experiment E9 of the journal extension (RQ6, recency): how many <em>seconds</em> each production recorder needs
 * to show a change in the latency distribution, and how long it keeps showing an incident that is over, at the
 * request rates of a server with 200 platform threads and of one with thousands of virtual threads. The survey's
 * E8 counted post-shift updates at one fixed rate; but forward decay (Dropwizard, &alpha; per second), the
 * sliding window (5 minutes), Micrometer's rotating window (2 minutes), and the VictoriaMetrics swap (5 minutes)
 * all forget on time, while the uniform reservoir forgets on count, so the rate decides which one reacts first.
 * <p>
 * Every recorder follows one logical clock ({@link StrategyCatalog#createOnClock}, so Micrometer rotates too),
 * and requests arrive evenly at {@code rate} per second. The scenarios, with a baseline regime (lognormal, median
 * 3 ms, &sigma; = 0.6) and a degraded one (median 30 ms):
 * <ul>
 *   <li>{@code step-up}: 10 minutes of baseline, then 10 of degraded latency (an incident starts);</li>
 *   <li>{@code step-down}: 10 minutes degraded, then 10 of baseline (the incident ends);</li>
 *   <li>{@code spike}: baseline with 30 seconds of degraded latency at minute 10;</li>
 *   <li>{@code spike-then-quiet}: as {@code spike}, but after the spike the traffic drops to 1 request per second
 *       for 10 minutes, as at night, which is when a recorder that forgets on count, or one that decays only on
 *       update, keeps reporting the incident.</li>
 * </ul>
 * Every {@code rs.recency.stepSeconds} (5 s) of logical time it reads p99 and p99.9 from each recorder. The truth
 * is the analytic quantile of the regime in force at that moment. Per regime change, it reports the seconds until
 * p99 is first within 10% of the new truth ({@code converge_s}, empty if never within the run), the seconds after
 * which it stays within 20% until the regime changes again ({@code settle_s}, robust to noise that touches the 10%
 * band early), and for the changes to
 * the degraded regime, the seconds until p99 crosses twice the baseline p99, the threshold of a typical alert
 * ({@code alarm_s}). For the spikes, it reports the highest p99 shown during and after the spike relative to the
 * degraded p99 ({@code peak_ratio}).
 * <p>
 * Outputs under {@code metrics/TimeRecency/}: {@code recency-series-<scenario>-r<rate>.csv} (mean p99 per recorder
 * over the repetitions, chart-ready), {@code recency-summary.csv}, and {@code recency-events.csv} (one row per
 * repetition). Rates are {@code rs.recency.rates} (100,1000,10000) and repetitions {@code rs.reps} (10); the
 * 10,000-per-second runs dominate the time (about 12 million updates per recorder and repetition).
 *
 * @author Vlad Mihalcea
 */
public class TimeRecencyExperimentTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(TimeRecencyExperimentTest.class);

    private static final int REPS = Integer.getInteger("rs.reps", 10);
    private static final long STEP_SECONDS = Long.getLong("rs.recency.stepSeconds", 5);
    private static final int[] RATES = parseRates(System.getProperty("rs.recency.rates", "100,1000,10000"));
    private static final long BASE_SEED = 12_000L;

    static final double SIGMA = 0.6;
    static final Regime BASELINE = new Regime("baseline", Math.log(3.0e6), SIGMA);
    static final Regime DEGRADED = new Regime("degraded", Math.log(30.0e6), SIGMA);
    static final double CONVERGED = 0.10;
    static final double SETTLED = 0.20;

    /** A lognormal latency regime in nanoseconds, with its analytic quantiles as the truth. */
    record Regime(String name, double mu, double sigma) {
        long sample(SplittableRandom random) {
            return Math.max(1, (long) Math.exp(mu + sigma * random.nextGaussian()));
        }

        double quantile(double q) {
            return Math.exp(mu + sigma * Normal.inverseCdf(q));
        }
    }

    /** One stretch of a scenario: a regime at a request rate for a duration. */
    record Segment(Regime regime, long seconds, double rate) {
    }

    record Scenario(String name, List<Segment> segments) {
    }

    static List<Scenario> scenarios(int rate) {
        return List.of(
                new Scenario("step-up", List.of(new Segment(BASELINE, 600, rate), new Segment(DEGRADED, 600, rate))),
                new Scenario("step-down", List.of(new Segment(DEGRADED, 600, rate), new Segment(BASELINE, 600, rate))),
                new Scenario("spike", List.of(new Segment(BASELINE, 600, rate), new Segment(DEGRADED, 30, rate),
                        new Segment(BASELINE, 600, rate))),
                new Scenario("spike-then-quiet", List.of(new Segment(BASELINE, 600, rate),
                        new Segment(DEGRADED, 30, rate), new Segment(BASELINE, 600, 1))));
    }

    @Test
    public void measureTimeRecency() {
        try (CsvWriter events = new CsvWriter(CsvWriter.metricsPath("TimeRecency", "recency-events.csv"),
                "scenario", "rate", "recorder", "rep", "event", "event_s", "to_regime", "converge_s", "settle_s",
                "alarm_s", "peak_ratio");
             CsvWriter summary = new CsvWriter(CsvWriter.metricsPath("TimeRecency", "recency-summary.csv"),
                     "scenario", "rate", "recorder", "event", "to_regime", "converge_s", "converge_ci95",
                     "converged_reps", "settle_s", "settle_ci95", "settled_reps", "alarm_s", "alarm_ci95",
                     "alarmed_reps", "peak_ratio", "peak_ci95", "reps")) {
            for (int rate : RATES) {
                for (Scenario scenario : scenarios(rate)) {
                    runScenario(scenario, rate, events, summary);
                }
            }
        }
    }

    private void runScenario(Scenario scenario, int rate, CsvWriter events, CsvWriter summary) {
        long totalSeconds = 0;
        for (Segment segment : scenario.segments()) {
            totalSeconds += segment.seconds();
        }
        int checkpoints = (int) (totalSeconds / STEP_SECONDS);
        List<String> recorders = StrategyCatalog.RECENCY_RECORDERS;
        Map<String, double[]> meanP99 = new LinkedHashMap<>();
        double[] truthP99 = new double[checkpoints];

        for (String name : recorders) {
            double[] sum = new double[checkpoints];
            List<EventStats> eventStats = new ArrayList<>();
            for (int rep = 0; rep < REPS; rep++) {
                long seed = BASE_SEED + 101L * rep;
                Clock.Manual clock = new Clock.Manual(TimeUnit.SECONDS.toNanos(1));
                SamplingStrategy recorder = StrategyCatalog.createOnClock(
                        name, StrategyCatalog.DEFAULT_K, clock, StrategyCatalog.strategySeed(seed));
                double[] series = replay(scenario, recorder, clock, seed, truthP99, STEP_SECONDS, 0.99)[0];
                for (int i = 0; i < checkpoints; i++) {
                    sum[i] += series[i];
                }
                List<double[]> repEvents = events(scenario, series);
                for (int e = 0; e < repEvents.size(); e++) {
                    if (eventStats.size() <= e) {
                        eventStats.add(new EventStats());
                    }
                    double[] event = repEvents.get(e);
                    eventStats.get(e).add(event);
                    events.writeRow(scenario.name(), rate, name, rep, e, event[0], regimeAfter(scenario, e).name(),
                            blankIfNaN(event[1]), blankIfNaN(event[4]), blankIfNaN(event[2]), blankIfNaN(event[3]));
                }
            }
            for (int i = 0; i < checkpoints; i++) {
                sum[i] /= REPS;
            }
            meanP99.put(name, sum);
            for (int e = 0; e < eventStats.size(); e++) {
                EventStats stats = eventStats.get(e);
                summary.writeRow(scenario.name(), rate, name, e, regimeAfter(scenario, e).name(),
                        blankIfNaN(stats.converge.mean()), stats.converge.ci95HalfWidth(), stats.converge.count(),
                        blankIfNaN(stats.settle.mean()), stats.settle.ci95HalfWidth(), stats.settle.count(),
                        blankIfNaN(stats.alarm.mean()), stats.alarm.ci95HalfWidth(), stats.alarm.count(),
                        blankIfNaN(stats.peak.mean()), stats.peak.ci95HalfWidth(), REPS);
            }
            LOGGER.info("Recency {} at {}/s done for {}", scenario.name(), rate, name);
        }

        String[] header = new String[recorders.size() + 2];
        header[0] = "t_s";
        header[1] = "truth_p99_ns";
        for (int r = 0; r < recorders.size(); r++) {
            header[r + 2] = recorders.get(r);
        }
        try (CsvWriter series = new CsvWriter(CsvWriter.metricsPath("TimeRecency",
                "recency-series-" + scenario.name() + "-r" + rate + ".csv"), header)) {
            for (int i = 0; i < checkpoints; i++) {
                Object[] row = new Object[recorders.size() + 2];
                row[0] = (i + 1) * STEP_SECONDS;
                row[1] = truthP99[i];
                for (int r = 0; r < recorders.size(); r++) {
                    row[r + 2] = meanP99.get(recorders.get(r))[i];
                }
                series.writeRow(row);
            }
        }
    }

    /**
     * Replays the scenario into the recorder and returns, per requested quantile, its estimate at every checkpoint,
     * one every {@code stepSeconds} of logical time; {@code truthP99}, if not null, receives the analytic p99 of the
     * regime in force at each checkpoint.
     */
    static double[][] replay(Scenario scenario, SamplingStrategy recorder, Clock.Manual clock, long seed,
                             double[] truthP99, long stepSeconds, double... quantiles) {
        SplittableRandom random = new SplittableRandom(seed);
        long start = clock.nanoTime();
        long stepNanos = TimeUnit.SECONDS.toNanos(stepSeconds);
        long nextCheckpoint = start + stepNanos;
        int checkpoint = 0;
        List<double[]> series = new ArrayList<>();
        long segmentStart = start;
        for (Segment segment : scenario.segments()) {
            long segmentEnd = segmentStart + TimeUnit.SECONDS.toNanos(segment.seconds());
            double interval = 1e9 / segment.rate();
            double t = segmentStart + interval;
            while (true) {
                long arrival = (long) t;
                while (nextCheckpoint <= Math.min(arrival, segmentEnd)) {
                    clock.set(nextCheckpoint);
                    double[] estimates = new double[quantiles.length];
                    for (int q = 0; q < quantiles.length; q++) {
                        estimates[q] = recorder.valueAtQuantile(quantiles[q]);
                    }
                    series.add(estimates);
                    if (truthP99 != null && checkpoint < truthP99.length) {
                        truthP99[checkpoint] = segment.regime().quantile(0.99);
                    }
                    checkpoint++;
                    nextCheckpoint += stepNanos;
                }
                if (arrival >= segmentEnd) {
                    break;
                }
                clock.set(arrival);
                recorder.update(segment.regime().sample(random), arrival);
                t += interval;
            }
            segmentStart = segmentEnd;
        }
        double[][] out = new double[quantiles.length][series.size()];
        for (int i = 0; i < series.size(); i++) {
            for (int q = 0; q < quantiles.length; q++) {
                out[q][i] = series.get(i)[q];
            }
        }
        return out;
    }

    /**
     * For each regime change: {seconds of the change since the start, converge_s, alarm_s, peak_ratio, settle_s},
     * where the last four are NaN when they do not apply or never happen within the run.
     */
    static List<double[]> events(Scenario scenario, double[] series) {
        List<double[]> out = new ArrayList<>();
        double alarm = 2 * BASELINE.quantile(0.99);
        long changeSeconds = 0;
        List<Segment> segments = scenario.segments();
        for (int s = 0; s < segments.size() - 1; s++) {
            changeSeconds += segments.get(s).seconds();
            Segment after = segments.get(s + 1);
            if (after.regime() == segments.get(s).regime()) {
                continue;
            }
            long endSeconds = changeSeconds + after.seconds();
            double truth = after.regime().quantile(0.99);
            double converge = Double.NaN;
            double alarmAt = Double.NaN;
            double peak = Double.NaN;
            boolean spike = after.regime() == DEGRADED && after.seconds() < 60;
            // A spike may raise the alarm after it ended, since the recorders lag: look 5 minutes further.
            long alarmEnd = spike ? endSeconds + 300 : endSeconds;
            for (int i = 0; i < series.length; i++) {
                long t = (i + 1) * STEP_SECONDS;
                if (t <= changeSeconds) {
                    continue;
                }
                if (after.regime() == DEGRADED && t <= alarmEnd && Double.isNaN(alarmAt) && series[i] >= alarm) {
                    alarmAt = t - changeSeconds;
                }
                if (t <= endSeconds && Double.isNaN(converge)
                        && Math.abs(series[i] - truth) / truth <= CONVERGED) {
                    converge = t - changeSeconds;
                }
            }
            if (spike) {
                // A spike: the highest p99 shown from its start until 5 minutes after it ended.
                double best = 0;
                for (int i = 0; i < series.length; i++) {
                    long t = (i + 1) * STEP_SECONDS;
                    if (t > changeSeconds && t <= endSeconds + 300) {
                        best = Math.max(best, series[i]);
                    }
                }
                peak = best / truth;
            }
            // Settled: the earliest checkpoint from which every later one, up to the next change, is within 20%.
            double settle = Double.NaN;
            for (int i = series.length - 1; i >= 0; i--) {
                long t = (i + 1) * STEP_SECONDS;
                if (t <= changeSeconds) {
                    break;
                }
                if (t > endSeconds) {
                    continue;
                }
                if (Math.abs(series[i] - truth) / truth > SETTLED) {
                    break;
                }
                settle = t - changeSeconds;
            }
            out.add(new double[]{changeSeconds, converge, alarmAt, peak, settle});
        }
        return out;
    }

    private static Regime regimeAfter(Scenario scenario, int event) {
        int seen = -1;
        List<Segment> segments = scenario.segments();
        for (int s = 0; s < segments.size() - 1; s++) {
            if (segments.get(s + 1).regime() != segments.get(s).regime() && ++seen == event) {
                return segments.get(s + 1).regime();
            }
        }
        throw new IllegalArgumentException("No event " + event + " in " + scenario.name());
    }

    private static final class EventStats {
        final Statistics converge = new Statistics();
        final Statistics alarm = new Statistics();
        final Statistics peak = new Statistics();
        final Statistics settle = new Statistics();

        void add(double[] event) {
            if (!Double.isNaN(event[1])) {
                converge.add(event[1]);
            }
            if (!Double.isNaN(event[2])) {
                alarm.add(event[2]);
            }
            if (!Double.isNaN(event[3])) {
                peak.add(event[3]);
            }
            if (!Double.isNaN(event[4])) {
                settle.add(event[4]);
            }
        }
    }

    private static Object blankIfNaN(double value) {
        return Double.isNaN(value) ? "" : value;
    }

    private static int[] parseRates(String spec) {
        String[] parts = spec.split(",");
        int[] rates = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            rates[i] = Integer.parseInt(parts[i].trim());
        }
        return rates;
    }
}
