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
 *       Amadeus SA- Initial Implementation
 *
 */

package org.eclipse.edc.telemetry.store.sql;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.dse.edc.spi.telemetryagent.TelemetryRecord;
import org.eclipse.dse.edc.spi.telemetryagent.TelemetryRecordStore;
import org.eclipse.edc.spi.persistence.EdcPersistenceException;
import org.eclipse.edc.spi.query.Criterion;
import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.spi.result.StoreResult;
import org.eclipse.edc.sql.QueryExecutor;
import org.eclipse.edc.sql.lease.spi.SqlLeaseContextBuilder;
import org.eclipse.edc.sql.store.AbstractSqlStore;
import org.eclipse.edc.telemetry.store.sql.schema.TelemetryRecordStatements;
import org.eclipse.edc.transaction.datasource.spi.DataSourceRegistry;
import org.eclipse.edc.transaction.spi.TransactionContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.String.format;
import static java.util.stream.Collectors.toList;
import static org.eclipse.edc.spi.query.Criterion.criterion;

public class SqlTelemetryRecordStore extends AbstractSqlStore implements TelemetryRecordStore {

    private final TelemetryRecordStatements telemetryStatements;
    private final SqlLeaseContextBuilder leaseContext;

    public SqlTelemetryRecordStore(DataSourceRegistry dataSourceRegistry, String dataSourceName, TransactionContext transactionContext, ObjectMapper objectMapper, TelemetryRecordStatements telemetryRecordStatements, QueryExecutor queryExecutor,
                                   SqlLeaseContextBuilder leaseContext) {
        super(dataSourceRegistry, dataSourceName, transactionContext, objectMapper, queryExecutor);
        this.telemetryStatements = Objects.requireNonNull(telemetryRecordStatements);
        this.leaseContext = leaseContext;
    }

    private void update(Connection connection, TelemetryRecord record) {
        queryExecutor.execute(connection, telemetryStatements.getUpdateTelemetryRecordTemplate(), toJson(record.getProperties()), record.getState(), record.getStateCount(), record.getStateTimestamp(), record.getErrorDetail(),
                toJson(record.getTraceContext()), record.getUpdatedAt(), record.getId());
    }

    private void insert(Connection connection, TelemetryRecord record) {
        queryExecutor.execute(connection, telemetryStatements.getInsertTelemetryRecordTemplate(), record.getId(), record.getType(), toJson(record.getProperties()), record.getState(), record.getStateCount(), record.getStateTimestamp(),
                record.getErrorDetail(), toJson(record.getTraceContext()), record.getCreatedAt(), record.getUpdatedAt());
    }

    @Override
    public StoreResult<Void> save(TelemetryRecord record) {
        var id = record.getId();
        return transactionContext.execute(() -> {
            try (var connection = getConnection()) {
                if (!existsById(id, connection)) {
                    insert(connection, record);
                } else {
                    var breakLeaseResult = leaseContext.withConnection(connection).breakLease(id);
                    if (breakLeaseResult.failed()) {
                        throw new IllegalStateException(breakLeaseResult.getFailureDetail());
                    }
                    update(connection, record);
                }
                return StoreResult.success();
            } catch (SQLException e) {
                throw new EdcPersistenceException(e);
            }
        });
    }

    @Override
    public StoreResult<TelemetryRecord> deleteById(String recordId) {
        Objects.requireNonNull(recordId);

        return transactionContext.execute(() -> {
            try (var connection = getConnection()) {
                var record = findById(recordId);
                if (record == null) {
                    return StoreResult.notFound(format(RECORD_NOT_FOUND_TEMPLATE, recordId));
                }

                queryExecutor.execute(connection, telemetryStatements.getDeleteTelemetryRecordByIdTemplate(), recordId);

                return StoreResult.success(record);
            } catch (Exception e) {
                throw new EdcPersistenceException(e.getMessage(), e);
            }
        });
    }

    @Override
    public @Nullable TelemetryRecord findById(String recordId) {
        Objects.requireNonNull(recordId);

        try (var connection = getConnection()) {
            var querySpec = QuerySpec.Builder.newInstance().filter(criterion("id", "=", recordId)).build();
            return executeQuery(connection, querySpec).findFirst().orElse(null);
        } catch (SQLException e) {
            throw new EdcPersistenceException(e);
        }
    }

