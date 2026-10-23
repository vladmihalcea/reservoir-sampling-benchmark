package com.vladmihalcea.phd.reservoir.framework;

import com.codahale.metrics.LockFreeExponentiallyDecayingReservoir;
import com.codahale.metrics.Reservoir;
import com.codahale.metrics.Snapshot;
import com.vladmihalcea.phd.reservoir.api.Clock;
import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.api.Taxonomy;

/**
 * Adapter over Dropwizard Metrics' {@code LockFreeExponentiallyDecayingReservoir}, the lock-free sibling
 * of {@code ExponentiallyDecayingReservoir}: the same forward-decay sampling (size 1028, {@code alpha =
 * 0.015} by default), but each update publishes its sample with a compare-and-set on the reservoir state
 * instead of taking the read lock of a {@code ReentrantReadWriteLock}, and rescaling swaps in a new state
 * rather than holding the write lock. It is the candidate replacement for FlexyPool's default reservoir
 * when thousands of (virtual) threads record into one timer.
 *
 * @author Vlad Mihalcea
 */
public class DropwizardLockFreeExpDecay implements SamplingStrategy {

    private final int size;
    private final double alpha;
    private final com.codahale.metrics.Clock dropwizardClock;
    private Reservoir reservoir;

    public DropwizardLockFreeExpDecay(int size, double alpha, Clock clock) {
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
        this.reservoir = build();
    }

    private Reservoir build() {
        return LockFreeExponentiallyDecayingReservoir.builder()
                .size(size)
                .alpha(alpha)
                .clock(dropwizardClock)
                .build();
    }

    @Override
    public void update(long value) {
        reservoir.update(value);
    }

    @Override
    public void update(long value, long timeNanos) {
        // Like the locking variant, the reservoir reads the injected clock, which the caller advances.
        reservoir.update(value);
    }

    @Override
    public double valueAtQuantile(double q) {
        Snapshot snapshot = reservoir.getSnapshot();
        return snapshot.getValue(q);
    }

    @Override
    public void reset() {
        reservoir = build();
    }

    @Override
    public String id() {
        return "Dropwizard-LockFreeExpDecay(a=" + alpha + ",k=" + size + ")";
    }

    @Override
    public Taxonomy taxonomy() {
        return Taxonomy.WRS_N;
    }
}
