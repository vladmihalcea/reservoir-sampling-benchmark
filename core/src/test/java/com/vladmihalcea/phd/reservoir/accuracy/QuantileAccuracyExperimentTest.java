package com.vladmihalcea.phd.reservoir.accuracy;

import com.vladmihalcea.phd.reservoir.api.Clock;
import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.catalog.StrategyCatalog;
import com.vladmihalcea.phd.reservoir.baseline.ExactQuantileBaseline;
import com.vladmihalcea.phd.reservoir.util.CsvWriter;
import com.vladmihalcea.phd.reservoir.util.Statistics;
import com.vladmihalcea.phd.reservoir.workload.SyntheticWorkloads;
import com.vladmihalcea.phd.reservoir.workload.TraceWorkloads;
import com.vladmihalcea.phd.reservoir.workload.Workload;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Experiment E6: quantile accuracy of every strategy against the exact quantile baseline, over every synthetic
 * workload and a range of stream lengths, averaged over repeated seeds with a 95% confidence interval.
 * This is the survey's headline accuracy table (claim C2). Time-aware strategies are driven from a
 * manual clock that advances evenly across the stream so their decay/window behaviour is exercised
 * deterministically.
 * <p>
 * One CSV per workload is written under {@code metrics/QuantileAccuracy/}, with columns
 * {@code strategy, quantile, stream_length, mean_rel_error, ci95}.
 *
 * @author Vlad Mihalcea
 */
public class QuantileAccuracyExperimentTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuantileAccuracyExperimentTest.class);

    // Sizes default to publication scale but can be shrunk for a smoke run, e.g. -Drs.reps=2 -Drs.maxStream=10000.
    private static final int MAX_STREAM = Integer.getInteger("rs.maxStream", 1_000_000);
    private static final int[] STREAM_LENGTHS =
            MAX_STREAM >= 1_000_000 ? new int[]{10_000, 1_000_000} : new int[]{Math.min(10_000, MAX_STREAM)};
    private static final int REPETITIONS = Integer.getInteger("rs.reps", 30);
    private static final long BASE_SEED = 1_000L;

    @Test
    public void measureAccuracy() {
        int maxLength = STREAM_LENGTHS[STREAM_LENGTHS.length - 1];
        // Synthetic distributions plus the captured "realistic" traces (claim C2: synthetic AND realistic).
        List<Workload> workloads = new ArrayList<>(SyntheticWorkloads.all());
        if (TraceWorkloads.available()) {
            workloads.addAll(TraceWorkloads.all());
        } else {
            LOGGER.warn("Trace resources not on classpath; running synthetic workloads only");
        }
        for (Workload workload : workloads) {
            Path csv = CsvWriter.metricsPath("QuantileAccuracy", "accuracy-" + workload.name() + ".csv");
            // Chart-ready wide form (one row per strategy, one column per quantile) at the longest stream.
            Path bars = CsvWriter.metricsPath("QuantileAccuracy", "bars-" + workload.name() + ".csv");
            try (CsvWriter out = new CsvWriter(csv,
                    "strategy", "quantile", "stream_length", "mean_rel_error", "ci95");
                 CsvWriter wide = new CsvWriter(bars,
                         "strategy", "p50", "p90", "p95", "p99", "p999")) {
                for (int streamLength : STREAM_LENGTHS) {
                    runWorkload(workload, streamLength, out, streamLength == maxLength ? wide : null);
                }
            }
            LOGGER.info("Wrote accuracy CSVs for workload '{}' to {}", workload.name(), csv);
        }
    }

    private void runWorkload(Workload workload, int streamLength, CsvWriter out, CsvWriter wide) {
        double[] quantiles = StrategyCatalog.TARGET_QUANTILES;
        List<String> strategyNames = StrategyCatalog.all(Clock.SYSTEM, 1L).stream()
                .map(StrategyCatalog.NamedStrategy::name).toList();

        for (String strategyName : strategyNames) {
            Statistics[] errorStats = new Statistics[quantiles.length];
            for (int i = 0; i < quantiles.length; i++) {
                errorStats[i] = new Statistics();
            }

            for (int rep = 0; rep < REPETITIONS; rep++) {
                long seed = BASE_SEED + rep;
                long[] values = workload.generate(streamLength, seed);

                Clock.Manual clock = new Clock.Manual();
                long step = Math.max(1, StrategyCatalog.TRACE_SPAN_NANOS / streamLength);
                SamplingStrategy strategy = StrategyCatalog.create(strategyName, StrategyCatalog.DEFAULT_K, clock, seed);

                ExactQuantileBaseline baseline = new ExactQuantileBaseline();
                for (long value : values) {
                    clock.advanceNanos(step);
                    strategy.update(value, clock.nanoTime());
                    baseline.add(value);
                }
                for (int i = 0; i < quantiles.length; i++) {
                    double truth = baseline.valueAtQuantile(quantiles[i]);
                    double estimate = strategy.valueAtQuantile(quantiles[i]);
                    errorStats[i].add(ExactQuantileBaseline.relativeError(estimate, truth));
                }
            }

            for (int i = 0; i < quantiles.length; i++) {
                out.writeRow(strategyName, quantiles[i], streamLength,
                        errorStats[i].mean(), errorStats[i].ci95HalfWidth());
            }
            if (wide != null) {
                wide.writeRow(strategyName, errorStats[0].mean(), errorStats[1].mean(),
                        errorStats[2].mean(), errorStats[3].mean(), errorStats[4].mean());
            }
        }
    }
}
