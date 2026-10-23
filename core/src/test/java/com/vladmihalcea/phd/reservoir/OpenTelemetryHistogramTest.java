package com.vladmihalcea.phd.reservoir;

import com.vladmihalcea.phd.reservoir.baseline.ExactQuantileBaseline;
import com.vladmihalcea.phd.reservoir.framework.OpenTelemetryHistogram;
import com.vladmihalcea.phd.reservoir.workload.SyntheticWorkloads;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The OpenTelemetry SDK adapters. The explicit-bucket histogram cannot track a millisecond-scale p50 with the HTTP
 * semantic-convention buckets (most values fall in the first bucket, 0-5 ms), so instead of the accuracy gate of
 * {@link StrategyCorrectnessTest} it must reproduce Prometheus' {@code histogram_quantile} over the exact bucket
 * counts of the stream; the exponential histogram must track the quantiles closely; both report the exact maximum.
 *
 * @author Vlad Mihalcea
 */
class OpenTelemetryHistogramTest {

    private static final long[] VALUES = SyntheticWorkloads.lognormal().generate(200_000, 7L);

    @Test
    void explicitHistogramReproducesHistogramQuantileOverTheExactCounts() {
        OpenTelemetryHistogram histogram = new OpenTelemetryHistogram(OpenTelemetryHistogram.Kind.EXPLICIT);
        for (long value : VALUES) {
            histogram.update(value);
        }
        List<Double> bounds = OpenTelemetryHistogram.HTTP_DURATION_BOUNDARIES;
        long[] counts = new long[bounds.size() + 1];
        for (long value : VALUES) {
            double seconds = value / 1e9;
            int bucket = 0;
            while (bucket < bounds.size() && seconds > bounds.get(bucket)) {
                bucket++;
            }
            counts[bucket]++;
        }
        for (double q : new double[]{0.5, 0.9, 0.99, 0.999}) {
            double expected = histogramQuantile(bounds, counts, VALUES.length, q) * 1e9;
            assertEquals(expected, histogram.valueAtQuantile(q), expected * 1e-9, "q=" + q);
        }
    }

    @Test
    void exponentialHistogramTracksTheQuantiles() {
        OpenTelemetryHistogram histogram = new OpenTelemetryHistogram(OpenTelemetryHistogram.Kind.EXPONENTIAL);
        ExactQuantileBaseline exact = new ExactQuantileBaseline();
        for (long value : VALUES) {
            histogram.update(value);
            exact.add(value);
        }
        for (double q : new double[]{0.5, 0.9, 0.99, 0.999}) {
            double error = ExactQuantileBaseline.relativeError(histogram.valueAtQuantile(q), exact.valueAtQuantile(q));
            assertTrue(error < 0.05, "q=" + q + " error " + error);
        }
        assertTrue(histogram.scale() > 0, "the 160 buckets should leave more than one bucket per doubling");
    }

    @Test
    void bothReportTheExactMaximumAndForgetOnReset() {
        long max = Arrays.stream(VALUES).max().orElseThrow();
        for (OpenTelemetryHistogram.Kind kind : OpenTelemetryHistogram.Kind.values()) {
            OpenTelemetryHistogram histogram = new OpenTelemetryHistogram(kind);
            for (long value : VALUES) {
                histogram.update(value);
            }
            assertEquals(max, histogram.valueAtQuantile(1.0), max * 1e-9, kind.name());
            histogram.reset();
            assertTrue(Double.isNaN(histogram.valueAtQuantile(0.5)), kind + " should be empty after reset");
        }
    }

    @Test
    void concurrentRecordingKeepsEveryValue() throws Exception {
        OpenTelemetryHistogram histogram = new OpenTelemetryHistogram(OpenTelemetryHistogram.Kind.EXPONENTIAL);
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            int from = t;
            executor.execute(() -> {
                for (int i = from; i < VALUES.length; i += threads) {
                    histogram.update(VALUES[i]);
                }
                done.countDown();
            });
        }
        done.await();
        executor.shutdown();
        long max = Arrays.stream(VALUES).max().orElseThrow();
        assertEquals(max, histogram.valueAtQuantile(1.0), max * 1e-9);
    }

    /** An independent {@code histogram_quantile}: linear interpolation, first bucket from 0, overflow to the top bound. */
    private static double histogramQuantile(List<Double> bounds, long[] counts, long total, double q) {
        double rank = q * total;
        long cumulative = 0;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > 0 && rank <= cumulative + counts[i]) {
                if (i == bounds.size()) {
                    return bounds.get(bounds.size() - 1);
                }
                double lower = i == 0 ? 0 : bounds.get(i - 1);
                return lower + (bounds.get(i) - lower) * (rank - cumulative) / counts[i];
            }
            cumulative += counts[i];
        }
        return bounds.get(bounds.size() - 1);
    }
}
