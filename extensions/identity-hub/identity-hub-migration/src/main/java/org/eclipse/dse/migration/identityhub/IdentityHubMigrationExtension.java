package org.eclipse.dse.migration.identityhub;

import org.eclipse.dse.migration.spi.MigrationRegistry;
import org.eclipse.dse.migration.spi.MigrationSet;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.transaction.datasource.spi.DataSourceRegistry;

import static org.eclipse.dse.migration.identityhub.IdentityHubMigrationExtension.NAME;

/**
 * Contributes the migrations for the identity-hub store tables.
 * <p>
 * This set is wired into the issuer-service launcher as well as the identity-hub one. Five upstream
 * store modules ({@code identity-hub-credentials}, {@code -did}, {@code -keypair},
 * {@code -participantcontext} and {@code sts-client}) sit in both the identityhub and the
 * issuerservice SQL BOM, so both runtimes create those tables and either one may boot first.
 * Objects shared with the control-plane and data-plane ({@code edc_lease}) live in the shared
 * migration module, not here.
 */
@Extension(value = NAME, categories = { "sql", "persistence", "storage" })
public class IdentityHubMigrationExtension implements ServiceExtension {

    public static final String NAME = "Identity Hub Database Migration";

    private static final String LOCATION = "classpath:db/migration/identity-hub";

    @Setting(description = "The datasource the identity-hub migrations are applied to",
            defaultValue = DataSourceRegistry.DEFAULT_DATASOURCE, key = "edc.migration.datasource.identityhub", required = false)
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