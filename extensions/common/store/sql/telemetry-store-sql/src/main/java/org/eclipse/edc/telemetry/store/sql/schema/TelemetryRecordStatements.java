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
 *       2024 Amadeus SA - initial API and implementation
 *
 */

package org.eclipse.edc.telemetry.store.sql.schema;

import org.eclipse.edc.runtime.metamodel.annotation.ExtensionPoint;
import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.sql.lease.StatefulEntityStatements;
import org.eclipse.edc.sql.statement.SqlStatements;
import org.eclipse.edc.sql.translation.SqlQueryStatement;

/**
 * Defines queries used by the SqlAssetIndexServiceExtension.
 */
@ExtensionPoint
public interface TelemetryRecordStatements extends SqlStatements, StatefulEntityStatements {

    default String getTelemetryRecordTable() {
        return "edc_telemetry_record";
    }

    default String getIdColumn() {
        return "record_id";
    }

    /**
     * The telemetry record ID column.
     */
    default String getRecordIdColumn() {
        return "record_id";
    }

    /**
     * The telemetry record type column.
     */
    default String getTypeColumn() {
        return "type";
    }

    /**
     * The telemetry record properties column.
     */
    default String getPropertiesColumn() {
        return "properties";
    }

    default String getCreatedAtColumn() {
        return "created_at";
    }

    default String getUpdatedAtColumn() {
        return "updated_at";
    }

    /**
     * INSERT clause for Telemetry Records.
     */
    String getInsertTelemetryRecordTemplate();

    /**
     * UPDATE clause for Telemetry Records.
     */
    String getUpdateTelemetryRecordTemplate();

    /**
     * SELECT COUNT clause for Telemetry Records.
     */
    String getCountTelemetryRecordByIdClause();

    /**
     * SELECT clause for all Telemetry Records.
     */
    String getSelectTelemetryRecordTemplate();

    /**
     * DELETE clause for Telemetry Records.
     */
    String getDeleteTelemetryRecordByIdTemplate();

    /**
     * The COUNT variable used in SELECT COUNT queries.
     */
    String getCountVariableName();

    /**
     * Generates a SQL query using sub-select statements out of the query spec.
     *
     * @return A {@link SqlQueryStatement} that contains the SQL and statement parameters
     */
    SqlQueryStatement createQuery(QuerySpec query);

    /**
     * Generates a SQL query selecting records not currently leased, joining on the lease table.
     *
     * @return A {@link SqlQueryStatement} that contains the SQL and statement parameters
     */
    SqlQueryStatement createNextNotLeasedQuery(QuerySpec query);

}
