package org.eclipse.dse.migration.flyway;

import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.persistence.EdcPersistenceException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Runs Flyway against one datasource. Kept free of EDC extension plumbing so it can be tested
 * directly against a database.
 * <p>
 * The configuration below gives one {@code flyway_schema_history} per database, shared by every
 * runtime writing to it, and expects migrations that are idempotent rather than baselined on a known
 * starting state. See the module README for why.
 */
public class FlywayMigrationRunner {

    /**
     * Name of the shared history table. Explicit rather than left to the Flyway default, because
     * several runtimes write to the same one and a silent default change would split the history.
     */
    public static final String HISTORY_TABLE = "flyway_schema_history";

    /**
     * Retries of the whole {@code migrate()} call, for the one race {@code lockRetryCount} does not
     * cover: two runtimes booting against a brand new, empty database (edc.sql.schema.autocreate is
     * now false, so this happens on a participant's first ever boot) both find no
     * {@code flyway_schema_history} table and race to create and baseline it. Flyway's own lock lives
     * *in* that table, so this specific race happens before the lock exists to serialise on --
     * whichever loses gets "Unable to baseline schema history table ... as it already exists, and is
     * empty" instead of a lock wait. Once one of them finishes, the table is baselined and every
     * further attempt (including this retry) takes the normal locked path.
     */
    private static final int BASELINE_RACE_RETRIES = 5;
    private static final long BASELINE_RACE_RETRY_DELAY_MS = 200;

    private final Monitor monitor;
    private final int lockRetryCount;

    public FlywayMigrationRunner(Monitor monitor, int lockRetryCount) {
        this.monitor = monitor;
        this.lockRetryCount = lockRetryCount;
    }

    /**
     * A failed migration is fatal: the runtime must not serve traffic against a schema it does not
     * match.
     *
     * @throws EdcPersistenceException if Flyway fails, so the boot fails fast rather than later on a
     *                                 column-not-found error.
     */
    public MigrateResult migrate(String dataSourceName, DataSource dataSource, List<String> locations, Map<String, String> placeholders) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(locations.toArray(new String[0]))
                .placeholders(placeholders)
                .table(HISTORY_TABLE)
                // A database Flyway sees may already be populated -- by SqlSchemaBootstrapper on a
                // runtime still running with edc.sql.schema.autocreate=true, or by a production
                // database that predates this migration mechanism entirely. Baselining at version 0
                // lets every migration replay; they are written to be idempotent. Manual migrations
                // were applied in the past without being recorded, so the prior state of a database is
                // not knowable.
                .baselineOnMigrate(true)
                .baselineVersion("0")
                // A runtime sees history rows written by the other runtimes sharing this database,
                // for migrations it does not carry on its own classpath. Flyway splits those in two:
                // 'missing' below its own highest version, 'future' above it. Which of the two a given
                // runtime sees depends on the boot order, so both have to be tolerated. Passing this
                // setting at all drops Flyway's own default of '*:future', hence repeating it here.
                .ignoreMigrationPatterns("*:missing", "*:future")
                // Migration modules are released at different rates, so a lower version can appear
                // after a higher one has already been applied.
                .outOfOrder(true)
                // Concurrent pod startups serialise on Flyway's lock; the retry count has to cover
                // the slowest runtime sharing the database, or the second pod fails its boot.
                .lockRetryCount(lockRetryCount)
                // Nothing in this codebase should ever drop a schema.
                .cleanDisabled(true)
                .load();

        FlywayException lastFailure = null;
        for (var attempt = 1; attempt <= BASELINE_RACE_RETRIES; attempt++) {
            try {
                MigrateResult result = flyway.migrate();
                // targetSchemaVersion is null when nothing was applied, which is the expected steady state.
                String version = result.targetSchemaVersion == null ? "unchanged" : result.targetSchemaVersion;
                monitor.info("Flyway migration on datasource '%s': %d migration(s) applied, schema version %s"
                        .formatted(dataSourceName, result.migrationsExecuted, version));
                return result;
            } catch (FlywayException e) {
                if (!isBaselineRace(e) || attempt == BASELINE_RACE_RETRIES) {
                    throw new EdcPersistenceException("Flyway migration failed on datasource '%s'".formatted(dataSourceName), e);
                }
                lastFailure = e;
                monitor.info("Flyway migration on datasource '%s': lost the baseline race on attempt %d/%d, retrying"
                        .formatted(dataSourceName, attempt, BASELINE_RACE_RETRIES));
                sleep(BASELINE_RACE_RETRY_DELAY_MS);
            }
        }
        // Unreachable: the loop always returns or throws, but the compiler cannot see that.
        throw new EdcPersistenceException("Flyway migration failed on datasource '%s'".formatted(dataSourceName), lastFailure);
    }

    /**
     * The one message Flyway gives this race, distinguished from every other {@link FlywayException}
     * so that a genuine migration failure still fails fast instead of retrying a doomed attempt five
     * times.
     */
    private static boolean isBaselineRace(FlywayException e) {
        String message = e.getMessage();
        return message != null && message.contains("Unable to baseline schema history table");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcPersistenceException("Interrupted while retrying the baseline race", e);
        }
    }
}
