package com.vladmihalcea.phd.reservoir.workload;

import java.util.List;
import java.util.SplittableRandom;

/**
 * Factory of the synthetic latency distributions exercised by the accuracy experiments. Each captures
 * a distribution shape that stresses reservoir sampling differently:
 * <ul>
 *   <li>{@code uniform} / {@code exponential} — smooth baselines;</li>
 *   <li>{@code lognormal} — the typical latency shape (long right tail);</li>
 *   <li>{@code pareto} — extreme heavy tail that stresses p99/p999 estimation;</li>
 *   <li>{@code bimodal} — a fast path plus a rare slow path;</li>
 *   <li>{@code regime-shift} — a step change in the distribution mid-stream that separates recency-aware
 *       strategies (forward decay, sliding window, VictoriaMetrics swap) from a plain uniform reservoir.</li>
 * </ul>
 * The {@code conn-pool} and {@code http-latency} generators reuse these shapes with parameters chosen to
 * mimic the realistic traces named in the abstract; they can be swapped for captured CSV traces via
 * {@link WorkloadTrace}.
 *
 * @author Vlad Mihalcea
 */
public final class SyntheticWorkloads {

    private static final long MS = 1_000_000L;

    private SyntheticWorkloads() {
    }

    public static Workload uniform() {
        return named("uniform", (rnd, n) -> {
            long[] out = new long[n];
            for (int i = 0; i < n; i++) {
                out[i] = MS + (long) (rnd.nextDouble() * 100 * MS);
            }
            return out;
        });
    }

    public static Workload exponential() {
        return named("exponential", (rnd, n) -> {
            long[] out = new long[n];
            double meanMs = 5.0;
            for (int i = 0; i < n; i++) {
                double x = -Math.log(1.0 - rnd.nextDouble()) * meanMs;
                out[i] = clampNanos(x * MS);
            }
            return out;
        });
    }

    public static Workload lognormal() {
        return lognormal("lognormal", Math.log(3.0), 0.9);
    }

    public static Workload pareto() {
        return named("pareto", (rnd, n) -> {
            long[] out = new long[n];
            double xmMs = 2.0;
            double alpha = 1.5;
            for (int i = 0; i < n; i++) {
                double x = xmMs / Math.pow(1.0 - rnd.nextDouble(), 1.0 / alpha);
                out[i] = clampNanos(x * MS);
            }
            return out;
        });
    }

    public static Workload bimodal() {
        return named("bimodal", (rnd, n) -> {
            long[] out = new long[n];
            for (int i = 0; i < n; i++) {
                if (rnd.nextDouble() < 0.9) {
                    out[i] = clampNanos((0.8 + rnd.nextDouble() * 0.6) * MS);
                } else {
                    out[i] = clampNanos((40 + rnd.nextDouble() * 30) * MS);
                }
            }
            return out;
        });
    }

    /**
     * Lognormal for the first half, then a step change to a 10x slower median for the second half.
     */
    public static Workload regimeShift() {
        return named("regime-shift", (rnd, n) -> {
            long[] out = new long[n];
            int half = n / 2;
            for (int i = 0; i < n; i++) {
                double mu = i < half ? Math.log(3.0) : Math.log(30.0);
                double x = Math.exp(mu + 0.6 * gaussian(rnd));
                out[i] = clampNanos(x * MS);
            }
            return out;
        });
    }

    /**
     * Database connection-pool acquisition times: mostly sub-millisecond, with occasional multi-second
     * waits when the pool saturates (a heavy-tailed lognormal with a rare spike component).
     */
    public static Workload connectionPool() {
        return named("conn-pool", (rnd, n) -> {
            long[] out = new long[n];
            for (int i = 0; i < n; i++) {
                if (rnd.nextDouble() < 0.02) {
                    out[i] = clampNanos((500 + rnd.nextDouble() * 4500) * MS); // saturation spike
                } else {
                    double x = Math.exp(Math.log(0.4) + 0.8 * gaussian(rnd));
                    out[i] = clampNanos(x * MS);
                }
            }
            return out;
        });
    }

    /**
     * HTTP request latency: lognormal body around ~8 ms with a heavier tail than a pure lognormal.
     */
    public static Workload httpLatency() {
        return lognormal("http-latency", Math.log(8.0), 1.1);
    }

    public static Workload lognormal(String name, double mu, double sigma) {
        return named(name, (rnd, n) -> {
            long[] out = new long[n];
            for (int i = 0; i < n; i++) {
                double x = Math.exp(mu + sigma * gaussian(rnd));
                out[i] = clampNanos(x * MS);
            }
            return out;
        });
    }

    /** All synthetic workloads used by the accuracy sweep, in a stable order. */
    public static List<Workload> all() {
        return List.of(
                uniform(),
                exponential(),
                lognormal(),
                pareto(),
                bimodal(),
                regimeShift(),
                connectionPool(),
                httpLatency()
        );
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private interface Generator {
        long[] generate(SplittableRandom rnd, int n);
    }

    private static Workload named(String name, Generator generator) {
        return new Workload() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public long[] generate(int n, long seed) {
                return generator.generate(new SplittableRandom(seed), n);
            }
        };
    }

    private static double gaussian(SplittableRandom rnd) {
        // Box-Muller; SplittableRandom has no nextGaussian in Java 21's base API path used here.
        double u1 = Math.max(rnd.nextDouble(), Double.MIN_VALUE);
        double u2 = rnd.nextDouble();
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
    }

    private static long clampNanos(double nanos) {
        if (nanos < 1.0) {
            return 1L;
        }
        long max = 60_000L * MS; // 60 s ceiling, matching the HdrHistogram trackable range
        return (long) Math.min(nanos, max);
    }
}
