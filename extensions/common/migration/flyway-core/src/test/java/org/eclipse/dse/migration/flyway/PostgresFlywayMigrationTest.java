package org.eclipse.dse.migration.flyway;

import org.eclipse.edc.junit.annotations.ComponentTest;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.persistence.EdcPersistenceException;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;

/**
 * Checks the claim everything else rests on: an existing 0.14 database and a database freshly
 * autocreated by 0.15 converge to the same schema once the migrations have run. Without it, applying
 * migrations to a database whose prior state is unknown is guesswork.
 * <p>
 * The two fixtures are the real upstream DDL, extracted from the Connector tags v0.14.0 and v0.15.0
 * plus the matching DSE {@code telemetry-record-schema.sql}. They stand in for what
 * {@code SqlSchemaBootstrapper} creates at boot, which is why this test can run on a 0.14 codebase.
 * Regenerate them from those tags when the covered store set changes.
 * <p>
 * Migration sets are discovered from the source tree rather than listed here, so a module added later
 * is covered by default.
 */
@ComponentTest
class PostgresFlywayMigrationTest {

    private static final String PARTICIPANT_ID = "did:web:example:eonax";

    /** Every migration set: what a database written by several runtimes ends up seeing. */
    private static final List<String> ALL_LOCATIONS = MigrationScripts.locations();

    /**
     * What a control-plane runtime carries: the shared set plus its own. It does not carry the
     * data-plane set, even when both write to the same database.
     */
    private static final List<String> CONTROL_PLANE_LOCATIONS = MigrationScripts.locationsOf("shared", "control-plane");

    private static final List<String> DATA_PLANE_LOCATIONS = MigrationScripts.locationsOf("shared", "data-plane");

    private static final List<String> IDENTITY_HUB_LOCATIONS = MigrationScripts.locationsOf("shared", "identity-hub");

    /**
     * The issuer-service carries the identity-hub set as well: the two runtimes share five store
     * modules, so whichever boots first must be able to migrate the tables both of them create.
     */
    private static final List<String> ISSUER_SERVICE_LOCATIONS =
            MigrationScripts.locationsOf("shared", "identity-hub", "issuer-service");

    /** The non-shared locations each runtime carries -- what a barrier-gated boot's first phase sees. */
    private static final List<String> CONTROL_PLANE_OWN_LOCATIONS = MigrationScripts.locationsOf("control-plane");
    private static final List<String> DATA_PLANE_OWN_LOCATIONS = MigrationScripts.locationsOf("data-plane");
    private static final List<String> IDENTITY_HUB_OWN_LOCATIONS = MigrationScripts.locationsOf("identity-hub");
    private static final List<String> ISSUER_SERVICE_OWN_LOCATIONS = MigrationScripts.locationsOf("identity-hub", "issuer-service");

    private static final int TOTAL_MIGRATIONS = MigrationScripts.all().size();

    private final Monitor monitor = mock();

    /**
     * Known limitation, not a regression: {@code CREATE UNIQUE INDEX ... ON edc_lease (lease_id)}
     * lives inside a checksum-frozen 0.14 baseline file and cannot be corrected there. It fails as
     * soon as this replays {@code baselineOnMigrate} from zero against {@code fixtures/schema-0.14.sql},
     * a fixture that predates the frozen baseline's own inconsistency. No code change can close this
     * path -- {@code edc.sql.schema.autocreate=false} is what prevents it from ever happening for real
     * (see {@code flyway-core/README.md#tests}).
     */
    @Tag("known-limitation")
    @Test
    void migratedFrom014_convergesWithFreshly015Database() {
        var migrated = PostgresTestDatabase.createWith("fixtures/schema-0.14.sql");
        var fresh = PostgresTestDatabase.createWith("fixtures/schema-0.15.sql");

        migrate(migrated);
        migrate(fresh);

        assertThat(migrated.columns()).containsExactlyElementsOf(fresh.columns());
        assertThat(migrated.constraints()).containsExactlyElementsOf(fresh.constraints());
        assertThat(migrated.indexes()).containsExactlyElementsOf(fresh.indexes());
    }

