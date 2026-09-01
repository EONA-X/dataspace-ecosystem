package org.eclipse.dse.telemetrystorage.store.sql.schema;

import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.sql.statement.SqlStatements;
import org.eclipse.edc.sql.translation.SqlQueryStatement;

public interface TelemetryEventStatements extends SqlStatements {

    default String getTelemetryEventTable() {
        return "telemetry_event";
    }

    default String getParticipant_idTable() {
        return "participant_id";
    }

    default String getReportTable() {
        return "report";
    }

    default String getIdColumn() {
        return "id";
    }

    default String getContractIdColumn() {
        return "contract_id";
    }

    default String getParticipantDidColumn() {
        return "participant_did";
    }

    default String getMsgSizeColumn() {
        return "msg_size";
    }

    default String getCsvIdColumn() {
        return "csv_id";
    }

    default String getTimestampColumn() {
        return "timestamp";
    }

    default String getResponseStatusCodeColumn() {
        return "response_status_code";
    }

    String getDeleteByIdTemplate();

    String getFindByTemplate();

    String getInsertTemplate();

    String getCountTemplate();

    String getAlreadyExistQueryTemplate();

    String getUpdateTemplate();

    SqlQueryStatement createQuery(QuerySpec querySpec);

}