package com.vladmihalcea.phd.reservoir.framework;

import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.api.Taxonomy;
import io.prometheus.metrics.core.metrics.Histogram;
import io.prometheus.metrics.model.snapshots.HistogramSnapshot;
import io.prometheus.metrics.model.snapshots.NativeHistogramBuckets;

/**
 * Adapter over the Prometheus Java client's <em>native</em> (sparse, exponential-bucket) histogram, the
 * modern successor to classic fixed-bucket histograms and the mechanism VictoriaMetrics' {@code vmrange}
 * buckets mirror. Every observation is folded into a geometric bucket in {@code O(1)}; the schema
 * self-adjusts (coarsening the resolution) once the number of populated buckets hits the production cap,
 * exactly as the real client does. It performs no reservoir sampling, so it is a {@link Taxonomy#NON_RS}
 * contrast class.
 * <p>
 * {@link #valueAtQuantile(double)} reproduces Prometheus' {@code histogram_quantile} over native buckets:
 * it locates the bucket containing the target rank and interpolates linearly between that bucket's
 * exponential lower and upper bounds.
 *
 * @author Vlad Mihalcea
 */
public class PrometheusNativeHistogram implements SamplingStrategy {

    private final int maxBuckets;
    private Histogram histogram;

    public PrometheusNativeHistogram() {
        this(160);
    }

    /**
     * @param maxBuckets cap on populated native buckets before the schema coarsens (client default is 160)
     */
    public PrometheusNativeHistogram(int maxBuckets) {
        this.maxBuckets = maxBuckets;
        this.histogram = build();
    }

    private Histogram build() {
        return Histogram.builder()
                .name("reservoir_survey_native")
                .help("Native exponential histogram for the reservoir sampling survey")
                .withoutExemplars()
                .nativeOnly()
                .nativeMaxNumberOfBuckets(maxBuckets)
                .build();
    }

    @Override
    public void update(long value) {
        histogram.observe(value);
    }

    @Override
    public double valueAtQuantile(double q) {
        HistogramSnapshot snapshot = (HistogramSnapshot) histogram.collect();
        if (snapshot.getDataPoints().isEmpty()) {
            return Double.NaN;
        }
        HistogramSnapshot.HistogramDataPointSnapshot dp = snapshot.getDataPoints().get(0);
        int schema = dp.getNativeSchema();
        double base = Math.pow(2.0, Math.pow(2.0, -schema));
        NativeHistogramBuckets positive = dp.getNativeBucketsForPositiveValues();

        long zeroCount = dp.getNativeZeroCount();
        long total = zeroCount;
        for (int i = 0; i < positive.size(); i++) {
            total += positive.getCount(i);
        }
        if (total == 0) {
            return Double.NaN;
        }

        // Native buckets are held sorted ascending by index; collect (index, count) to be safe.
        int n = positive.size();
        int[] indices = new int[n];
        long[] counts = new long[n];
        for (int i = 0; i < n; i++) {
            indices[i] = positive.getBucketIndex(i);
            counts[i] = positive.getCount(i);
        }
        sortByIndex(indices, counts);

        double targetRank = q * total;
        double cumulative = zeroCount;
        double zeroThreshold = dp.getNativeZeroThreshold();
        if (targetRank <= cumulative) {
            return zeroThreshold;
        }
        for (int i = 0; i < n; i++) {
            if (targetRank <= cumulative + counts[i]) {
                double upper = Math.pow(base, indices[i]);
                double lower = Math.pow(base, indices[i] - 1);
                double fraction = counts[i] == 0 ? 0.0 : (targetRank - cumulative) / counts[i];
                return lower + fraction * (upper - lower);
            }
            cumulative += counts[i];
        }
        return Math.pow(base, indices[n - 1]);
    }

    private static void sortByIndex(int[] indices, long[] counts) {
        // Insertion sort: bucket sets are small and usually already ordered.
        for (int i = 1; i < indices.length; i++) {
            int idx = indices[i];
            long cnt = counts[i];
            int j = i - 1;
            while (j >= 0 && indices[j] > idx) {
                indices[j + 1] = indices[j];
                counts[j + 1] = counts[j];
                j--;
            }
            indices[j + 1] = idx;
            counts[j + 1] = cnt;
        }
    }

    @Override
    public void reset() {
        histogram = build();
    }

    @Override
    public String id() {
        return "Prometheus-Native(maxb=" + maxBuckets + ")";
    }

    @Override
    public Taxonomy taxonomy() {
        return Taxonomy.NON_RS;
    }
}
