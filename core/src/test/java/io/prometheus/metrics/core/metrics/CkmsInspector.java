package io.prometheus.metrics.core.metrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Debugging aid that drives the Prometheus client's {@code CKMSQuantiles} rank summary directly and
 * prints its internal state: the sorted {@code (value, g, delta)} samples, their rank bounds, the
 * estimated quantiles against the exact ones, and a step-by-step trace of how {@code get(q)} walks
 * the list.
 * <p>
 * It lives in the Prometheus package because {@code CKMSQuantiles}, its {@code Sample} and
 * {@code Quantile} classes, and the {@code samples}, {@code n}, and {@code f(int)} members are all
 * package-private. Being in the same package on the same class path gives it (and the debugger's
 * expression evaluator) direct access to them, without reflection.
 * <p>
 * Run {@link #main(String[])} from the IDE in debug mode. Useful breakpoints:
 * <ul>
 *   <li>{@link #onFlush(CKMSQuantiles, int)}: called each time the 128-value buffer has been sorted
 *       and merged into the sample list (inspect {@code ckms.samples} there, or evaluate
 *       {@code CkmsInspector.dump(ckms)});</li>
 *   <li>{@code CKMSQuantiles.insertBatch}, {@code CKMSQuantiles.compress}, and
 *       {@code CKMSQuantiles.get}, to step through the algorithm itself (the sources jar is in the
 *       local Maven repository, so the IDE can attach it);</li>
 *   <li>a conditional breakpoint in {@code compress()} on {@code left.g + right.g + right.delta < f(r)}
 *       to watch individual merges.</li>
 * </ul>
 * With no arguments the input is the five-range example (10,000 / 1,000 / 100 / 10 / 5 values).
 * Pass a file path to use your own values instead, one number per line.
 *
 * @author Vlad Mihalcea
 */
public class CkmsInspector {

    /** Target quantiles; the error follows the survey adapter, epsilon = (1 - q) / 10. */
    private static final double[] TARGET_QUANTILES = {0.5, 0.9, 0.95, 0.99, 0.999};

    /** {lower bound, upper bound, count} per range; values are uniform inside each range. */
    private static final double[][] RANGES = {
            {0, 10, 10_000},
            {10, 100, 1_000},
            {100, 200, 100},
            {200, 500, 10},
            {500, 1_000, 5}
    };

    private static final long SEED = 42L;

    /** Print a snapshot after every N flushes, e.g. -Dckms.snapshotEvery=10 (0 disables them). */
    private static final int SNAPSHOT_EVERY_FLUSHES = Integer.getInteger("ckms.snapshotEvery", 0);

    /**
     * Compress once more after the last flush, e.g. -Dckms.compressAtEnd=true, to see the steady-state
     * summary. Off by default, because a Prometheus scrape (get) sees the uncompressed state.
     */
    private static final boolean COMPRESS_AT_END = Boolean.getBoolean("ckms.compressAtEnd");

    /** The quantile whose get() walk is traced at the end. */
    private static final double TRACED_QUANTILE = 0.99;

    public static void main(String[] args) throws IOException {
        List<Double> values = args.length > 0 ? readValues(Path.of(args[0])) : generateValues();

        CKMSQuantiles ckms = new CKMSQuantiles(targets());
        int flushes = 0;
        for (double value : values) {
            int before = ckms.n;
            ckms.insert(value);
            // n only grows when the buffer is flushed into the sample list.
            if (ckms.n != before) {
                onFlush(ckms, ++flushes);
            }
        }
        // get() flushes the values still waiting in the buffer, but it does not compress them: until
        // the next 128-insert cycle they remain in the list as uncompressed g=1 samples.
        ckms.get(0.5);
        if (COMPRESS_AT_END) {
            System.out.printf(Locale.ROOT, "samples before the final compress: %d%n", ckms.samples.size());
            ckms.compress();
        }

        System.out.println(dump(ckms));
        System.out.println(compareWithExact(ckms, values));
        System.out.println(traceGet(ckms, TRACED_QUANTILE));
    }

    /**
     * Called after every buffer flush. Put a breakpoint here to inspect {@code ckms.samples} as the
     * summary evolves; note that compress() runs separately, every 128 inserts.
     */
    static void onFlush(CKMSQuantiles ckms, int flushes) {
        if (SNAPSHOT_EVERY_FLUSHES > 0 && flushes % SNAPSHOT_EVERY_FLUSHES == 0) {
            System.out.printf(Locale.ROOT, "--- after flush %d: n=%d, samples=%d%n",
                    flushes, ckms.n, ckms.samples.size());
        }
    }

    /** The sample list with each sample's rank bounds. Callable from the debugger. */
    static String dump(CKMSQuantiles ckms) {
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "n=%d, retained samples=%d (%.2f%%)%n",
                ckms.n, ckms.samples.size(), 100.0 * ckms.samples.size() / Math.max(1, ckms.n)));
        out.append(String.format(Locale.ROOT, "%5s %12s %7s %7s %8s %8s%n",
                "idx", "value", "g", "delta", "rmin", "rmax"));
        int rank = 0;
        int index = 0;
        for (CKMSQuantiles.Sample sample : ckms.samples) {
            // rmin is the sum of g up to this sample; rmax adds the sample's rank uncertainty.
            rank += sample.g;
            out.append(String.format(Locale.ROOT, "%5d %12.4f %7d %7d %8d %8d%n",
                    index++, sample.value, sample.g, sample.delta, rank, rank + sample.delta));
        }
        return out.toString();
    }

    /** Estimated vs. exact value for every target quantile. */
    static String compareWithExact(CKMSQuantiles ckms, List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        StringBuilder out = new StringBuilder(String.format(Locale.ROOT, "%8s %8s %12s %12s %10s%n",
                "quantile", "epsilon", "estimate", "exact", "rel.error"));
        for (CKMSQuantiles.Quantile target : ckms.quantiles) {
            double estimate = ckms.get(target.quantile);
            double exact = sorted.get(Math.max(0, (int) Math.ceil(target.quantile * sorted.size()) - 1));
            out.append(String.format(Locale.ROOT, "%8s %8.4f %12.4f %12.4f %9.2f%%%n",
                    target.quantile, target.epsilon, estimate, exact, 100 * Math.abs(estimate - exact) / exact));
        }
        return out.toString();
    }

    /** Replays the get(q) walk with its intermediate ranks, printing the samples around the stop. */
    static String traceGet(CKMSQuantiles ckms, double q) {
        int desiredRank = (int) Math.ceil(q * ckms.n);
        int allowedError = ckms.f(desiredRank);
        int upperBound = desiredRank + allowedError / 2;
        StringBuilder out = new StringBuilder(String.format(Locale.ROOT,
                "get(%s): desiredRank=ceil(%s * %d)=%d, f(desiredRank)=%d, upperBound=%d%n",
                q, q, ckms.n, desiredRank, allowedError, upperBound));

        List<CKMSQuantiles.Sample> samples = new ArrayList<>(ckms.samples);
        int rank = 0;
        int stop = samples.size();
        for (int i = 0; i < samples.size(); i++) {
            CKMSQuantiles.Sample sample = samples.get(i);
            if (rank + sample.g + sample.delta > upperBound) {
                stop = i;
                break;
            }
            rank += sample.g;
        }
        rank = 0;
        for (int i = 0; i < samples.size() && i <= stop; i++) {
            CKMSQuantiles.Sample sample = samples.get(i);
            if (i >= stop - 3) {
                out.append(String.format(Locale.ROOT, "  #%d value=%.4f: %d + g %d + delta %d = %d %s upperBound%n",
                        i, sample.value, rank, sample.g, sample.delta, rank + sample.g + sample.delta,
                        rank + sample.g + sample.delta > upperBound ? ">" : "<="));
            }
            rank += sample.g;
        }
        double result = stop == samples.size()
                ? samples.get(samples.size() - 1).value
                : samples.get(Math.max(0, stop - 1)).value;
        out.append(String.format(Locale.ROOT, "  -> returns the previous sample: %.4f (ckms.get=%.4f)%n",
                result, ckms.get(q)));
        return out.toString();
    }

    private static CKMSQuantiles.Quantile[] targets() {
        return Arrays.stream(TARGET_QUANTILES)
                .mapToObj(q -> new CKMSQuantiles.Quantile(q, Math.max((1 - q) / 10, 1e-4)))
                .toArray(CKMSQuantiles.Quantile[]::new);
    }

    private static List<Double> generateValues() {
        Random random = new Random(SEED);
        List<Double> values = new ArrayList<>();
        for (double[] range : RANGES) {
            for (int i = 0; i < range[2]; i++) {
                values.add(range[0] + random.nextDouble() * (range[1] - range[0]));
            }
        }
        // Shuffle so the summary sees the values in arrival order, not sorted by range.
        Collections.shuffle(values, random);
        return values;
    }

    private static List<Double> readValues(Path path) throws IOException {
        List<Double> values = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            if (!line.isBlank()) {
                values.add(Double.parseDouble(line.trim()));
            }
        }
        return values;
    }
}
