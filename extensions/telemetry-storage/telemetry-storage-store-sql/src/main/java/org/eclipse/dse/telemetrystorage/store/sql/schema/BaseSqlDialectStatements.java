package org.eclipse.dse.telemetrystorage.store.sql.schema;

import org.eclipse.dse.telemetrystorage.store.sql.postgres.TelemetryEventMapping;
import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.sql.translation.SqlOperatorTranslator;
import org.eclipse.edc.sql.translation.SqlQueryStatement;

import static java.lang.String.format;

public class BaseSqlDialectStatements implements TelemetryEventStatements {

    protected final SqlOperatorTranslator operatorTranslator;

    public BaseSqlDialectStatements(SqlOperatorTranslator operatorTranslator) {
        this.operatorTranslator = operatorTranslator;
    }

    @Override
    public String getDeleteByIdTemplate() {
        return format("DELETE FROM %s WHERE %s = ?", getTelemetryEventTable(), getIdColumn());
    }

    @Override
    public String getFindByTemplate() {
        return format("SELECT * FROM %s WHERE %s = ?", getTelemetryEventTable(), getIdColumn());
    }

    @Override
    public String getInsertTemplate() {
        return format("INSERT INTO %s (%s, %s, %s, %s, %s, %s) VALUES (?, ?, ?, ?, ?, ?)",
                getTelemetryEventTable(),
                getIdColumn(),
                getContractIdColumn(),
                getParticipantDidColumn(),
                getResponseStatusCodeColumn(),
                getMsgSizeColumn(),
                getTimestampColumn());
    }

    @Override
    public String getCountTemplate() {
        return format("SELECT COUNT(*) FROM %s WHERE %s = ?", getTelemetryEventTable(), getIdColumn());
    }

    @Override
    public String getAlreadyExistQueryTemplate() {
        return format("SELECT %s FROM %s WHERE %s = ? OR (%s = ? AND %s = ? AND %s = ?) LIMIT 1",
                getIdColumn(),
                getTelemetryEventTable(),
                getIdColumn(),
                getContractIdColumn(),
                getParticipantDidColumn(),
                getTimestampColumn());
    }

    @Override
    public String getUpdateTemplate() {
        return format("UPDATE %s SET %s = ?, %s = ?, %s = ?, %s = ?, %s = ? WHERE %s = ?",
                getTelemetryEventTable(),
                getContractIdColumn(),
                getParticipantDidColumn(),
                getMsgSizeColumn(),
                getCsvIdColumn(),
                getTimestampColumn(),
                getIdColumn());
    }

    @Override
    public SqlQueryStatement createQuery(QuerySpec querySpec) {
        var select = format("SELECT * FROM %s", getTelemetryEventTable());
        return new SqlQueryStatement(select, querySpec, new TelemetryEventMapping(this), operatorTranslator);
    }
}