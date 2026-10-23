package com.vladmihalcea.phd.reservoir.starvation;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * A construct placed in front of the connection pool that decides when a client may borrow a connection.
 * Each client runs its whole unit of work (acquire, transaction, release) through {@link #run}, so the
 * construct can bound how many clients reach the pool at once ({@code fair-semaphore},
 * {@code unfair-semaphore}), move the work onto a fixed set of platform threads ({@code bounded-executor}),
 * or space out the attempts in time ({@code rate-limiter}). With {@code none}, every client goes straight
 * to the pool and the pool's own hand-off decides who waits.
 *
 * @author Vlad Mihalcea
 */
public interface AdmissionControl extends AutoCloseable {

    /** A unit of work that may throw. */
    @FunctionalInterface
    interface Work {
        void run() throws Exception;
    }

    /** Runs the work once admitted. */
    void run(Work work) throws Exception;

    String id();

    @Override
    default void close() {
    }

    /**
     * Builds an admission control by id.
     *
     * @param id          {@code none}, {@code fair-semaphore}, {@code unfair-semaphore},
     *                    {@code bounded-executor} or {@code rate-limiter}
     * @param permits     clients admitted at once (semaphores) or executor threads
     * @param ratePerSec  admissions per second ({@code rate-limiter} only)
     */
    static AdmissionControl of(String id, int permits, double ratePerSec) {
        return switch (id) {
            case "none" -> new AdmissionControl() {
                @Override
                public void run(Work work) throws Exception {
                    work.run();
                }

                @Override
                public String id() {
                    return "none";
                }
            };
            case "fair-semaphore" -> new SemaphoreAdmission(permits, true);
            case "unfair-semaphore" -> new SemaphoreAdmission(permits, false);
            case "bounded-executor" -> new BoundedExecutorAdmission(permits);
            case "rate-limiter" -> new RateLimiterAdmission(ratePerSec);
            default -> throw new IllegalArgumentException("Unknown admission control: " + id);
        };
    }

    /** At most {@code permits} clients reach the pool at once; fair mode admits them in arrival order. */
    final class SemaphoreAdmission implements AdmissionControl {

        private final Semaphore semaphore;
        private final boolean fair;

        SemaphoreAdmission(int permits, boolean fair) {
            this.semaphore = new Semaphore(permits, fair);
            this.fair = fair;
        }

        @Override
        public void run(Work work) throws Exception {
            semaphore.acquire();
            try {
                work.run();
            } finally {
                semaphore.release();
            }
        }

        @Override
        public String id() {
            return fair ? "fair-semaphore" : "unfair-semaphore";
        }
    }

    /**
     * The work runs on a fixed pool of {@code threads} platform threads while the client waits for it, the
     * classic way to cap database concurrency before virtual threads.
     */
    final class BoundedExecutorAdmission implements AdmissionControl {

        private final ExecutorService executor;

        BoundedExecutorAdmission(int threads) {
            this.executor = Executors.newFixedThreadPool(threads);
        }

        @Override
        public void run(Work work) throws Exception {
            try {
                executor.submit(() -> {
                    work.run();
                    return null;
                }).get();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof Exception cause) {
                    throw cause;
                }
                throw e;
            }
        }

        @Override
        public String id() {
            return "bounded-executor";
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }

    /** Admits one client every {@code 1 / ratePerSec} seconds, in the order the slots are claimed. */
    final class RateLimiterAdmission implements AdmissionControl {

        private final long intervalNanos;
        private final AtomicLong nextSlot = new AtomicLong(System.nanoTime());

        RateLimiterAdmission(double ratePerSec) {
            if (ratePerSec <= 0) {
                throw new IllegalArgumentException("The rate must be positive: " + ratePerSec);
            }
            this.intervalNanos = Math.max(1, (long) (1e9 / ratePerSec));
        }

        @Override
        public void run(Work work) throws Exception {
            long now = System.nanoTime();
            // Claim the next slot; an idle limiter does not bank slots from the past.
            long slot = nextSlot.getAndUpdate(next -> Math.max(next, now) + intervalNanos);
            long start = Math.max(slot, now);
            while (System.nanoTime() < start) {
                LockSupport.parkNanos(start - System.nanoTime());
            }
            work.run();
        }

        @Override
        public String id() {
            return "rate-limiter";
        }
    }
}