    @Test
    void rerunningMigrations_appliesNothing() {
        var database = PostgresTestDatabase.createWith("fixtures/schema-0.14.sql");

        var first = migrate(database);
        var columnsAfterFirstRun = database.columns();
        var second = migrate(database);

        assertThat(first).isEqualTo(TOTAL_MIGRATIONS);
        assertThat(second).isZero();
        assertThat(database.columns()).containsExactlyElementsOf(columnsAfterFirstRun);
    }

    @Test
    void edcLease_isCreatedOnce_withTheCompositeKey() {
        var database = PostgresTestDatabase.createWith("fixtures/schema-0.14.sql");

        migrate(database);

        assertThat(database.columnNames())
                .contains("edc_lease.resource_id", "edc_lease.resource_kind")
                .doesNotContain("edc_lease.lease_id");
        assertThat(database.constraints()).contains("edc_lease p resource_id,resource_kind");
    }

    /**
     * On an empty database every backfill touches zero rows, so the {@code SET NOT NULL} that follows
     * passes whether or not the backfill is right. This runs them against actual data.
     */
    @Test
    void backfills_fillEveryExistingRow() {
        var database = PostgresTestDatabase.createWith("fixtures/schema-0.14.sql");
        database.execute(PostgresTestDatabase.readResource("fixtures/seed-0.14.sql"));

        migrate(database);

        // Tables whose participant_context_id is backfilled from the placeholder.
        assertThat(database.distinctValues("edc_transfer_process", "participant_context_id")).containsExactly(PARTICIPANT_ID);
        assertThat(database.distinctValues("edc_contract_negotiation", "participant_context_id")).containsExactly(PARTICIPANT_ID);
        assertThat(database.distinctValues("edc_contract_agreement", "agr_participant_context_id")).containsExactly(PARTICIPANT_ID);
        assertThat(database.distinctValues("edc_asset", "participant_context_id")).containsExactly(PARTICIPANT_ID);
        assertThat(database.distinctValues("edc_policydefinitions", "participant_context_id")).containsExactly(PARTICIPANT_ID);
        assertThat(database.distinctValues("edc_contract_definitions", "participant_context_id")).containsExactly(PARTICIPANT_ID);
        assertThat(database.distinctValues("edc_edr_entry", "participant_context_id")).containsExactly(PARTICIPANT_ID);

        // agr_agreement_id is copied from the existing primary key, not from the placeholder.
        assertThat(database.query("SELECT agr_id || '/' || agr_agreement_id FROM edc_contract_agreement ORDER BY 1"))
                .containsExactly("agreement-1/agreement-1", "agreement-2/agreement-2");

        // credential_resource.usage is derived from the VC type, not from a single constant. Reading it
        // back with the wrong spelling would break CredentialUsage.valueOf() at runtime, not here, so
        // the exact constant names are asserted.
        assertThat(database.query("SELECT id || '/' || usage FROM credential_resource ORDER BY 1"))
                .containsExactly("cred-1/Holder", "cred-2/StatusList");

        // Read back with fromJson(.., Set<KeyPairUsage>), whose constants serialise through @JsonProperty.
        assertThat(database.distinctValues("keypair_resource", "usage"))
                .containsExactly("[\"sign_credentials\",\"sign_presentation\",\"sign_token\"]");

        // Backfilled from the row's own primary key, which held the participant context id in 0.14 —
        // deliberately not the placeholder, so this asserts a different value from the columns above.
        assertThat(database.distinctValues("edc_sts_client", "participant_context_id")).containsExactly("participant-1");

        assertThat(database.query("SELECT anonymous::text || '/' || properties::text FROM holders"))
                .containsExactly("false/{}");

        // The rows themselves survive: a backfill that dropped data would still satisfy the assertions above.
        assertThat(database.rowCounts()).contains(
                "edc_transfer_process 2",
                "edc_data_plane 1",
                "edc_telemetry_record 1",
                "credential_resource 2",
                "keypair_resource 1",
                "edc_sts_client 1",
                "holders 1",
                "edc_credential_offers 1",
                "edc_holder_credentialrequest 1",
                "edc_issuance_process 1");
    }

