package com.vladmihalcea.phd.reservoir;

import com.vladmihalcea.phd.reservoir.api.Clock;
import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.catalog.StrategyCatalog;
import com.vladmihalcea.phd.reservoir.baseline.ExactQuantileBaseline;
import com.vladmihalcea.phd.reservoir.workload.SyntheticWorkloads;
import com.vladmihalcea.phd.reservoir.workload.Workload;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sanity check that every cataloged strategy produces quantile estimates within a generous tolerance of
 * the exact quantile on a stationary lognormal stream. This guards against gross implementation errors
 * (wrong heap direction, broken interpolation, empty snapshots) before the expensive accuracy sweep runs.
 *
 * @author Vlad Mihalcea
 */
class StrategyCorrectnessTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(StrategyCorrectnessTest.class);

    @Test
    void allStrategiesTrackStationaryQuantiles() {
        int n = 200_000;
        Workload workload = SyntheticWorkloads.lognormal();
        long[] values = workload.generate(n, 7L);

        ExactQuantileBaseline baseline = new ExactQuantileBaseline();
        for (long v : values) {
            baseline.add(v);
        }
        double trueP50 = baseline.valueAtQuantile(0.50);
        double trueP90 = baseline.valueAtQuantile(0.90);
        double trueP99 = baseline.valueAtQuantile(0.99);

        for (StrategyCatalog.NamedStrategy named : StrategyCatalog.all(Clock.SYSTEM, 123L)) {
            SamplingStrategy strategy = named.create();
            for (long v : values) {
                strategy.update(v);
            }
            double p50 = strategy.valueAtQuantile(0.50);
            double p90 = strategy.valueAtQuantile(0.90);
            double p99 = strategy.valueAtQuantile(0.99);

            LOGGER.info("{}: p50 err={} p90 err={} p99 err={}", named.name(),
                    fmt(ExactQuantileBaseline.relativeError(p50, trueP50)),
                    fmt(ExactQuantileBaseline.relativeError(p90, trueP90)),
                    fmt(ExactQuantileBaseline.relativeError(p99, trueP99)));

            assertFalse(Double.isNaN(p50), named.name() + " returned NaN p50");
            assertTrue(ExactQuantileBaseline.relativeError(p50, trueP50) < 0.15,
                    named.name() + " p50 off: got " + p50 + " expected ~" + trueP50);
            assertTrue(ExactQuantileBaseline.relativeError(p90, trueP90) < 0.20,
                    named.name() + " p90 off: got " + p90 + " expected ~" + trueP90);
            assertTrue(ExactQuantileBaseline.relativeError(p99, trueP99) < 0.40,
                    named.name() + " p99 off: got " + p99 + " expected ~" + trueP99);
        }
    }

    @Test
    void productionRecordersTrackStationaryQuantiles() {
        // The recorder set of the acquisition-tail study includes the lock-free reservoir, which all() omits.
        long[] values = SyntheticWorkloads.lognormal().generate(200_000, 7L);
        ExactQuantileBaseline baseline = new ExactQuantileBaseline();
        for (long v : values) {
            baseline.add(v);
        }
        for (String name : StrategyCatalog.PRODUCTION_RECORDERS) {
            SamplingStrategy strategy = StrategyCatalog.create(name, StrategyCatalog.DEFAULT_K, Clock.SYSTEM, 123L);
            for (long v : values) {
                strategy.update(v);
            }
            for (double q : new double[]{0.50, 0.90}) {
                double truth = baseline.valueAtQuantile(q);
                double estimate = strategy.valueAtQuantile(q);
                assertTrue(ExactQuantileBaseline.relativeError(estimate, truth) < 0.20,
                        name + " q" + q + " off: got " + estimate + " expected ~" + truth);
            }
        }
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.3f", d);
    }
}
