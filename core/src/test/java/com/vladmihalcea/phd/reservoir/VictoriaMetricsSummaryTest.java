package com.vladmihalcea.phd.reservoir;

import com.vladmihalcea.phd.reservoir.api.Clock;
import com.vladmihalcea.phd.reservoir.vm.VictoriaMetricsSummary;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the documented VictoriaMetrics semantics: the two-reservoir mid-window swap makes a quantile
 * query reflect the most recent half-window of data, so after a regime change the estimate tracks the new
 * distribution once a swap has promoted the freshly filled reservoir.
 *
 * @author Vlad Mihalcea
 */
class VictoriaMetricsSummaryTest {

    @Test
    void swapPromotesRecentReservoirAfterRegimeChange() {
        Clock.Manual clock = new Clock.Manual();
        long window = TimeUnit.MINUTES.toNanos(5);
        VictoriaMetricsSummary vm = new VictoriaMetricsSummary(1000, window, clock, 99L);

        long lowLatency = TimeUnit.MILLISECONDS.toNanos(2);
        long highLatency = TimeUnit.MILLISECONDS.toNanos(200);

        // Fill the first half-window with low-latency values.
        long halfWindow = window / 2;
        for (int i = 0; i < 5000; i++) {
            clock.advanceNanos(halfWindow / 6000);
            vm.update(lowLatency, clock.nanoTime());
        }
        // Cross one swap boundary so "current" holds the low-latency data.
        clock.advanceNanos(halfWindow);
        vm.update(lowLatency, clock.nanoTime());
        double afterLow = vm.valueAtQuantile(0.5);
        assertTrue(Math.abs(afterLow - lowLatency) < lowLatency * 0.5,
                "median should reflect low-latency regime, was " + afterLow);

        // Now feed a full half-window of high-latency values and cross another swap.
        for (int i = 0; i < 5000; i++) {
            clock.advanceNanos(halfWindow / 6000);
            vm.update(highLatency, clock.nanoTime());
        }
        clock.advanceNanos(halfWindow);
        vm.update(highLatency, clock.nanoTime());
        double afterHigh = vm.valueAtQuantile(0.5);
        assertTrue(afterHigh > lowLatency * 10,
                "median should have shifted toward the high-latency regime, was " + afterHigh);
    }
}
