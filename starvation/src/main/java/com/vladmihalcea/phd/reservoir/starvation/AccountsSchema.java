package com.vladmihalcea.phd.reservoir.starvation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The pgbench-style {@code accounts} table of the connection-pool-auto-scaling repository, which the
 * starvation study reuses as its OLTP fixture so its throughput is comparable with that repository's E1
 * measurements. PostgreSQL only.
 *
 * @author Vlad Mihalcea
 */
public final class AccountsSchema {

    private static final Logger LOGGER = LoggerFactory.getLogger(AccountsSchema.class);

    /** One scale unit is this many account rows (pgbench convention). */
    public static final int ROWS_PER_SCALE = 1_000;

    private final int scaleFactor;

    public AccountsSchema(int scaleFactor) {
        this.scaleFactor = Math.max(1, scaleFactor);
    }

    public int accountCount() {
        return scaleFactor * ROWS_PER_SCALE;
    }

    /** Drops and recreates the table, loads {@link #accountCount()} rows, and analyzes it. */
    public void create(DataSource dataSource) {
        long start = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement ddl = connection.createStatement()) {
                ddl.execute("DROP TABLE IF EXISTS accounts");
                ddl.execute("""
                        CREATE TABLE accounts (
                            id      BIGINT       PRIMARY KEY,
                            balance BIGINT       NOT NULL,
                            filler  VARCHAR(120) NOT NULL
                        )
                        """);
            }
            connection.commit();
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO accounts (id, balance, filler) VALUES (?, ?, ?)")) {
                int rows = accountCount();
                for (int id = 1; id <= rows; id++) {
                    insert.setLong(1, id);
                    insert.setLong(2, 100_000L);
                    insert.setString(3, "account-" + id);
                    insert.addBatch();
                    if (id % 1_000 == 0) {
                        insert.executeBatch();
                    }
                }
                insert.executeBatch();
            }
            connection.commit();
            try (Statement analyze = connection.createStatement()) {
                analyze.execute("ANALYZE accounts");
            }
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create the accounts schema", e);
        }
        LOGGER.info("Created accounts schema with {} rows in {} ms",
                accountCount(), (System.nanoTime() - start) / 1_000_000);
    }
}
