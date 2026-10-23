package com.vladmihalcea.phd.reservoir.starvation;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the {@link StarvationDriver} against an in-memory pool of {@code size} permits whose transactions
 * take a fixed time, so the expected throughput, timeouts and admission bounds are known. No database.
 *
 * @author Vlad Mihalcea
 */
public class StarvationDriverTest {

    @Test
    public void virtualThreadsSaturateThePoolAndLogLosslessly() throws Exception {
        FakePool pool = new FakePool(4, 2_000);
        StarvationDriver.Result result = new StarvationDriver(
                new StarvationDriver.Config(ThreadKind.VIRTUAL, 200, 200, 1_000),
                AdmissionControl.of("none", 0, 0), "none")
                .run(pool, sleep(1));

        // 4 connections held 1 ms each: about 4,000 transactions per second (sleep overshoot lowers it).
        assertTrue(result.throughput() > 1_500 && result.throughput() < 4_500, "throughput " + result.throughput());
        assertEquals(0, result.acquisitionFailures());
        assertEquals(result.acquisitions(), result.traceTimestamps().length);
        for (int i = 1; i < result.traceTimestamps().length; i++) {
            assertTrue(result.traceTimestamps()[i] >= result.traceTimestamps()[i - 1], "trace must be ordered");
        }
        // 200 clients share 4 connections through an unfair semaphore: a client that returns a connection
        // and asks again barges ahead of the waiting ones, so most acquisitions are instant and the
        // starvation sits in the tail. This is the barging behind hypothesis H1.
        assertTrue(result.acquire().p50() < TimeUnit.MILLISECONDS.toNanos(1), "p50 " + result.acquire().p50());
        // How rare the waits are varies from run to run (even p99 can be instant), so assert on the maximum.
        assertTrue(result.acquire().max() > TimeUnit.MILLISECONDS.toNanos(5), "max " + result.acquire().max());
        assertTrue(result.jainFairness() > 0 && result.jainFairness() <= 1.0, "jain " + result.jainFairness());
    }

    @Test
    public void acquisitionTimeoutsAreLoggedWithTheirWait() throws Exception {
        FakePool pool = new FakePool(1, 100);
        StarvationDriver.Result result = new StarvationDriver(
                new StarvationDriver.Config(ThreadKind.VIRTUAL, 50, 200, 1_000),
                AdmissionControl.of("none", 0, 0), "none")
                .run(pool, sleep(20));

        assertTrue(result.acquisitionFailures() > 0, "the starved clients should time out");
        assertTrue(result.acquire().max() >= TimeUnit.MILLISECONDS.toNanos(100), "max " + result.acquire().max());
        assertTrue(result.starvedClients() > 0, "some clients should complete nothing");
    }

    @Test
    public void semaphoreAdmissionBoundsTheClientsAtThePool() throws Exception {
        FakePool pool = new FakePool(4, 2_000);
        StarvationDriver.Result result = new StarvationDriver(
                new StarvationDriver.Config(ThreadKind.VIRTUAL, 100, 200, 800),
                AdmissionControl.of("fair-semaphore", 2, 0), "none")
                .run(pool, sleep(1));

        assertTrue(pool.maxConcurrentBorrowers.get() <= 2, "borrowers " + pool.maxConcurrentBorrowers.get());
        // The wait moves from the pool to the semaphore: the total wait dwarfs the acquisition time.
        assertTrue(result.totalWait().p50() > 10 * result.acquire().p50(),
                "wait " + result.totalWait().p50() + " vs acquire " + result.acquire().p50());
    }

    @Test
    public void theRecorderSeesTheMeasurementWindowOnly() throws Exception {
        FakePool pool = new FakePool(4, 2_000);
        StarvationDriver.Result result = new StarvationDriver(
                new StarvationDriver.Config(ThreadKind.VIRTUAL, 100, 300, 1_000),
                AdmissionControl.of("none", 0, 0), "HdrHistogram-Recorder")
                .run(pool, sleep(1));

        // HdrHistogram keeps 3 significant digits, so its p99 matches the exact p99 of the same window.
        double error = Math.abs(result.recorderP99() - result.acquire().p99()) / result.acquire().p99();
        assertTrue(error < 0.05, "recorder p99 " + result.recorderP99() + " vs exact " + result.acquire().p99());
    }

    @Test
    public void nearestRankPercentiles() {
        long[] sorted = new long[1_000];
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = i + 1;
        }
        StarvationDriver.Percentiles p = StarvationDriver.Percentiles.of(sorted, sorted.length);
        assertEquals(500, p.p50());
        assertEquals(990, p.p99());
        assertEquals(999, p.p999());
        assertEquals(1_000, p.max());
    }

    /**
     * Holds the connection for {@code millis}, busy-waiting: on Windows a short park sleeps a whole 15.6 ms
     * timer tick. Only as many transactions run at once as the pool has connections, so the spin is cheap.
     */
    private static StarvationDriver.Transaction sleep(long millis) {
        return (connection, random) -> {
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            while (System.nanoTime() < end) {
                Thread.onSpinWait();
            }
        };
    }

    /** A pool of {@code size} permits; borrowing waits up to {@code timeoutMillis}, then throws. */
    private static final class FakePool implements DataSource {

        private final Semaphore permits;
        private final long timeoutMillis;
        private final AtomicInteger borrowers = new AtomicInteger();
        final AtomicInteger maxConcurrentBorrowers = new AtomicInteger();

        FakePool(int size, long timeoutMillis) {
            this.permits = new Semaphore(size, false);
            this.timeoutMillis = timeoutMillis;
        }

        @Override
        public Connection getConnection() throws SQLException {
            int now = borrowers.incrementAndGet();
            maxConcurrentBorrowers.accumulateAndGet(now, Math::max);
            try {
                if (!permits.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    throw new SQLException("Timeout waiting for a connection");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException(e);
            } finally {
                borrowers.decrementAndGet();
            }
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if ("close".equals(method.getName())) {
                            permits.release();
                        }
                        return null;
                    });
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            return null;
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
