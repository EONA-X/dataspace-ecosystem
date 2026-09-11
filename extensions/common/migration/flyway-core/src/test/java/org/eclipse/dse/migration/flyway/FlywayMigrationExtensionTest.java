package org.eclipse.dse.migration.flyway;

import org.eclipse.dse.migration.spi.MigrationSet;
import org.eclipse.edc.boot.system.injection.ObjectFactory;
import org.eclipse.edc.junit.extensions.DependencyInjectionExtension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.security.Vault;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.spi.system.configuration.ConfigFactory;
import org.eclipse.edc.sql.bootstrapper.SqlSchemaBootstrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(DependencyInjectionExtension.class)
class FlywayMigrationExtensionTest {

    private static final String PARTICIPANT_ID = "did:web:example:eonax";

    private final Vault vault = mock();
    private final Monitor monitor = mock();

    @BeforeEach
    void setup(ServiceExtensionContext context) {
        context.registerService(Vault.class, vault);
        context.registerService(Monitor.class, monitor);
        context.registerService(SqlSchemaBootstrapper.class, mock());
        when(context.getConfig()).thenReturn(ConfigFactory.fromMap(Map.of("edc.participant.id", PARTICIPANT_ID)));
    }

    /**
     * {@code prepare()} reads the registry back through {@code migrationRegistry()}, not through the
     * injected service. A provider handing out a new instance on each call would give it an empty
     * one, dropping every set registered during {@code initialize()} without a word: the runtime
     * would log "nothing to do" and boot against an unmigrated schema.
     */
    @Test
    void migrationRegistry_keepsWhatWasRegisteredOnIt(ObjectFactory factory) {
        var extension = factory.constructInstance(FlywayMigrationExtension.class);
        var registered = new MigrationSet("module", "default", "classpath:db/migration/shared", true);

        extension.migrationRegistry().register(registered);

        assertThat(extension.migrationRegistry().getMigrationSets()).containsExactly(registered);
    }

    @Test
    void prepare_whenNoMigrationSetRegistered_doesNothing(ObjectFactory factory) {
        var extension = factory.constructInstance(FlywayMigrationExtension.class);

        extension.prepare();

        verifyNoInteractions(vault);
    }

    @Test
    void prepare_whenDisabled_doesNotTouchTheDatabase(ServiceExtensionContext context, ObjectFactory factory) {
        when(context.getConfig()).thenReturn(ConfigFactory.fromMap(Map.of(
                "edc.participant.id", PARTICIPANT_ID,
                "edc.migration.enabled", "false")));
        var extension = factory.constructInstance(FlywayMigrationExtension.class);
        extension.migrationRegistry().register(new MigrationSet("module", "default", "classpath:db/migration/shared", true));

        extension.prepare();

        verifyNoInteractions(vault);
    }

    /**
     * {@code buildDataSource()} needs {@code initialize(context)} to have run first (it is where the
     * context field gets set) -- exactly what {@code ExtensionLifecycleManager} guarantees at real
     * boot, and what this test does explicitly since {@code ObjectFactory.constructInstance} only
     * wires {@code @Inject} fields, not lifecycle methods.
     */
    @Test
    void prepare_whenUrlNotConfigured_failsFast(ServiceExtensionContext context, ObjectFactory factory) {
        when(context.getConfig("edc.datasource.unknown")).thenReturn(ConfigFactory.empty());
        var extension = factory.constructInstance(FlywayMigrationExtension.class);
        extension.initialize(context);
        extension.migrationRegistry().register(new MigrationSet("module", "unknown", "classpath:db/migration/shared", true));

        assertThatExceptionOfType(EdcException.class)
                .isThrownBy(extension::prepare)
                .withMessageContaining("unknown");
    }

