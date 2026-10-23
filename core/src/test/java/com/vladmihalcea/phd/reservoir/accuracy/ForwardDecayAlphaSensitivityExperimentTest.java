package com.vladmihalcea.phd.reservoir.accuracy;

import com.vladmihalcea.phd.reservoir.api.Clock;
import com.vladmihalcea.phd.reservoir.baseline.ExactQuantileBaseline;
import com.vladmihalcea.phd.reservoir.baseline.TargetTruth;
import com.vladmihalcea.phd.reservoir.catalog.StrategyCatalog;
import com.vladmihalcea.phd.reservoir.impl.ForwardDecayReservoir;
import com.vladmihalcea.phd.reservoir.util.CsvWriter;
import com.vladmihalcea.phd.reservoir.util.Statistics;
import com.vladmihalcea.phd.reservoir.workload.SyntheticWorkloads;
import com.vladmihalcea.phd.reservoir.workload.Workload;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * Experiment E7 (paper topic 3.1.6.2): sensitivity of forward-decay quantile estimates to the decay
 * parameter {@code alpha}, which Dropwizard hardcodes to 0.015. For each workload profile the parametric
 * {@link ForwardDecayReservoir} is swept across {@code alpha} values and its tail-quantile error measured
 * against the <em>recent</em> truth (the last quarter of the stream), since the whole point of decay is to
 * track the current distribution. A workload where 0.015 is far from the error-minimizing alpha is direct
 * evidence for the survey's claim that the constant has never been validated across workloads.
 * <p>
 * The original sweep spreads its 500,000 values over 10 minutes (833 per second), which fixes the arrival rate, so
 * alpha alone cannot tell whether an error comes from the decay or from the number of values the decay leaves the
 * reservoir to choose from. A forward-decay reservoir weights a value of age {@code a} by {@code exp(-alpha a)}: about
 * {@code rate / alpha} values carry most of the weight, and the reservoir keeps {@code k} of them, so the ratio
 * {@code m = rate / (alpha k)} counts the candidates per slot. When {@code m} is large, the reservoir holds a sample of
 * many recent values with similar weights; when it approaches 1, it holds nearly all of them with weights that differ
 * by up to a factor {@code e}, and its effective sample size drops. The rate grid repeats the sweep at 100, 1,000, and
 * 10,000 values per second (alpha up to 0.19, below the 0.197 at which Dropwizard's hourly rescale overflows) and
 * scores each estimate against both the recent quarter and the reservoir's own target, the decayed distribution
 * ({@link TargetTruth.Decayed}), which isolates the sampling error; if the errors line up on {@code m} across rates,
 * {@code m}, not alpha, is the parameter that sets the accuracy.
 * <p>
 * Outputs ({@code metrics/AlphaSensitivity/}): {@code alpha-<workload>.csv} and {@code alpha-wide-<workload>.csv} (the
 * original sweep), and {@code alpha-rate-<workload>.csv} (the rate grid, with {@code m}).
 *
 * @author Vlad Mihalcea
 */
public class ForwardDecayAlphaSensitivityExperimentTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ForwardDecayAlphaSensitivityExperimentTest.class);

    private static final double[] ALPHAS = {0.001, 0.005, 0.015, 0.05, 0.1};
    private static final double[] GRID_ALPHAS = {0.001, 0.005, 0.015, 0.05, 0.1, 0.19};
    private static final int[] GRID_RATES = {100, 1_000, 10_000};
    private static final double[] TAIL_QUANTILES = {0.95, 0.99, 0.999};
    private static final int STREAM_LENGTH = Integer.getInteger("rs.stream", 500_000);
    private static final int REPETITIONS = Integer.getInteger("rs.reps", 30);
    private static final long BASE_SEED = 3_000L;
    private static final double RECENT_FRACTION = 0.25;

    @Test
    public void measureAlphaSensitivity() {
        List<Workload> workloads = List.of(
                SyntheticWorkloads.lognormal("steady", Math.log(3.0), 0.9),
                SyntheticWorkloads.bimodal(),
                SyntheticWorkloads.regimeShift(),
                SyntheticWorkloads.connectionPool()
        );
        long spanStep = Math.max(1, StrategyCatalog.TRACE_SPAN_NANOS / STREAM_LENGTH);

        for (Workload workload : workloads) {
            Path csv = CsvWriter.metricsPath("AlphaSensitivity", "alpha-" + workload.name() + ".csv");
            // Chart-ready wide form: one row per alpha, one column per tail quantile.
            Path wideCsv = CsvWriter.metricsPath("AlphaSensitivity", "alpha-wide-" + workload.name() + ".csv");
            try (CsvWriter out = new CsvWriter(csv,
                    "alpha", "quantile", "workload", "mean_rel_error", "ci95");
                 CsvWriter wide = new CsvWriter(wideCsv, "alpha", "p95", "p99", "p999")) {
                for (double alpha : ALPHAS) {
                    Result r = measure(workload, alpha, spanStep);
                    for (int i = 0; i < TAIL_QUANTILES.length; i++) {
                        out.writeRow(alpha, TAIL_QUANTILES[i], workload.name(), r.recent[i].mean(),
                                r.recent[i].ci95HalfWidth());
                    }
                    wide.writeRow(alpha, r.recent[0].mean(), r.recent[1].mean(), r.recent[2].mean());
                }
            }
            try (CsvWriter grid = new CsvWriter(
                    CsvWriter.metricsPath("AlphaSensitivity", "alpha-rate-" + workload.name() + ".csv"),
                    "alpha", "rate", "k", "m", "quantile", "recent_rel_error", "recent_ci95", "decayed_rel_error",
                    "decayed_ci95", "decayed_rank_error", "decayed_rank_ci95")) {
                for (int rate : GRID_RATES) {
                    long step = 1_000_000_000L / rate;
                    for (double alpha : GRID_ALPHAS) {
                        Result r = measure(workload, alpha, step);
                        double m = rate / (alpha * StrategyCatalog.DEFAULT_K);
                        for (int i = 0; i < TAIL_QUANTILES.length; i++) {
                            grid.writeRow(alpha, rate, StrategyCatalog.DEFAULT_K, m, TAIL_QUANTILES[i],
                                    r.recent[i].mean(), r.recent[i].ci95HalfWidth(), r.decayed[i].mean(),
                                    r.decayed[i].ci95HalfWidth(), r.decayedRank[i].mean(),
                                    r.decayedRank[i].ci95HalfWidth());
                        }
                    }
                }
            }
            LOGGER.info("Wrote alpha-sensitivity CSVs for '{}' to {}", workload.name(), csv);
        }
    }

    private record Result(Statistics[] recent, Statistics[] decayed, Statistics[] decayedRank) {
    }

    /** Runs the repetitions at one alpha, with the clock advancing {@code step} nanoseconds per value. */
    private Result measure(Workload workload, double alpha, long step) {
        Statistics[] recent = new Statistics[TAIL_QUANTILES.length];
        Statistics[] decayed = new Statistics[TAIL_QUANTILES.length];
        Statistics[] decayedRank = new Statistics[TAIL_QUANTILES.length];
        for (int i = 0; i < TAIL_QUANTILES.length; i++) {
            recent[i] = new Statistics();
            decayed[i] = new Statistics();
            decayedRank[i] = new Statistics();
        }
        int recentSuffix = (int) (STREAM_LENGTH * RECENT_FRACTION);

        for (int rep = 0; rep < REPETITIONS; rep++) {
            long seed = BASE_SEED + rep;
            long[] values = workload.generate(STREAM_LENGTH, seed);
            long[] timestamps = new long[STREAM_LENGTH];

            Clock.Manual clock = new Clock.Manual();
            ForwardDecayReservoir reservoir = new ForwardDecayReservoir(
                    StrategyCatalog.DEFAULT_K, alpha, clock, StrategyCatalog.strategySeed(seed));

            ExactQuantileBaseline baseline = new ExactQuantileBaseline();
            for (int v = 0; v < STREAM_LENGTH; v++) {
                clock.advanceNanos(step);
                timestamps[v] = clock.nanoTime();
                reservoir.update(values[v], clock.nanoTime());
                baseline.add(values[v]);
            }
            TargetTruth target = TargetTruth.of(new TargetTruth.Decayed(alpha), values, timestamps);
            for (int i = 0; i < TAIL_QUANTILES.length; i++) {
                double estimate = reservoir.valueAtQuantile(TAIL_QUANTILES[i]);
                recent[i].add(ExactQuantileBaseline.relativeError(estimate,
                        baseline.valueAtQuantileOverSuffix(TAIL_QUANTILES[i], recentSuffix)));
                decayed[i].add(ExactQuantileBaseline.relativeError(estimate,
                        target.valueAtQuantile(TAIL_QUANTILES[i])));
                decayedRank[i].add(target.rankError(estimate, TAIL_QUANTILES[i]));
            }
        }
        return new Result(recent, decayed, decayedRank);
    }
}
