package org.eclipse.dse.migration.flyway;

import org.eclipse.dse.migration.spi.MigrationSet;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MigrationRegistryImplTest {

    private final MigrationRegistryImpl registry = new MigrationRegistryImpl();

    @Test
    void getMigrationSets_isUnmodifiable() {
        registry.register(new MigrationSet("module", "default", "classpath:db/migration/module", false));

        assertThat(registry.getMigrationSets()).isUnmodifiable();
    }

    /**
     * A module wired into a launcher twice registers twice. Flyway would then find every script in
     * that location twice and fail the boot on a duplicate version.
     */
    @Test
    void register_ignoresAnAlreadyRegisteredSet() {
        var set = new MigrationSet("module", "default", "classpath:db/migration/module", false);

        registry.register(set);
        registry.register(new MigrationSet("module", "default", "classpath:db/migration/module", false));

        assertThat(registry.getMigrationSets()).containsExactly(set);
    }

    @Test
    void register_rejectsNull() {
        assertThatIllegalArgumentException().isThrownBy(() -> registry.register(null));
    }

    @Test
    void migrationSet_rejectsBlankFields() {
        assertThatIllegalArgumentException().isThrownBy(() -> new MigrationSet(" ", "default", "classpath:x", false));
        assertThatIllegalArgumentException().isThrownBy(() -> new MigrationSet("module", "", "classpath:x", false));
        assertThatIllegalArgumentException().isThrownBy(() -> new MigrationSet("module", "default", null, false));
    }
}
