package com.vladmihalcea.phd.reservoir.framework;

import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.api.Taxonomy;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Adapter over a Micrometer {@code DistributionSummary} configured to publish client-side percentiles.
 * Under the hood Micrometer computes these from a ring of HdrHistogram-backed windows, so this adapter
 * shows what Micrometer applications actually get. Because Micrometer materializes percentiles only for
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
        this(publishedQuantiles, minExpected, maxExpected, new SimpleMeterRegistry());
    }

    /**
     * A summary whose percentile and max windows rotate on the given clock instead of the wall clock. With
     * Micrometer's defaults ({@code distributionStatisticExpiry} of 2 minutes over a ring of 3 buffers), the window
     * rotates every 40 seconds, and the published percentiles cover only the last 40 to 80 seconds, not the 2 minutes
     * of the expiry: after a step change, the median shows the new value 21 to 30 s later, and after the last record,
     * the percentiles read 0 within 41 to 59 s while {@link #max()} keeps its value ({@code MicrometerWindowTest}).
     * The time-based recency experiments need this constructor, since a replayed stream spans minutes of logical time
     * in milliseconds of wall time, during which the wall-clock summary would never rotate.
     */
    public MicrometerSummary(double[] publishedQuantiles, com.vladmihalcea.phd.reservoir.api.Clock clock) {
        this(publishedQuantiles, 1.0, 60_000_000_000.0, new SimpleMeterRegistry(SimpleConfig.DEFAULT,
                new io.micrometer.core.instrument.Clock() {
                    @Override
                    public long wallTime() {
                        return clock.nanoTime() / 1_000_000L;
                    }

                    @Override
                    public long monotonicTime() {
                        return clock.nanoTime();
                    }
                }));
    }

    private MicrometerSummary(double[] publishedQuantiles, double minExpected, double maxExpected,
                              SimpleMeterRegistry registry) {
        this.publishedQuantiles = publishedQuantiles.clone();
        this.registry = registry;
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

    /**
     * Micrometer's own maximum, a time-decaying max over the distribution-statistic window. It is what a
     * dashboard shows as the max; {@link #valueAtQuantile} with {@code q = 1.0} would return the nearest
     * published percentile instead.
     */
    public double max() {
        return summary.max();
    }

    public double[] publishedQuantiles() {
        return publishedQuantiles.clone();
    }
}
