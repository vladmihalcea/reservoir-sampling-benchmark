package com.vladmihalcea.phd.reservoir.starvation;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.SplittableRandom;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * This module's copy of experiment E1 of the connection-pool-auto-scaling repository
 * ({@code ThroughputVsPoolSizeBenchmark}), restricted to the case the M1 validation needs: HikariCP of size
 * {@code poolSize}, the read-mostly workload, and a fair semaphore admitting {@code min(128, poolSize + 16)}
 * threads to the pool. The {@link StarvationDriver} must reproduce its throughput before it replaces JMH.
 *
 * @author Vlad Mihalcea
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class AcquireTransactionBenchmark {

    @State(Scope.Benchmark)
    public static class PoolState {

        @Param({"16"})
        public int poolSize;

        private HikariDataSource dataSource;
        private AccountsWorkload workload;
        private Semaphore clientThrottle;

        @Setup(Level.Trial)
        public void setUp() {
            AccountsSchema schema = new AccountsSchema(10);
            schema.create(PostgreSqlSupport.adminDataSource());
            JdbcConfig jdbc = PostgreSqlSupport.jdbcConfig();
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(jdbc.url());
            config.setUsername(jdbc.username());
            config.setPassword(jdbc.password());
            config.setMaximumPoolSize(poolSize);
            config.setMinimumIdle(poolSize);
            config.setConnectionTimeout(10_000);
            dataSource = new HikariDataSource(config);
            workload = AccountsWorkload.readMostly(schema.accountCount());
            clientThrottle = new Semaphore(Math.min(poolSize + 16, 128), true);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (dataSource != null) {
                dataSource.close();
            }
        }
    }

    @State(Scope.Thread)
    public static class Rng {
        final SplittableRandom random = new SplittableRandom(System.nanoTime());
    }

    @Benchmark
    public void transaction(PoolState state, Rng rng, Blackhole blackhole) throws SQLException {
        state.clientThrottle.acquireUninterruptibly();
        try (Connection connection = state.dataSource.getConnection()) {
            state.workload.run(connection, rng.random);
            blackhole.consume(connection);
        } finally {
            state.clientThrottle.release();
        }
    }
}
