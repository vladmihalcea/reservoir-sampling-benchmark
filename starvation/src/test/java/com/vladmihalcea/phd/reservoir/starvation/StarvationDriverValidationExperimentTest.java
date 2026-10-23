package com.vladmihalcea.phd.reservoir.starvation;

import com.vladmihalcea.phd.reservoir.util.CsvWriter;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Milestone M1 of the virtual-threads study: before the custom {@link StarvationDriver} replaces JMH, it must
 * reproduce the JMH throughput of experiment E1 under the same conditions, namely 128 platform threads, a
 * fair semaphore admitting {@code N + 16} of them to a HikariCP pool of size {@code N}, and the read-mostly
 * workload on PostgreSQL. For each {@code N}, {@link AcquireTransactionBenchmark}, this module's copy of
 * the E1 benchmark of the connection-pool-auto-scaling repository, runs first (one fork, 3 warm-up and 5
 * measurement iterations of 3 s), then the driver, so both see the same machine and database state; the
 * committed E1 results come from a run three months older and cannot serve as the reference. Results go to
 * {@code metrics/VirtualThreads/m1-validation.csv}.
 *
 * @author Vlad Mihalcea
 */
public class StarvationDriverValidationExperimentTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(StarvationDriverValidationExperimentTest.class);

    private static final int[] SIZES = {16, 48};
    private static final double TOLERANCE = 0.10;

    @Test
    public void driverReproducesTheJmhThroughput() throws Exception {
        PostgreSqlSupport.assumeDatabaseAvailable();
        try (CsvWriter out = new CsvWriter(CsvWriter.metricsPath("VirtualThreads", "m1-validation.csv"),
                "pool_size", "driver_tps", "jmh_tps", "jmh_error", "relative_difference")) {
            for (int size : SIZES) {
                RunResult jmh = jmh(size);
                double jmhScore = jmh.getPrimaryResult().getScore();
                double jmhError = jmh.getPrimaryResult().getScoreError();

                VirtualThreadPoolExperimentTest.createSchema();
                AccountsWorkload workload = AccountsWorkload.readMostly(new AccountsSchema(VirtualThreadPoolExperimentTest.SCALE).accountCount());
                StarvationDriver.Result result;
                try (StarvationPools.Pool pool = StarvationPools.open("hikari", PostgreSqlSupport.jdbcConfig(), size, 10_000)) {
                    result = new StarvationDriver(
                            new StarvationDriver.Config(ThreadKind.PLATFORM, 128, 9_000, 15_000),
                            AdmissionControl.of("fair-semaphore", size + 16, 0), "none")
                            .run(pool.dataSource(),
                                    workload);
                }
                double difference = (result.throughput() - jmhScore) / jmhScore;
                out.writeRow(size, result.throughput(), jmhScore, jmhError, difference);
                LOGGER.info("M1 validation at N={}: driver {} tx/s vs JMH {} +/- {} tx/s ({}%)", size,
                        String.format("%.0f", result.throughput()), String.format("%.0f", jmhScore),
                        String.format("%.0f", jmhError), String.format("%+.1f", 100 * difference));
                assertTrue(Math.abs(difference) <= TOLERANCE,
                        "driver " + result.throughput() + " vs JMH " + jmhScore + " at N=" + size);
            }
        }
    }

    private static RunResult jmh(int size) throws Exception {
        Options options = new OptionsBuilder()
                .include(AcquireTransactionBenchmark.class.getSimpleName() + ".transaction")
                .param("poolSize", String.valueOf(size))
                .threads(128)
                .forks(1)
                .warmupIterations(3)
                .warmupTime(TimeValue.seconds(3))
                .measurementIterations(5)
                .measurementTime(TimeValue.seconds(3))
                .build();
        Collection<RunResult> results = new Runner(options).run();
        return results.iterator().next();
    }
}
