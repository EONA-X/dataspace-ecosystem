package org.eclipse.dse.telemetrystorage.store.sql;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.dse.spi.telemetrystorage.TelemetryEvent;
import org.eclipse.dse.spi.telemetrystorage.TelemetryEventStore;
import org.eclipse.dse.telemetrystorage.store.sql.schema.TelemetryEventStatements;
import org.eclipse.edc.spi.persistence.EdcPersistenceException;
import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.spi.result.StoreResult;
import org.eclipse.edc.sql.QueryExecutor;
import org.eclipse.edc.sql.store.AbstractSqlStore;
import org.eclipse.edc.transaction.datasource.spi.DataSourceRegistry;
import org.eclipse.edc.transaction.spi.TransactionContext;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.stream.Stream;

import static java.lang.String.format;

public class SqlTelemetryEventStore extends AbstractSqlStore implements TelemetryEventStore {

    private final TelemetryEventStatements statements;

    public SqlTelemetryEventStore(DataSourceRegistry dataSourceRegistry, String dataSourceName,
                                  TransactionContext transactionContext, TelemetryEventStatements statements,
                                  ObjectMapper objectMapper, QueryExecutor queryExecutor) {
        super(dataSourceRegistry, dataSourceName, transactionContext, objectMapper, queryExecutor);
        this.statements = statements;
    }

    @Override
    public @NotNull Stream<TelemetryEvent> query(QuerySpec spec) {
        return transactionContext.execute(() -> {
            Objects.requireNonNull(spec);

            try {
                var queryStmt = statements.createQuery(spec);
                return queryExecutor.query(getConnection(), true, this::mapResultSet, queryStmt.getQueryAsString(), queryStmt.getParameters());
            } catch (SQLException exception) {
                throw new EdcPersistenceException(exception);
            }
        });
    }

    @Override
    public TelemetryEvent findById(String definitionId) {
        Objects.requireNonNull(definitionId);
        return transactionContext.execute(() -> {
            try (var connection = getConnection()) {
                return findById(connection, definitionId);
            } catch (Exception exception) {
                throw new EdcPersistenceException(exception);
            }
        });
    }

    @Override
    public StoreResult<Void> save(TelemetryEvent attestation) {
        return transactionContext.execute(() -> {
            try (var connection = getConnection()) {
                var conflictingId = findConflictingId(connection, attestation);
                if (conflictingId != null) {
                    if (conflictingId.equals(attestation.id())) {
                        return StoreResult.alreadyExists(format(TELEMETRY_EVENT_ALREADY_EXISTS, attestation.id()));
                    }
                    return StoreResult.alreadyExists(format(TELEMETRY_EVENT_DUPLICATE_CONTRACT_PARTICIPANT_TIMESTAMP,
                            attestation.contractId(), attestation.participantId(), attestation.timestamp()));
                }
                insertInternal(connection, attestation);
                return StoreResult.success();
            } catch (Exception e) {
                return StoreResult.generalError("An error occurred: " + e.getMessage());
            }
        });
    }

    @Override
    public StoreResult<TelemetryEvent> deleteById(String id) {
        Objects.requireNonNull(id);
        return transactionContext.execute(() -> {
            try (var connection = getConnection()) {
                var entity = findById(connection, id);
                if (entity != null) {
                    queryExecutor.execute(connection, statements.getDeleteByIdTemplate(), id);
                    return StoreResult.success(entity);
                } else {
                    return StoreResult.notFound(format(TELEMETRY_EVENT_NOT_FOUND, id));
                }

            } catch (Exception e) {
                throw new EdcPersistenceException(e.getMessage(), e);
            }
        });

    }

    private TelemetryEvent mapResultSet(ResultSet resultSet) throws Exception {
        return new TelemetryEvent(
                resultSet.getString(statements.getIdColumn()),
                resultSet.getString(statements.getContractIdColumn()),
                resultSet.getString(statements.getParticipantDidColumn()),
                resultSet.getInt(statements.getResponseStatusCodeColumn()),
                resultSet.getInt(statements.getMsgSizeColumn()),
                resultSet.getObject(statements.getCsvIdColumn(), Integer.class),
                resultSet.getTimestamp(statements.getTimestampColumn())
        );
    }

    private void insertInternal(Connection connection, TelemetryEvent attestation) {
        transactionContext.execute(() -> {
            queryExecutor.execute(connection, statements.getInsertTemplate(),
                    attestation.id(),
                    attestation.contractId(),
                    attestation.participantId(),
                    attestation.responseStatusCode(),
                    attestation.responseSize(),
                    attestation.timestamp()
            );
        });
    }

    private void updateInternal(Connection connection, TelemetryEvent attestation) {
        Objects.requireNonNull(attestation);
        queryExecutor.execute(connection, statements.getUpdateTemplate(),
                attestation.contractId(),
                attestation.participantId(),
                attestation.responseStatusCode(),
                attestation.responseSize(),
                attestation.csvId(),
                attestation.timestamp(),
                attestation.id());
    }

    private String findConflictingId(Connection connection, TelemetryEvent attestation) {
        var sql = statements.getAlreadyExistQueryTemplate();
        try (var stream = queryExecutor.query(connection, false, this::mapId, sql,
                attestation.id(), attestation.contractId(), attestation.participantId(), attestation.timestamp())) {
            return stream.findFirst().orElse(null);
        }
    }

    private String mapId(ResultSet resultSet) throws SQLException {
        return resultSet.getString(statements.getIdColumn());
    }

    private TelemetryEvent findById(Connection connection, String id) {
        var sql = statements.getFindByTemplate();
        return queryExecutor.single(connection, false, this::mapResultSet, sql, id);
    }
}
