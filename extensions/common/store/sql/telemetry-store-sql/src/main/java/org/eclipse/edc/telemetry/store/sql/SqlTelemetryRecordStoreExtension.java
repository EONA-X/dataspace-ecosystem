/*
 *  Copyright (c) 2024 Amadeus SA
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Amadeus SA - Initial Implementation
 */

package org.eclipse.edc.telemetry.store.sql;

import org.eclipse.dse.edc.spi.telemetryagent.TelemetryRecordStore;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.runtime.metamodel.annotation.Provides;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.spi.types.TypeManager;
import org.eclipse.edc.sql.QueryExecutor;
import org.eclipse.edc.sql.bootstrapper.SqlSchemaBootstrapper;
import org.eclipse.edc.sql.lease.spi.SqlLeaseContextBuilderProvider;
import org.eclipse.edc.telemetry.store.sql.schema.TelemetryRecordStatements;
import org.eclipse.edc.telemetry.store.sql.schema.postgres.PostgresTelemetryRecordStatements;
import org.eclipse.edc.transaction.datasource.spi.DataSourceRegistry;
import org.eclipse.edc.transaction.spi.TransactionContext;

import java.time.Clock;

@Provides({TelemetryRecordStore.class})
@Extension(value = SqlTelemetryRecordStoreExtension.EXTENSION_NAME)
public class SqlTelemetryRecordStoreExtension implements ServiceExtension {

    public static final String EXTENSION_NAME = "SQL Telemetry Record Store";

    @Setting(description = "The datasource to be used", defaultValue = DataSourceRegistry.DEFAULT_DATASOURCE, key = "edc.datasource.telemetry.record")
    private String dataSourceName;

    @Inject
    private DataSourceRegistry dataSourceRegistry;

    @Inject
    private TransactionContext transactionContext;

    @Inject(required = false)
    private TelemetryRecordStatements dialect;

    @Inject
    private TypeManager typeManager;

    @Inject
    private QueryExecutor queryExecutor;

    @Inject
    private SqlSchemaBootstrapper sqlSchemaBootstrapper;

    @Inject
    private Clock clock;

    @Inject
    private SqlLeaseContextBuilderProvider leaseContextBuilderProvider;

    @Override
    public String name() {
        return EXTENSION_NAME;
    }

    @Provider
    public TelemetryRecordStore telemetryRecordStore(ServiceExtensionContext context) {
        sqlSchemaBootstrapper.addStatementFromResource(dataSourceName, "telemetry-record-schema.sql");
        var dialect = getDialect();
        return new SqlTelemetryRecordStore(
                dataSourceRegistry,
                dataSourceName,
                transactionContext,
                typeManager.getMapper(),
                dialect,
                queryExecutor,
                leaseContextBuilderProvider.createContextBuilder(dialect.getTelemetryRecordTable()));
    }

    private TelemetryRecordStatements getDialect() {
        return dialect != null ? dialect : new PostgresTelemetryRecordStatements(leaseContextBuilderProvider.getStatements(), clock);
    }
}
