package com.vladmihalcea.phd.reservoir.workload;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

/**
 * A materialized stream of nanosecond values with matching logical arrival timestamps. The timestamps
 * let time-decayed and windowed strategies be replayed deterministically: a synthetic trace spreads the
 * observations evenly across a configurable wall span, so a regime shift at the middle of the value
 * array coincides with the middle of the time span.
 *
 * @author Vlad Mihalcea
 */
public final class WorkloadTrace {

    private final String name;
    private final long[] values;
    private final long[] timestampsNanos;

    private WorkloadTrace(String name, long[] values, long[] timestampsNanos) {
        this.name = name;
        this.values = values;
        this.timestampsNanos = timestampsNanos;
    }

    /**
     * Wraps already-materialized value and timestamp arrays (e.g. parsed from a captured trace resource).
     */
    public static WorkloadTrace of(String name, long[] values, long[] timestampsNanos) {
        return new WorkloadTrace(name, values, timestampsNanos);
    }

    /**
     * Builds a trace from a workload, spreading {@code n} observations evenly over {@code spanNanos}.
     */
    public static WorkloadTrace synthetic(Workload workload, int n, long seed, long spanNanos) {
        long[] values = workload.generate(n, seed);
        long[] timestamps = new long[n];
        long step = n > 1 ? spanNanos / (n - 1) : 0;
        for (int i = 0; i < n; i++) {
            timestamps[i] = i * step;
        }
        return new WorkloadTrace(workload.name(), values, timestamps);
    }

    /**
     * Loads a captured trace from a CSV file with a {@code timestamp_ns,value_ns} header; a file whose name ends in
     * {@code .gz} is read as gzip-compressed.
     */
    public static WorkloadTrace fromCsv(String name, Path csv) {
        try (BufferedReader reader = open(csv)) {
            long[] values = new long[1 << 16];
            long[] timestamps = new long[1 << 16];
            int n = 0;
            String line = reader.readLine(); // header
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                int comma = line.indexOf(',');
                if (n == values.length) {
                    values = Arrays.copyOf(values, n * 2);
                    timestamps = Arrays.copyOf(timestamps, n * 2);
                }
                timestamps[n] = Long.parseLong(line.substring(0, comma).trim());
                values[n] = Long.parseLong(line.substring(comma + 1).trim());
                n++;
            }
            return new WorkloadTrace(name, Arrays.copyOf(values, n), Arrays.copyOf(timestamps, n));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static BufferedReader open(Path csv) throws IOException {
        if (csv.getFileName().toString().endsWith(".gz")) {
            return new BufferedReader(new InputStreamReader(
                    new GZIPInputStream(Files.newInputStream(csv), 1 << 16), StandardCharsets.US_ASCII), 1 << 16);
        }
        return Files.newBufferedReader(csv);
    }

    public String name() {
        return name;
    }

    public long[] values() {
        return values;
    }

    public long[] timestampsNanos() {
        return timestampsNanos;
    }

    public int size() {
        return values.length;
    }
}
