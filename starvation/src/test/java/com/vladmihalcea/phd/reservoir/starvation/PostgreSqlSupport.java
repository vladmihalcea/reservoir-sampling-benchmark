package com.vladmihalcea.phd.reservoir.starvation;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Assumptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * The PostgreSQL database of the starvation experiments, as in the connection-pool-auto-scaling repository:
 * a <strong>local</strong> {@code uslpool} database when one answers on {@code localhost} (override with
 * {@code usl.db.url}, {@code usl.db.username} and {@code usl.db.password}), otherwise a JVM-wide
 * {@code postgres:17-alpine} Testcontainer with room for 500 connections. Tests call
 * {@link #assumeDatabaseAvailable()} so they are skipped, not failed, without either.
 *
 * @author Vlad Mihalcea
 */
public final class PostgreSqlSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger(PostgreSqlSupport.class);

    private static final int MAX_CONNECTIONS = 500;

    private static final String LOCAL_JDBC_URL =
            System.getProperty("usl.db.url", "jdbc:postgresql://localhost/uslpool");
    private static final String LOCAL_USERNAME = System.getProperty("usl.db.username", "postgres");
    private static final String LOCAL_PASSWORD = System.getProperty("usl.db.password", "admin");

    private static volatile Boolean localAvailable;
    private static volatile PostgreSQLContainer<?> container;

    private PostgreSqlSupport() {
    }

    public static synchronized boolean isLocalDatabaseAvailable() {
        if (localAvailable == null) {
            try (Connection ignored = DriverManager.getConnection(LOCAL_JDBC_URL, LOCAL_USERNAME, LOCAL_PASSWORD)) {
                localAvailable = Boolean.TRUE;
                LOGGER.info("Using local PostgreSQL database at {}", LOCAL_JDBC_URL);
            } catch (SQLException e) {
                localAvailable = Boolean.FALSE;
                LOGGER.info("Local PostgreSQL database not reachable at {} ({}) - will fall back to Docker",
                        LOCAL_JDBC_URL, e.getMessage());
            }
        }
        return localAvailable;
    }

    private static synchronized PostgreSQLContainer<?> container() {
        if (container == null) {
            PostgreSQLContainer<?> instance = new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("uslpool")
                    .withUsername("postgres")
                    .withPassword("admin")
                    .withCommand("postgres", "-c", "max_connections=" + MAX_CONNECTIONS)
                    .withReuse(true);
            instance.start();
            container = instance;
            LOGGER.info("Started PostgreSQL Testcontainer at {}", instance.getJdbcUrl());
        }
        return container;
    }

    public static JdbcConfig jdbcConfig() {
        return isLocalDatabaseAvailable()
                ? new JdbcConfig(LOCAL_JDBC_URL, LOCAL_USERNAME, LOCAL_PASSWORD)
                : new JdbcConfig(container().getJdbcUrl(), container().getUsername(), container().getPassword());
    }

    /** A two-connection administrative data source for schema creation, separate from the pool under test. */
    public static DataSource adminDataSource() {
        JdbcConfig jdbc = jdbcConfig();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbc.url());
        config.setUsername(jdbc.username());
        config.setPassword(jdbc.password());
        config.setMaximumPoolSize(2);
        config.setPoolName("admin");
        return new HikariDataSource(config);
    }

    public static boolean isDatabaseAvailable() {
        if (isLocalDatabaseAvailable()) {
            return true;
        }
        try {
            return org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void assumeDatabaseAvailable() {
        Assumptions.assumeTrue(isDatabaseAvailable(),
                "Neither a local PostgreSQL 'uslpool' database nor Docker is available - skipping the test");
    }
}
