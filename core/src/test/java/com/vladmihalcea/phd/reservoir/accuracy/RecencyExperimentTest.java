package com.vladmihalcea.phd.reservoir.accuracy;

import com.vladmihalcea.phd.reservoir.api.Clock;
import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.catalog.StrategyCatalog;
import com.vladmihalcea.phd.reservoir.baseline.ExactQuantileBaseline;
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
 * Experiment E8: how quickly each strategy's p99 estimate tracks a step change in the latency
 * distribution. A pre-shift low-latency regime is followed by a high-latency regime; the target is the
 * exact p99 of the post-shift distribution, and we record the p99 error at increasing numbers of
 * post-shift observations. This produces the survey's most intuitive figure — a uniform reservoir
 * converges slowly (its old samples linger), while forward decay, the sliding window and the
 * VictoriaMetrics swap adapt at different, measurable rates.
 *
 * @author Vlad Mihalcea
 */
public class RecencyExperimentTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(RecencyExperimentTest.class);

    private static final List<String> STRATEGIES = List.of(
            "Vitter-R", "ES-A-ExpJ", "ForwardDecay-0.015", "Dropwizard-ExpDecay",
            "Dropwizard-SlidingWindow", "VictoriaMetrics");

    private static final int PRE_SHIFT = 200_000;
    private static final int POST_SHIFT = 400_000;
    private static final int[] CHECKPOINTS = {0, 1_000, 5_000, 10_000, 50_000, 100_000, 200_000, 399_999};
    private static final int REPETITIONS = Integer.getInteger("rs.reps", 20);
    private static final long BASE_SEED = 5_000L;

    @Test
    public void measureRecency() {
        Path csv = CsvWriter.metricsPath("Recency", "recency.csv");
        Path wideCsv = CsvWriter.metricsPath("Recency", "recency-wide.csv");
        Workload preRegime = SyntheticWorkloads.lognormal("pre", Math.log(3.0), 0.6);
        Workload postRegime = SyntheticWorkloads.lognormal("post", Math.log(30.0), 0.6);

        java.util.LinkedHashMap<String, double[]> curves = new java.util.LinkedHashMap<>();

        try (CsvWriter out = new CsvWriter(csv,
                "strategy", "post_shift_updates", "mean_rel_error", "ci95")) {
            for (String strategyName : STRATEGIES) {
                Statistics[] stats = new Statistics[CHECKPOINTS.length];
                for (int i = 0; i < stats.length; i++) {
                    stats[i] = new Statistics();
                }

                for (int rep = 0; rep < REPETITIONS; rep++) {
                    long seed = BASE_SEED + rep;
                    long[] pre = preRegime.generate(PRE_SHIFT, seed);
                    long[] post = postRegime.generate(POST_SHIFT, seed ^ 0xABCDEF);
                    double postTruthP99 = ExactQuantileBaseline.of(post, post.length, 0.99);

                    Clock.Manual clock = new Clock.Manual();
                    long step = Math.max(1, StrategyCatalog.TRACE_SPAN_NANOS / (PRE_SHIFT + POST_SHIFT));
                    SamplingStrategy strategy = StrategyCatalog.create(
                            strategyName, StrategyCatalog.DEFAULT_K, clock, seed);

                    for (long value : pre) {
                        clock.advanceNanos(step);
                        strategy.update(value, clock.nanoTime());
                    }
                    int checkpointIndex = 0;
                    for (int i = 0; i < post.length && checkpointIndex < CHECKPOINTS.length; i++) {
                        clock.advanceNanos(step);
                        strategy.update(post[i], clock.nanoTime());
                        if (i == CHECKPOINTS[checkpointIndex]) {
                            double estimate = strategy.valueAtQuantile(0.99);
                            stats[checkpointIndex].add(
                                    ExactQuantileBaseline.relativeError(estimate, postTruthP99));
                            checkpointIndex++;
                        }
                    }
                }

                double[] means = new double[CHECKPOINTS.length];
                for (int i = 0; i < CHECKPOINTS.length; i++) {
                    out.writeRow(strategyName, CHECKPOINTS[i], stats[i].mean(), stats[i].ci95HalfWidth());
                    means[i] = stats[i].mean();
                }
                curves.put(strategyName, means);
                LOGGER.info("Recency curve done for {}", strategyName);
            }
        }

        // Chart-ready wide form: one row per checkpoint, one column per strategy.
        String[] header = new String[STRATEGIES.size() + 1];
        header[0] = "post_shift_updates";
        for (int s = 0; s < STRATEGIES.size(); s++) {
            header[s + 1] = STRATEGIES.get(s);
        }
        try (CsvWriter wide = new CsvWriter(wideCsv, header)) {
            for (int i = 0; i < CHECKPOINTS.length; i++) {
                Object[] row = new Object[STRATEGIES.size() + 1];
                row[0] = CHECKPOINTS[i];
                for (int s = 0; s < STRATEGIES.size(); s++) {
                    row[s + 1] = curves.get(STRATEGIES.get(s))[i];
                }
                wide.writeRow(row);
            }
        }
        LOGGER.info("Wrote recency CSVs to {}", csv);
    }
}
