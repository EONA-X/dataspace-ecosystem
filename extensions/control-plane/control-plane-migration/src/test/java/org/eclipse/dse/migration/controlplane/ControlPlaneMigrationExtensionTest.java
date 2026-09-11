package org.eclipse.dse.migration.controlplane;

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

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The database tests in {@code flyway-core} address every set as a Flyway {@code filesystem:}
 * location, read straight from the source tree. Production reads them from the classpath, at the
 * location this extension declares. Nothing else compares the two: a renamed resource directory, or a
 * typo in the constant, would leave those tests green while Flyway silently found no script at all --
 * and a migration that never runs is worse than one that fails, because the runtime boots and serves
 * traffic against a schema it does not match.
 */
@ExtendWith(DependencyInjectionExtension.class)
class ControlPlaneMigrationExtensionTest {

    private final MigrationRegistry migrationRegistry = mock();

    @BeforeEach
    void setup(ServiceExtensionContext context) {
        context.registerService(MigrationRegistry.class, migrationRegistry);
    }

    @Test
    void registersScriptsThatExistWhereItSaysTheyDo(ServiceExtensionContext context, ObjectFactory factory) throws IOException, URISyntaxException {
        var extension = factory.constructInstance(ControlPlaneMigrationExtension.class);

        extension.initialize(context);

        var registered = ArgumentCaptor.forClass(MigrationSet.class);
        verify(migrationRegistry).register(registered.capture());
        MigrationSet migrationSet = registered.getValue();
        assertThat(migrationSet.dataSourceName()).isEqualTo("default");
        assertThat(migrationSet.location()).isEqualTo("classpath:db/migration/control-plane");
        assertThat(scriptsAt(migrationSet.location()))
                .describedAs("Flyway finds no migration at the declared location, so this runtime would " +
                        "apply nothing and boot as if there were nothing to do")
                .isNotEmpty();
    }

    private List<String> scriptsAt(String location) throws IOException, URISyntaxException {
        var resourcePath = location.substring("classpath:".length());
        URL directory = getClass().getClassLoader().getResource(resourcePath);
        assertThat(directory).describedAs("%s is not on the classpath", resourcePath).isNotNull();

        try (Stream<Path> files = Files.list(Path.of(directory.toURI()))) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".sql"))
                    .toList();
        }
    }

    /**
     * The test above builds the extension by hand, which is how a runtime never loads it. EDC finds
     * extensions through {@link java.util.ServiceLoader}, so an extension missing from
     * {@code META-INF/services} is simply absent at boot: no error, no migration, and a runtime that
     * serves traffic against an unmigrated schema.
     */
    @Test
    void isDeclaredForServiceLoading() {
        assertThat(ServiceLoader.load(ServiceExtension.class))
                .describedAs("%s is not listed in META-INF/services/%s, so no runtime will load it",
                        ControlPlaneMigrationExtension.class.getName(), ServiceExtension.class.getName())
                .hasAtLeastOneElementOfType(ControlPlaneMigrationExtension.class);
    }

}
