package com.vladmihalcea.phd.reservoir.accuracy;

import com.codahale.metrics.Snapshot;
import com.vladmihalcea.phd.reservoir.api.Clock;
import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.baseline.ExactQuantileBaseline;
import com.vladmihalcea.phd.reservoir.framework.DropwizardExpDecay;
import com.vladmihalcea.phd.reservoir.framework.DropwizardLockFreeExpDecay;
import com.vladmihalcea.phd.reservoir.framework.DropwizardUniform;
import com.vladmihalcea.phd.reservoir.util.CsvWriter;
import com.vladmihalcea.phd.reservoir.util.Normal;
import com.vladmihalcea.phd.reservoir.util.Statistics;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Experiment E10 of the journal extension (RQ5, claim C5): the forward-decay parameter {@code alpha = 0.015} of
 * Dropwizard Metrics, measured on the library itself, in both its {@code ExponentiallyDecayingReservoir} (the
 * default of Dropwizard timers and of FlexyPool) and its {@code LockFreeExponentiallyDecayingReservoir}. E7 and
 * E7b swept alpha on the clean-room {@code ForwardDecayReservoir}; this experiment tests what the library's
 * Javadoc promises for its defaults, that a reservoir of 1028 elements "offers a 99.9% confidence level with a 5%
 * margin of error assuming a normal distribution", and that alpha = 0.015 "heavily biases the reservoir to the
 * past 5 minutes of measurements". Every part drives the reservoirs from a logical clock, so it is deterministic
 * up to the reservoirs' own {@code ThreadLocalRandom}.
 * <ol>
 *   <li><b>A, the window that alpha gives</b> ({@code alpha-window.csv}). A steady stream at 100, 1,000, or 10,000
 *       requests per second for 50 minutes, whose values are their arrival times in milliseconds, so the snapshot
 *       is a distribution of ages. It reports the weighted median and p90 age and the weighted share of the last
 *       5 minutes (what {@code getValue(q)} computes from), the same for the retained samples unweighted
 *       ({@code getValues()}), and the forward-decay theory: a weight {@code exp(-alpha * age)} gives a median age
 *       of {@code ln 2 / alpha} (46 s at 0.015) and a 5-minute share of {@code 1 - exp(-300 alpha)}, truncated at
 *       the length of the run. The reservoir samples by priority {@code weight / u} and the snapshot then weighs
 *       the sample by the same weights again, so the measured ages show how much more recent than the theory the
 *       reported quantiles are.</li>
 *   <li><b>B, the Javadoc's confidence claim</b> ({@code alpha-claim.csv}). A stationary lognormal (median 3 ms,
 *       &sigma; = 0.6) at 1,000 per second for 10 minutes, {@code rs.alpha.claimReps} (300) times. Per quantile,
 *       the rank of the estimate under the true distribution gives the rank error {@code |F(estimate) - q|}; the
 *       claim read as a rank margin holds when 99.9% of the repetitions are within 0.05. Since a 0.05 rank margin
 *       around p99 spans p94 to p100, it also reports the rank error relative to the tail mass,
 *       {@code |F(estimate) - q| / (1 - q)}, and the share of repetitions within 5% of the true value. The
 *       uniform reservoir of the same size is the reference, since the claim's normal-approximation argument
 *       assumes a uniform sample.</li>
 *   <li><b>C, the alpha trade-off on the library</b> ({@code alpha-tradeoff.csv}). Alpha from 0.001 to 0.1 on the
 *       step-up scenario of E9 at 1,000 per second: the mean p99 and p99.9 error over the last 5 minutes of the
 *       baseline (the steady-state noise that alpha leaves) against the seconds until p99 is within 10% after
 *       the shift (its speed of adaptation): first within in each repetition ({@code converge_s}, the mean over the
 *       repetitions), and the first checkpoint from which the mean error over the repetitions stays within until the
 *       end ({@code settle_s}, E7b's rule, since a noisy estimate dips into the bound before it stays there), and
 *       {@code m = rate / (alpha k)}, the values per reservoir slot that carry most of the decayed weight.</li>
 *   <li><b>D, the hourly rescale</b> ({@code alpha-rescale.csv}, {@code alpha-rescale-series-<impl>.csv}). Both
 *       reservoirs renormalize their weights {@code exp(alpha * t)} once an hour; until then {@code t} grows to
 *       3,600 s, so {@code exp(alpha * t)} exceeds the largest double once alpha is above {@code ln(Double.MAX)
 *       / 3600 = 0.197}. Three hours at 100 per second, baseline until minute 50 and degraded latency after,
 *       for alpha in {0.015, 0.1, 0.19, 0.2, 0.3}: the number of checkpoints whose p99 is not a finite positive
 *       number, the longest run of checkpoints with an unchanged p99 (a reservoir that admits no new sample
 *       freezes), the seconds to converge after the shift, and the p99 change across each rescale.</li>
 * </ol>
 *
 * @author Vlad Mihalcea
 */
public class DropwizardAlphaExperimentTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(DropwizardAlphaExperimentTest.class);

    private static final int K = 1028;
    private static final double[] ALPHAS = {0.001, 0.005, 0.015, 0.05, 0.1};
    private static final double[] RESCALE_ALPHAS = {0.015, 0.1, 0.19, 0.2, 0.3};
    private static final int[] RATES = {100, 1_000, 10_000};
    private static final String[] IMPLS = {"Dropwizard-ExpDecay", "Dropwizard-LockFreeExpDecay"};
    private static final int REPS = Integer.getInteger("rs.reps", 5);
    private static final int CLAIM_REPS = Integer.getInteger("rs.alpha.claimReps", 300);
    /** The repetitions of the alpha trade-off; its settle time needs a smooth mean error series. */
    private static final int TRADEOFF_REPS = Integer.getInteger("rs.alpha.tradeoffReps", 50);
    private static final double[] CLAIM_QUANTILES = {0.5, 0.75, 0.9, 0.95, 0.99, 0.999};
    private static final long BASE_SEED = 15_000L;

    private static SamplingStrategy create(String impl, double alpha, Clock clock) {
        return switch (impl) {
            case "Dropwizard-ExpDecay" -> new DropwizardExpDecay(K, alpha, clock);
            case "Dropwizard-LockFreeExpDecay" -> new DropwizardLockFreeExpDecay(K, alpha, clock);
            case "Dropwizard-Uniform" -> new DropwizardUniform(K);
            default -> throw new IllegalArgumentException(impl);
        };
    }

    private static Snapshot snapshot(SamplingStrategy strategy) {
        if (strategy instanceof DropwizardExpDecay s) {
            return s.snapshot();
        }
        return ((DropwizardLockFreeExpDecay) strategy).snapshot();
    }

    // ---------------------------------------------------------------- A: the window that alpha gives

    @Test
    public void measureAlphaWindow() {
        long runSeconds = Long.getLong("rs.alpha.windowSeconds", 3_000);
        try (CsvWriter out = new CsvWriter(CsvWriter.metricsPath("DropwizardAlpha", "alpha-window.csv"),
                "impl", "alpha", "rate", "rep", "weighted_median_age_s", "weighted_p90_age_s", "weighted_share_5min",
                "retained_median_age_s", "retained_share_5min", "theory_median_age_s", "theory_share_5min")) {
            for (String impl : IMPLS) {
                for (double alpha : ALPHAS) {
                    for (int rate : RATES) {
                        for (int rep = 0; rep < REPS; rep++) {
                            Clock.Manual clock = new Clock.Manual(TimeUnit.SECONDS.toNanos(1));
                            long start = clock.nanoTime();
                            SamplingStrategy reservoir = create(impl, alpha, clock);
                            long count = runSeconds * rate;
                            double interval = 1e9 / rate;
                            for (long i = 1; i <= count; i++) {
                                long now = start + (long) (i * interval);
                                clock.set(now);
                                reservoir.update(TimeUnit.NANOSECONDS.toMillis(now - start) + 1);
                            }
                            double nowMs = TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - start) + 1;
                            Snapshot snapshot = snapshot(reservoir);
                            double weightedMedianAge = (nowMs - snapshot.getValue(0.5)) / 1000.0;
                            double weightedP90Age = (nowMs - snapshot.getValue(0.1)) / 1000.0;
                            double weightedShare = 1.0 - quantileOf(snapshot, nowMs - 300_000);
                            long[] retained = snapshot.getValues();
                            Arrays.sort(retained);
                            double retainedMedianAge = (nowMs - retained[retained.length / 2]) / 1000.0;
                            long recent = Arrays.stream(retained).filter(v -> v >= nowMs - 300_000).count();
                            double truncation = 1 - Math.exp(-alpha * runSeconds);
                            double theoryMedian = -Math.log(1 - 0.5 * truncation) / alpha;
                            double theoryShare = (1 - Math.exp(-300 * alpha)) / truncation;
                            out.writeRow(impl, alpha, rate, rep, weightedMedianAge, weightedP90Age, weightedShare,
                                    retainedMedianAge, recent / (double) retained.length, theoryMedian, theoryShare);
                        }
                        LOGGER.info("Window of {} at alpha={} and {}/s measured", impl, alpha, rate);
                    }
                }
            }
        }
    }

    /** The smallest {@code q} whose weighted quantile reaches {@code value}, by bisection. */
    private static double quantileOf(Snapshot snapshot, double value) {
        double low = 0;
        double high = 1;
        for (int i = 0; i < 40; i++) {
            double mid = (low + high) / 2;
            if (snapshot.getValue(mid) >= value) {
                high = mid;
            } else {
                low = mid;
            }
        }
        return high;
    }

    // ---------------------------------------------------------------- B: the Javadoc's confidence claim

    @Test
    public void measureConfidenceClaim() {
        TimeRecencyExperimentTest.Regime regime = TimeRecencyExperimentTest.BASELINE;
        int rate = 1_000;
        long seconds = 600;
        try (CsvWriter out = new CsvWriter(CsvWriter.metricsPath("DropwizardAlpha", "alpha-claim.csv"),
                "impl", "quantile", "reps", "mean_rank_error", "p999_rank_error", "share_rank_within_0.05",
                "mean_tail_rank_error", "mean_abs_rel_error", "share_value_within_5pct")) {
            for (String impl : new String[]{"Dropwizard-ExpDecay", "Dropwizard-LockFreeExpDecay", "Dropwizard-Uniform"}) {
                double[][] rankErrors = new double[CLAIM_QUANTILES.length][CLAIM_REPS];
                double[][] relErrors = new double[CLAIM_QUANTILES.length][CLAIM_REPS];
                for (int rep = 0; rep < CLAIM_REPS; rep++) {
                    Clock.Manual clock = new Clock.Manual(TimeUnit.SECONDS.toNanos(1));
                    long start = clock.nanoTime();
                    SamplingStrategy reservoir = create(impl, 0.015, clock);
                    SplittableRandom random = new SplittableRandom(BASE_SEED + rep);
                    long count = seconds * rate;
                    double interval = 1e9 / rate;
                    for (long i = 1; i <= count; i++) {
                        clock.set(start + (long) (i * interval));
                        reservoir.update(regime.sample(random));
                    }
                    for (int q = 0; q < CLAIM_QUANTILES.length; q++) {
                        double quantile = CLAIM_QUANTILES[q];
                        double estimate = reservoir.valueAtQuantile(quantile);
                        double rank = Normal.cdf((Math.log(estimate) - regime.mu()) / regime.sigma());
                        rankErrors[q][rep] = Math.abs(rank - quantile);
                        relErrors[q][rep] = ExactQuantileBaseline.relativeError(estimate, regime.quantile(quantile));
                    }
                }
                for (int q = 0; q < CLAIM_QUANTILES.length; q++) {
                    double quantile = CLAIM_QUANTILES[q];
                    double[] ranks = rankErrors[q].clone();
                    Arrays.sort(ranks);
                    Statistics rank = new Statistics();
                    Statistics tail = new Statistics();
                    Statistics rel = new Statistics();
                    int rankWithin = 0;
                    int valueWithin = 0;
                    for (int rep = 0; rep < CLAIM_REPS; rep++) {
                        rank.add(rankErrors[q][rep]);
                        tail.add(rankErrors[q][rep] / (1 - quantile));
                        rel.add(relErrors[q][rep]);
                        if (rankErrors[q][rep] <= 0.05) {
                            rankWithin++;
                        }
                        if (relErrors[q][rep] <= 0.05) {
                            valueWithin++;
                        }
                    }
                    double p999 = ranks[Math.min(ranks.length - 1, (int) Math.ceil(0.999 * ranks.length) - 1)];
                    out.writeRow(impl, quantile, CLAIM_REPS, rank.mean(), p999, rankWithin / (double) CLAIM_REPS,
                            tail.mean(), rel.mean(), valueWithin / (double) CLAIM_REPS);
                }
                LOGGER.info("Confidence claim of {} measured over {} repetitions", impl, CLAIM_REPS);
            }
        }
    }

    // ---------------------------------------------------------------- C: the alpha trade-off on the library

    @Test
    public void measureAlphaTradeoff() {
        TimeRecencyExperimentTest.Scenario stepUp = TimeRecencyExperimentTest.scenarios(1_000).get(0);
        long step = Long.getLong("rs.recency.stepSeconds", 5);
        double baseP99 = TimeRecencyExperimentTest.BASELINE.quantile(0.99);
        double baseP999 = TimeRecencyExperimentTest.BASELINE.quantile(0.999);
        double degradedP99 = TimeRecencyExperimentTest.DEGRADED.quantile(0.99);
        // The step-up scenario arrives at 1,000 values per second; m = rate / (alpha k) counts the values that carry
        // most of the decayed weight per reservoir slot (see ForwardDecayAlphaSensitivityExperimentTest).
        int rate = 1_000;
        try (CsvWriter out = new CsvWriter(CsvWriter.metricsPath("DropwizardAlpha", "alpha-tradeoff.csv"),
                "impl", "alpha", "steady_p99_error", "steady_p99_ci95", "steady_p999_error", "steady_p999_ci95",
                "converge_s", "converge_ci95", "converged_reps", "reps", "settle_s", "rate", "m")) {
            for (String impl : IMPLS) {
                for (double alpha : ALPHAS) {
                    Statistics p99 = new Statistics();
                    Statistics p999 = new Statistics();
                    Statistics converge = new Statistics();
                    // The mean p99 error against the degraded truth at every checkpoint after the shift, over the reps.
                    double[] afterShiftError = null;
                    for (int rep = 0; rep < TRADEOFF_REPS; rep++) {
                        Clock.Manual clock = new Clock.Manual(TimeUnit.SECONDS.toNanos(1));
                        SamplingStrategy reservoir = create(impl, alpha, clock);
                        double[][] series = TimeRecencyExperimentTest.replay(stepUp, reservoir, clock,
                                BASE_SEED + 37L * rep, null, step, 0.99, 0.999);
                        if (afterShiftError == null) {
                            afterShiftError = new double[series[0].length];
                        }
                        Statistics e99 = new Statistics();
                        Statistics e999 = new Statistics();
                        double converged = Double.NaN;
                        for (int i = 0; i < series[0].length; i++) {
                            long t = (i + 1) * step;
                            if (t > 300 && t <= 600) {
                                e99.add(ExactQuantileBaseline.relativeError(series[0][i], baseP99));
                                e999.add(ExactQuantileBaseline.relativeError(series[1][i], baseP999));
                            }
                            if (t > 600) {
                                double error = Math.abs(series[0][i] - degradedP99) / degradedP99;
                                afterShiftError[i] += error / (TRADEOFF_REPS);
                                if (error <= TimeRecencyExperimentTest.CONVERGED && Double.isNaN(converged)) {
                                    converged = t - 600;
                                }
                            }
                        }
                        p99.add(e99.mean());
                        p999.add(e999.mean());
                        if (!Double.isNaN(converged)) {
                            converge.add(converged);
                        }
                    }
                    // Settled: the first checkpoint from which the mean error stays within the bound until the end,
                    // E7b's rule. A single run's p99 keeps a 5-7% steady-state error and crosses a 10% bound now and
                    // then until the end, so the rule needs the mean over the repetitions, as E7b applies it, and over
                    // the 120 checkpoints of the 10 minutes after the shift it also needs a 30 s moving average:
                    // otherwise one noisy checkpoint near the end moves the settle time by minutes.
                    int smoothing = (int) Math.max(1, 30 / step);
                    double settled = Double.NaN;
                    for (int i = afterShiftError.length - 1; i >= 0; i--) {
                        long t = (i + 1) * step;
                        double sum = 0;
                        int count = 0;
                        for (int j = i; j > i - smoothing && j >= 0 && (j + 1) * step > 600; j--) {
                            sum += afterShiftError[j];
                            count++;
                        }
                        if (t <= 600 || sum / count > TimeRecencyExperimentTest.CONVERGED) {
                            break;
                        }
                        settled = t - 600;
                    }
                    out.writeRow(impl, alpha, p99.mean(), p99.ci95HalfWidth(), p999.mean(), p999.ci95HalfWidth(),
                            converge.count() == 0 ? "" : converge.mean(), converge.ci95HalfWidth(), converge.count(),
                            TRADEOFF_REPS, Double.isNaN(settled) ? "" : settled, rate, rate / (alpha * K));
                    LOGGER.info("{} alpha={}: steady p99 error {}, converges in {} s, settles in {} s", impl, alpha,
                            String.format("%.3f", p99.mean()), String.format("%.0f", converge.mean()),
                            String.format("%.0f", settled));
                }
            }
        }
    }

    // ---------------------------------------------------------------- D: the hourly rescale

    @Test
    public void measureRescale() {
        int rate = 100;
        long step = 10;
        TimeRecencyExperimentTest.Scenario scenario = new TimeRecencyExperimentTest.Scenario("rescale", List.of(
                new TimeRecencyExperimentTest.Segment(TimeRecencyExperimentTest.BASELINE, 3_000, rate),
                new TimeRecencyExperimentTest.Segment(TimeRecencyExperimentTest.DEGRADED, 7_800, rate)));
        double degradedP99 = TimeRecencyExperimentTest.DEGRADED.quantile(0.99);
        try (CsvWriter out = new CsvWriter(CsvWriter.metricsPath("DropwizardAlpha", "alpha-rescale.csv"),
                "impl", "alpha", "rep", "invalid_checkpoints", "longest_frozen_s", "converge_s",
                "jump_at_1h", "jump_at_2h", "jump_at_3h", "median_step_change")) {
            for (String impl : IMPLS) {
                double[][] meanSeries = new double[RESCALE_ALPHAS.length][];
                for (int a = 0; a < RESCALE_ALPHAS.length; a++) {
                    double alpha = RESCALE_ALPHAS[a];
                    for (int rep = 0; rep < REPS; rep++) {
                        Clock.Manual clock = new Clock.Manual(TimeUnit.SECONDS.toNanos(1));
                        SamplingStrategy reservoir = create(impl, alpha, clock);
                        double[] series = TimeRecencyExperimentTest.replay(scenario, reservoir, clock,
                                BASE_SEED + 53L * rep, null, step, 0.99)[0];
                        if (meanSeries[a] == null) {
                            meanSeries[a] = new double[series.length];
                        }
                        int invalid = 0;
                        int frozen = 0;
                        int longestFrozen = 0;
                        double converged = Double.NaN;
                        double[] changes = new double[series.length - 1];
                        for (int i = 0; i < series.length; i++) {
                            meanSeries[a][i] += series[i] / REPS;
                            long t = (i + 1) * step;
                            if (!(series[i] > 0) || Double.isInfinite(series[i])) {
                                invalid++;
                            }
                            if (i > 0) {
                                changes[i - 1] = Math.abs(series[i] - series[i - 1]);
                                frozen = series[i] == series[i - 1] ? frozen + 1 : 0;
                                longestFrozen = Math.max(longestFrozen, frozen);
                            }
                            if (t > 3_000 && Double.isNaN(converged)
                                    && Math.abs(series[i] - degradedP99) / degradedP99
                                    <= TimeRecencyExperimentTest.CONVERGED) {
                                converged = t - 3_000;
                            }
                        }
                        double[] sorted = changes.clone();
                        Arrays.sort(sorted);
                        out.writeRow(impl, alpha, rep, invalid, longestFrozen * step,
                                Double.isNaN(converged) ? "" : converged,
                                jump(series, step, 3_600), jump(series, step, 7_200), jump(series, step, 10_800),
                                sorted[sorted.length / 2]);
                    }
                    LOGGER.info("Rescale behavior of {} at alpha={} measured", impl, alpha);
                }
                String[] header = new String[RESCALE_ALPHAS.length + 1];
                header[0] = "t_s";
                for (int a = 0; a < RESCALE_ALPHAS.length; a++) {
                    header[a + 1] = "a=" + RESCALE_ALPHAS[a];
                }
                try (CsvWriter series = new CsvWriter(CsvWriter.metricsPath("DropwizardAlpha",
                        "alpha-rescale-series-" + impl + ".csv"), header)) {
                    for (int i = 0; i < meanSeries[0].length; i++) {
                        Object[] row = new Object[RESCALE_ALPHAS.length + 1];
                        row[0] = (i + 1) * step;
                        for (int a = 0; a < RESCALE_ALPHAS.length; a++) {
                            row[a + 1] = meanSeries[a][i];
                        }
                        series.writeRow(row);
                    }
                }
            }
        }
    }

    /** The p99 change between the checkpoints just before and just after {@code seconds}. */
    private static Object jump(double[] series, long step, long seconds) {
        int after = (int) (seconds / step) + 1;
        int before = after - 2;
        if (before < 0 || after >= series.length) {
            return "";
        }
        return Math.abs(series[after] - series[before]);
    }
}
