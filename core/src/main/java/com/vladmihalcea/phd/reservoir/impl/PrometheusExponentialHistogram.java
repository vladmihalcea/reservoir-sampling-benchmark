package com.vladmihalcea.phd.reservoir.impl;

import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.api.Taxonomy;

/**
 * Clean-room fixed-bucket exponential histogram, the non-reservoir-sampling contrast class that
 * represents how Prometheus (and VictoriaMetrics' {@code vmrange} buckets) actually compute quantiles
 * in production: every observation is folded into a pre-allocated geometric bucket on {@code O(1)}
 * insert, and {@code valueAtQuantile} interpolates linearly inside the containing bucket, exactly as
 * Prometheus' {@code histogram_quantile} does. Memory is fixed regardless of stream length and no
 * randomness is involved, which is the whole point the survey makes about the "practice" side
 * diverging from reservoir-sampling "theory".
 *
 * @author Vlad Mihalcea
 */
public class PrometheusExponentialHistogram implements SamplingStrategy {

    private final double minValue;
    private final double logBase;
    private final long[] counts;
    private final double[] upperBounds;
    private long total;
    private long underflow;

    /**
     * @param minValue    lower edge of the first finite bucket (values below fall in an underflow bucket)
     * @param maxValue    upper edge of the last finite bucket
     * @param bucketCount number of geometric buckets spanning [minValue, maxValue]
     */
    public PrometheusExponentialHistogram(double minValue, double maxValue, int bucketCount) {
        this.minValue = minValue;
        this.logBase = Math.log(maxValue / minValue) / bucketCount;
        this.counts = new long[bucketCount];
        this.upperBounds = new double[bucketCount];
        for (int i = 0; i < bucketCount; i++) {
            upperBounds[i] = minValue * Math.exp(logBase * (i + 1));
        }
    }

    @Override
    public void update(long value) {
        total++;
        if (value < minValue) {
            underflow++;
            return;
        }
        int idx = (int) (Math.log(value / minValue) / logBase);
        if (idx >= counts.length) {
            idx = counts.length - 1;
        }
        counts[idx]++;
    }

    @Override
    public double valueAtQuantile(double q) {
        if (total == 0) {
            return Double.NaN;
        }
        double targetRank = q * total;
        long cumulative = underflow;
        if (targetRank <= cumulative) {
            return minValue;
        }
        double lowerBound = minValue;
        for (int i = 0; i < counts.length; i++) {
            long bucket = counts[i];
            if (bucket > 0 && targetRank <= cumulative + bucket) {
                double fraction = (targetRank - cumulative) / bucket;
                return lowerBound + fraction * (upperBounds[i] - lowerBound);
            }
            cumulative += bucket;
            lowerBound = upperBounds[i];
        }
        return upperBounds[counts.length - 1];
    }

    @Override
    public void reset() {
        java.util.Arrays.fill(counts, 0L);
        total = 0;
        underflow = 0;
    }

    @Override
    public String id() {
        return "Prometheus-Histogram(b=" + counts.length + ")";
    }

    @Override
    public Taxonomy taxonomy() {
        return Taxonomy.NON_RS;
    }
}
