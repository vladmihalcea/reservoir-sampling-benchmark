package com.vladmihalcea.phd.reservoir.starvation;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.supplier.AgroalDataSourceConfigurationSupplier;
import io.agroal.api.security.NamePrincipal;
import io.agroal.api.security.SimplePassword;
import org.apache.commons.dbcp2.BasicDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The pools of the virtual-thread starvation study, each built with one acquisition timeout and opened to
 * its full size before the clients start, so a run measures borrowing and not connection creation:
 * <ul>
 *   <li>{@code hikari}: HikariCP with {@code minimumIdle = maximumPoolSize};</li>
 *   <li>{@code dbcp2}: DBCP2 with its defaults, including {@code testOnBorrow = true}, which validates
 *       every borrowed connection with a round trip;</li>
 *   <li>{@code dbcp2-no-validation}: DBCP2 with {@code testOnBorrow = false};</li>
 *   <li>{@code agroal}: Agroal, which needs an explicit size and timeout.</li>
 * </ul>
 * The default sizes are the pools' own defaults (HikariCP 10, DBCP2 8) and 10 for Agroal, which has none;
 * the optimum sizes are the read-mostly {@code N*} of the ICASC paper.
 *
 * @author Vlad Mihalcea
 */
public final class StarvationPools {

    public static final String[] POOLS = {"hikari", "dbcp2", "dbcp2-no-validation", "agroal"};

    private StarvationPools() {
    }

    /** An opened pool and how to close it. */
    public record Pool(String id, int size, DataSource dataSource, AutoCloseable closer) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            closer.close();
        }
    }

    public static int defaultSize(String pool) {
        return pool.startsWith("dbcp2") ? 8 : 10;
    }

    /** The read-mostly optimum {@code N*} fitted in the ICASC paper (Table I). */
    public static int optimumSize(String pool) {
        return switch (pool) {
            case "hikari" -> 45;
            case "dbcp2", "dbcp2-no-validation" -> 56;
            case "agroal" -> 46;
            default -> throw new IllegalArgumentException("Unknown pool: " + pool);
        };
    }

    public static Pool open(String pool, JdbcConfig jdbc, int size, long timeoutMillis) throws Exception {
        Pool opened = switch (pool) {
            case "hikari" -> {
                HikariConfig config = new HikariConfig();
                config.setJdbcUrl(jdbc.url());
                config.setUsername(jdbc.username());
                config.setPassword(jdbc.password());
                config.setMaximumPoolSize(size);
                config.setMinimumIdle(size);
                config.setConnectionTimeout(timeoutMillis);
                config.setPoolName("starvation-hikari");
                HikariDataSource dataSource = new HikariDataSource(config);
                yield new Pool(pool, size, dataSource, dataSource::close);
            }
            case "dbcp2", "dbcp2-no-validation" -> {
                BasicDataSource dataSource = new BasicDataSource();
                dataSource.setUrl(jdbc.url());
                dataSource.setUsername(jdbc.username());
                dataSource.setPassword(jdbc.password());
                dataSource.setMaxTotal(size);
                dataSource.setMaxIdle(size);
                dataSource.setInitialSize(size);
                dataSource.setMaxWaitMillis(timeoutMillis);
                dataSource.setTestOnBorrow(!"dbcp2-no-validation".equals(pool));
                yield new Pool(pool, size, dataSource, dataSource::close);
            }
            case "agroal" -> {
                AgroalDataSource dataSource = AgroalDataSource.from(new AgroalDataSourceConfigurationSupplier()
                        .connectionPoolConfiguration(cp -> cp
                                .maxSize(size)
                                .minSize(size)
                                .initialSize(size)
                                .acquisitionTimeout(Duration.ofMillis(timeoutMillis))
                                .connectionFactoryConfiguration(cf -> cf
                                        .jdbcUrl(jdbc.url())
                                        .principal(new NamePrincipal(jdbc.username()))
                                        .credential(new SimplePassword(jdbc.password())))));
                yield new Pool(pool, size, dataSource, dataSource::close);
            }
            default -> throw new IllegalArgumentException("Unknown pool: " + pool);
        };
        fill(opened);
        return opened;
    }

    /** Borrows {@code size} connections at once and returns them, so every connection is open. */
    private static void fill(Pool pool) throws SQLException {
        List<Connection> held = new ArrayList<>(pool.size());
        try {
            for (int i = 0; i < pool.size(); i++) {
                held.add(pool.dataSource().getConnection());
            }
        } finally {
            for (Connection connection : held) {
                connection.close();
            }
        }
    }
}
