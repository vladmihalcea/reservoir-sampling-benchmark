package com.vladmihalcea.phd.reservoir.framework;

import com.codahale.metrics.SlidingTimeWindowArrayReservoir;
import com.codahale.metrics.Snapshot;
import com.vladmihalcea.phd.reservoir.api.Clock;
import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.api.Taxonomy;

import java.util.concurrent.TimeUnit;

/**
 * Adapter over Dropwizard Metrics' {@code SlidingTimeWindowArrayReservoir}, which retains <em>every</em>
 * observation from the last N seconds rather than a bounded sample. It is included as the "unbounded
 * memory, exact recent window" extreme of the design space: it never samples, so it trades memory for
 * accuracy and gives the recency experiment (E8) a fast-reacting but memory-hungry reference point.
 *
 * @author Vlad Mihalcea
 */
public class DropwizardSlidingTimeWindow implements SamplingStrategy {

    private final long windowNanos;
    private final com.codahale.metrics.Clock dropwizardClock;
    private SlidingTimeWindowArrayReservoir reservoir;

    public DropwizardSlidingTimeWindow(long window, TimeUnit unit, Clock clock) {
        this.windowNanos = unit.toNanos(window);
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
        this.reservoir = new SlidingTimeWindowArrayReservoir(windowNanos, TimeUnit.NANOSECONDS, dropwizardClock);
    }

    @Override
    public void update(long value) {
        reservoir.update(value);
    }

    @Override
    public void update(long value, long timeNanos) {
        reservoir.update(value);
    }

    @Override
    public double valueAtQuantile(double q) {
        Snapshot snapshot = reservoir.getSnapshot();
        return snapshot.getValue(q);
    }

    @Override
    public void reset() {
        reservoir = new SlidingTimeWindowArrayReservoir(windowNanos, TimeUnit.NANOSECONDS, dropwizardClock);
    }

    @Override
    public String id() {
        return "Dropwizard-SlidingWindow(" + TimeUnit.NANOSECONDS.toSeconds(windowNanos) + "s)";
    }

    @Override
    public Taxonomy taxonomy() {
        return Taxonomy.NON_RS;
    }
}
