package com.vladmihalcea.phd.reservoir.starvation;

import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;

import javax.sql.DataSource;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A closed-loop load driver for pool starvation, outside JMH because JMH threads are platform threads.
 * {@code clients} threads of the chosen {@link ThreadKind} loop: pass the {@link AdmissionControl}, borrow
 * a connection, run one transaction, and release it. After {@code warmupMillis}, a measurement window of
 * {@code measureMillis} opens.
 *
 * <p>Every attempt is logged losslessly by its own client into pre-allocated, client-confined arrays (no
 * shared structure on the hot path), and the logs are merged after the run: the time to acquire the
 * connection (the pool's wait), the total wait including the admission control, the completion time, and
 * the outcome. An attempt whose {@code getConnection} throws (the pool's acquisition timeout) is logged
 * with the time it waited. From the merged log of the window the driver computes exact percentiles, the
 * throughput, Jain's fairness index over the clients' completed transactions, and the number of clients
 * that completed nothing. During the window only, each acquisition time is also recorded into the
 * shared recorder ({@link Recorders}), so its estimates and the exact percentiles describe the same values.
 *
 * @author Vlad Mihalcea
 */
public final class StarvationDriver {

    /** One transaction of the workload. */
    @FunctionalInterface
    public interface Transaction {
        void run(Connection connection, SplittableRandom random) throws SQLException;
    }

    public record Config(ThreadKind kind, int clients, long warmupMillis, long measureMillis) {
    }

    /** Exact percentiles of a set of durations, in nanoseconds. */
    public record Percentiles(double p50, double p99, double p999, double max) {

        static Percentiles of(long[] sorted, int length) {
            if (length == 0) {
                return new Percentiles(Double.NaN, Double.NaN, Double.NaN, Double.NaN);
            }
            return new Percentiles(rank(sorted, length, 0.5), rank(sorted, length, 0.99),
                    rank(sorted, length, 0.999), sorted[length - 1]);
        }

        /** Nearest-rank quantile of an ascending array. */
        static double rank(long[] sorted, int length, double q) {
            int index = (int) Math.ceil(q * length) - 1;
            return sorted[Math.max(0, Math.min(length - 1, index))];
        }
    }

    public record Result(ThreadKind kind, int clients, String admission, String recorder, double throughput,
                         long acquisitions, long acquisitionFailures, long transactionFailures,
                         Percentiles acquire, Percentiles totalWait, double jainFairness, int starvedClients,
                         double cpuUtilization, double recorderP99, double recorderP999, double recorderMax,
                         long[] traceTimestamps, long[] traceAcquireNanos) {

        /** Writes the window's acquisition times as a {@code timestamp_ns,value_ns} CSV, in completion order. */
        public void writeTrace(Path csv) {
            try {
                Files.createDirectories(csv.getParent());
                try (BufferedWriter out = Files.newBufferedWriter(csv)) {
                    out.write("timestamp_ns,value_ns\n");
                    for (int i = 0; i < traceTimestamps.length; i++) {
                        out.write(traceTimestamps[i] + "," + traceAcquireNanos[i] + "\n");
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static final byte COMMITTED = 0;
    private static final byte ACQUISITION_FAILED = 1;
    private static final byte TRANSACTION_FAILED = 2;

    /** One client's lossless log. Only its own thread writes it; the driver reads it after the run. */
    private static final class ClientLog {

        long[] endNanos = new long[256];
        long[] acquireNanos = new long[256];
        long[] waitNanos = new long[256];
        byte[] outcome = new byte[256];
        int size;

        void add(long end, long acquire, long wait, byte result) {
            if (size == endNanos.length) {
                int capacity = size * 2;
                endNanos = Arrays.copyOf(endNanos, capacity);
                acquireNanos = Arrays.copyOf(acquireNanos, capacity);
                waitNanos = Arrays.copyOf(waitNanos, capacity);
                outcome = Arrays.copyOf(outcome, capacity);
            }
            endNanos[size] = end;
            acquireNanos[size] = acquire;
            waitNanos[size] = wait;
            outcome[size] = result;
            size++;
        }
    }

    private final Config config;
    private final AdmissionControl admission;
    private final String recorderId;
    private final SamplingStrategy recorder;

    private volatile boolean stop;
    private volatile boolean measuring;

    /**
     * @param recorderId one of {@link Recorders#IDS}; {@code none} records nothing
     */
    public StarvationDriver(Config config, AdmissionControl admission, String recorderId) {
        this.config = config;
        this.admission = admission;
        this.recorderId = recorderId;
        this.recorder = Recorders.create(recorderId);
    }

    public Result run(DataSource dataSource, Transaction transaction) throws InterruptedException {
        ClientLog[] logs = new ClientLog[config.clients()];
        CountDownLatch finished = new CountDownLatch(config.clients());
        Thread.Builder builder = config.kind().builder();
        for (int c = 0; c < config.clients(); c++) {
            ClientLog log = new ClientLog();
            logs[c] = log;
            final long seed = 31L * c + 7;
            builder.start(() -> {
                try {
                    loop(dataSource, transaction, log, new SplittableRandom(seed));
                } finally {
                    finished.countDown();
                }
            });
        }

        Thread.sleep(config.warmupMillis());
        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long cpuStart = os.getProcessCpuTime();
        long windowStart = System.nanoTime();
        measuring = true;
        Thread.sleep(config.measureMillis());
        measuring = false;
        long windowEnd = System.nanoTime();
        long cpuEnd = os.getProcessCpuTime();
        stop = true;
        finished.await(2, TimeUnit.MINUTES);

        return summarize(logs, windowStart, windowEnd,
                (cpuEnd - cpuStart) / (double) ((windowEnd - windowStart) * Runtime.getRuntime().availableProcessors()));
    }

    private void loop(DataSource dataSource, Transaction transaction, ClientLog log, SplittableRandom random) {
        while (!stop) {
            long attemptStart = System.nanoTime();
            try {
                admission.run(() -> {
                    long start = System.nanoTime();
                    Connection connection;
                    try {
                        connection = dataSource.getConnection();
                    } catch (SQLException e) {
                        long end = System.nanoTime();
                        record(end - start);
                        log.add(end, end - start, end - attemptStart, ACQUISITION_FAILED);
                        return;
                    }
                    long acquired = System.nanoTime();
                    record(acquired - start);
                    byte result = COMMITTED;
                    try (connection) {
                        transaction.run(connection, random);
                    } catch (SQLException e) {
                        result = TRANSACTION_FAILED;
                    }
                    log.add(System.nanoTime(), acquired - start, acquired - attemptStart, result);
                });
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
                log.add(System.nanoTime(), 0, System.nanoTime() - attemptStart, TRANSACTION_FAILED);
            }
        }
    }

    private void record(long nanos) {
        if (measuring && recorder != null) {
            recorder.update(nanos);
        }
    }

    private Result summarize(ClientLog[] logs, long windowStart, long windowEnd, double cpuUtilization) {
        int total = 0;
        for (ClientLog log : logs) {
            total += log.size;
        }
        long[] acquire = new long[total];
        long[] wait = new long[total];
        long[] traceEnd = new long[total];
        int acquisitions = 0;
        long committed = 0;
        long acquisitionFailures = 0;
        long transactionFailures = 0;
        double sum = 0;
        double sumOfSquares = 0;
        int starved = 0;
        for (ClientLog log : logs) {
            long clientCommitted = 0;
            for (int i = 0; i < log.size; i++) {
                long end = log.endNanos[i];
                if (end < windowStart || end > windowEnd) {
                    continue;
                }
                switch (log.outcome[i]) {
                    case COMMITTED -> clientCommitted++;
                    case ACQUISITION_FAILED -> acquisitionFailures++;
                    default -> transactionFailures++;
                }
                if (log.outcome[i] != TRANSACTION_FAILED || log.acquireNanos[i] > 0) {
                    acquire[acquisitions] = log.acquireNanos[i];
                    wait[acquisitions] = log.waitNanos[i];
                    traceEnd[acquisitions] = end;
                    acquisitions++;
                }
            }
            committed += clientCommitted;
            sum += clientCommitted;
            sumOfSquares += (double) clientCommitted * clientCommitted;
            if (clientCommitted == 0) {
                starved++;
            }
        }

        // The trace keeps the completion order, so sort a copy of the acquisition times for the percentiles.
        long[] traceTimestamps = Arrays.copyOf(traceEnd, acquisitions);
        long[] traceValues = Arrays.copyOf(acquire, acquisitions);
        sortByTimestamp(traceTimestamps, traceValues);
        long[] sortedAcquire = Arrays.copyOf(acquire, acquisitions);
        long[] sortedWait = Arrays.copyOf(wait, acquisitions);
        Arrays.sort(sortedAcquire);
        Arrays.sort(sortedWait);

        double seconds = (windowEnd - windowStart) / 1e9;
        double jain = sumOfSquares == 0 ? Double.NaN : (sum * sum) / (logs.length * sumOfSquares);
        return new Result(config.kind(), config.clients(), admission.id(), recorderId, committed / seconds,
                acquisitions, acquisitionFailures, transactionFailures,
                Percentiles.of(sortedAcquire, acquisitions), Percentiles.of(sortedWait, acquisitions),
                jain, starved, cpuUtilization,
                recorder == null ? Double.NaN : recorder.valueAtQuantile(0.99),
                recorder == null ? Double.NaN : recorder.valueAtQuantile(0.999),
                recorder == null ? Double.NaN : Recorders.max(recorder),
                traceTimestamps, traceValues);
    }

    /** Sorts both arrays by the timestamps, keeping each value next to its timestamp. */
    private static void sortByTimestamp(long[] timestamps, long[] values) {
        Integer[] order = new Integer[timestamps.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Long.compare(timestamps[a], timestamps[b]));
        long[] t = timestamps.clone();
        long[] v = values.clone();
        for (int i = 0; i < order.length; i++) {
            timestamps[i] = t[order[i]];
            values[i] = v[order[i]];
        }
    }

    /** The configurations of a thread-kind x client-count sweep, in that nesting order. */
    public static List<Config> grid(ThreadKind[] kinds, int[] clients, long warmupMillis, long measureMillis) {
        List<Config> configs = new ArrayList<>();
        for (ThreadKind kind : kinds) {
            for (int c : clients) {
                configs.add(new Config(kind, c, warmupMillis, measureMillis));
            }
        }
        return configs;
    }
}
