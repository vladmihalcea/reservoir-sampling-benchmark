package com.vladmihalcea.phd.reservoir.framework;

import com.codahale.metrics.ExponentiallyDecayingReservoir;
import com.codahale.metrics.Snapshot;
import com.vladmihalcea.phd.reservoir.api.Clock;
import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.api.Taxonomy;

/**
 * Adapter over Dropwizard Metrics' {@code ExponentiallyDecayingReservoir}, the forward-decay reservoir
 * whose decay parameter {@code alpha} is hardcoded to {@code 0.015} in the library. The adapter drives
 * Dropwizard's internal clock from the injected {@link Clock} so the sensitivity study can replay a
 * trace against wall-clock-independent logical time, and it exposes {@code alpha} so the fixed-0.015
 * production value can be compared with the parametric {@code ForwardDecayReservoir}.
 *
 * @author Vlad Mihalcea
 */
public class DropwizardExpDecay implements SamplingStrategy {

    public static final int DEFAULT_SIZE = 1028;
    public static final double DEFAULT_ALPHA = 0.015;

    private final int size;
    private final double alpha;
    private final com.codahale.metrics.Clock dropwizardClock;
    private ExponentiallyDecayingReservoir reservoir;

    public DropwizardExpDecay() {
        this(DEFAULT_SIZE, DEFAULT_ALPHA, Clock.SYSTEM);
    }

    public DropwizardExpDecay(int size, double alpha, Clock clock) {
        this.size = size;
        this.alpha = alpha;
        this.dropwizardClock = new com.codahale.metrics.Clock() {
            @Override
            public long getTick() {
                return clock.nanoTime();
            }

            @Override
            public long getTime() {
                return clock.nanoTime() / 1_000_000L;
            }
        };
        this.reservoir = new ExponentiallyDecayingReservoir(size, alpha, dropwizardClock);
    }

    @Override
    public void update(long value) {
        reservoir.update(value);
    }

    @Override
    public void update(long value, long timeNanos) {
        // Dropwizard's reservoir reads its own clock, which the injected Clock already advances.
        reservoir.update(value);
    }

    @Override
    public double valueAtQuantile(double q) {
        Snapshot snapshot = reservoir.getSnapshot();
        return snapshot.getValue(q);
    }

    @Override
    public void reset() {
        reservoir = new ExponentiallyDecayingReservoir(size, alpha, dropwizardClock);
    }

    @Override
    public String id() {
        return "Dropwizard-ExpDecay(a=" + alpha + ",k=" + size + ")";
    }

    @Override
    public Taxonomy taxonomy() {
        return Taxonomy.WRS_N;
    }
}