    /**
     * The UAT layout: the runtimes of one participant share a single database but carry different
     * migration sets, so each sees history rows for migrations it cannot resolve on its own classpath.
     * That is what {@code ignoreMigrationPatterns} is for — without it, the second runtime fails its
     * boot on a validation error.
     * <p>
     * Without the barrier, whichever of these boots first would apply the shared set immediately,
     * potentially before a sibling's still-pending, checksum-frozen baseline has run — the exact race
     * that broke a real playground upgrade (ticket #605). Booting through {@link #migrateWithBarrier}
     * guarantees every runtime's own migrations land before any of them reaches the shared set.
     */
    @Test
    void runtimesWithDifferentSets_shareOneHistory() throws Exception {
        var shared = PostgresTestDatabase.createWith("fixtures/schema-0.14.sql");
        var reference = PostgresTestDatabase.createWith("fixtures/schema-0.14.sql");
        var expectedModules = Set.of("control-plane", "data-plane", "identity-hub", "issuer-service");
        var barrierTimeout = Duration.ofSeconds(10);
        var executor = Executors.newFixedThreadPool(4);

        try {
            Callable<Integer> controlPlaneBoot = () -> migrateWithBarrier(
                    shared, CONTROL_PLANE_OWN_LOCATIONS, CONTROL_PLANE_LOCATIONS, "control-plane", expectedModules, barrierTimeout);
            Callable<Integer> dataPlaneBoot = () -> migrateWithBarrier(
                    shared, DATA_PLANE_OWN_LOCATIONS, DATA_PLANE_LOCATIONS, "data-plane", expectedModules, barrierTimeout);
            Callable<Integer> identityHubBoot = () -> migrateWithBarrier(
                    shared, IDENTITY_HUB_OWN_LOCATIONS, IDENTITY_HUB_LOCATIONS, "identity-hub", expectedModules, barrierTimeout);
            Callable<Integer> issuerServiceBoot = () -> migrateWithBarrier(
                    shared, ISSUER_SERVICE_OWN_LOCATIONS, ISSUER_SERVICE_LOCATIONS, "issuer-service", expectedModules, barrierTimeout);

            List<Future<Integer>> results = executor.invokeAll(
                    List.of(controlPlaneBoot, dataPlaneBoot, identityHubBoot, issuerServiceBoot));
            var applied = 0;
            for (Future<Integer> result : results) {
                applied += result.get();
            }
            migrate(reference);

            // Every migration lands exactly once across the four runtimes, same as one full boot.
            assertThat(applied).isEqualTo(TOTAL_MIGRATIONS);
            assertThat(shared.historyRowCount()).isEqualTo(TOTAL_MIGRATIONS + 1); // + the baseline row
            // Splitting the run across runtimes must land on the same schema as running everything at once.
            // dse_migration_barrier itself is excluded: it is the barrier's own coordination table, not
            // part of the EDC schema, and only exists on the barrier-coordinated side of this comparison.
            assertThat(shared.columns().stream().filter(column -> !column.startsWith("dse_migration_barrier")).toList())
                    .containsExactlyElementsOf(reference.columns());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Boot order across runtimes is not guaranteed. When the runtime carrying the highest migration
     * version boots first, every other runtime finds a history row it cannot resolve, at a version
     * above anything on its own classpath. Flyway calls that a <em>future</em> migration and rejects
     * it separately from a <em>missing</em> one.
     * <p>
     * Barrier-gated, both boot concurrently rather than one fully finishing before the other starts --
     * the point is that neither reaches the shared set before both have applied their own baseline, so
     * the future-migration tolerance is exercised without also hitting the checksum-frozen-baseline race.
     */
    @Test
    void runtimeBootingAfterHigherVersion_doesNotFail() throws Exception {
        var database = PostgresTestDatabase.createWith("fixtures/schema-0.14.sql");
        var expectedModules = Set.of("issuer-service", "control-plane");
        var barrierTimeout = Duration.ofSeconds(10);
        var executor = Executors.newFixedThreadPool(2);

        try {
            Callable<Integer> issuerServiceBoot = () -> migrateWithBarrier(
                    database, ISSUER_SERVICE_OWN_LOCATIONS, ISSUER_SERVICE_LOCATIONS, "issuer-service", expectedModules, barrierTimeout);
            Callable<Integer> controlPlaneBoot = () -> migrateWithBarrier(
                    database, CONTROL_PLANE_OWN_LOCATIONS, CONTROL_PLANE_LOCATIONS, "control-plane", expectedModules, barrierTimeout);

            List<Future<Integer>> results = executor.invokeAll(List.of(issuerServiceBoot, controlPlaneBoot));
            assertThatNoException().isThrownBy(() -> {
                for (Future<Integer> result : results) {
                    result.get();
                }
            });
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Why the shared migration module must never be copy-pasted per launcher: two copies that drift
     * apart have different checksums for the same version, and the second runtime to boot fails.
     */
    @Test
    void divergentCopyOfMigration_failsTheBoot(@TempDir Path divergentCopy) throws IOException {
        var database = PostgresTestDatabase.createWith("fixtures/schema-0.14.sql");
        migrate(database);

        Files.writeString(divergentCopy.resolve("V20260804.1__recreate_edc_lease.sql"),
                "-- a copy that drifted from the shared module\nDROP TABLE IF EXISTS edc_lease CASCADE;\n");

        assertThatExceptionOfType(EdcPersistenceException.class)
                .isThrownBy(() -> migrate(database, List.of("filesystem:" + divergentCopy)))
                .withRootCauseInstanceOf(FlywayValidateException.class);
    }

    /**
     * Two runtimes sharing one database boot at the same time. They serialise on the Flyway lock;
     * between them the migrations must be applied exactly once, and neither boot may fail.
     */
    @Test
    void concurrentRuntimes_shareOneHistory_withoutCorruption() throws Exception {
        var database = PostgresTestDatabase.createWith("fixtures/schema-0.14.sql");
        var executor = Executors.newFixedThreadPool(2);

        try {
            Callable<Integer> boot = () -> migrate(database);
            List<Future<Integer>> results = executor.invokeAll(List.of(boot, boot));

            var applied = 0;
            for (Future<Integer> result : results) {
                applied += result.get();
            }
            assertThat(applied).isEqualTo(TOTAL_MIGRATIONS);
            assertThat(database.historyRowCount()).isEqualTo(TOTAL_MIGRATIONS + 1); // + the baseline row
        } finally {
            executor.shutdownNow();
        }
    }

    private int migrate(PostgresTestDatabase database) {
        return migrate(database, ALL_LOCATIONS);
    }

    private int migrate(PostgresTestDatabase database, List<String> locations) {
        return new FlywayMigrationRunner(monitor, 100)
                .migrate("test", database.dataSource(), locations,
                        Map.of(FlywayMigrationExtension.PARTICIPANT_CONTEXT_ID_PLACEHOLDER, PARTICIPANT_ID))
                .migrationsExecuted;
    }

    /**
     * Mirrors the two-phase flow {@code FlywayMigrationExtension.prepare()} runs when
     * {@code edc.migration.barrier.expected-modules} is set: this runtime's own (non-shared)
     * migrations, then a wait for every name in {@code expectedModules}, then the full set --
     * shared migrations included, applied by whichever caller gets there first.
     */
    private int migrateWithBarrier(PostgresTestDatabase database, List<String> ownLocations, List<String> allLocations,
                                    String thisModule, Set<String> expectedModules, Duration timeout) {
        var runner = new FlywayMigrationRunner(monitor, 100);
        var placeholders = Map.of(FlywayMigrationExtension.PARTICIPANT_CONTEXT_ID_PLACEHOLDER, PARTICIPANT_ID);

        var ownApplied = runner.migrate("test", database.dataSource(), ownLocations, placeholders).migrationsExecuted;
        new MigrationBarrier(monitor).blockUntilSiblingsFinishNonSharedMigrations(database.dataSource(), thisModule, expectedModules, timeout);
        var totalApplied = runner.migrate("test", database.dataSource(), allLocations, placeholders).migrationsExecuted;

        return ownApplied + totalApplied;
    }
}
