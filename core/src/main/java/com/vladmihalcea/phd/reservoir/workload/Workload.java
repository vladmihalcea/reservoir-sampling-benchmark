package com.vladmihalcea.phd.reservoir.workload;

/**
 * A named, reproducible latency distribution used to drive the sampling strategies. All values are
 * expressed in nanoseconds, matching how APM timers record durations. Every generator is seeded so
 * that accuracy runs are deterministic and comparable across strategies.
 *
 * @author Vlad Mihalcea
 */
public interface Workload {

    String name();

    /**
     * Generates {@code n} nanosecond latency observations from the given seed.
     */
    long[] generate(int n, long seed);
}
