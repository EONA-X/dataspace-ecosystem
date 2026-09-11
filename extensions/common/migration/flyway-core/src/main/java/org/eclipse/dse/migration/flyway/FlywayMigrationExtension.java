package org.eclipse.dse.migration.flyway;

import org.eclipse.dse.migration.spi.MigrationRegistry;
import org.eclipse.dse.migration.spi.MigrationSet;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.security.Vault;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.spi.system.configuration.Config;
import org.eclipse.edc.sql.DriverManagerConnectionFactory;
import org.eclipse.edc.sql.bootstrapper.SqlSchemaBootstrapper;
import org.eclipse.edc.sql.datasource.ConnectionFactoryDataSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.sql.DataSource;

import static java.util.Optional.ofNullable;
import static org.eclipse.dse.migration.flyway.FlywayMigrationExtension.NAME;

/**
 * Applies the Flyway migrations contributed by the migration modules on the runtime classpath.
 * <p>
 * Migrations run in {@code prepare()}: {@code ExtensionLifecycleManager} invokes every
 * {@code initialize()} before any {@code prepare()}, so all migration modules have registered by
 * then. Injecting {@link SqlSchemaBootstrapper} places this extension after
 * {@code SqlSchemaBootstrapperExtension} in the dependency-sorted order, so {@code prepare()} runs
 * after the schema autocreate has completed.
 * <p>
 * The datasource Flyway migrates against is built directly from {@code edc.datasource.<name>}
 * configuration (same vault-then-config resolution and setting keys as
 * {@code CommonsConnectionPoolServiceExtension}), not resolved through {@link
 * org.eclipse.edc.transaction.datasource.spi.DataSourceRegistry}: that registry hands back
 * connections wrapped for participation in EDC's local transaction context, and that wrapper's
 * {@code setAutoCommit()} unconditionally throws {@code UnsupportedOperationException} --
 * incompatible with Flyway, which manages autocommit itself as part of its locking and schema
 * history protocol. This is also how the Mobility Data Space reference implementation does it
 * ({@code DatabaseMigrationConfiguration.getDataSource()}), for the same reason.
 */
@Extension(value = NAME, categories = { "sql", "persistence", "storage" })
public class FlywayMigrationExtension implements ServiceExtension {

    private static final String EDC_DATASOURCE_PREFIX = "edc.datasource";

    public static final String NAME = "Flyway Database Migration Extension";

    /**
     * Flyway placeholder resolved from {@code edc.participant.id}, required by the migrations that
     * backfill the {@code participant_context_id} columns introduced in EDC 0.15.0.
     */
    public static final String PARTICIPANT_CONTEXT_ID_PLACEHOLDER = "participantContextId";

    @Setting(description = "When false, no migration is applied at boot. Intended for debugging a failing migration, not for normal operation.",
            key = "edc.migration.enabled", defaultValue = "true", required = false)
    private boolean enabled;

    @Setting(description = "Number of times Flyway retries acquiring its lock before giving up. Runtimes sharing a database serialise on this lock, so the value must cover the slowest of them booting.",
            key = "edc.migration.lock.retry.count", defaultValue = "50", required = false)
    private int lockRetryCount;

    @Setting(description = "Comma-separated signal key of every migration-contributing runtime sharing this database, this " +
            "one included. A runtime's signal key is the moduleName() of every non-shared MigrationSet it carries, " +
            "joined with '+' (most runtimes carry exactly one, e.g. 'Control Plane Database Migration'; the " +
            "issuer-service launcher carries two, e.g. 'Identity Hub Database Migration+Issuer Service Database " +
            "Migration', since it bundles identity-hub-migration on its classpath as well as its own). Example for a " +
            "participant with separate control-plane/data-plane/identity-hub runtimes: 'Control Plane Database " +
            "Migration,Data Plane Database Migration,Identity Hub Database Migration'. When set, migration runs in two " +
            "phases: this runtime's own migrations first, then a wait for every key listed here to have done the same, " +
            "then the shared, cross-module set. Prevents that shared set from reshaping an object a sibling's " +
            "still-pending, checksum-frozen baseline depends on. Left unset, migration runs as a single pass, exactly " +
            "as before this setting existed.",
            key = "edc.migration.barrier.expected-modules", defaultValue = "", required = false)
    private String barrierExpectedModules;

