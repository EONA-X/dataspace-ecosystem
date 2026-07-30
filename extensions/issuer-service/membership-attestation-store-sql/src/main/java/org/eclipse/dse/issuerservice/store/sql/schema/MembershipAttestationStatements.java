package org.eclipse.dse.issuerservice.store.sql.schema;

import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.sql.statement.SqlStatements;
import org.eclipse.edc.sql.translation.SqlQueryStatement;

public interface MembershipAttestationStatements extends SqlStatements {


    default String getMembershipAttestationTable() {
        return "membership_attestation";
    }

    default String getNameColumn() {
        return "name";
    }

    default String getIdColumn() {
        return "id";
    }

    default String getHolderIdColumn() {
        return "holder_id";
    }

    default String getMembershipTypeColumn() {
        return "membership_type";
    }

    default String getMembershipStartDateColumn() {
        return "membership_start_date";
    }

    default String getGroupsColumn() {
        return "groups";
    }

    String getDeleteByIdTemplate();

    String getFindByTemplate();

    String getInsertTemplate();

    String getCountTemplate();

    String getUpdateTemplate();

    SqlQueryStatement createQuery(QuerySpec querySpec);

}
