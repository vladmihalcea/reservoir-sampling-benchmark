package com.vladmihalcea.phd.reservoir.accuracy;

import com.vladmihalcea.phd.reservoir.api.Clock;
import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.baseline.ExactQuantileBaseline;
import com.vladmihalcea.phd.reservoir.catalog.StrategyCatalog;
import com.vladmihalcea.phd.reservoir.util.CsvWriter;
import com.vladmihalcea.phd.reservoir.util.Statistics;
import com.vladmihalcea.phd.reservoir.workload.SyntheticWorkloads;
import com.vladmihalcea.phd.reservoir.workload.Workload;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Experiment E6b (claim C2 headline): <em>how many samples does each sampling strategy need to reach a
 * target tail-quantile accuracy?</em> For every reservoir strategy whose accuracy is governed by its
 * sample budget {@code k}, the reservoir size is swept and the mean p99 relative error against the exact
 * baseline recorded; the smallest {@code k} whose mean error falls at or below the target is the strategy's
 * "samples for target accuracy". A strategy that needs a much larger {@code k} than another to hit the same
 * error pays that as memory and per-update cost — this is the figure that turns the accuracy table into an
 * apples-to-apples memory-for-accuracy comparison.
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

    private static final int[] K_SWEEP = {64, 128, 256, 512, 1028, 2048, 4096, 8192};
    private static final double TARGET_QUANTILE = 0.99;
    private static final double TARGET_ERROR = Double.parseDouble(
            System.getProperty("rs.targetError", "0.05"));
    private static final int STREAM_LENGTH = Integer.getInteger("rs.stream", 200_000);
    private static final int REPETITIONS = Integer.getInteger("rs.reps", 20);
    private static final long BASE_SEED = 7_000L;

    @Test
    public void measureSamplesForTargetError() {
        // Stationary heavy-tailed workloads: the tail is where the sample budget bites hardest.
        List<Workload> workloads = List.of(
                SyntheticWorkloads.lognormal(),
                SyntheticWorkloads.pareto(),
                SyntheticWorkloads.bimodal());

        for (Workload workload : workloads) {
            Path errCsv = CsvWriter.metricsPath("SamplesForAccuracy", "error-" + workload.name() + ".csv");
            Path kNeededCsv = CsvWriter.metricsPath("SamplesForAccuracy", "kneeded-" + workload.name() + ".csv");
            Path wideCsv = CsvWriter.metricsPath("SamplesForAccuracy", "error-wide-" + workload.name() + ".csv");
            // Chart-ready wide error surface: rows = k, one column per strategy.
            Map<String, double[]> errorByStrategy = new LinkedHashMap<>();
            try (CsvWriter out = new CsvWriter(errCsv,
                    "strategy", "k", "mean_p99_error", "ci95");
                 CsvWriter kNeededOut = new CsvWriter(kNeededCsv, "strategy", "k_needed", "target_error")) {
                for (String strategyName : K_TUNABLE) {
                    int kNeeded = -1;
                    double[] errors = new double[K_SWEEP.length];
                    for (int ki = 0; ki < K_SWEEP.length; ki++) {
                        int k = K_SWEEP[ki];
                        Statistics error = measure(workload, strategyName, k);
                        out.writeRow(strategyName, k, error.mean(), error.ci95HalfWidth());
                        errors[ki] = error.mean();
                        if (kNeeded < 0 && error.mean() <= TARGET_ERROR) {
                            kNeeded = k;
                        }
                    }
                    errorByStrategy.put(strategyName, errors);
                    // -1 (never reached within the sweep) is charted as the top of the sweep + a marker.
                    kNeededOut.writeRow(strategyName, kNeeded, TARGET_ERROR);
                    LOGGER.info("{} on '{}' reaches p99 error <= {} at k={}",
                            strategyName, workload.name(), TARGET_ERROR,
                            kNeeded < 0 ? ">" + K_SWEEP[K_SWEEP.length - 1] : kNeeded);
                }
            }
            String[] header = new String[K_TUNABLE.size() + 1];
            header[0] = "k";
            for (int i = 0; i < K_TUNABLE.size(); i++) {
                header[i + 1] = K_TUNABLE.get(i);
            }
            try (CsvWriter wide = new CsvWriter(wideCsv, header)) {
                for (int ki = 0; ki < K_SWEEP.length; ki++) {
                    Object[] row = new Object[K_TUNABLE.size() + 1];
                    row[0] = K_SWEEP[ki];
                    for (int i = 0; i < K_TUNABLE.size(); i++) {
                        row[i + 1] = errorByStrategy.get(K_TUNABLE.get(i))[ki];
                    }
                    wide.writeRow(row);
                }
            }
            LOGGER.info("Wrote samples-for-accuracy CSVs for '{}' to {}", workload.name(), errCsv);
        }
    }

    private Statistics measure(Workload workload, String strategyName, int k) {
        Statistics error = new Statistics();
        for (int rep = 0; rep < REPETITIONS; rep++) {
            long seed = BASE_SEED + rep;
            long[] values = workload.generate(STREAM_LENGTH, seed);

            Clock.Manual clock = new Clock.Manual();
            long step = Math.max(1, StrategyCatalog.TRACE_SPAN_NANOS / STREAM_LENGTH);
            SamplingStrategy strategy = StrategyCatalog.create(strategyName, k, clock, seed);

            ExactQuantileBaseline baseline = new ExactQuantileBaseline();
            for (long value : values) {
                clock.advanceNanos(step);
                strategy.update(value, clock.nanoTime());
                baseline.add(value);
            }
            double truth = baseline.valueAtQuantile(TARGET_QUANTILE);
            double estimate = strategy.valueAtQuantile(TARGET_QUANTILE);
            error.add(ExactQuantileBaseline.relativeError(estimate, truth));
        }
        return error;
    }
}
