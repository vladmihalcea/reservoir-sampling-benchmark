package com.vladmihalcea.phd.reservoir.starvation;

import com.vladmihalcea.phd.reservoir.util.CsvWriter;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.file.Path;

/**
 * Experiment V1 of the virtual-threads study (RQ1, H1): how HikariCP, DBCP2 (with and without validation
 * on borrow) and Agroal behave when 200 to 10,000 platform or virtual threads compete for them, at
 * the pools' default sizes and at the read-mostly {@code N*} of the ICASC paper, with no admission control.
 * Each configuration runs the read-mostly workload on PostgreSQL for {@code usl.vt.measureMillis} (60 s)
 * after a {@code usl.vt.warmupMillis} (15 s, three acquisition timeouts) warm-up, with a 5 s acquisition timeout,
 * {@code usl.vt.trials} (5) times. The trials are interleaved: every configuration runs once before any runs a second
 * time, so slow drift in the database or the machine spreads over all of them instead of favoring the first. The
 * client counts 2,000 and 5,000 locate where the acquisition timeouts begin, between the 1,000 clients that rarely
 * time out and the 10,000 that time out by the thousands.
 * <p>
 * Every trial reports throughput, the exact acquisition-time percentiles, acquisition timeouts, Jain's
 * fairness index over the clients' completed transactions, the clients that completed nothing, and the
 * client process's CPU utilization ({@code metrics/VirtualThreads/v1-trials.csv}); the summary
 * ({@code v1-pool-behavior.csv}) has the median, the mean with its 95% interval, and the range of every metric over the
 * trials ({@link TrialSummaries}). For the first trial of the runs with at least {@code usl.vt.traceClients} (1,000)
 * clients at the default size, it also exports the lossless acquisition trace that the core module's V2 experiment
 * replays ({@code metrics/VirtualThreads/traces/*.csv.gz}).
 *
 * @author Vlad Mihalcea
 */
public class VirtualThreadPoolExperimentTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(VirtualThreadPoolExperimentTest.class);

    static final long WARMUP_MILLIS = Long.getLong("usl.vt.warmupMillis", 15_000);
    static final long MEASURE_MILLIS = Long.getLong("usl.vt.measureMillis", 60_000);
    static final long TIMEOUT_MILLIS = Long.getLong("usl.vt.timeoutMillis", 5_000);
    static final int TRIALS = Integer.getInteger("usl.vt.trials", 5);
    static final int[] CLIENTS = clients();
    private static final int TRACE_CLIENTS = Integer.getInteger("usl.vt.traceClients", 1_000);
    static final int SCALE = 10;

    static final String[] RESULT_HEADER = {"trial", "pool", "pool_size", "thread_kind", "clients", "admission",
            "recorder", "tps", "acquisitions", "acquisition_timeouts", "tx_failures",
            "acquire_p50_ms", "acquire_p99_ms", "acquire_p999_ms", "acquire_max_ms",
            "wait_p50_ms", "wait_p99_ms", "wait_p999_ms", "wait_max_ms",
            "jain", "starved_clients", "cpu_util", "rec_p99_ms", "rec_p999_ms", "rec_max_ms"};

    /** The client counts, {@code usl.vt.clients} (comma-separated) or 200, 1,000, 2,000, 5,000, and 10,000. */
    private static int[] clients() {
        String list = System.getProperty("usl.vt.clients", "200,1000,2000,5000,10000");
        return java.util.Arrays.stream(list.split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray();
    }

    @Test
    public void measurePoolBehaviorUnderVirtualThreads() throws Exception {
        PostgreSqlSupport.assumeDatabaseAvailable();
        createSchema();
        AccountsWorkload workload = AccountsWorkload.readMostly(new AccountsSchema(SCALE).accountCount());
        TrialSummaries summaries = new TrialSummaries();

        try (CsvWriter out = new CsvWriter(CsvWriter.metricsPath("VirtualThreads", "v1-trials.csv"),
                RESULT_HEADER)) {
            for (int trial = 0; trial < TRIALS; trial++) {
                for (String pool : StarvationPools.POOLS) {
                    for (int size : new int[]{StarvationPools.defaultSize(pool), StarvationPools.optimumSize(pool)}) {
                        try (StarvationPools.Pool opened =
                                     StarvationPools.open(pool, PostgreSqlSupport.jdbcConfig(), size, TIMEOUT_MILLIS)) {
                            for (StarvationDriver.Config config : StarvationDriver.grid(ThreadKind.values(), CLIENTS,
                                    WARMUP_MILLIS, MEASURE_MILLIS)) {
                                StarvationDriver.Result result = new StarvationDriver(config,
                                        AdmissionControl.of("none", 0, 0), "none")
                                        .run(opened.dataSource(), workload);
                                writeRow(out, trial, pool, size, result);
                                summaries.add(key(pool, size, result), result);
                                if (trial == 0 && size == StarvationPools.defaultSize(pool)
                                        && config.clients() >= TRACE_CLIENTS) {
                                    result.writeTrace(tracePath(pool, size, config));
                                }
                            }
                        }
                    }
                }
            }
        }
        summaries.write(CsvWriter.metricsPath("VirtualThreads", "v1-pool-behavior.csv"));
    }

    static TrialSummaries.Key key(String pool, int size, StarvationDriver.Result result) {
        return new TrialSummaries.Key(pool, size, result.kind().id(), result.clients(), result.admission(),
                result.recorder());
    }

    static Path tracePath(String pool, int size, StarvationDriver.Config config) {
        return CsvWriter.metricsPath("VirtualThreads", "traces/" + pool + "-n" + size + "-"
                + config.kind().id() + "-" + config.clients() + ".csv.gz");
    }

    static void createSchema() throws Exception {
        DataSource admin = PostgreSqlSupport.adminDataSource();
        try {
            new AccountsSchema(SCALE).create(admin);
        } finally {
            if (admin instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }

    static void writeRow(CsvWriter out, int trial, String pool, int size, StarvationDriver.Result r) {
        out.writeRow(trial, pool, size, r.kind().id(), r.clients(), r.admission(), r.recorder(),
                r.throughput(), r.acquisitions(), r.acquisitionFailures(), r.transactionFailures(),
                ms(r.acquire().p50()), ms(r.acquire().p99()), ms(r.acquire().p999()), ms(r.acquire().max()),
                ms(r.totalWait().p50()), ms(r.totalWait().p99()), ms(r.totalWait().p999()), ms(r.totalWait().max()),
                r.jainFairness(), r.starvedClients(), r.cpuUtilization(),
                ms(r.recorderP99()), ms(r.recorderP999()), ms(r.recorderMax()));
        LOGGER.info("trial {}: {} n={} {} x{} [{} / {}]: {} tx/s, acquire p50={} p99={} p99.9={} max={} ms, "
                        + "timeouts={}, jain={}, starved={}, cpu={}",
                trial, pool, size, r.kind().id(), r.clients(), r.admission(), r.recorder(),
                String.format("%.0f", r.throughput()), fmt(r.acquire().p50()), fmt(r.acquire().p99()),
                fmt(r.acquire().p999()), fmt(r.acquire().max()), r.acquisitionFailures(),
                String.format("%.3f", r.jainFairness()), r.starvedClients(),
                String.format("%.2f", r.cpuUtilization()));
    }

    static double ms(double nanos) {
        return nanos / 1e6;
    }

    private static String fmt(double nanos) {
        return String.format("%.2f", nanos / 1e6);
    }
}
