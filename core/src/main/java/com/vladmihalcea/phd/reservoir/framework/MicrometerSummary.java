package com.vladmihalcea.phd.reservoir.framework;

import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.api.Taxonomy;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Adapter over a Micrometer {@code DistributionSummary} configured to publish client-side percentiles.
 * Under the hood Micrometer computes these from a ring of HdrHistogram-backed windows, so this adapter
 * shows what Micrometer applications actually get. Because Micrometer materialises percentiles only for
 * the quantiles it was told to publish, the queried quantile set is fixed at construction; the survey's
 * accuracy experiments query exactly this set.
 *
 * @author Vlad Mihalcea
 */
public class MicrometerSummary implements SamplingStrategy {

    private final double[] publishedQuantiles;
    private final SimpleMeterRegistry registry;
    private final DistributionSummary summary;

    public MicrometerSummary(double[] publishedQuantiles) {
        this(publishedQuantiles, 1.0, 60_000_000_000.0);
    }

    public MicrometerSummary(double[] publishedQuantiles, double minExpected, double maxExpected) {
        this.publishedQuantiles = publishedQuantiles.clone();
        this.registry = new SimpleMeterRegistry();
        this.summary = DistributionSummary.builder("reservoir.survey.summary")
                .publishPercentiles(publishedQuantiles)
                .percentilePrecision(3)
                .minimumExpectedValue(minExpected)
                .maximumExpectedValue(maxExpected)
                .register(registry);
    }

    @Override
    public void update(long value) {
        summary.record(value);
    }

    @Override
    public double valueAtQuantile(double q) {
        ValueAtPercentile nearest = null;
        double bestDelta = Double.MAX_VALUE;
        for (ValueAtPercentile vap : summary.takeSnapshot().percentileValues()) {
            double delta = Math.abs(vap.percentile() - q);
            if (delta < bestDelta) {
                bestDelta = delta;
                nearest = vap;
            }
        }
        return nearest == null ? Double.NaN : nearest.value();
    }

    @Override
    public void reset() {
        // A DistributionSummary cannot be individually reset; a fresh registry mirrors reservoir reset.
        registry.clear();
    }

    @Override
    public String id() {
        return "Micrometer-Summary";
    }

    @Override
    public Taxonomy taxonomy() {
        return Taxonomy.NON_RS;
    }

    public double[] publishedQuantiles() {
        return publishedQuantiles.clone();
    }
}
