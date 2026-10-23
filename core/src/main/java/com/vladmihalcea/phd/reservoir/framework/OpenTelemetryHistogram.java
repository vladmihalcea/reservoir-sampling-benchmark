package com.vladmihalcea.phd.reservoir.framework;

import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.api.Taxonomy;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.Base2ExponentialHistogramOptions;
import io.opentelemetry.sdk.metrics.ExplicitBucketHistogramOptions;
import io.opentelemetry.sdk.metrics.InstrumentSelector;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.View;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.ExponentialHistogramBuckets;
import io.opentelemetry.sdk.metrics.data.ExponentialHistogramPointData;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.CollectionRegistration;
import io.opentelemetry.sdk.metrics.export.MetricReader;

import java.util.Collection;
import java.util.List;

/**
 * Adapter over a histogram of the OpenTelemetry Java SDK ({@code opentelemetry-sdk-metrics}), in the two
 * aggregations an application gets:
 * <ul>
 *   <li>{@link Kind#EXPLICIT}: the explicit-bucket histogram with the boundaries that the OpenTelemetry semantic
 *       conventions advise for {@code http.server.request.duration} (in seconds: 5, 10, 25, 50, 75, 100, 250,
 *       500, 750 ms, then 1, 2.5, 5, 7.5, 10 s), the histogram the OpenTelemetry Java agent records for every
 *       HTTP request;</li>
 *   <li>{@link Kind#EXPONENTIAL}: the base-2 exponential histogram with the SDK's defaults (at most 160 buckets,
 *       maximum scale 20), which picks its own resolution and lowers the scale when the range of the values
 *       needs more buckets.</li>
 * </ul>
 * Values are recorded in seconds, as the semantic conventions prescribe, through a {@link DoubleHistogram}
 * without attributes; the SDK keeps its default exemplar filter. A pull {@link MetricReader} with cumulative
 * temporality (the OTLP exporter's default) reads the histogram, so a query covers the whole stream, like the
 * Prometheus histogram adapters.
 * <p>
 * The SDK computes no quantiles: a backend estimates them from the buckets. {@link #valueAtQuantile(double)} does
 * what Prometheus' {@code histogram_quantile} does: it finds the bucket of the target rank and interpolates
 * linearly between its bounds (the explicit histogram's first bucket starts at 0, and a rank in its overflow
 * bucket returns the highest finite boundary). Unlike a Prometheus histogram, an OpenTelemetry histogram also
 * records the minimum and the maximum, so {@code q = 0} and {@code q = 1} return them exactly. It performs no
 * reservoir sampling, so it is a {@link Taxonomy#NON_RS} contrast class.
 *
 * @author Vlad Mihalcea
 */
public class OpenTelemetryHistogram implements SamplingStrategy {

    /** The two aggregations of an OpenTelemetry histogram. */
    public enum Kind {EXPLICIT, EXPONENTIAL}

    /**
     * The bucket boundaries, in seconds, that the HTTP semantic conventions advise for
     * {@code http.server.request.duration} and {@code http.client.request.duration}.
     */
    public static final List<Double> HTTP_DURATION_BOUNDARIES = List.of(
            0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1.0, 2.5, 5.0, 7.5, 10.0);

    /** The SDK's defaults for the base-2 exponential histogram. */
    public static final int DEFAULT_MAX_BUCKETS = 160;
    public static final int DEFAULT_MAX_SCALE = 20;

    private static final String NAME = "reservoir.survey.duration";
    private static final double NANOS_PER_SECOND = 1e9;

    private final Kind kind;
    private SdkMeterProvider provider;
    private PullReader reader;
    private DoubleHistogram histogram;

    public OpenTelemetryHistogram(Kind kind) {
        this.kind = kind;
        build();
    }

    private void build() {
        reader = new PullReader();
        Aggregation aggregation = kind == Kind.EXPLICIT
                ? Aggregation.explicitBucketHistogram(ExplicitBucketHistogramOptions.builder()
                        .setBucketBoundaries(HTTP_DURATION_BOUNDARIES).build())
                : Aggregation.base2ExponentialBucketHistogram(Base2ExponentialHistogramOptions.builder()
                        .setMaxBuckets(DEFAULT_MAX_BUCKETS).setMaxScale(DEFAULT_MAX_SCALE).build());
        provider = SdkMeterProvider.builder()
                .registerMetricReader(reader)
                .registerView(InstrumentSelector.builder().setName(NAME).build(),
                        View.builder().setAggregation(aggregation).build())
                .build();
        histogram = provider.get("reservoir-sampling-benchmark").histogramBuilder(NAME).setUnit("s").build();
    }

    @Override
    public void update(long value) {
        histogram.record(value / NANOS_PER_SECOND);
    }

