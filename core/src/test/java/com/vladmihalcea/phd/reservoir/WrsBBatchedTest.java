package com.vladmihalcea.phd.reservoir;

import com.vladmihalcea.phd.reservoir.impl.EfraimidisSpirakisAExpJ;
import com.vladmihalcea.phd.reservoir.impl.WrsBBatched;
import com.vladmihalcea.phd.reservoir.baseline.ExactQuantileBaseline;
import com.vladmihalcea.phd.reservoir.workload.SyntheticWorkloads;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirms that the batched WRS-B produces statistically equivalent quantiles to the per-element
 * A-ExpJ it accelerates, and that its bulk {@link WrsBBatched#updateBatch} path agrees with the exact
 * quantile within the tolerance expected for a size-1028 reservoir. Correctness is the precondition for
 * the E3 speedup claim to be meaningful.
 *
 * @author Vlad Mihalcea
 */
class WrsBBatchedTest {

    @Test
    void batchedMatchesPerElementAndBaseline() {
        int n = 500_000;
        int k = 1028;
        long[] values = SyntheticWorkloads.exponential().generate(n, 11L);

        ExactQuantileBaseline baseline = new ExactQuantileBaseline();
        for (long v : values) {
            baseline.add(v);
        }
        double truth = baseline.valueAtQuantile(0.99);

        EfraimidisSpirakisAExpJ perElement = new EfraimidisSpirakisAExpJ(k, 5L);
        for (long v : values) {
            perElement.update(v);
        }

        WrsBBatched batched = new WrsBBatched(k, 4096, 5L);
        batched.updateBatch(values, n);

        double perElementP99 = perElement.valueAtQuantile(0.99);
        double batchedP99 = batched.valueAtQuantile(0.99);

        assertTrue(ExactQuantileBaseline.relativeError(perElementP99, truth) < 0.35,
                "A-ExpJ p99 off: " + perElementP99 + " vs " + truth);
        assertTrue(ExactQuantileBaseline.relativeError(batchedP99, truth) < 0.35,
                "WRS-B p99 off: " + batchedP99 + " vs " + truth);
    }
}
