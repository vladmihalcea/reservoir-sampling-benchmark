package com.vladmihalcea.phd.reservoir.framework;

import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.api.Taxonomy;
import io.prometheus.metrics.core.metrics.Summary;
import io.prometheus.metrics.model.snapshots.Quantile;
import io.prometheus.metrics.model.snapshots.SummarySnapshot;

import java.util.concurrent.TimeUnit;

/**
 * Adapter over the Prometheus Java client's classic {@code Summary}, which estimates quantiles with the
 * Cormode-Korn-Muthukrishnan-Srivastava (CKMS) biased-quantile rank summary rather than reservoir
 * sampling. This is a deliberate <em>contrast</em> class in the Hübschle-Schneider taxonomy (reported as
 * {@link Taxonomy#NON_RS}): it shows what a widely deployed framework gives up (arbitrary post-hoc
 * quantiles) and gains (bounded, deterministic error at pre-declared quantiles) by abandoning sampling.
 * <p>
 * Because CKMS materialises only the quantiles it is told to track, the queried set is fixed at
 * construction and {@link #valueAtQuantile(double)} returns the tracked objective nearest to {@code q},
 * exactly as an operator reading a Prometheus {@code Summary} would. The client's built-in time decay is
 * disabled here (a single, effectively unbounded age bucket) so the estimate is over the whole stream and
 * the accuracy comparison is against the all-time baseline rather than a rolling window.
 *
 * @author Vlad Mihalcea
 */
public class PrometheusSummaryCKMS implements SamplingStrategy {

    private final double[] trackedQuantiles;
    private final Summary summary;

    public PrometheusSummaryCKMS(double[] trackedQuantiles) {
        this.trackedQuantiles = trackedQuantiles.clone();
        Summary.Builder builder = Summary.builder()
                .name("reservoir_survey_ckms")
                .help("CKMS biased-quantile summary for the reservoir sampling survey")
                .withoutExemplars()
                // One age bucket over a very long window ⇒ no rotation during a benchmark run.
                .numberOfAgeBuckets(1)
                .maxAgeSeconds(TimeUnit.DAYS.toSeconds(3650));
        for (double q : trackedQuantiles) {
            builder.quantile(q, errorFor(q));
        }
        this.summary = builder.build();
    }

    /**
     * Per-objective error tolerance, tightened towards the tail so p99/p999 stay usable — the same
     * trade-off a production CKMS configuration makes.
     */
    private static double errorFor(double q) {
        double tail = 1.0 - q;
        return Math.max(tail / 10.0, 1e-4);
    }

    @Override
    public void update(long value) {
        summary.observe(value);
    }

    @Override
    public double valueAtQuantile(double q) {
        SummarySnapshot snapshot = summary.collect();
        if (snapshot.getDataPoints().isEmpty()) {
            return Double.NaN;
        }
        double best = Double.NaN;
        double bestDelta = Double.MAX_VALUE;
        for (Quantile quantile : snapshot.getDataPoints().get(0).getQuantiles()) {
            double delta = Math.abs(quantile.getQuantile() - q);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = quantile.getValue();
            }
        }
        return best;
    }

    @Override
    public void reset() {
        summary.clear();
    }

    @Override
    public String id() {
        return "Prometheus-CKMS";
    }

    @Override
    public Taxonomy taxonomy() {
        return Taxonomy.NON_RS;
    }

    public double[] trackedQuantiles() {
        return trackedQuantiles.clone();
    }
}
