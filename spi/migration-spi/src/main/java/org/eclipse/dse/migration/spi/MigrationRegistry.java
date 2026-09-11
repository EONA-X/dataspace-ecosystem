package org.eclipse.dse.migration.spi;

import org.eclipse.edc.runtime.metamodel.annotation.ExtensionPoint;

import java.util.List;

/**
 * Collects the {@link MigrationSet}s contributed by the migration modules present on the runtime
 * classpath. Modules register during {@code ServiceExtension#initialize()}; the migration extension
 * reads the registry during {@code prepare()}, once every module has registered.
 * <p>
 * Registration is explicit rather than a classpath scan, so that a module missing from a launcher is
 * visible in the boot log instead of silently skipped.
 */
@ExtensionPoint
public interface MigrationRegistry {

    /**
     * Must be called during the {@code initialize()} phase.
     * <p>
     * Registering the same set twice is a no-op: the migration engine would otherwise find every
     * script in the repeated location twice and fail the boot on a duplicate version.
     */
    void register(MigrationSet migrationSet);

    List<MigrationSet> getMigrationSets();
}