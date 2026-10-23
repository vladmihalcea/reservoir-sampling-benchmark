package com.vladmihalcea.phd.reservoir.starvation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.SplittableRandom;

/**
 * One OLTP transaction against the {@link AccountsSchema} table, as in the connection-pool-auto-scaling
 * repository: {@code readMostly} reads one balance 90% of the time and increments it 10% of the time, and
 * {@code writeHeavy} splits 50/50. The caller supplies a seeded random source, so a run is reproducible.
 *
 * @author Vlad Mihalcea
 */
public final class AccountsWorkload implements StarvationDriver.Transaction {

    private final String name;
    private final double writeRatio;
    private final int accountCount;

    private AccountsWorkload(String name, double writeRatio, int accountCount) {
        this.name = name;
        this.writeRatio = writeRatio;
        this.accountCount = accountCount;
    }

    public static AccountsWorkload readMostly(int accountCount) {
        return new AccountsWorkload("readMostly", 0.10, accountCount);
    }

    public static AccountsWorkload writeHeavy(int accountCount) {
        return new AccountsWorkload("writeHeavy", 0.50, accountCount);
    }

    public String name() {
        return name;
    }

    /** Runs one transaction; the connection is in auto-commit mode on entry and on exit. */
    @Override
    public void run(Connection connection, SplittableRandom random) throws SQLException {
        int accountId = 1 + random.nextInt(accountCount);
        boolean write = random.nextInt(1_000_000) / 1_000_000.0 < writeRatio;
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            if (write) {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE accounts SET balance = balance + 1 WHERE id = ?")) {
                    update.setLong(1, accountId);
                    update.executeUpdate();
                }
            } else {
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT balance FROM accounts WHERE id = ?")) {
                    select.setLong(1, accountId);
                    try (ResultSet rs = select.executeQuery()) {
                        if (rs.next()) {
                            rs.getLong(1);
                        }
                    }
                }
            }
            connection.commit();
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException ignore) {
                // best-effort rollback; the original exception is propagated
            }
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }
}
