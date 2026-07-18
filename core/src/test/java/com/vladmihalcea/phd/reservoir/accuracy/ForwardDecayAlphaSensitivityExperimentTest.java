package com.vladmihalcea.phd.reservoir.accuracy;

import com.vladmihalcea.phd.reservoir.api.Clock;
import com.vladmihalcea.phd.reservoir.impl.ForwardDecayReservoir;
import com.vladmihalcea.phd.reservoir.baseline.ExactQuantileBaseline;
import com.vladmihalcea.phd.reservoir.util.CsvWriter;
import com.vladmihalcea.phd.reservoir.util.Statistics;
import com.vladmihalcea.phd.reservoir.catalog.StrategyCatalog;
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
 *
 * @author Vlad Mihalcea
 */
public class ForwardDecayAlphaSensitivityExperimentTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ForwardDecayAlphaSensitivityExperimentTest.class);

    private static final double[] ALPHAS = {0.001, 0.005, 0.015, 0.05, 0.1};
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

        for (Workload workload : workloads) {
            Path csv = CsvWriter.metricsPath("AlphaSensitivity", "alpha-" + workload.name() + ".csv");
            // Chart-ready wide form: one row per alpha, one column per tail quantile.
            Path wideCsv = CsvWriter.metricsPath("AlphaSensitivity", "alpha-wide-" + workload.name() + ".csv");
            try (CsvWriter out = new CsvWriter(csv,
                    "alpha", "quantile", "workload", "mean_rel_error", "ci95");
                 CsvWriter wide = new CsvWriter(wideCsv, "alpha", "p95", "p99", "p999")) {
                for (double alpha : ALPHAS) {
                    measure(workload, alpha, out, wide);
                }
            }
            LOGGER.info("Wrote alpha-sensitivity CSVs for '{}' to {}", workload.name(), csv);
        }
    }

    private void measure(Workload workload, double alpha, CsvWriter out, CsvWriter wide) {
        Statistics[] stats = new Statistics[TAIL_QUANTILES.length];
        for (int i = 0; i < stats.length; i++) {
            stats[i] = new Statistics();
        }
        int recentSuffix = (int) (STREAM_LENGTH * RECENT_FRACTION);

        for (int rep = 0; rep < REPETITIONS; rep++) {
            long seed = BASE_SEED + rep;
            long[] values = workload.generate(STREAM_LENGTH, seed);

            Clock.Manual clock = new Clock.Manual();
            long step = Math.max(1, StrategyCatalog.TRACE_SPAN_NANOS / STREAM_LENGTH);
            ForwardDecayReservoir reservoir = new ForwardDecayReservoir(
                    StrategyCatalog.DEFAULT_K, alpha, clock, seed);

            ExactQuantileBaseline baseline = new ExactQuantileBaseline();
            for (long value : values) {
                clock.advanceNanos(step);
                reservoir.update(value, clock.nanoTime());
                baseline.add(value);
            }
            for (int i = 0; i < TAIL_QUANTILES.length; i++) {
                double truth = baseline.valueAtQuantileOverSuffix(TAIL_QUANTILES[i], recentSuffix);
                double estimate = reservoir.valueAtQuantile(TAIL_QUANTILES[i]);
                stats[i].add(ExactQuantileBaseline.relativeError(estimate, truth));
            }
        }
        for (int i = 0; i < TAIL_QUANTILES.length; i++) {
            out.writeRow(alpha, TAIL_QUANTILES[i], workload.name(),
                    stats[i].mean(), stats[i].ci95HalfWidth());
        }
        wide.writeRow(alpha, stats[0].mean(), stats[1].mean(), stats[2].mean());
    }
}
