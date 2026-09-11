package org.eclipse.dse.migration.telemetryagent;

import org.eclipse.dse.migration.spi.MigrationRegistry;
import org.eclipse.dse.migration.spi.MigrationSet;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.transaction.datasource.spi.DataSourceRegistry;

import static org.eclipse.dse.migration.telemetryagent.TelemetryAgentMigrationExtension.NAME;

/**
 * Gives the telemetry-agent a signal key on the migration barrier, even though it owns no table of
 * its own -- its store tables (see {@code telemetry-store-sql}) are migrated by {@code
 * data-plane-migration}, not by this module.
 * <p>
 * Without this, the telemetry-agent is invisible to the barrier while still touching {@code
 * edc_lease}: it acquires leases against every participant database it writes to, through {@code
 * LeaseStatements} generated for the EDC version its own jar was built against. A telemetry-agent
 * still running pre-barrier code has no way to hold back the shared reshape a sibling runtime applies,
 * and a telemetry-agent already on this branch has no way to wait for that reshape to actually land
 * before it starts leasing -- it must carry {@code migration-shared} itself (see the launcher's
 * dependencies) so its own phase-2 {@code Flyway.migrate()} call blocks on the same lock the reshape
 * runs under, the same way {@code control-plane}/{@code data-plane}/{@code identity-hub} already do.
 */
@Extension(value = NAME, categories = { "sql", "persistence", "storage" })
public class TelemetryAgentMigrationExtension implements ServiceExtension {

    public static final String NAME = "Telemetry Agent Database Migration";

    private static final String LOCATION = "classpath:db/migration/telemetry-agent";

    @Setting(description = "The datasource the telemetry-agent's (empty) migration set is applied to",
            defaultValue = DataSourceRegistry.DEFAULT_DATASOURCE, key = "edc.migration.datasource.telemetryagent", required = false)
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