    /**
     * Two modules can declare the same location while differing by name, so the registry cannot
     * collapse them — the record's equality covers all three fields. Flyway must still see it once:
     * a repeated location makes it find every script in it twice and fail on a duplicate version.
     */
    @Test
    void groupLocationsByDataSource_passesEachLocationOnce(ObjectFactory factory) {
        var extension = factory.constructInstance(FlywayMigrationExtension.class);

        var grouped = extension.groupLocationsByDataSource(List.of(
                new MigrationSet("first", "default", "classpath:db/migration/shared", true),
                new MigrationSet("second", "default", "classpath:db/migration/shared", true),
                new MigrationSet("third", "default", "classpath:db/migration/control-plane", false),
                new MigrationSet("fourth", "other", "classpath:db/migration/shared", true)));

        assertThat(grouped).containsOnlyKeys("default", "other");
        assertThat(grouped.get("default"))
                .containsExactly("classpath:db/migration/control-plane", "classpath:db/migration/shared");
        // The same location against a different datasource is a different run, and stays.
        assertThat(grouped.get("other")).containsExactly("classpath:db/migration/shared");
    }

    /**
     * The issuer-service launcher bundles identity-hub-migration on its classpath as well as its own,
     * so its registry carries two non-shared {@link MigrationSet}s at once -- the one runtime topology
     * in this codebase where the signal key is a composite. It must join with {@code '+'}, not
     * {@code ','}: {@link FlywayMigrationExtension#prepare()} splits {@code
     * edc.migration.barrier.expected-modules} on commas, so a composite key built with the same
     * character could never be listed there as one entry, and the barrier would deadlock waiting for
     * names that never get signalled individually.
     */
    @Test
    void buildSignalKey_joinsMultipleOwnSetsWithPlus_notComma(ObjectFactory factory) {
        var extension = factory.constructInstance(FlywayMigrationExtension.class);

        var signalKey = extension.buildSignalKey(List.of(
                new MigrationSet("Issuer Service Database Migration", "default", "classpath:db/migration/issuer-service", false),
                new MigrationSet("Identity Hub Database Migration", "default", "classpath:db/migration/identity-hub", false)));

        assertThat(signalKey).isEqualTo("Identity Hub Database Migration+Issuer Service Database Migration");
    }

    @Test
    void buildPlaceholders_exposesTheParticipantIdUnderTheNameScriptsUse(ObjectFactory factory) {
        var extension = factory.constructInstance(FlywayMigrationExtension.class);

        assertThat(extension.buildFlywayPlaceholders())
                .containsExactly(entry(FlywayMigrationExtension.PARTICIPANT_CONTEXT_ID_PLACEHOLDER, PARTICIPANT_ID));
    }

    /**
     * Only some migrations use the placeholder, so an unset participant id is not fatal on its own.
     * Flyway fails the boot itself if a script that needs it is on the classpath.
     */
    @Test
    void buildPlaceholders_whenParticipantIdIsUnset_warnsInsteadOfFailing(ServiceExtensionContext context, ObjectFactory factory) {
        when(context.getConfig()).thenReturn(ConfigFactory.empty());
        var extension = factory.constructInstance(FlywayMigrationExtension.class);

        assertThat(extension.buildFlywayPlaceholders()).isEmpty();
        verify(monitor).warning(contains(FlywayMigrationExtension.PARTICIPANT_CONTEXT_ID_PLACEHOLDER));
    }

    /**
     * The injected {@link SqlSchemaBootstrapper} carries no behaviour here: its only purpose is to
     * order this extension after {@code SqlSchemaBootstrapperExtension}, so that migrations run once
     * the tables exist. Nothing else in the code would fail if the field were removed, hence this
     * test.
     */
    @Test
    void injectsSqlSchemaBootstrapper_toRunAfterSchemaAutocreate() {
        var injectsBootstrapper = Arrays.stream(FlywayMigrationExtension.class.getDeclaredFields())
                .anyMatch(field -> field.getType().equals(SqlSchemaBootstrapper.class) &&
                        field.isAnnotationPresent(Inject.class));

        assertThat(injectsBootstrapper)
                .describedAs("FlywayMigrationExtension must @Inject SqlSchemaBootstrapper to be ordered after the schema bootstrapper")
                .isTrue();
    }
}
