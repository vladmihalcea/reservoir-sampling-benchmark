package com.vladmihalcea.phd.reservoir.starvation;

import com.vladmihalcea.phd.reservoir.util.CsvWriter;
import com.vladmihalcea.phd.reservoir.util.SampleSummary;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Experiment V3 of the virtual-threads study (RQ3, H3): does recording every acquisition time into a shared
 * metrics recorder slow the pool down when 10,000 virtual threads compete for it? HikariCP at its default
 * size runs the read-mostly workload on PostgreSQL with exactly one recorder of {@link Recorders#IDS} enabled per
 * trial ({@code none} as the baseline), for the V1 window (15 s warm-up, 60 s measurement). The trials are
 * interleaved, one repetition of every recorder at a time, {@code usl.vt.reps} (5) repetitions, so slow drift in the
 * database does not favor the recorders measured first. Each recorder's throughput and acquisition p99 (mean with
 * its 95% Student-t interval, and median), their change against {@code none}, and the range of the acquisition
 * timeouts go to {@code metrics/VirtualThreads/v3-reservoir-overhead.csv}; every trial is also logged in the V1 format
 * ({@code v3-trials.csv}), and every metric is summarized in {@code v3-summary.csv} ({@link TrialSummaries}).
 *
 * @author Vlad Mihalcea
 */
public class ReservoirOverheadExperimentTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReservoirOverheadExperimentTest.class);

    private static final int CLIENTS = 10_000;
    private static final int REPS = Integer.getInteger("usl.vt.reps", VirtualThreadPoolExperimentTest.TRIALS);

    @Test
    public void measureReservoirOverhead() throws Exception {
        PostgreSqlSupport.assumeDatabaseAvailable();
        VirtualThreadPoolExperimentTest.createSchema();
        AccountsWorkload workload = AccountsWorkload.readMostly(
                new AccountsSchema(VirtualThreadPoolExperimentTest.SCALE).accountCount());

        TrialSummaries summaries = new TrialSummaries();
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
                            .run(pool.dataSource(), workload);
                    VirtualThreadPoolExperimentTest.writeRow(trials, rep, "hikari", size, result);
                    summaries.add(VirtualThreadPoolExperimentTest.key("hikari", size, result), result);
                }
            }
        }
        summaries.write(CsvWriter.metricsPath("VirtualThreads", "v3-summary.csv"));

        SampleSummary baseTps = summaries.summary(key(size, "none"), "tps");
        SampleSummary baseP99 = summaries.summary(key(size, "none"), "acquire_p99_ms");
        try (CsvWriter out = new CsvWriter(CsvWriter.metricsPath("VirtualThreads", "v3-reservoir-overhead.csv"),
                "recorder", "trials", "tps", "tps_ci95", "tps_change", "tps_median", "tps_median_change",
                "acquire_p99_ms", "p99_ci95", "p99_change", "p99_median",
                "timeouts_median", "timeouts_min", "timeouts_max")) {
            for (String id : Recorders.IDS) {
                SampleSummary t = summaries.summary(key(size, id), "tps");
                SampleSummary p = summaries.summary(key(size, id), "acquire_p99_ms");
                SampleSummary timeouts = summaries.summary(key(size, id), "acquisition_timeouts");
                out.writeRow(id, t.count(), t.mean(), t.tCi95HalfWidth(), t.mean() / baseTps.mean() - 1,
                        t.median(), t.median() / baseTps.median() - 1,
                        p.mean(), p.tCi95HalfWidth(), p.mean() / baseP99.mean() - 1, p.median(),
                        timeouts.median(), timeouts.min(), timeouts.max());
                LOGGER.info("V3 {}: {} +/- {} tx/s ({}%), acquire p99 {} ms ({}%), timeouts {} [{}, {}]", id,
                        String.format("%.0f", t.mean()), String.format("%.0f", t.tCi95HalfWidth()),
                        String.format("%+.1f", 100 * (t.mean() / baseTps.mean() - 1)),
                        String.format("%.2f", p.mean()), String.format("%+.1f", 100 * (p.mean() / baseP99.mean() - 1)),
                        String.format("%.0f", timeouts.median()), String.format("%.0f", timeouts.min()),
                        String.format("%.0f", timeouts.max()));
            }
        }
    }

    private static TrialSummaries.Key key(int size, String recorder) {
        return new TrialSummaries.Key("hikari", size, ThreadKind.VIRTUAL.id(), CLIENTS, "none", recorder);
    }
}
