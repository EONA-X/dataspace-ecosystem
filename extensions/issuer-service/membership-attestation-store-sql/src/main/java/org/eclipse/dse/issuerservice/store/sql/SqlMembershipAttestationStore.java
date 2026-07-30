package org.eclipse.dse.issuerservice.store.sql;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.dse.issuerservice.store.sql.schema.MembershipAttestationStatements;
import org.eclipse.dse.spi.issuerservice.MembershipAttestation;
import org.eclipse.dse.spi.issuerservice.MembershipAttestationStore;
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
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static java.lang.String.format;

public class SqlMembershipAttestationStore extends AbstractSqlStore implements MembershipAttestationStore {

    private final MembershipAttestationStatements statements;

    public SqlMembershipAttestationStore(DataSourceRegistry dataSourceRegistry, String dataSourceName,
                                         TransactionContext transactionContext, MembershipAttestationStatements statements,
                                         ObjectMapper objectMapper, QueryExecutor queryExecutor) {
        super(dataSourceRegistry, dataSourceName, transactionContext, objectMapper, queryExecutor);
        this.statements = statements;
    }

    @Override
    public @NotNull Stream<MembershipAttestation> query(QuerySpec spec) {
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
    public MembershipAttestation findById(String definitionId) {
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
    public StoreResult<Void> save(MembershipAttestation attestation) {
        return transactionContext.execute(() -> {
            try (var connection = getConnection()) {
                if (existsById(connection, attestation.id())) {
                    return StoreResult.alreadyExists(format(MEMBERSHIP_ATTESTATION_ALREADY_EXISTS, attestation.id()));
                } else {
                    insertInternal(connection, attestation);
                    return StoreResult.success();

                }
            } catch (Exception e) {
                throw new EdcPersistenceException(e.getMessage(), e);
            }
        });
    }

    @Override
    public StoreResult<Void> update(MembershipAttestation attestation) {
        return transactionContext.execute(() -> {
            try (var connection = getConnection()) {
                if (existsById(connection, attestation.id())) {
                    updateInternal(connection, attestation);
                    return StoreResult.success();
                } else {
                    return StoreResult.notFound(format(MEMBERSHIP_ATTESTATION_NOT_FOUND, attestation.id()));
                }
            } catch (Exception e) {
                throw new EdcPersistenceException(e.getMessage(), e);
            }
        });
    }

    @Override
    public StoreResult<MembershipAttestation> deleteById(String id) {
        Objects.requireNonNull(id);
        return transactionContext.execute(() -> {
            try (var connection = getConnection()) {
                var entity = findById(connection, id);
                if (entity != null) {
                    queryExecutor.execute(connection, statements.getDeleteByIdTemplate(), id);
                    return StoreResult.success(entity);
                } else {
                    return StoreResult.notFound(format(MEMBERSHIP_ATTESTATION_NOT_FOUND, id));
                }

            } catch (Exception e) {
                throw new EdcPersistenceException(e.getMessage(), e);
            }
        });

    }

    private MembershipAttestation mapResultSet(ResultSet resultSet) throws Exception {
        return new MembershipAttestation(
                resultSet.getString(statements.getIdColumn()),
                resultSet.getString(statements.getHolderIdColumn()),
                resultSet.getString(statements.getNameColumn()),
                resultSet.getString(statements.getMembershipTypeColumn()),
                resultSet.getTimestamp(statements.getMembershipStartDateColumn()).toInstant(),
                toGroupsList(resultSet.getString(statements.getGroupsColumn()))
        );
    }

    private List<String> toGroupsList(String groups) {
        return groups == null ? List.of() : fromJson(groups, new TypeReference<List<String>>() { });
    }

    private void insertInternal(Connection connection, MembershipAttestation attestation) {
        transactionContext.execute(() -> {
            var groups = toJson(attestation.groups());
            queryExecutor.execute(connection, statements.getInsertTemplate(),
                    attestation.id(),
                    attestation.name(),
                    attestation.membershipType(),
                    attestation.holderId(),
                    Timestamp.from(attestation.membershipStartDate()),
                    groups
            );
        });
    }

    private void updateInternal(Connection connection, MembershipAttestation attestation) {
        Objects.requireNonNull(attestation);
        var groups = toJson(attestation.groups());
        queryExecutor.execute(connection, statements.getUpdateTemplate(),
                attestation.id(),
                attestation.name(),
                attestation.membershipType(),
                attestation.holderId(),
                Timestamp.from(attestation.membershipStartDate()),
                groups,
                attestation.id());
    }

    private boolean existsById(Connection connection, String definitionId) {
        var sql = statements.getCountTemplate();
        try (var stream = queryExecutor.query(connection, false, this::mapCount, sql, definitionId)) {
            return stream.findFirst().orElse(0L) > 0;
        }
    }

    private long mapCount(ResultSet resultSet) throws SQLException {
        return resultSet.getLong(1);
    }

    private MembershipAttestation findById(Connection connection, String id) {
        var sql = statements.getFindByTemplate();
        return queryExecutor.single(connection, false, this::mapResultSet, sql, id);
    }
}
