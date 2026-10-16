package org.eclipse.dse.migration.flyway;

import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.persistence.EdcPersistenceException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Coordinates the two-phase migration split in {@link FlywayMigrationExtension}: every runtime
 * sharing a database applies its own, non-shared migrations first, signals here, and only once
 * every runtime it was told to expect has done the same is any of them allowed to apply the shared,
 * cross-module set (see {@code MigrationSet.shared()}). Without this, whichever runtime reaches the
 * shared set first can leave a sibling's still-pending, Flyway checksum-frozen baseline unable to
 * complete a migration it depends on -- the concrete failure this exists to prevent is a destructive
 * reshape of a table multiple modules hold a foreign key into (see the shared module's own
 * migrations for that story).
 * <p>
 * Deliberately not a Flyway migration itself: the coordination table has to exist, and this runtime
 * has to be able to signal on it, before its own lowest-versioned migration has necessarily run --
 * a chicken-and-egg problem Flyway's own bookkeeping can't resolve.
 * <p>
 * The wait blocks on a single connection for its whole duration (bounded by {@code timeout}), the
 * same trade-off {@code FlywayMigrationExtension.buildDataSource()} already makes for the
 * unpooled datasource this runs against: a handful of statements at boot, not worth pooling.
 */
public class MigrationBarrier {

    private static final String BARRIER_TABLE = "dse_migration_barrier";
    private static final long POLL_INTERVAL_MS = 500;

    private final Monitor monitor;

    public MigrationBarrier(Monitor monitor) {
        this.monitor = monitor;
    }

    /**
     * Signals that {@code signalKey} has finished its own migrations, then blocks until every name
     * in {@code expectedModules} has done the same.
     *
     * @throws EdcPersistenceException if {@code timeout} elapses first, or on any SQL failure.
     */
    public void blockUntilSiblingsFinishNonSharedMigrations(DataSource dataSource, String signalKey, Set<String> expectedModules, Duration timeout) {
        try (Connection connection = dataSource.getConnection()) {
            createTableIfMissing(connection);
            signal(connection, signalKey);
            monitor.info("Migration barrier: '%s' signaled, waiting for %s".formatted(signalKey, expectedModules));

            Instant deadline = Instant.now().plus(timeout);
            Set<String> signaled = readSignaledModules(connection);
            while (!signaled.containsAll(expectedModules)) {
                if (Instant.now().isAfter(deadline)) {
                    Set<String> missing = new HashSet<>(expectedModules);
                    missing.removeAll(signaled);
                    throw new EdcPersistenceException(
                            "Migration barrier timed out after %s waiting for %s (already signaled: %s)"
                                    .formatted(timeout, missing, signaled));
                }
                monitor.debug("Migration barrier: waiting for %s (already signaled: %s)".formatted(expectedModules, signaled));
                sleep();
                signaled = readSignaledModules(connection);
            }
            monitor.info("Migration barrier: satisfied, every expected module signaled (%s)".formatted(signaled));
        } catch (SQLException e) {
            throw new EdcPersistenceException("Migration barrier failed on datasource", e);
        }
    }

    /**
     * {@code CREATE TABLE IF NOT EXISTS} is not atomic in Postgres: the existence check and the
     * creation are two separate steps, so two connections racing here can both pass the check and
     * both attempt the create, and the loser fails on a catalog unique-key violation (23505) or on the
     * row type created alongside the table (42710, "type already exists") rather than a clean
     * "already exists" (42P07) -- exactly the concurrent-boot scenario this class exists to coordinate.
     * The loser's failure is not a real problem, since the winner's table is what it would have
     * created anyway, so it is swallowed rather than propagated.
     */
    private void createTableIfMissing(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS %s (
                        module_name VARCHAR PRIMARY KEY,
                        signaled_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )""".formatted(BARRIER_TABLE));
        } catch (SQLException e) {
            String sqlState = e.getSQLState();
            boolean concurrentlyCreated = "23505".equals(sqlState) || "42P07".equals(sqlState) || "42710".equals(sqlState);
            if (!concurrentlyCreated) {
                throw e;
            }
        }
    }

    private void signal(Connection connection, String signalKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO %s (module_name) VALUES (?) ON CONFLICT DO NOTHING".formatted(BARRIER_TABLE))) {
            statement.setString(1, signalKey);
            statement.execute();
        }
    }

    private Set<String> readSignaledModules(Connection connection) throws SQLException {
        Set<String> signaled = new HashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT module_name FROM %s".formatted(BARRIER_TABLE))) {
            while (resultSet.next()) {
                signaled.add(resultSet.getString("module_name"));
            }
        }
        return signaled;
    }

    private void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcPersistenceException("Interrupted while waiting on the migration barrier", e);
        }
    }
}
