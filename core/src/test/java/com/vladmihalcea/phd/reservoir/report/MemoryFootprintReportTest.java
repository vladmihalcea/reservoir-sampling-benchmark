package com.vladmihalcea.phd.reservoir.report;

import com.vladmihalcea.phd.reservoir.api.Clock;
import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.catalog.StrategyCatalog;
import com.vladmihalcea.phd.reservoir.util.CsvWriter;
import com.vladmihalcea.phd.reservoir.workload.SyntheticWorkloads;
import org.junit.jupiter.api.Test;
import org.openjdk.jol.info.GraphLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * Experiment E4 (retained-memory half): walks each warmed strategy's object graph with JOL and reports
 * the deep footprint in bytes after a fixed number of updates. This is the "how much memory does the
 * strategy actually hold" figure for the survey (C3); the complementary per-update allocation rate comes
 * from running the JMH benchmarks with {@code -prof gc} ({@code gc.alloc.rate.norm}).
 * <p>
 * Bounded reservoirs should show flat, {@code k}-proportional footprints; the sliding-time-window and
 * exact structures should grow with the stream, which is precisely the trade-off the paper documents.
 *
 * @author Vlad Mihalcea
 */
public class MemoryFootprintReportTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryFootprintReportTest.class);
    // JOL walks the whole retained graph; production scale is 10^6 updates (overridable for a quick pass).
    private static final int UPDATES = Integer.getInteger("rs.updates", 1_000_000);
    // Matches the reservoir sizes swept by the E1 throughput benchmark so the two figures line up.
    private static final int[] K_SWEEP = {128, 512, 1028, 4096};

    static {
        // Lets JOL traverse JDK-internal fields (needed for Micrometer's object graph on JDK 21).
        System.setProperty("jol.magicFieldOffset", "true");
    }

    @Test
    public void reportFootprints() {
        long[] values = SyntheticWorkloads.lognormal().generate(UPDATES, 2028L);
        Path csv = CsvWriter.metricsPath("MemoryFootprint", "MemoryFootprint.csv");

        Path wideCsv = CsvWriter.metricsPath("MemoryFootprint", "MemoryFootprint-wide.csv");
        List<String> strategyNames = StrategyCatalog.all(Clock.SYSTEM, 1L).stream()
                .map(StrategyCatalog.NamedStrategy::name).toList();

        String[] wideHeader = new String[K_SWEEP.length + 1];
        wideHeader[0] = "strategy";
        for (int i = 0; i < K_SWEEP.length; i++) {
            wideHeader[i + 1] = "k" + K_SWEEP[i];
        }

        try (CsvWriter out = new CsvWriter(csv, "strategy", "taxonomy", "k", "updates", "retained_bytes");
             CsvWriter wide = new CsvWriter(wideCsv, wideHeader)) {
            for (String name : strategyNames) {
                long[] perK = new long[K_SWEEP.length];
                String taxonomy = null;
                for (int i = 0; i < K_SWEEP.length; i++) {
                    int k = K_SWEEP[i];
                    SamplingStrategy strategy = StrategyCatalog.create(name, k, Clock.SYSTEM, 1L);
                    for (long value : values) {
                        strategy.update(value);
                    }
                    // Force a query so lazily built structures (e.g. snapshots) are not double-counted.
                    strategy.valueAtQuantile(0.99);
                    taxonomy = strategy.taxonomy().code();

                    long bytes;
                    try {
                        bytes = GraphLayout.parseInstance(strategy).totalSize();
                    } catch (Throwable t) {
                        LOGGER.warn("JOL could not measure {} @ k={}: {}", name, k, t.toString());
                        bytes = -1;
                    }
                    LOGGER.info("{} @ k={}: retained {} bytes after {} updates", name, k, bytes, UPDATES);
                    out.writeRow(name, taxonomy, k, UPDATES, bytes);
                    perK[i] = bytes;
                }
                Object[] wideRow = new Object[K_SWEEP.length + 1];
                wideRow[0] = name;
                for (int i = 0; i < K_SWEEP.length; i++) {
                    wideRow[i + 1] = perK[i];
                }
                wide.writeRow(wideRow);
            }
        }
        LOGGER.info("Wrote memory footprint report to {}", csv);
    }
}
