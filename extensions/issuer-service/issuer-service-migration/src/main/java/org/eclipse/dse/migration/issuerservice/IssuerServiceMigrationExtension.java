package org.eclipse.dse.migration.issuerservice;

import org.eclipse.dse.migration.spi.MigrationRegistry;
import org.eclipse.dse.migration.spi.MigrationSet;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.transaction.datasource.spi.DataSourceRegistry;

import static org.eclipse.dse.migration.issuerservice.IssuerServiceMigrationExtension.NAME;

/**
 * Contributes the migrations for the tables only the issuer-service creates. The tables its SQL BOM
 * shares with the identity-hub are migrated by the identity-hub set, which is wired into this
 * runtime as well.
 */
@Extension(value = NAME, categories = { "sql", "persistence", "storage" })
public class IssuerServiceMigrationExtension implements ServiceExtension {

    public static final String NAME = "Issuer Service Database Migration";

    private static final String LOCATION = "classpath:db/migration/issuer-service";

    @Setting(description = "The datasource the issuer-service migrations are applied to",
            defaultValue = DataSourceRegistry.DEFAULT_DATASOURCE, key = "edc.migration.datasource.issuerservice", required = false)
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