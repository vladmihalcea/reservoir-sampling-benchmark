package com.vladmihalcea.phd.reservoir.workload;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;

/**
 * Reproducible generator for the "realistic" traces named in the abstract (a database connection-pool
 * acquisition trace and an HTTP request-latency trace). It emits {@code timestamp_ns,value_ns} CSVs whose
 * <em>arrival timestamps</em> follow a Poisson process (exponential inter-arrival gaps) and whose latency
 * values follow a heavy-tailed model calibrated to each source, so time-decayed and windowed strategies
 * see realistic inter-arrival spacing rather than the uniform step the synthetic sweep uses.
 * <p>
 * These are model-generated stand-ins committed under {@code resources/traces/} for reproducibility; the
 * same file format accepts a trace captured from a HikariCP/FlexyPool-instrumented run or a Spring Boot
 * app under load ({@link WorkloadTrace#fromCsv}), which is the intended camera-ready replacement. Running
 * {@code main} regenerates the committed CSVs deterministically from a fixed seed.
 *
 * @author Vlad Mihalcea
 */
public final class TraceGenerator {

    private static final long MS = 1_000_000L;
    private static final long MAX_NANOS = 60_000L * MS; // 60 s ceiling, matching the HdrHistogram range

    /** Default number of observations per committed trace (kept modest so the CSVs stay repo-friendly). */
    public static final int DEFAULT_SIZE = 50_000;

    public static final String CONN_POOL_FILE = "conn-pool-trace.csv";
    public static final String HTTP_LATENCY_FILE = "http-latency-trace.csv";

    private TraceGenerator() {
    }

    /**
     * @param args optional {@code [outputDir] [size]}; defaults to {@code core/src/main/resources/traces}
     *             and {@link #DEFAULT_SIZE}.
     */
    public static void main(String[] args) {
        Path dir = Path.of(args.length > 0 ? args[0] : "core/src/main/resources/traces");
        int size = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_SIZE;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        writeCsv(dir.resolve(CONN_POOL_FILE), connectionPool(size, 20250706L));
        writeCsv(dir.resolve(HTTP_LATENCY_FILE), httpLatency(size, 20250707L));
        System.out.printf("Wrote %d-row traces to %s%n", size, dir.toAbsolutePath());
    }

    /**
     * Connection-pool acquisition: high arrival rate (~5k req/s), mostly sub-millisecond, with rare
     * multi-second saturation spikes (2% of acquisitions) when the pool is exhausted.
     */
    public static long[][] connectionPool(int n, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        double meanInterArrivalNanos = 200_000.0; // ~5k acquisitions/second
        return build(n, rnd, meanInterArrivalNanos, r -> {
            if (r.nextDouble() < 0.02) {
                return clamp((500 + r.nextDouble() * 4500) * MS); // saturation spike: 0.5–5 s
            }
            return clamp(Math.exp(Math.log(0.4) + 0.8 * gaussian(r)) * MS); // sub-ms body
        });
    }

    /**
     * HTTP request latency: moderate arrival rate (~1k req/s), lognormal body around ~8 ms with a heavier
     * right tail (5% of requests draw from a slow 50–500 ms component — GC pauses, cold caches, upstream).
     */
    public static long[][] httpLatency(int n, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        double meanInterArrivalNanos = 1_000_000.0; // ~1k requests/second
        return build(n, rnd, meanInterArrivalNanos, r -> {
            if (r.nextDouble() < 0.05) {
                return clamp((50 + r.nextDouble() * 450) * MS); // slow-path tail: 50–500 ms
            }
            return clamp(Math.exp(Math.log(8.0) + 0.7 * gaussian(r)) * MS); // ~8 ms lognormal body
        });
    }

    private interface LatencyModel {
        long sample(SplittableRandom rnd);
    }

    private static long[][] build(int n, SplittableRandom rnd, double meanInterArrivalNanos, LatencyModel model) {
        long[] timestamps = new long[n];
        long[] values = new long[n];
        long clock = 0;
        for (int i = 0; i < n; i++) {
            // Exponential inter-arrival gap ⇒ Poisson arrival process.
            double gap = -Math.log(Math.max(rnd.nextDouble(), Double.MIN_VALUE)) * meanInterArrivalNanos;
            clock += Math.max(1, (long) gap);
            timestamps[i] = clock;
            values[i] = model.sample(rnd);
        }
        return new long[][]{timestamps, values};
    }

    private static void writeCsv(Path file, long[][] tsAndValues) {
        long[] timestamps = tsAndValues[0];
        long[] values = tsAndValues[1];
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("timestamp_ns,value_ns\n");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < values.length; i++) {
                sb.setLength(0);
                sb.append(timestamps[i]).append(',').append(values[i]).append('\n');
                w.write(sb.toString());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static double gaussian(SplittableRandom rnd) {
        double u1 = Math.max(rnd.nextDouble(), Double.MIN_VALUE);
        double u2 = rnd.nextDouble();
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
    }

    private static long clamp(double nanos) {
        if (nanos < 1.0) {
            return 1L;
        }
        return (long) Math.min(nanos, MAX_NANOS);
    }
}
