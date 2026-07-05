package com.vladmihalcea.phd.reservoir.accuracy;

import com.vladmihalcea.phd.reservoir.api.Clock;
import com.vladmihalcea.phd.reservoir.baseline.ExactQuantileBaseline;
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
import java.util.LinkedHashMap;

/**
 * Experiment E7b (claim C5, paper topic 3.1.6.2): how the forward-decay parameter {@code alpha} trades
 * steady-state accuracy against <em>speed of adaptation</em> after a regime shift. Where E7 measures the
 * error alpha leaves on a stationary tail and E8 measures convergence at the single hardcoded
 * {@code alpha=0.015}, this experiment sweeps alpha over a step change in the latency distribution and
 * records, for each alpha, the p99 error as a function of the number of post-shift observations — and the
 * number of updates each alpha needs to bring p99 within 5% of the new truth.
 * <p>
 * A larger alpha forgets the old regime faster (converges sooner) but retains fewer effective samples
 * (noisier steady state); the hardcoded 0.015 sits at one fixed point on that curve, never chosen for any
 * particular workload. This is the strongest single piece of evidence for the survey's claim that the
 * constant is unvalidated.
 *
 * @author Vlad Mihalcea
 */
public class AlphaConvergenceExperimentTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(AlphaConvergenceExperimentTest.class);

    private static final double[] ALPHAS = {0.001, 0.005, 0.015, 0.05, 0.1};
    private static final int PRE_SHIFT = 200_000;
    private static final int POST_SHIFT = 400_000;
    private static final int[] CHECKPOINTS = {0, 1_000, 5_000, 10_000, 25_000, 50_000, 100_000, 200_000, 399_999};
    private static final double CONVERGED = 0.05;
    private static final int REPETITIONS = Integer.getInteger("rs.reps", 20);
    private static final long BASE_SEED = 6_000L;

    @Test
    public void measureAlphaConvergence() {
        Workload preRegime = SyntheticWorkloads.lognormal("pre", Math.log(3.0), 0.6);
        Workload postRegime = SyntheticWorkloads.lognormal("post", Math.log(30.0), 0.6);

        Path csv = CsvWriter.metricsPath("AlphaConvergence", "alpha-convergence.csv");
        Path wideCsv = CsvWriter.metricsPath("AlphaConvergence", "alpha-convergence-wide.csv");
        Path timeCsv = CsvWriter.metricsPath("AlphaConvergence", "alpha-converge-time.csv");

        LinkedHashMap<Double, double[]> curves = new LinkedHashMap<>();
        LinkedHashMap<Double, Integer> convergeUpdates = new LinkedHashMap<>();

        try (CsvWriter out = new CsvWriter(csv, "alpha", "post_shift_updates", "mean_rel_error", "ci95")) {
            for (double alpha : ALPHAS) {
                Statistics[] stats = new Statistics[CHECKPOINTS.length];
                for (int i = 0; i < stats.length; i++) {
                    stats[i] = new Statistics();
                }

                for (int rep = 0; rep < REPETITIONS; rep++) {
                    long seed = BASE_SEED + rep;
                    long[] pre = preRegime.generate(PRE_SHIFT, seed);
                    long[] post = postRegime.generate(POST_SHIFT, seed ^ 0x5A5A5A);
                    double postTruthP99 = ExactQuantileBaseline.of(post, post.length, 0.99);

                    Clock.Manual clock = new Clock.Manual();
                    long step = Math.max(1, StrategyCatalog.TRACE_SPAN_NANOS / (PRE_SHIFT + POST_SHIFT));
                    ForwardDecayReservoir reservoir = new ForwardDecayReservoir(
                            StrategyCatalog.DEFAULT_K, alpha, clock, seed);

                    for (long value : pre) {
                        clock.advanceNanos(step);
                        reservoir.update(value, clock.nanoTime());
                    }
                    int checkpointIndex = 0;
                    for (int i = 0; i < post.length && checkpointIndex < CHECKPOINTS.length; i++) {
                        clock.advanceNanos(step);
                        reservoir.update(post[i], clock.nanoTime());
                        if (i == CHECKPOINTS[checkpointIndex]) {
                            double estimate = reservoir.valueAtQuantile(0.99);
                            stats[checkpointIndex].add(
                                    ExactQuantileBaseline.relativeError(estimate, postTruthP99));
                            checkpointIndex++;
                        }
                    }
                }

                double[] means = new double[CHECKPOINTS.length];
                int converged = -1;
                for (int i = 0; i < CHECKPOINTS.length; i++) {
                    out.writeRow(alpha, CHECKPOINTS[i], stats[i].mean(), stats[i].ci95HalfWidth());
                    means[i] = stats[i].mean();
                    if (converged < 0 && means[i] <= CONVERGED) {
                        converged = CHECKPOINTS[i];
                    }
                }
                curves.put(alpha, means);
                convergeUpdates.put(alpha, converged);
                LOGGER.info("alpha={} converges to within {} of new p99 after {} updates",
                        alpha, CONVERGED, converged < 0 ? ">" + POST_SHIFT : converged);
            }
        }

        // Chart-ready wide form: one row per checkpoint, one column per alpha.
        String[] header = new String[ALPHAS.length + 1];
        header[0] = "post_shift_updates";
        for (int a = 0; a < ALPHAS.length; a++) {
            header[a + 1] = "a=" + ALPHAS[a];
        }
        try (CsvWriter wide = new CsvWriter(wideCsv, header)) {
            for (int i = 0; i < CHECKPOINTS.length; i++) {
                Object[] row = new Object[ALPHAS.length + 1];
                row[0] = CHECKPOINTS[i];
                for (int a = 0; a < ALPHAS.length; a++) {
                    row[a + 1] = curves.get(ALPHAS[a])[i];
                }
                wide.writeRow(row);
            }
        }
        try (CsvWriter time = new CsvWriter(timeCsv, "alpha", "updates_to_converge")) {
            for (double alpha : ALPHAS) {
                Integer converged = convergeUpdates.get(alpha);
                time.writeRow(alpha, converged < 0 ? POST_SHIFT : converged);
            }
        }
        LOGGER.info("Wrote alpha-convergence CSVs to {}", csv);
    }
}
