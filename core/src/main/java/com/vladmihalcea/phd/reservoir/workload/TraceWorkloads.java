package com.vladmihalcea.phd.reservoir.workload;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the committed "realistic" traces (see {@link TraceGenerator}) from the classpath and exposes them
 * two ways: as timestamped {@link WorkloadTrace}s (for time-aware replay that honours the recorded
 * inter-arrival gaps) and as plain {@link Workload}s (values only) so the captured traces drop straight
 * into the value-based accuracy sweep (E6) alongside the synthetic distributions. A trace-backed
 * {@code Workload} ignores the requested seed — a captured trace is a fixed sample — and truncates to the
 * requested length.
 *
 * @author Vlad Mihalcea
 */
public final class TraceWorkloads {

    private static final String RESOURCE_DIR = "/traces/";

    private TraceWorkloads() {
    }

    public static WorkloadTrace connectionPoolTrace() {
        return load("conn-pool", RESOURCE_DIR + TraceGenerator.CONN_POOL_FILE);
    }

    public static WorkloadTrace httpLatencyTrace() {
        return load("http-latency", RESOURCE_DIR + TraceGenerator.HTTP_LATENCY_FILE);
    }

    /** Trace-backed workloads, named {@code <source>-trace} to distinguish them from the synthetic shapes. */
    public static List<Workload> all() {
        List<Workload> list = new ArrayList<>();
        for (WorkloadTrace trace : List.of(connectionPoolTrace(), httpLatencyTrace())) {
            list.add(asWorkload(trace));
        }
        return list;
    }

    /** Whether the committed trace resources are present on the classpath. */
    public static boolean available() {
        try (InputStream in = TraceWorkloads.class.getResourceAsStream(
                RESOURCE_DIR + TraceGenerator.CONN_POOL_FILE)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static Workload asWorkload(WorkloadTrace trace) {
        long[] values = trace.values();
        String name = trace.name() + "-trace";
        return new Workload() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public long[] generate(int n, long seed) {
                int len = Math.min(n, values.length);
                long[] out = new long[len];
                System.arraycopy(values, 0, out, 0, len);
                return out;
            }
        };
    }

    private static WorkloadTrace load(String name, String resource) {
        try (InputStream in = TraceWorkloads.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Trace resource not found on classpath: " + resource
                        + " — run TraceGenerator to (re)create it under src/main/resources/traces.");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                List<long[]> rows = new ArrayList<>();
                String line = reader.readLine(); // header
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    int comma = line.indexOf(',');
                    long ts = Long.parseLong(line.substring(0, comma).trim());
                    long value = Long.parseLong(line.substring(comma + 1).trim());
                    rows.add(new long[]{ts, value});
                }
                long[] values = new long[rows.size()];
                long[] timestamps = new long[rows.size()];
                for (int i = 0; i < rows.size(); i++) {
                    timestamps[i] = rows.get(i)[0];
                    values[i] = rows.get(i)[1];
                }
                return WorkloadTrace.of(name, values, timestamps);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
