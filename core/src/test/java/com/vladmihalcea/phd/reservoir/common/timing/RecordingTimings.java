package com.vladmihalcea.phd.reservoir.common.timing;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the request-level measurements of the last iteration of a benchmark that drives a shared latency recorder
 * ({@code VirtualThreadRecorderBenchmark}): the latency of the recorder's {@code update} call, the response time of
 * the requests, the scrape time, and the process CPU load. The benchmark publishes them at the end of every
 * iteration, and the {@link RecordingProfiler} reports them as the iteration's secondary results, the way the
 * layer profiler of the TOSEM harness reports the time per layer.
 *
 * @author Vlad Mihalcea
 */
public final class RecordingTimings {

    /**
     * One measurement of an iteration.
     *
     * @param label the secondary result's name, e.g. {@code rec.update.p99.ns}
     * @param value the value of this iteration
     * @param unit  the unit
     * @param max   whether the iterations aggregate by maximum instead of by average
     */
    public record Measurement(String label, double value, String unit, boolean max) {
    }

    private static List<Measurement> lastIteration = List.of();

    private RecordingTimings() {
    }

    /** Called by the benchmark at the end of an iteration. */
    public static synchronized void publish(List<Measurement> measurements) {
        lastIteration = new ArrayList<>(measurements);
    }

    /** Returns and clears the measurements of the last iteration. */
    public static synchronized List<Measurement> take() {
        List<Measurement> measurements = lastIteration;
        lastIteration = List.of();
        return measurements;
    }
}
