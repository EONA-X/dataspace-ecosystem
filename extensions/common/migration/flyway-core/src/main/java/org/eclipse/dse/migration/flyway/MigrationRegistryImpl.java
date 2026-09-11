package org.eclipse.dse.migration.flyway;

import org.eclipse.dse.migration.spi.MigrationRegistry;
import org.eclipse.dse.migration.spi.MigrationSet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Registration happens during the single-threaded boot phase, so no synchronisation is needed.
 */
public class MigrationRegistryImpl implements MigrationRegistry {

    private final List<MigrationSet> migrationSets = new ArrayList<>();

    @Override
    public void register(MigrationSet migrationSet) {
        if (migrationSet == null) {
            throw new IllegalArgumentException("migrationSet cannot be null");
        }
        if (!migrationSets.contains(migrationSet)) {
            migrationSets.add(migrationSet);
        }
    }

    @Override
    public List<MigrationSet> getMigrationSets() {
        return Collections.unmodifiableList(migrationSets);
    }
}
