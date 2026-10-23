package com.vladmihalcea.phd.reservoir.accuracy;

import com.vladmihalcea.phd.reservoir.api.Clock;
import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.baseline.ExactQuantileBaseline;
import com.vladmihalcea.phd.reservoir.catalog.StrategyCatalog;
import com.vladmihalcea.phd.reservoir.util.CsvWriter;
import com.vladmihalcea.phd.reservoir.util.SampleSummary;
import com.vladmihalcea.phd.reservoir.workload.SyntheticWorkloads;
import com.vladmihalcea.phd.reservoir.workload.Workload;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Experiment E6b (claim C2 headline): <em>how many samples does each sampling strategy need to reach a
 * target tail-quantile accuracy?</em> For every reservoir strategy whose accuracy is governed by its
 * sample budget {@code k}, the reservoir size is swept from 64 to 65,536 and the p99 relative error against the exact
 * baseline recorded over {@code rs.reps} (50) seeded streams of {@code rs.stream} (1,000,000) values. A strategy that
 * needs a much larger {@code k} than another to hit the same error pays that as memory and per-update cost; this is
 * the figure that turns the accuracy table into an apples-to-apples memory-for-accuracy comparison.
 * <p>
 * The relative error of a tail quantile is itself heavy-tailed across seeds (one unlucky reservoir misses the tail by
 * a factor of ten), so its mean jumps between neighboring {@code k} and a decision on the mean alone flips between
 * runs: the November 15 and 17 runs swapped Vitter's R and L on Pareto. The experiment therefore reports, per strategy
 * and {@code k}: the mean with its 95% percentile-bootstrap interval, the median with its bootstrap interval, the 90th
 * percentile across seeds (the error that 90% of the runs stay within), and the error theory predicts for a uniform
 * reservoir, {@code sqrt(2 / pi) * sqrt(q (1 - q) / k * (1 - k / n)) / (f(x_q) x_q)} with the density {@code f} at the
 * p99 estimated from the exact stream. A strategy "needs" the smallest {@code k} from which a criterion holds at every
 * larger {@code k} of the sweep: the bootstrap upper bound of the mean (the headline, {@code k_needed}), the median, or
 * the 90th percentile at or below the target ({@code rs.targetError}, 5%); -1 when the sweep never gets there.
 * <p>
 * Histogram / rank-summary strategies are excluded because their accuracy is set by bucket resolution, not
 * a sample count; they appear in the plain accuracy sweep (E6) instead.
 *
 * @author Vlad Mihalcea
 */
public class SamplesForTargetErrorExperimentTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(SamplesForTargetErrorExperimentTest.class);

    /** Strategies whose accuracy is a function of the reservoir size k. */
    private static final List<String> K_TUNABLE = List.of(
            "Vitter-R", "Vitter-L-skip", "ES-A-Res", "ES-A-ExpJ", "ForwardDecay-0.015",
            "WRS-B", "Dropwizard-Uniform", "Dropwizard-ExpDecay");

    private static final int[] K_SWEEP = {64, 128, 256, 512, 1028, 2048, 4096, 8192, 16384, 32768, 65536};
    private static final double TARGET_QUANTILE = 0.99;
    private static final double DENSITY_HALF_WIDTH = 0.0025;
    private static final double TARGET_ERROR = Double.parseDouble(
            System.getProperty("rs.targetError", "0.05"));
    private static final int STREAM_LENGTH = Integer.getInteger("rs.stream", 1_000_000);
    private static final int REPETITIONS = Integer.getInteger("rs.reps", 50);
    private static final long BASE_SEED = 7_000L;

    @Test
    public void measureSamplesForTargetError() {
        // Stationary heavy-tailed workloads: the tail is where the sample budget bites hardest.
        List<Workload> workloads = List.of(
                SyntheticWorkloads.lognormal(),
                SyntheticWorkloads.pareto(),
                SyntheticWorkloads.bimodal());
        int[] sweep = Arrays.stream(K_SWEEP).filter(k -> k < STREAM_LENGTH).toArray();

        for (Workload workload : workloads) {
            // errors[strategy][k] over the seeds, and the uniform-reservoir theory per k.
            SampleSummary[][] errors = new SampleSummary[K_TUNABLE.size()][sweep.length];
            for (SampleSummary[] row : errors) {
                for (int ki = 0; ki < sweep.length; ki++) {
                    row[ki] = new SampleSummary();
                }
            }
            SampleSummary[] theory = new SampleSummary[sweep.length];
            for (int ki = 0; ki < sweep.length; ki++) {
                theory[ki] = new SampleSummary();
            }

            long step = Math.max(1, StrategyCatalog.TRACE_SPAN_NANOS / STREAM_LENGTH);
            for (int rep = 0; rep < REPETITIONS; rep++) {
                long seed = BASE_SEED + rep;
                long[] values = workload.generate(STREAM_LENGTH, seed);
                long[] sorted = values.clone();
                Arrays.sort(sorted);
                double truth = ExactQuantileBaseline.ofSorted(sorted, STREAM_LENGTH, TARGET_QUANTILE);
                double density = 2 * DENSITY_HALF_WIDTH
                        / (ExactQuantileBaseline.ofSorted(sorted, STREAM_LENGTH, TARGET_QUANTILE + DENSITY_HALF_WIDTH)
                        - ExactQuantileBaseline.ofSorted(sorted, STREAM_LENGTH, TARGET_QUANTILE - DENSITY_HALF_WIDTH));
                for (int ki = 0; ki < sweep.length; ki++) {
                    int k = sweep[ki];
                    double rankSd = Math.sqrt(TARGET_QUANTILE * (1 - TARGET_QUANTILE) / k
                            * (1 - k / (double) STREAM_LENGTH));
                    theory[ki].add(Math.sqrt(2 / Math.PI) * rankSd / (density * truth));
                }
                for (int s = 0; s < K_TUNABLE.size(); s++) {
                    for (int ki = 0; ki < sweep.length; ki++) {
                        Clock.Manual clock = new Clock.Manual();
                        SamplingStrategy strategy = StrategyCatalog.create(
                                K_TUNABLE.get(s), sweep[ki], clock, StrategyCatalog.strategySeed(seed));
                        for (long value : values) {
                            clock.advanceNanos(step);
                            strategy.update(value, clock.nanoTime());
                        }
                        double estimate = strategy.valueAtQuantile(TARGET_QUANTILE);
                        errors[s][ki].add(ExactQuantileBaseline.relativeError(estimate, truth));
                    }
                }
            }
            write(workload, sweep, errors, theory);
        }
    }

    private static void write(Workload workload, int[] sweep, SampleSummary[][] errors, SampleSummary[] theory) {
        String name = workload.name();
        List<String> header = new ArrayList<>();
        header.add("k");
        header.addAll(K_TUNABLE);
        header.add("Theory-uniform");
        try (CsvWriter out = new CsvWriter(CsvWriter.metricsPath("SamplesForAccuracy", "error-" + name + ".csv"),
                "strategy", "k", "mean_p99_error", "ci95", "mean_ci95_low", "mean_ci95_high", "median_p99_error",
                "median_ci95_low", "median_ci95_high", "p90_p99_error", "reps", "theory_uniform");
             CsvWriter kNeeded = new CsvWriter(CsvWriter.metricsPath("SamplesForAccuracy", "kneeded-" + name + ".csv"),
                     "strategy", "k_needed", "target_error", "k_needed_median", "k_needed_p90", "k_needed_mean_point");
             CsvWriter wide = new CsvWriter(CsvWriter.metricsPath("SamplesForAccuracy", "error-wide-" + name + ".csv"),
                     header.toArray(String[]::new));
             CsvWriter wideMedian = new CsvWriter(
                     CsvWriter.metricsPath("SamplesForAccuracy", "error-median-wide-" + name + ".csv"),
                     header.toArray(String[]::new))) {
            double[][] upper = new double[K_TUNABLE.size()][sweep.length];
            double[][] median = new double[K_TUNABLE.size()][sweep.length];
            double[][] p90 = new double[K_TUNABLE.size()][sweep.length];
            double[][] mean = new double[K_TUNABLE.size()][sweep.length];
            for (int s = 0; s < K_TUNABLE.size(); s++) {
                for (int ki = 0; ki < sweep.length; ki++) {
                    SampleSummary e = errors[s][ki];
                    double[] meanCi = e.bootstrapMeanCi95();
                    double[] medianCi = e.bootstrapMedianCi95();
                    mean[s][ki] = e.mean();
                    upper[s][ki] = meanCi[1];
                    median[s][ki] = e.median();
                    p90[s][ki] = e.quantile(0.9);
                    out.writeRow(K_TUNABLE.get(s), sweep[ki], e.mean(), e.tCi95HalfWidth(), meanCi[0], meanCi[1],
                            e.median(), medianCi[0], medianCi[1], p90[s][ki], e.count(), theory[ki].mean());
                }
                int needed = kNeeded(sweep, upper[s]);
                kNeeded.writeRow(K_TUNABLE.get(s), needed, TARGET_ERROR, kNeeded(sweep, median[s]),
                        kNeeded(sweep, p90[s]), kNeeded(sweep, mean[s]));
                LOGGER.info("{} on '{}' keeps its mean p99 error (95% upper bound) <= {} from k={}, median from k={}, "
                                + "90% of the runs from k={}", K_TUNABLE.get(s), name, TARGET_ERROR,
                        label(sweep, needed), label(sweep, kNeeded(sweep, median[s])),
                        label(sweep, kNeeded(sweep, p90[s])));
            }
            for (int ki = 0; ki < sweep.length; ki++) {
                Object[] row = new Object[K_TUNABLE.size() + 2];
                Object[] rowMedian = new Object[K_TUNABLE.size() + 2];
                row[0] = sweep[ki];
                rowMedian[0] = sweep[ki];
                for (int s = 0; s < K_TUNABLE.size(); s++) {
                    row[s + 1] = mean[s][ki];
                    rowMedian[s + 1] = median[s][ki];
                }
                row[K_TUNABLE.size() + 1] = theory[ki].mean();
                rowMedian[K_TUNABLE.size() + 1] = theory[ki].mean();
                wide.writeRow(row);
                wideMedian.writeRow(rowMedian);
            }
        }
        LOGGER.info("Wrote samples-for-accuracy CSVs for '{}'", name);
    }

    /** The smallest k of the sweep from which {@code error <= target} holds at every larger k, or -1. */
    static int kNeeded(int[] sweep, double[] error) {
        int needed = -1;
        for (int ki = sweep.length - 1; ki >= 0 && error[ki] <= TARGET_ERROR; ki--) {
            needed = sweep[ki];
        }
        return needed;
    }

    private static String label(int[] sweep, int needed) {
        return needed < 0 ? ">" + sweep[sweep.length - 1] : String.valueOf(needed);
    }
}
