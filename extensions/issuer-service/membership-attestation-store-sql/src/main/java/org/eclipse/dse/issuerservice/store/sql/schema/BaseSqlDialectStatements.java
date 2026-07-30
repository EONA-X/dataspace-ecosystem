package org.eclipse.dse.issuerservice.store.sql.schema;

import org.eclipse.dse.issuerservice.store.sql.postgres.MembershipAttestationMapping;
import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.sql.translation.SqlOperatorTranslator;
import org.eclipse.edc.sql.translation.SqlQueryStatement;

import static java.lang.String.format;

public class BaseSqlDialectStatements implements MembershipAttestationStatements {

    protected final SqlOperatorTranslator operatorTranslator;

    public BaseSqlDialectStatements(SqlOperatorTranslator operatorTranslator) {
        this.operatorTranslator = operatorTranslator;
    }

    @Override
    public String getDeleteByIdTemplate() {
        return executeStatement().delete(getMembershipAttestationTable(), getIdColumn());
    }

    @Override
    public String getFindByTemplate() {
        return format("SELECT * FROM %s WHERE %s = ?", getMembershipAttestationTable(), getIdColumn());
    }

    @Override
    public String getInsertTemplate() {
        return executeStatement()
                .column(getIdColumn())
                .column(getNameColumn())
                .column(getMembershipTypeColumn())
                .column(getHolderIdColumn())
                .column(getMembershipStartDateColumn())
                .column(getGroupsColumn())
                .insertInto(getMembershipAttestationTable());
    }

    @Override
    public String getCountTemplate() {
        return format("SELECT COUNT (%s) FROM %s WHERE %s = ?",
                getIdColumn(),
                getMembershipAttestationTable(),
                getIdColumn());
    }

    @Override
    public String getUpdateTemplate() {
        return executeStatement()
                .column(getIdColumn())
                .column(getNameColumn())
                .column(getMembershipTypeColumn())
                .column(getHolderIdColumn())
                .column(getMembershipStartDateColumn())
                .column(getGroupsColumn())
                .update(getMembershipAttestationTable(), getIdColumn());

    }

    @Override
    public SqlQueryStatement createQuery(QuerySpec querySpec) {
        var select = format("SELECT * FROM %s", getMembershipAttestationTable());
        return new SqlQueryStatement(select, querySpec, new MembershipAttestationMapping(this), operatorTranslator);
    }

}
