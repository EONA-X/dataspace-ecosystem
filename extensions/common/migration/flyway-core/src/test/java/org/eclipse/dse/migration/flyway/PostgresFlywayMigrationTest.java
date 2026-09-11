package org.eclipse.dse.migration.flyway;

import org.eclipse.edc.junit.annotations.ComponentTest;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.persistence.EdcPersistenceException;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

    private static final int TOTAL_MIGRATIONS = MigrationScripts.all().size();
    private static final int CONTROL_PLANE_MIGRATIONS = MigrationScripts.in("shared", "control-plane").size();
    private static final int DATA_PLANE_MIGRATIONS = MigrationScripts.in("data-plane").size();
    private static final int IDENTITY_HUB_MIGRATIONS = MigrationScripts.in("identity-hub").size();
    private static final int ISSUER_SERVICE_MIGRATIONS = MigrationScripts.in("issuer-service").size();

    private final Monitor monitor = mock();

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
     * Booting them in this order is the worst case for the shared set: the control-plane applies it,
     * and every later runtime has to accept finding it already there.
     */
    @Test
    void runtimesWithDifferentSets_shareOneHistory() {
        var shared = PostgresTestDatabase.createWith("fixtures/schema-0.14.sql");
        var reference = PostgresTestDatabase.createWith("fixtures/schema-0.14.sql");

        var controlPlane = migrate(shared, CONTROL_PLANE_LOCATIONS);
        var dataPlane = migrate(shared, DATA_PLANE_LOCATIONS);
        var identityHub = migrate(shared, IDENTITY_HUB_LOCATIONS);
        var issuerService = migrate(shared, ISSUER_SERVICE_LOCATIONS);
        migrate(reference);

        assertThat(controlPlane).isEqualTo(CONTROL_PLANE_MIGRATIONS);
        // The shared set is already applied by the time the other runtimes boot.
        assertThat(dataPlane).isEqualTo(DATA_PLANE_MIGRATIONS);
        assertThat(identityHub).isEqualTo(IDENTITY_HUB_MIGRATIONS);
        // Likewise the identity-hub set, which this runtime also carries.
        assertThat(issuerService).isEqualTo(ISSUER_SERVICE_MIGRATIONS);
        assertThat(shared.historyRowCount()).isEqualTo(TOTAL_MIGRATIONS + 1); // + the baseline row
        // Splitting the run across runtimes must land on the same schema as running everything at once.
        assertThat(shared.columns()).containsExactlyElementsOf(reference.columns());
    }

    /**
     * Boot order across runtimes is not guaranteed. When the runtime carrying the highest migration
     * version boots first, every other runtime finds a history row it cannot resolve, at a version
     * above anything on its own classpath. Flyway calls that a <em>future</em> migration and rejects
     * it separately from a <em>missing</em> one.
     */
    @Test
    void runtimeBootingAfterHigherVersion_doesNotFail() {
        var database = PostgresTestDatabase.createWith("fixtures/schema-0.14.sql");

        migrate(database, ISSUER_SERVICE_LOCATIONS);

        assertThatNoException().isThrownBy(() -> migrate(database, CONTROL_PLANE_LOCATIONS));
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
}
