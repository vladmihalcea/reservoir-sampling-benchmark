package com.vladmihalcea.phd.reservoir.starvation;

import com.vladmihalcea.phd.reservoir.util.CsvWriter;
import com.vladmihalcea.phd.reservoir.util.Statistics;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Experiment V3 of the virtual-threads study (RQ3, H3): does recording every acquisition time into a shared
 * metrics recorder slow the pool down when 10,000 virtual threads compete for it? HikariCP at its default
 * size runs the read-mostly workload on PostgreSQL with exactly one recorder of {@link Recorders#IDS} enabled per
 * trial ({@code none} as the baseline). The trials are interleaved, one repetition of every recorder at a
 * time, so slow drift in the database does not favor the recorders measured first. Each recorder's mean
 * throughput and acquisition p99, and their change against {@code none}, go to
 * {@code metrics/VirtualThreads/v3-reservoir-overhead.csv}; every trial is also logged in the V1 format
 * ({@code v3-trials.csv}).
 *
 * @author Vlad Mihalcea
 */
public class ReservoirOverheadExperimentTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReservoirOverheadExperimentTest.class);

    private static final int CLIENTS = 10_000;
    private static final int REPS = Integer.getInteger("usl.vt.reps", 3);

    @Test
    public void measureReservoirOverhead() throws Exception {
        PostgreSqlSupport.assumeDatabaseAvailable();
        VirtualThreadPoolExperimentTest.createSchema();
        AccountsWorkload workload = AccountsWorkload.readMostly(new AccountsSchema(VirtualThreadPoolExperimentTest.SCALE).accountCount());

        Map<String, Statistics> throughput = new LinkedHashMap<>();
        Map<String, Statistics> p99 = new LinkedHashMap<>();
        for (String id : Recorders.IDS) {
            throughput.put(id, new Statistics());
            p99.put(id, new Statistics());
        }
        int size = StarvationPools.defaultSize("hikari");
        try (StarvationPools.Pool pool = StarvationPools.open("hikari", PostgreSqlSupport.jdbcConfig(), size,
                VirtualThreadPoolExperimentTest.TIMEOUT_MILLIS);
             CsvWriter trials = new CsvWriter(CsvWriter.metricsPath("VirtualThreads", "v3-trials.csv"),
                     VirtualThreadPoolExperimentTest.RESULT_HEADER)) {
            for (int rep = 0; rep < REPS; rep++) {
                for (String id : Recorders.IDS) {
                    StarvationDriver.Result result = new StarvationDriver(
                            new StarvationDriver.Config(ThreadKind.VIRTUAL, CLIENTS,
                                    VirtualThreadPoolExperimentTest.WARMUP_MILLIS,
                                    VirtualThreadPoolExperimentTest.MEASURE_MILLIS),
                            AdmissionControl.of("none", 0, 0), id)
                            .run(pool.dataSource(),
                                    workload);
                    VirtualThreadPoolExperimentTest.writeRow(trials, "hikari", size, result);
                    throughput.get(id).add(result.throughput());
                    p99.get(id).add(result.acquire().p99() / 1e6);
                }
            }
        }

        double baseTps = throughput.get("none").mean();
        double baseP99 = p99.get("none").mean();
        try (CsvWriter out = new CsvWriter(CsvWriter.metricsPath("VirtualThreads", "v3-reservoir-overhead.csv"),
                "recorder", "tps", "tps_ci95", "tps_change", "acquire_p99_ms", "p99_ci95", "p99_change")) {
            for (String id : Recorders.IDS) {
                Statistics t = throughput.get(id);
                Statistics p = p99.get(id);
                out.writeRow(id, t.mean(), t.ci95HalfWidth(), t.mean() / baseTps - 1,
                        p.mean(), p.ci95HalfWidth(), p.mean() / baseP99 - 1);
                LOGGER.info("V3 {}: {} +/- {} tx/s ({}%), acquire p99 {} ms ({}%)", id,
                        String.format("%.0f", t.mean()), String.format("%.0f", t.ci95HalfWidth()),
                        String.format("%+.1f", 100 * (t.mean() / baseTps - 1)),
                        String.format("%.2f", p.mean()), String.format("%+.1f", 100 * (p.mean() / baseP99 - 1)));
            }
        }
    }
}
