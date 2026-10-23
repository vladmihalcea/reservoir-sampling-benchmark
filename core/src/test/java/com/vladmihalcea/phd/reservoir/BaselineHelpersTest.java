package com.vladmihalcea.phd.reservoir;

import com.vladmihalcea.phd.reservoir.api.Clock;
import com.vladmihalcea.phd.reservoir.api.SamplingStrategy;
import com.vladmihalcea.phd.reservoir.baseline.ExactQuantileBaseline;
import com.vladmihalcea.phd.reservoir.baseline.TargetTruth;
import com.vladmihalcea.phd.reservoir.catalog.StrategyCatalog;
import com.vladmihalcea.phd.reservoir.impl.ForwardDecayReservoir;
import com.vladmihalcea.phd.reservoir.impl.VitterR;
import com.vladmihalcea.phd.reservoir.util.Proportions;
import com.vladmihalcea.phd.reservoir.util.SampleSummary;
import com.vladmihalcea.phd.reservoir.workload.AcquisitionTraces;
import com.vladmihalcea.phd.reservoir.workload.WorkloadTrace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the helpers of the journal extension's experiments against the strategies they describe: each
 * {@link TargetTruth} target must be the distribution its strategy actually reports, the uniform-reservoir hit
 * probability must match simulated reservoirs, and the trace and summary helpers must round-trip.
 *
 * @author Vlad Mihalcea
 */
class BaselineHelpersTest {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    /** One value per second, equal to the minute it falls in (1, 2, ...), for {@code minutes} minutes. */
    private static long[][] minuteStream(int minutes) {
        int n = minutes * 60;
        long[] values = new long[n];
        long[] timestamps = new long[n];
        for (int i = 0; i < n; i++) {
            timestamps[i] = (i + 1) * SECOND;
            values[i] = 1 + i / 60;
        }
        return new long[][]{values, timestamps};
    }

    @Test
    void allTimeTargetMatchesTheExactBaseline() {
        long[] values = new SplittableRandom(1).longs(10_001, 1, 1_000_000).toArray();
        long[] timestamps = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            timestamps[i] = i;
        }
        TargetTruth truth = TargetTruth.of(new TargetTruth.AllTime(), values, timestamps);
        for (double q : StrategyCatalog.TARGET_QUANTILES) {
            assertEquals(ExactQuantileBaseline.of(values, values.length, q), truth.valueAtQuantile(q));
        }
        long[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        assertEquals(ExactQuantileBaseline.cdfSorted(sorted, sorted.length, sorted[5000]), truth.cdf(sorted[5000]));
        assertEquals(0.0, truth.rankError(truth.valueAtQuantile(0.5), 0.5), 1e-3);
    }

    @Test
    void slidingWindowTargetIsWhatDropwizardsWindowReports() {
        long[][] stream = minuteStream(10);
        Clock.Manual clock = new Clock.Manual();
        SamplingStrategy window = StrategyCatalog.create("Dropwizard-SlidingWindow", StrategyCatalog.DEFAULT_K,
                clock, 1);
        for (int i = 0; i < stream[0].length; i++) {
            clock.set(stream[1][i]);
            window.update(stream[0][i], stream[1][i]);
        }
        TargetTruth truth = TargetTruth.of(new TargetTruth.Window(TimeUnit.MINUTES.toNanos(5)), stream[0], stream[1]);
        assertEquals(300, truth.size());
        for (double q : new double[]{0.01, 0.5, 0.99}) {
            assertEquals(truth.valueAtQuantile(q), window.valueAtQuantile(q), 0.5, "q=" + q);
        }
    }

    @Test
    void lastHalfWindowTargetIsWhatVictoriaMetricsReports() {
        // 10 minutes end on a swap (at 2.5, 5, 7.5, and 10 min): the summary reads the values of 7.5-10 min, but
        // the swap happens at the last value, which goes to the new reservoir, so the read covers 7.5 min to 10 min
        // minus one second.
        for (int minutes : new int[]{9, 10, 11}) {
            long[][] stream = minuteStream(minutes);
            Clock.Manual clock = new Clock.Manual();
            SamplingStrategy vm = StrategyCatalog.create("VictoriaMetrics", StrategyCatalog.DEFAULT_K, clock, 7);
            for (int i = 0; i < stream[0].length; i++) {
                clock.set(stream[1][i]);
                vm.update(stream[0][i], stream[1][i]);
            }
            TargetTruth truth = TargetTruth.of(new TargetTruth.LastHalfWindow(TimeUnit.MINUTES.toNanos(5) / 2),
                    stream[0], stream[1]);
            assertEquals(150, truth.size(), minutes + " minutes");
            for (double q : new double[]{0.01, 0.5, 0.99}) {
                assertEquals(truth.valueAtQuantile(q), vm.valueAtQuantile(q), 0.5, minutes + " minutes, q=" + q);
            }
        }
    }