    @Override
    public @NotNull Stream<TelemetryRecord> queryTelemetryRecords(QuerySpec querySpec) {
        return transactionContext.execute(() -> {
            try {
                return executeQuery(getConnection(), querySpec);
            } catch (SQLException e) {
                throw new EdcPersistenceException(e);
            }
        });
    }

    private Stream<TelemetryRecord> executeQuery(Connection connection, QuerySpec querySpec) {
        var statement = telemetryStatements.createQuery(querySpec);
        return queryExecutor.query(connection, true, this::mapTelemetryRecord, statement.getQueryAsString(), statement.getParameters());
    }

    @Override
    public @NotNull List<TelemetryRecord> nextNotLeased(int max, Criterion... criteria) {
        return transactionContext.execute(() -> {
            var filter = Arrays.stream(criteria).collect(toList());
            var querySpec = QuerySpec.Builder.newInstance().filter(filter).sortField("stateTimestamp").limit(max).build();
            var statement = telemetryStatements.createNextNotLeasedQuery(querySpec);

            try (
                    var connection = getConnection();
                    var stream = queryExecutor.query(connection, true, this::mapTelemetryRecord, statement.getQueryAsString(), statement.getParameters())
            ) {
                return stream.filter(record -> leaseContext.withConnection(connection).acquireLease(record.getId()).succeeded())
                        .collect(Collectors.toList());
            } catch (SQLException e) {
                throw new EdcPersistenceException(e);
            }
        });
    }

    @Override
    public StoreResult<TelemetryRecord> findByIdAndLease(String id) {
        return transactionContext.execute(() -> {
            try (var connection = getConnection()) {
                var entity = findByIdInternal(connection, id);
                if (entity == null) {
                    return StoreResult.notFound(format("TelemetryRecord %s not found", id));
                }

                return leaseContext.withConnection(connection).acquireLease(entity.getId()).map(it -> entity);
            } catch (SQLException e) {
                throw new EdcPersistenceException(e);
            }
        });
    }

    private int mapRowCount(ResultSet resultSet) throws SQLException {
        return resultSet.getInt(telemetryStatements.getCountVariableName());
    }

    private boolean existsById(String recordId, Connection connection) {
        var sql = telemetryStatements.getCountTelemetryRecordByIdClause();
        try (var stream = queryExecutor.query(connection, false, this::mapRowCount, sql, recordId)) {
            return stream.findFirst().orElse(0) > 0;
        }
    }

    private @Nullable TelemetryRecord findByIdInternal(Connection conn, String id) {
        return transactionContext.execute(() -> {
            var querySpec = QuerySpec.Builder.newInstance().filter(criterion("id", "=", id)).build();
            return single(executeQuery(conn, querySpec).collect(toList()));
        });
    }

    private TelemetryRecord mapTelemetryRecord(ResultSet resultSet) throws SQLException {
        return TelemetryRecord.Builder.newInstance()
                .id(resultSet.getString(telemetryStatements.getRecordIdColumn()))
                .type(resultSet.getString(telemetryStatements.getTypeColumn()))
                .traceContext(fromJson(resultSet.getString(telemetryStatements.getTraceContextColumn()), new TypeReference<>() {
                }))
                .properties(fromJson(resultSet.getString(telemetryStatements.getPropertiesColumn()), new TypeReference<>() {
                }))
                .createdAt(resultSet.getLong(telemetryStatements.getCreatedAtColumn()))
                .updatedAt(resultSet.getLong(telemetryStatements.getUpdatedAtColumn())).stateTimestamp(resultSet.getLong(telemetryStatements.getStateTimestampColumn()))
                .stateCount(resultSet.getInt(telemetryStatements.getStateCountColumn()))
                .state(resultSet.getInt(telemetryStatements.getStateColumn()))
                .build();
    }

    @Nullable
    private <T> T single(List<T> list) {
        if (list.size() > 1) {
            throw new IllegalStateException(getMultiplicityError(1, list.size()));
        }
        return list.isEmpty() ? null : list.get(0);
    }

    private String getMultiplicityError(int expectedSize, int actualSize) {
        return format("Expected to find %d items, but found %d", expectedSize, actualSize);
    }

}
