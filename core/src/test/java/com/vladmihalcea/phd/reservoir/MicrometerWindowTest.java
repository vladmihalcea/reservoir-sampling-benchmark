package com.vladmihalcea.phd.reservoir;

import com.vladmihalcea.phd.reservoir.api.Clock;
import com.vladmihalcea.phd.reservoir.catalog.StrategyCatalog;
import com.vladmihalcea.phd.reservoir.framework.MicrometerSummary;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down how much past traffic Micrometer's published percentiles cover with its defaults (a
 * {@code distributionStatisticExpiry} of 2 minutes over a ring of 3 buffers, so a rotation every 40 s): 40 to 80 s,
 * not the 2 minutes of the expiry. After a step change, the published median shows the new value 21 to 30 s later;
 * after the last record, the percentiles read 0 within 41 to 59 s, while the max keeps its value. Two forks of the
 * November V6 runs read p99 = 0 because their final read waited 35 s for a scrape in progress; the benchmark now
 * records that delay.
 *
 * @author Vlad Mihalcea
 */
class MicrometerWindowTest {

    private static final long MS = 1_000_000L;

    @Test
    void percentilesEmptyWithinAMinuteOfTheLastRecordWhileTheMaxStays() {
        for (long recordsUntil : new long[]{50_000, 95_000, 119_000, 121_000}) {
            assertTrue(p99AfterQuiet(recordsUntil, 40_000) > 0,
                    "p99 after 40 s of quiet, records until " + recordsUntil + " ms");
            assertEquals(0.0, p99AfterQuiet(recordsUntil, 60_000),
                    "p99 after 60 s of quiet, records until " + recordsUntil + " ms");
        }
        Clock.Manual clock = new Clock.Manual();
        MicrometerSummary summary = record(clock, 95_000);
        clock.set((95_000 + 60_000) * MS);
        assertEquals(0.0, summary.valueAtQuantile(0.99));
        assertEquals(20 * MS, summary.max(), 0.01 * 20 * MS, "the max outlives the percentiles");
    }

    @Test
    void medianFollowsAStepWithinHalfAMinute() {
        for (long switchAt : new long[]{100_000, 110_000, 130_000}) {
            Clock.Manual clock = new Clock.Manual();
            MicrometerSummary summary = new MicrometerSummary(StrategyCatalog.TARGET_QUANTILES, clock);
            long shownAfter = -1;
            for (long t = 1; t <= switchAt + 120_000; t++) {
                clock.set(t * MS);
                summary.update(t <= switchAt ? 10 * MS : 50 * MS);
                if (t > switchAt && t % 1_000 == 0 && shownAfter < 0 && summary.valueAtQuantile(0.5) > 30 * MS) {
                    shownAfter = t - switchAt;
                }
            }
            assertTrue(shownAfter >= 20_000 && shownAfter <= 40_000,
                    "median shows the new value " + shownAfter + " ms after a switch at " + switchAt + " ms");
        }
    }

    private static double p99AfterQuiet(long recordsUntil, long quiet) {
        Clock.Manual clock = new Clock.Manual();
        MicrometerSummary summary = record(clock, recordsUntil);
        clock.set((recordsUntil + quiet) * MS);
        return summary.valueAtQuantile(0.99);
    }

    /** One 20 ms value per millisecond from 1 ms to {@code until} ms. */
    private static MicrometerSummary record(Clock.Manual clock, long until) {
        MicrometerSummary summary = new MicrometerSummary(StrategyCatalog.TARGET_QUANTILES, clock);
        for (long t = 1; t <= until; t++) {
            clock.set(t * MS);
            summary.update(20 * MS);
        }
        return summary;
    }
}
