package org.eclipse.dse.migration.dataplane;

import org.eclipse.dse.migration.spi.MigrationRegistry;
import org.eclipse.dse.migration.spi.MigrationSet;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.transaction.datasource.spi.DataSourceRegistry;

import static org.eclipse.dse.migration.dataplane.DataPlaneMigrationExtension.NAME;

/**
 * Contributes the migrations for the data-plane store tables. Objects shared with other modules
 * ({@code edc_lease}) live in the shared migration module, not here.
 */
@Extension(value = NAME, categories = { "sql", "persistence", "storage" })
public class DataPlaneMigrationExtension implements ServiceExtension {

    public static final String NAME = "Data Plane Database Migration";

    private static final String LOCATION = "classpath:db/migration/data-plane";

    @Setting(description = "The datasource the data-plane migrations are applied to",
            defaultValue = DataSourceRegistry.DEFAULT_DATASOURCE, key = "edc.migration.datasource.dataplane", required = false)
    private String dataSourceName;

    @Inject
    private MigrationRegistry migrationRegistry;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        migrationRegistry.register(new MigrationSet(NAME, dataSourceName, LOCATION, false));
    }
}
