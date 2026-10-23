package com.vladmihalcea.phd.reservoir.starvation;

import com.vladmihalcea.phd.reservoir.util.CsvWriter;
import org.junit.jupiter.api.Test;

/**
 * Experiment V4 of the virtual-threads study (RQ4, H4): which admission control in front of the pool bounds
 * the tail when 10,000 virtual threads compete for it, and what the metrics report about it. HikariCP and
 * Agroal at their default size run the read-mostly workload on PostgreSQL behind each
 * {@link AdmissionControl}: none, a fair and an unfair semaphore admitting {@code N + 16} clients, a bounded
 * executor of {@code N + 16} platform threads, and a rate limiter at 95% of the throughput measured without
 * admission control.
 * <p>
 * Admission control moves the wait from the pool to the construct in front of it, so every row reports
 * two exact tails: the acquisition time (what a pool timer records) and the total wait including the
 * admission (what the client experiences). Each configuration runs twice, once with FlexyPool's default
 * Dropwizard {@code ExponentiallyDecayingReservoir} and once with HdrHistogram, to show whether the reported
 * tail follows the exact acquisition tail. Rows go to {@code metrics/VirtualThreads/v4-admission.csv} in the
 * V1 format.
 *
 * @author Vlad Mihalcea
 */
public class AdmissionControlExperimentTest {

    private static final int CLIENTS = 10_000;
    private static final String[] POOLS = {"hikari", "agroal"};
    private static final String[] ADMISSIONS = {"none", "fair-semaphore", "unfair-semaphore", "bounded-executor",
            "rate-limiter"};
    private static final String[] RECORDERS = {"Dropwizard-ExpDecay", "HdrHistogram-Recorder"};

    @Test
    public void measureAdmissionControl() throws Exception {
        PostgreSqlSupport.assumeDatabaseAvailable();
        VirtualThreadPoolExperimentTest.createSchema();
        AccountsWorkload workload = AccountsWorkload.readMostly(new AccountsSchema(VirtualThreadPoolExperimentTest.SCALE).accountCount());
        StarvationDriver.Transaction transaction =
                workload;

        try (CsvWriter out = new CsvWriter(CsvWriter.metricsPath("VirtualThreads", "v4-admission.csv"),
                VirtualThreadPoolExperimentTest.RESULT_HEADER)) {
            for (String poolId : POOLS) {
                int size = StarvationPools.defaultSize(poolId);
                try (StarvationPools.Pool pool = StarvationPools.open(poolId, PostgreSqlSupport.jdbcConfig(), size,
                        VirtualThreadPoolExperimentTest.TIMEOUT_MILLIS)) {
                    double unthrottled = Double.NaN;
                    for (String admissionId : ADMISSIONS) {
                        for (String recorderId : RECORDERS) {
                            double rate = Double.isNaN(unthrottled) ? 1 : 0.95 * unthrottled;
                            try (AdmissionControl admission = AdmissionControl.of(admissionId, size + 16, rate)) {
                                StarvationDriver.Result result = new StarvationDriver(
                                        new StarvationDriver.Config(ThreadKind.VIRTUAL, CLIENTS,
                                                VirtualThreadPoolExperimentTest.WARMUP_MILLIS,
                                                VirtualThreadPoolExperimentTest.MEASURE_MILLIS),
                                        admission, recorderId)
                                        .run(pool.dataSource(), transaction);
                                VirtualThreadPoolExperimentTest.writeRow(out, poolId, size, result);
                                if ("none".equals(admissionId) && Double.isNaN(unthrottled)) {
                                    unthrottled = result.throughput();
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
