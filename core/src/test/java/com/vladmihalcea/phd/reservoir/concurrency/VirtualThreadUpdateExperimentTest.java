package com.vladmihalcea.phd.reservoir.concurrency;

import com.vladmihalcea.phd.reservoir.api.Clock;
import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.catalog.StrategyCatalog;
import com.vladmihalcea.phd.reservoir.util.CsvWriter;
import com.vladmihalcea.phd.reservoir.util.Statistics;
import com.vladmihalcea.phd.reservoir.workload.SyntheticWorkloads;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Experiment V3a of the virtual-threads follow-up: the update cost of the shared production recorders
 * ({@link StrategyCatalog#CONCURRENT_RECORDERS}) when the writers are virtual threads, at a concurrency two
 * to three orders of magnitude above the survey's E2 sweep (1--16 platform threads). Every writer records
 * lognormal latencies into one shared recorder as fast as it can for {@code rs.vt.millis} (3 s); the
 * experiment reports the aggregate update rate and how many writers recorded at least once.
 * <p>
 * The second number matters because virtual threads are not time-sliced: a writer that never blocks keeps
 * its carrier thread, so with 10,000 virtual writers only as many make progress as there are carriers,
 * unless the recorder's update parks the writer (a contended {@code ReentrantReadWriteLock} or a
 * {@code synchronized} block does, a CAS retry loop does not). The update rate therefore measures each
 * recorder's synchronization at carrier-level concurrency, and the progress count shows which recorders
 * hand the carrier over.
 *
 * @author Vlad Mihalcea
 */
public class VirtualThreadUpdateExperimentTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(VirtualThreadUpdateExperimentTest.class);

    private static final long MILLIS = Long.getLong("rs.vt.millis", 3_000);
    private static final int REPS = Integer.getInteger("rs.reps", 3);

    private record Writers(String kind, int count) {
    }

    private static final Writers[] WRITERS = {
            new Writers("platform", 16),
            new Writers("virtual", 1_000),
            new Writers("virtual", 10_000),
    };

    @Test
    public void measureVirtualThreadUpdates() throws Exception {
        long[] values = SyntheticWorkloads.lognormal().generate(1 << 16, 11L);
        try (CsvWriter out = new CsvWriter(CsvWriter.metricsPath("VirtualThreadUpdate", "vt-update.csv"),
                "recorder", "thread_kind", "threads", "updates_per_sec", "ci95", "threads_progressed")) {
            for (String name : StrategyCatalog.CONCURRENT_RECORDERS) {
                for (Writers writers : WRITERS) {
                    Statistics rate = new Statistics();
                    int progressed = 0;
                    for (int rep = 0; rep < REPS; rep++) {
                        SamplingStrategy recorder = StrategyCatalog.create(
                                name, StrategyCatalog.DEFAULT_K, Clock.SYSTEM, StrategyCatalog.DEFAULT_SEED);
                        long[] result = run(recorder, writers, values);
                        rate.add(result[0] / (MILLIS / 1000.0));
                        progressed = (int) result[1];
                    }
                    out.writeRow(name, writers.kind(), writers.count(), rate.mean(), rate.ci95HalfWidth(), progressed);
                    LOGGER.info("{} with {} {} writers: {} updates/s, {} writers progressed", name, writers.count(),
                            writers.kind(), String.format("%.0f", rate.mean()), progressed);
                }
            }
        }
    }

    /** @return {total updates, writers that recorded at least once} */
    private static long[] run(SamplingStrategy recorder, Writers writers, long[] values) throws InterruptedException {
        AtomicBoolean stop = new AtomicBoolean();
        LongAdder updates = new LongAdder();
        LongAdder progressed = new LongAdder();
        CountDownLatch done = new CountDownLatch(writers.count());
        Thread.Builder builder = "virtual".equals(writers.kind()) ? Thread.ofVirtual() : Thread.ofPlatform();
        for (int t = 0; t < writers.count(); t++) {
            final int offset = t * 7919;
            builder.start(() -> {
                long local = 0;
                int mask = values.length - 1;
                while (!stop.get()) {
                    recorder.update(values[(int) ((offset + local) & mask)]);
                    local++;
                }
                updates.add(local);
                if (local > 0) {
                    progressed.increment();
                }
                done.countDown();
            });
        }
        Thread.sleep(MILLIS);
        stop.set(true);
        done.await();
        return new long[]{updates.sum(), progressed.sum()};
    }
}
