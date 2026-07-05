package com.vladmihalcea.phd.reservoir.api;

/**
 * Nanosecond time source injected into time-decayed and windowed strategies so that accuracy
 * experiments can advance logical time deterministically instead of depending on wall-clock time.
 *
 * @author Vlad Mihalcea
 */
@FunctionalInterface
public interface Clock {

    long nanoTime();

    /** Wall-clock source backed by {@link System#nanoTime()}, used by the JMH benchmarks. */
    Clock SYSTEM = System::nanoTime;

    /**
     * A manually advanced clock for reproducible time-decay experiments.
     */
    final class Manual implements Clock {

        private long nanos;

        public Manual(long startNanos) {
            this.nanos = startNanos;
        }

        public Manual() {
            this(0L);
        }

        @Override
        public long nanoTime() {
            return nanos;
        }

        public void set(long nanos) {
            this.nanos = nanos;
        }

        public void advanceNanos(long deltaNanos) {
            this.nanos += deltaNanos;
        }

        public void advanceMillis(long deltaMillis) {
            this.nanos += deltaMillis * 1_000_000L;
        }
    }
}
