package com.vladmihalcea.phd.reservoir.workload;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Loads the committed "realistic" traces from the classpath and exposes them two ways: as timestamped
 * {@link WorkloadTrace}s (for time-aware replay that honors the recorded inter-arrival gaps) and as plain
 * {@link Workload}s (values only) so the traces drop straight into the value-based accuracy sweep (E6) alongside
 * the synthetic distributions. A trace-backed {@code Workload} ignores the requested seed (a trace is a fixed
 * sample) and truncates to the requested length.
 * <p>
 * The two traces:
 * <ul>
 *   <li>{@code conn-pool-trace.csv.gz}: captured. It is a byte-identical copy of
 *       {@code metrics/Hostner-2026-11/s68/results/starvation-s68-20261117T104133Z/metrics/VirtualThreads/traces/hikari-n10-platform-1000.csv.gz}:
 *       every acquisition (1,397,392 of them in 60 s) of HikariCP with 10 connections and 1,000 platform-thread
 *       clients, trial 0 of V1 on the Hostner pair (17 Nov 2026), the trace that V2 also replays. Of the
 *       1,000-client traces, its shape is the closest to the stand-in it replaces (rare multi-second waits: 1.4% above
 *       1 s against the stand-in's 1.9%), and HikariCP with platform threads is the common production setup.</li>
 *   <li>{@code http-latency-trace.csv}: the model-generated stand-in of {@link TraceGenerator}.</li>
 * </ul>
 * A resource is read gzipped when {@code <name>.gz} is on the classpath, and plain otherwise.
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
        try (InputStream in = open(RESOURCE_DIR + TraceGenerator.CONN_POOL_FILE)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** Opens {@code resource}, or its gzipped copy {@code resource + ".gz"} when that is on the classpath. */
    private static InputStream open(String resource) throws IOException {
        InputStream gzipped = TraceWorkloads.class.getResourceAsStream(resource + ".gz");
        if (gzipped != null) {
            return new GZIPInputStream(gzipped, 1 << 16);
        }
        return TraceWorkloads.class.getResourceAsStream(resource);
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
        try (InputStream in = open(resource)) {
            if (in == null) {
                throw new IllegalStateException("Trace resource not found on classpath: " + resource
                        + " (or " + resource + ".gz) under src/main/resources/traces.");
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
