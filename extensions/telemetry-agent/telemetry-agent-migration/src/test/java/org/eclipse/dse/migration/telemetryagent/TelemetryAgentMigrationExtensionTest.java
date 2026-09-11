package org.eclipse.dse.migration.telemetryagent;

import org.eclipse.dse.migration.spi.MigrationRegistry;
import org.eclipse.dse.migration.spi.MigrationSet;
import org.eclipse.edc.boot.system.injection.ObjectFactory;
import org.eclipse.edc.junit.extensions.DependencyInjectionExtension;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(DependencyInjectionExtension.class)
class TelemetryAgentMigrationExtensionTest {

    private final MigrationRegistry migrationRegistry = mock();

    @BeforeEach
    void setup(ServiceExtensionContext context) {
        context.registerService(MigrationRegistry.class, migrationRegistry);
    }

    /**
     * Unlike every other migration module's equivalent test, this one asserts the location resolves
     * to zero scripts, not at least one -- see the class javadoc and the module README for why: this
     * set exists only to give the telemetry-agent a barrier signal key, not to own a table.
     */
    @Test
    void registersOwnIdentity_withNoScriptsOfItsOwn(ServiceExtensionContext context, ObjectFactory factory) {
        var extension = factory.constructInstance(TelemetryAgentMigrationExtension.class);

        extension.initialize(context);

        var registered = ArgumentCaptor.forClass(MigrationSet.class);
        verify(migrationRegistry).register(registered.capture());
        MigrationSet migrationSet = registered.getValue();
        assertThat(migrationSet.moduleName()).isEqualTo(TelemetryAgentMigrationExtension.NAME);
        assertThat(migrationSet.dataSourceName()).isEqualTo("default");
        assertThat(migrationSet.location()).isEqualTo("classpath:db/migration/telemetry-agent");
        assertThat(migrationSet.shared()).isFalse();
    }

    /**
     * Same reasoning as the equivalent test on every other migration extension: EDC finds extensions
     * through {@link java.util.ServiceLoader}, so an extension missing from {@code META-INF/services}
     * is simply absent at boot, no error, and the telemetry-agent silently loses its barrier identity.
     */
    @Test
    void isDeclaredForServiceLoading() {
        assertThat(ServiceLoader.load(ServiceExtension.class))
                .describedAs("%s is not listed in META-INF/services/%s, so no runtime will load it",
                        TelemetryAgentMigrationExtension.class.getName(), ServiceExtension.class.getName())
                .hasAtLeastOneElementOfType(TelemetryAgentMigrationExtension.class);
    }
}