    @Setting(description = "How long to wait for every runtime listed in 'edc.migration.barrier.expected-modules' before " +
            "failing the boot.", key = "edc.migration.barrier.timeout-seconds", defaultValue = "120", required = false)
    private int barrierTimeoutSeconds;

    @Setting(description = "Participant id of this runtime, exposed to migrations as the 'participantContextId' placeholder",
            key = "edc.participant.id", required = false)
    private String participantId;

    @Inject
    private Monitor monitor;

    @Inject
    private Vault vault;

    /**
     * Not used directly. Injected so that this extension is ordered after
     * {@code SqlSchemaBootstrapperExtension}, whose {@code prepare()} creates the tables the
     * migrations then alter. Removing this field silently breaks that ordering.
     */
    @Inject
    private SqlSchemaBootstrapper sqlSchemaBootstrapper;

    private MigrationRegistryImpl registry;
    private ServiceExtensionContext context;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        this.context = context;
    }

    /**
     * The instance is cached in a field rather than created on each call, because {@code prepare()}
     * reads the registry through this method. Returning a fresh instance there would hand back an
     * empty one, and every set registered during {@code initialize()} would be dropped without a
     * word: no migration, and a runtime that boots against an unmigrated schema.
     * <p>
     * A default provider, so a runtime may substitute its own {@link MigrationRegistry}.
     */
    @Provider(isDefault = true)
    public MigrationRegistry migrationRegistry() {
        if (registry == null) {
            registry = new MigrationRegistryImpl();
        }
        return registry;
    }

    /**
     * Vocabulary used across {@code prepare()} and the two migrate methods below:
     * <ul>
     *   <li><b>non-shared / {@code !shared()}</b> -- a {@link MigrationSet} owned by exactly one
     *       module (e.g. {@code control-plane-migration}'s own set). Safe to apply as soon as this
     *       runtime boots: nothing else on the database depends on it finishing first.
     *   <li><b>shared / {@code shared()}</b> -- a {@link MigrationSet} contributed by the
     *       {@code migration-shared} module for a database object that belongs to no single store
     *       module (e.g. {@code edc_lease}, declared by nine of them). The same set is registered by
     *       every runtime that depends on {@code migration-shared}, so several JVMs can carry it at
     *       once -- see {@link MigrationBarrier}'s class javadoc for why applying it early is unsafe.
     *   <li><b>combined</b> -- non-shared and shared merged back together, i.e. everything this
     *       runtime's classpath has registered for a given datasource. What Flyway is finally pointed
     *       at; already-applied migrations in it are no-ops the second time (Flyway checks the
     *       history table), so re-passing the non-shared ones here is harmless.
     * </ul>
     */
    @Override
    public void prepare() {
        List<MigrationSet> migrationSets = migrationRegistry().getMigrationSets();

        if (!enabled) {
            monitor.warning("%s: migrations are disabled ('edc.migration.enabled' = false), %d registered set(s) skipped"
                    .formatted(NAME, migrationSets.size()));
            return;
        }

        if (migrationSets.isEmpty()) {
            monitor.debug("%s: no migration set registered, nothing to do".formatted(NAME));
            return;
        }

        FlywayMigrationRunner runner = new FlywayMigrationRunner(monitor, lockRetryCount);
        Map<String, String> placeholders = buildFlywayPlaceholders();
        Set<String> expectedModules = parseBarrierExpectedModules();

        if (expectedModules.isEmpty()) {
            migrateWithoutBarrier(migrationSets, runner, placeholders);
        } else {
            migrateBehindBarrier(migrationSets, runner, placeholders, expectedModules);
        }
    }

    /**
     * Default path, used when {@code edc.migration.barrier.expected-modules} is unset: this
     * runtime is the only one migrating its database(s), so every registered set -- shared and
     * non-shared alike -- is migrated together in a single Flyway run per datasource.
     */
    private void migrateWithoutBarrier(List<MigrationSet> migrationSets, FlywayMigrationRunner runner, Map<String, String> placeholders) {
        Map<String, List<String>> locationsByDataSource = groupLocationsByDataSource(migrationSets);
        locationsByDataSource.forEach((dataSourceName, locations) -> {
            DataSource dataSource = buildDataSource(dataSourceName);
            runner.migrate(dataSourceName, dataSource, locations, placeholders);
        });
    }

    /**
     * Path used when this database is shared by several runtimes (see {@code
     * edc.migration.barrier.expected-modules}). Per datasource: this runtime's non-shared sets
     * first, then {@link MigrationBarrier#blockUntilSiblingsFinishNonSharedMigrations} blocks until every sibling runtime
     * listed in {@code expectedModules} has done the same, then the combined set -- shared included
     * -- is migrated. See {@link MigrationBarrier}'s class javadoc for why the shared set has to
     * wait.
     */
    private void migrateBehindBarrier(List<MigrationSet> migrationSets, FlywayMigrationRunner runner,
            Map<String, String> placeholders, Set<String> expectedModules) {
        Map<String, List<String>> combinedLocationsByDataSource = groupLocationsByDataSource(migrationSets);
        List<MigrationSet> nonSharedMigrationSets = migrationSets.stream().filter(set -> !set.shared()).toList();
        Map<String, List<String>> nonSharedLocationsByDataSource = groupLocationsByDataSource(nonSharedMigrationSets);
        String signalKey = buildSignalKey(nonSharedMigrationSets);
        MigrationBarrier barrier = new MigrationBarrier(monitor);
        Duration barrierTimeout = Duration.ofSeconds(barrierTimeoutSeconds);

        combinedLocationsByDataSource.forEach((dataSourceName, combinedLocations) -> {
            DataSource dataSource = buildDataSource(dataSourceName);
            List<String> nonSharedLocations = nonSharedLocationsByDataSource.getOrDefault(dataSourceName, List.of());
            runner.migrate(dataSourceName, dataSource, nonSharedLocations, placeholders);
            // Blocks until every sibling in expectedModules has also finished its non-shared migrations,
            // so the shared set below never reshapes something a sibling's still-pending, checksum-frozen
            // baseline depends on (see MigrationBarrier's class javadoc for the concrete failure this avoids).
            barrier.blockUntilSiblingsFinishNonSharedMigrations(dataSource, signalKey, expectedModules, barrierTimeout);
            runner.migrate(dataSourceName, dataSource, combinedLocations, placeholders);
        });
    }

    /**
     * The signal key this runtime uses on the barrier. A single JVM can carry more than one non-shared
     * {@link MigrationSet} -- the issuer-service launcher bundles {@code identity-hub-migration} on its
     * classpath as well as its own, since the two runtimes share store modules (see
     * {@code IssuerServiceMigrationExtension}'s dependency on it). Joined with {@code '+'}, not the
     * {@code ','} {@link #parseBarrierExpectedModules()} splits on: reusing that separator here would make a
     * composite key impossible to list as a single {@code edc.migration.barrier.expected-modules} entry,
     * splitting it back into names that never individually get signalled and permanently deadlocking
     * the barrier for exactly the multi-set runtime it exists to support.
     */
    String buildSignalKey(List<MigrationSet> nonSharedMigrationSets) {
        return nonSharedMigrationSets.stream().map(MigrationSet::moduleName).distinct().sorted().collect(Collectors.joining("+"));
    }

    /**
     * Empty when the setting is unset -- the two-phase split (and the barrier wait it needs) stays
     * off, matching this extension's behaviour before the setting existed.
     */
    private Set<String> parseBarrierExpectedModules() {
        if (barrierExpectedModules == null || barrierExpectedModules.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(barrierExpectedModules.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toSet());
    }

    /**
     * A datasource built straight from {@code edc.datasource.<name>} configuration -- see the class
     * javadoc for why this does not go through {@code DataSourceRegistry.resolve()}. Unpooled: Flyway
     * runs once at boot, for a handful of statements, not worth a connection pool of its own.
     */
    private DataSource buildDataSource(String dataSourceName) {
        String rootPath = EDC_DATASOURCE_PREFIX + "." + dataSourceName;
        Config config = context.getConfig(rootPath);

        String jdbcUrl = getSecretOrSetting(rootPath, "url", config)
                .orElseThrow(() -> new EdcException("%s: mandatory url for datasource '%s' not found (vault or config)"
                        .formatted(NAME, dataSourceName)));

        Properties properties = new Properties();
        properties.putAll(config.getRelativeEntries());
        getSecretOrSetting(rootPath, "user", config).ifPresent(u -> properties.put("user", u));
        getSecretOrSetting(rootPath, "password", config).ifPresent(p -> properties.put("password", p));

        return new ConnectionFactoryDataSource(new DriverManagerConnectionFactory(), jdbcUrl, properties);
    }

    private Optional<String> getSecretOrSetting(String rootPath, String key, Config config) {
        String fullKey = rootPath + "." + key;
        return ofNullable(vault.resolveSecret(fullKey))
                .or(() -> ofNullable(config.getString(key, null)));
    }

    /**
     * Nothing else connects the {@code edc.participant.id} setting to the placeholder name the
     * migration scripts use. A mismatch between the two only surfaces at boot, and only on the
     * environments carrying a migration that needs the placeholder.
     * <p>
     * Hence package-private rather than private: it lets a test assert that connection without
     * standing up a database.
     */
    Map<String, String> buildFlywayPlaceholders() {
        Map<String, String> placeholders = new HashMap<>();
        if (participantId != null && !participantId.isBlank()) {
            placeholders.put(PARTICIPANT_CONTEXT_ID_PLACEHOLDER, participantId);
        } else {
            // Not fatal on its own: only some migrations use the placeholder. Flyway fails the boot
            // with an explicit "no value provided for placeholder" if one of them actually needs it.
            monitor.warning("%s: 'edc.participant.id' is not set, the '%s' placeholder will be unresolved"
                    .formatted(NAME, PARTICIPANT_CONTEXT_ID_PLACEHOLDER));
        }
        return placeholders;
    }

    /**
     * Each database gets exactly one Flyway run, and therefore one history table. Locations are
     * sorted for a stable, reproducible run order.
     * <p>
     * Package-private rather than private so the grouping can be asserted without a database: what it
     * hands to Flyway decides how many runs happen and which locations each one sees.
     */
    Map<String, List<String>> groupLocationsByDataSource(List<MigrationSet> migrationSets) {
        Map<String, List<String>> byDataSource = new TreeMap<>();
        migrationSets.forEach(migrationSet -> {
            monitor.debug("%s: registered '%s' -> datasource '%s', location '%s'"
                    .formatted(NAME, migrationSet.moduleName(), migrationSet.dataSourceName(), migrationSet.location()));
            List<String> locations = byDataSource.computeIfAbsent(migrationSet.dataSourceName(), key -> new ArrayList<>());
            // Two modules may declare the same location while differing by name, so the registry
            // cannot collapse them. Flyway must still see it once: repeated locations make it find
            // every script in them twice and fail on a duplicate version.
            if (!locations.contains(migrationSet.location())) {
                locations.add(migrationSet.location());
            }
        });
        byDataSource.values().forEach(Collections::sort);
        return byDataSource;
    }
}
