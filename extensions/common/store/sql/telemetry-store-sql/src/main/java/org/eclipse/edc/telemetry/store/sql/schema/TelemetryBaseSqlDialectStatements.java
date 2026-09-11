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
 */

package org.eclipse.edc.telemetry.store.sql.schema;

import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.sql.lease.spi.LeaseStatements;
import org.eclipse.edc.sql.translation.SqlOperatorTranslator;
import org.eclipse.edc.sql.translation.SqlQueryStatement;
import org.eclipse.edc.telemetry.store.sql.schema.postgres.SqlTelemetryRecordMapping;

import java.time.Clock;

import static java.lang.String.format;

public class TelemetryBaseSqlDialectStatements implements TelemetryRecordStatements {

    protected final SqlOperatorTranslator operatorTranslator;
    protected final LeaseStatements leaseStatements;
    protected final Clock clock;

    public TelemetryBaseSqlDialectStatements(SqlOperatorTranslator operatorTranslator, LeaseStatements leaseStatements, Clock clock) {
        this.operatorTranslator = operatorTranslator;
        this.leaseStatements = leaseStatements;
        this.clock = clock;
    }

    @Override
    public String getInsertTelemetryRecordTemplate() {
        return executeStatement()
                .column(getRecordIdColumn())
                .column(getTypeColumn())
                .jsonColumn(getPropertiesColumn())
                .column(getStateColumn())
                .column(getStateCountColumn())
                .column(getStateTimestampColumn())
                .column(getErrorDetailColumn())
                .jsonColumn(getTraceContextColumn())
                .column(getCreatedAtColumn())
                .column(getUpdatedAtColumn())
                .insertInto(getTelemetryRecordTable());
    }

    @Override
    public String getUpdateTelemetryRecordTemplate() {
        return executeStatement()
                .jsonColumn(getPropertiesColumn())
                .column(getStateColumn())
                .column(getStateCountColumn())
                .column(getStateTimestampColumn())
                .column(getErrorDetailColumn())
                .jsonColumn(getTraceContextColumn())
                .column(getUpdatedAtColumn())
                .update(getTelemetryRecordTable(), getRecordIdColumn());
    }

    @Override
    public String getCountTelemetryRecordByIdClause() {
        return format("SELECT COUNT(*) AS %s FROM %s WHERE %s = ?",
                getCountVariableName(),
                getTelemetryRecordTable(),
                getRecordIdColumn());
    }

    @Override
    public String getSelectTelemetryRecordTemplate() {
        return format("SELECT * FROM %s AS a", getTelemetryRecordTable());
    }

    @Override
    public String getDeleteTelemetryRecordByIdTemplate() {
        return executeStatement()
                .delete(getTelemetryRecordTable(), getRecordIdColumn());
    }

    @Override
    public String getCountVariableName() {
        return "COUNT";
    }

    @Override
    public SqlQueryStatement createQuery(QuerySpec query) {
        return new SqlQueryStatement(getSelectTelemetryRecordTemplate(), query, new SqlTelemetryRecordMapping(this),
                operatorTranslator);
    }

    @Override
    public SqlQueryStatement createNextNotLeasedQuery(QuerySpec query) {
        var queryTemplate = "%s LEFT JOIN %s l ON a.%s = l.%s".formatted(getSelectTelemetryRecordTemplate(),
                leaseStatements.getLeaseTableName(), getIdColumn(), leaseStatements.getResourceIdColumn());
        return new SqlQueryStatement(queryTemplate, query, new SqlTelemetryRecordMapping(this), operatorTranslator)
                .addWhereClause(getNotLeasedFilter(), clock.millis(), getTelemetryRecordTable());
    }

    protected String getNotLeasedFilter() {
        return format("(l.%s IS NULL OR (? > (%s + %s) AND ? = l.%s))",
                leaseStatements.getResourceIdColumn(), leaseStatements.getLeasedAtColumn(),
                leaseStatements.getLeaseDurationColumn(), leaseStatements.getResourceKindColumn());
    }
}
