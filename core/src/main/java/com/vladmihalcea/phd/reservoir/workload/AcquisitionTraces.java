package com.vladmihalcea.phd.reservoir.workload;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Loads the connection-acquisition traces captured by the virtual-thread starvation driver of the
 * {@code starvation} module: one {@code timestamp_ns,value_ns} CSV per run, holding
 * every acquisition of the measurement window (including the waits that ended in a timeout) in completion
 * order. The timestamps are {@code System.nanoTime()} readings, so they are rebased to start at zero.
 * <p>
 * These are lossless ground-truth streams, unlike the synthetic {@link TraceGenerator} traces, so a
 * strategy replayed over one can be scored on the extreme tail (p99.9 and the maximum) that pool
 * starvation produces.
 *
 * @author Vlad Mihalcea
 */
public final class AcquisitionTraces {

    /** Default location, relative to a module directory: the shared metrics folder of this repository. */
    public static final String DEFAULT_DIR = "../metrics/VirtualThreads/traces";

    private AcquisitionTraces() {
    }

    /** The trace directory, from {@code -Drs.acquisitionTraces} or {@link #DEFAULT_DIR}. */
    public static Path directory() {
        return Path.of(System.getProperty("rs.acquisitionTraces", DEFAULT_DIR));
    }

    /** Every {@code *.csv} trace in the directory, sorted by file name, or an empty list if there is none. */
    public static List<WorkloadTrace> load(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> csvs = files.filter(p -> p.getFileName().toString().endsWith(".csv")).sorted().toList();
            List<WorkloadTrace> traces = new ArrayList<>(csvs.size());
            for (Path csv : csvs) {
                String name = csv.getFileName().toString().replaceFirst("\\.csv$", "");
                traces.add(rebased(WorkloadTrace.fromCsv(name, csv)));
            }
            return traces;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static WorkloadTrace rebased(WorkloadTrace trace) {
        long[] timestamps = trace.timestampsNanos().clone();
        if (timestamps.length > 0) {
            long origin = timestamps[0];
            for (long t : timestamps) {
                origin = Math.min(origin, t);
            }
            for (int i = 0; i < timestamps.length; i++) {
                timestamps[i] -= origin;
            }
        }
        return WorkloadTrace.of(trace.name(), trace.values(), timestamps);
    }
}