    @Override
    public double valueAtQuantile(double q) {
        for (MetricData metric : reader.collect()) {
            if (!NAME.equals(metric.getName())) {
                continue;
            }
            double seconds = kind == Kind.EXPLICIT
                    ? explicitQuantile(metric.getHistogramData().getPoints(), q)
                    : exponentialQuantile(metric.getExponentialHistogramData().getPoints(), q);
            return seconds * NANOS_PER_SECOND;
        }
        return Double.NaN;
    }

    private static double explicitQuantile(Collection<HistogramPointData> points, double q) {
        if (points.isEmpty()) {
            return Double.NaN;
        }
        HistogramPointData point = points.iterator().next();
        if (point.getCount() == 0) {
            return Double.NaN;
        }
        if (q <= 0 && point.hasMin()) {
            return point.getMin();
        }
        if (q >= 1 && point.hasMax()) {
            return point.getMax();
        }
        return explicitQuantile(point.getBoundaries(), point.getCounts(), point.getCount(), q);
    }

    /**
     * Prometheus' {@code histogram_quantile} over explicit buckets: {@code counts} has one more entry than
     * {@code boundaries}, the last one for the values above the highest boundary.
     */
    static double explicitQuantile(List<Double> boundaries, List<Long> counts, long total, double q) {
        double rank = q * total;
        double cumulative = 0;
        for (int i = 0; i < counts.size(); i++) {
            long count = counts.get(i);
            if (rank <= cumulative + count && count > 0) {
                if (i == boundaries.size()) {
                    return boundaries.get(boundaries.size() - 1); // the overflow bucket has no upper bound
                }
                double lower = i == 0 ? 0.0 : boundaries.get(i - 1);
                double upper = boundaries.get(i);
                return lower + (upper - lower) * (rank - cumulative) / count;
            }
            cumulative += count;
        }
        return boundaries.get(boundaries.size() - 1);
    }

    private static double exponentialQuantile(Collection<ExponentialHistogramPointData> points, double q) {
        if (points.isEmpty()) {
            return Double.NaN;
        }
        ExponentialHistogramPointData point = points.iterator().next();
        if (point.getCount() == 0) {
            return Double.NaN;
        }
        if (q <= 0 && point.hasMin()) {
            return point.getMin();
        }
        if (q >= 1 && point.hasMax()) {
            return point.getMax();
        }
        ExponentialHistogramBuckets positive = point.getPositiveBuckets();
        double base = Math.pow(2.0, Math.pow(2.0, -point.getScale()));
        double rank = q * point.getCount();
        double cumulative = point.getZeroCount();
        if (rank <= cumulative) {
            return 0.0;
        }
        List<Long> counts = positive.getBucketCounts();
        for (int j = 0; j < counts.size(); j++) {
            long count = counts.get(j);
            if (rank <= cumulative + count && count > 0) {
                // Bucket index i holds the values in (base^i, base^(i+1)].
                int index = positive.getOffset() + j;
                double lower = Math.pow(base, index);
                double upper = Math.pow(base, index + 1);
                return lower + (upper - lower) * (rank - cumulative) / count;
            }
            cumulative += count;
        }
        return point.hasMax() ? point.getMax() : Math.pow(base, positive.getOffset() + counts.size());
    }

    /** The scale the exponential histogram ended at (its resolution: 2^scale buckets per doubling), or 0. */
    public int scale() {
        for (MetricData metric : reader.collect()) {
            if (NAME.equals(metric.getName()) && kind == Kind.EXPONENTIAL) {
                Collection<ExponentialHistogramPointData> points = metric.getExponentialHistogramData().getPoints();
                return points.isEmpty() ? 0 : points.iterator().next().getScale();
            }
        }
        return 0;
    }

    @Override
    public void reset() {
        provider.close();
        build();
    }

    @Override
    public String id() {
        return kind == Kind.EXPLICIT ? "OpenTelemetry-Explicit(http-semconv)"
                : "OpenTelemetry-Exponential(maxb=" + DEFAULT_MAX_BUCKETS + ")";
    }

    @Override
    public Taxonomy taxonomy() {
        return Taxonomy.NON_RS;
    }

    /** A metric reader that collects on demand, with cumulative temporality, as an OTLP exporter would. */
    private static final class PullReader implements MetricReader {

        private volatile CollectionRegistration registration = CollectionRegistration.noop();

        @Override
        public void register(CollectionRegistration registration) {
            this.registration = registration;
        }

        Collection<MetricData> collect() {
            return registration.collectAllMetrics();
        }

        @Override
        public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
            return AggregationTemporality.CUMULATIVE;
        }

        @Override
        public CompletableResultCode forceFlush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