    @Test
    void decayedTargetIsWhatAForwardDecayReservoirLargerThanTheStreamReports() {
        // With k above the stream length, the reservoir keeps every value with its exact weight exp(alpha t), so its
        // weighted quantiles are those of the decayed target.
        SplittableRandom random = new SplittableRandom(3);
        int n = 3_000;
        long[] values = new long[n];
        long[] timestamps = new long[n];
        Clock.Manual clock = new Clock.Manual();
        ForwardDecayReservoir reservoir = new ForwardDecayReservoir(n + 1, 0.05, clock, 11);
        for (int i = 0; i < n; i++) {
            timestamps[i] = (i + 1) * (SECOND / 10);
            values[i] = 1 + random.nextLong(10_000);
            clock.set(timestamps[i]);
            reservoir.update(values[i], timestamps[i]);
        }
        TargetTruth truth = TargetTruth.of(new TargetTruth.Decayed(0.05), values, timestamps);
        for (double q : new double[]{0.1, 0.5, 0.9, 0.99}) {
            double expected = truth.valueAtQuantile(q);
            double estimate = reservoir.valueAtQuantile(q);
            assertTrue(truth.rankError(estimate, q) < 0.002,
                    "q=" + q + ": truth " + expected + ", reservoir " + estimate);
        }
        // And the decayed target differs from the all-time one when the values drift.
        long[] drifting = new long[n];
        for (int i = 0; i < n; i++) {
            drifting[i] = i + 1;
        }
        double decayedMedian = TargetTruth.of(new TargetTruth.Decayed(0.05), drifting, timestamps).valueAtQuantile(0.5);
        assertTrue(decayedMedian > 0.9 * n, "decayed median " + decayedMedian);
    }

    @Test
    void uniformReservoirHitMatchesSimulatedReservoirs() {
        int n = 20_000;
        int k = 100;
        for (int rare : new int[]{1, 5, 20, 100}) {
            int hits = 0;
            int trials = 4_000;
            for (int t = 0; t < trials; t++) {
                VitterR reservoir = new VitterR(k, 1_000L + t);
                SplittableRandom positions = new SplittableRandom(7L * t);
                java.util.Set<Integer> rareAt = new java.util.HashSet<>();
                while (rareAt.size() < rare) {
                    rareAt.add(positions.nextInt(n));
                }
                for (int i = 0; i < n; i++) {
                    reservoir.update(rareAt.contains(i) ? 1_000_000 : 1 + i % 1000);
                }
                if (reservoir.valueAtQuantile(1.0) >= 1_000_000) {
                    hits++;
                }
            }
            double model = Proportions.uniformReservoirHit(n, rare, k);
            double observed = hits / (double) trials;
            double[] ci = Proportions.wilson95(hits, trials);
            assertTrue(model >= ci[0] - 0.01 && model <= ci[1] + 0.01,
                    rare + " rare values: model " + model + ", simulated " + observed);
        }
        assertEquals(0.0, Proportions.uniformReservoirHit(1_000, 0, 10));
        assertEquals(1.0, Proportions.uniformReservoirHit(1_000, 1, 1_000));
        assertEquals(1 - Math.exp(-1028 * 10 / 1e6), Proportions.uniformReservoirHit(1_000_000, 10, 1028), 1e-3);
    }

    @Test
    void sampleSummaryReportsOrderStatisticsAndIntervals() {
        SampleSummary summary = new SampleSummary();
        for (double v : new double[]{5, 1, 4, 2, 3, Double.NaN}) {
            summary.add(v);
        }
        assertEquals(5, summary.count());
        assertEquals(3.0, summary.median());
        assertEquals(1.0, summary.min());
        assertEquals(5.0, summary.max());
        assertEquals(3.0, summary.mean());
        // s = sqrt(2.5), t(4) = 2.776
        assertEquals(2.776 * Math.sqrt(2.5) / Math.sqrt(5), summary.tCi95HalfWidth(), 1e-9);
        double[] ci = summary.bootstrapMeanCi95();
        assertTrue(ci[0] < 3 && ci[1] > 3 && ci[0] >= 1 && ci[1] <= 5);
        assertArrayEquals(ci, summary.bootstrapMeanCi95(), "seeded, so repeatable");
    }

    @Test
    void gzipTracesRoundTripAndAreListedWithTheCsvOnes(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.csv"), "timestamp_ns,value_ns\n100,5\n200,7\n");
        try (Writer out = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(dir.resolve("b.csv.gz"))),
                StandardCharsets.US_ASCII)) {
            out.write("timestamp_ns,value_ns\n0,9\n50,11\n125,13\n");
        }
        Files.writeString(dir.resolve("notes.txt"), "not a trace");
        List<Path> files = AcquisitionTraces.files(dir);
        assertEquals(List.of(dir.resolve("a.csv"), dir.resolve("b.csv.gz")), files);
        WorkloadTrace a = AcquisitionTraces.loadFile(files.get(0));
        WorkloadTrace b = AcquisitionTraces.loadFile(files.get(1));
        assertEquals("a", a.name());
        assertEquals("b", b.name());
        assertArrayEquals(new long[]{0, 100}, a.timestampsNanos());
        assertArrayEquals(new long[]{9, 11, 13}, b.values());
        assertArrayEquals(new long[]{0, 50, 125}, b.timestampsNanos());
    }
}
