package com.vladmihalcea.phd.reservoir.impl;

import com.vladmihalcea.phd.reservoir.api.Clock;
import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.api.Taxonomy;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * Clean-room, parametric implementation of Cormode's forward-decay reservoir (ICDE 2009), i.e. the
 * algorithm behind Dropwizard's {@code ExponentiallyDecayingReservoir}. Unlike Dropwizard, which
 * hardcodes the decay parameter {@code alpha = 0.015}, this class exposes {@code alpha} as a
 * constructor argument so that the sensitivity study (experiment E7 / paper topic 3.1.6.2) can sweep
 * it, and it takes an injectable {@link Clock} so the sweep is fully deterministic.
 * <p>
 * Each retained observation keeps a decay weight {@code g(t - L) = exp(alpha * (t - L))} relative to a
 * landmark {@code L}; quantiles are read from a weighted snapshot exactly as Dropwizard does. When the
 * elapsed time crosses the rescale threshold the landmark is advanced and all weights renormalised to
 * avoid floating-point overflow.
 *
 * @author Vlad Mihalcea
 */
public class ForwardDecayReservoir implements SamplingStrategy {

    private static final class WeightedSample {
        final long value;
        final double weight;

        WeightedSample(long value, double weight) {
            this.value = value;
            this.weight = weight;
        }
    }

    private final int k;
    private final double alpha;
    private final long rescaleThresholdNanos;
    private final Clock clock;
    private final java.util.Random random;
    private final TreeMap<Double, WeightedSample> samples = new TreeMap<>();

    private long landmarkNanos;
    private long nextRescaleNanos;

    public ForwardDecayReservoir(int k, double alpha, Clock clock, long seed) {
        this(k, alpha, TimeUnit.HOURS.toNanos(1), clock, seed);
    }

    public ForwardDecayReservoir(int k, double alpha, long rescaleThresholdNanos, Clock clock, long seed) {
        this.k = k;
        this.alpha = alpha;
        this.rescaleThresholdNanos = rescaleThresholdNanos;
        this.clock = clock;
        this.random = new java.util.Random(seed);
        this.landmarkNanos = clock.nanoTime();
        this.nextRescaleNanos = landmarkNanos + rescaleThresholdNanos;
    }

    @Override
    public void update(long value) {
        update(value, clock.nanoTime());
    }

    @Override
    public void update(long value, long timeNanos) {
        rescaleIfNeeded(timeNanos);
        double elapsedSeconds = (timeNanos - landmarkNanos) / 1e9;
        double itemWeight = Math.exp(alpha * elapsedSeconds);
        double u = random.nextDouble();
        if (u == 0.0) {
            u = Double.MIN_VALUE;
        }
        double priority = itemWeight / u;

        if (samples.size() < k) {
            samples.put(priority, new WeightedSample(value, itemWeight));
        } else {
            double first = samples.firstKey();
            if (first < priority && samples.putIfAbsent(priority, new WeightedSample(value, itemWeight)) == null) {
                samples.pollFirstEntry();
            }
        }
    }

    private void rescaleIfNeeded(long timeNanos) {
        if (timeNanos < nextRescaleNanos) {
            return;
        }
        long oldLandmark = landmarkNanos;
        landmarkNanos = timeNanos;
        nextRescaleNanos = timeNanos + rescaleThresholdNanos;
        double scale = Math.exp(-alpha * ((landmarkNanos - oldLandmark) / 1e9));

        TreeMap<Double, WeightedSample> rescaled = new TreeMap<>();
        for (var entry : samples.entrySet()) {
            WeightedSample old = entry.getValue();
            rescaled.put(entry.getKey() * scale, new WeightedSample(old.value, old.weight * scale));
        }
        samples.clear();
        samples.putAll(rescaled);
    }

    @Override
    public double valueAtQuantile(double q) {
        if (samples.isEmpty()) {
            return Double.NaN;
        }
        List<WeightedSample> ordered = new ArrayList<>(samples.values());
        ordered.sort((a, b) -> Long.compare(a.value, b.value));

        double totalWeight = 0.0;
        for (WeightedSample s : ordered) {
            totalWeight += s.weight;
        }
        // Weighted snapshot quantile, matching Dropwizard's WeightedSnapshot.
        double[] cumulative = new double[ordered.size()];
        double running = 0.0;
        for (int i = 0; i < ordered.size(); i++) {
            running += ordered.get(i).weight / totalWeight;
            cumulative[i] = running;
        }
        int pos = java.util.Arrays.binarySearch(cumulative, q);
        if (pos < 0) {
            pos = -pos - 1;
        }
        if (pos < 1) {
            return ordered.get(0).value;
        }
        if (pos >= ordered.size()) {
            return ordered.get(ordered.size() - 1).value;
        }
        return ordered.get(pos).value;
    }

    @Override
    public void reset() {
        samples.clear();
        landmarkNanos = clock.nanoTime();
        nextRescaleNanos = landmarkNanos + rescaleThresholdNanos;
    }

    @Override
    public String id() {
        return "ForwardDecay(a=" + alpha + ",k=" + k + ")";
    }

    @Override
    public Taxonomy taxonomy() {
        return Taxonomy.WRS_N;
    }

    public double alpha() {
        return alpha;
    }
}
