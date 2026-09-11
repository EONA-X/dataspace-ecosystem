package org.eclipse.dse.migration.shared;

import org.eclipse.dse.migration.spi.MigrationRegistry;
import org.eclipse.dse.migration.spi.MigrationSet;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.transaction.datasource.spi.DataSourceRegistry;

import static org.eclipse.dse.migration.shared.SharedMigrationExtension.NAME;

/**
 * Contributes the migrations for database objects that no single store module owns, because several
 * upstream modules declare them with {@code CREATE TABLE IF NOT EXISTS} — {@code edc_lease} first
 * among them. A versioned migration tool can only create such an object once, so it cannot belong to
 * any of the declaring modules.
 * <p>
 * Every launcher writing to a database that holds such an object depends on this module. It must
 * never be copy-pasted per launcher: divergent copies produce different Flyway checksums against the
 * shared history table, and the second runtime to start fails its boot.
 */
@Extension(value = NAME, categories = { "sql", "persistence", "storage" })
public class SharedMigrationExtension implements ServiceExtension {

    public static final String NAME = "Shared Database Migration";

    private static final String LOCATION = "classpath:db/migration/shared";

    @Setting(description = "The datasource the shared migrations are applied to",
            defaultValue = DataSourceRegistry.DEFAULT_DATASOURCE, key = "edc.migration.datasource.shared", required = false)
    private String dataSourceName;

    @Inject
    private MigrationRegistry migrationRegistry;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        migrationRegistry.register(new MigrationSet(NAME, dataSourceName, LOCATION, true));
    }
}
