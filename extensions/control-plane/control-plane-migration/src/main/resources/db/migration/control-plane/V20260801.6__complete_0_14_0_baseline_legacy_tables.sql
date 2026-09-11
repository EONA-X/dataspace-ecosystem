-- edc_agreement_retirement was never part of the 0.14.0 baseline (V20260801.1) -- it still
-- lived exclusively in schema.sql, bootstrapped at runtime via SqlSchemaBootstrapperExtension
-- (edc.sql.schema.autocreate=true). #605 (78a6b27) claimed Flyway owns the full 0.14.0 baseline
-- with autocreate=false, but missed this table -- any fresh boot with autocreate=false never
-- gets it created (found the same way as the issuer-service gap: a genuine empty-DB boot,
-- not caught by any test since none of them start from truly empty). Copied verbatim from
-- retirement-evaluation-store-sql/src/main/resources/schema.sql.

CREATE TABLE IF NOT EXISTS edc_agreement_retirement
(
    contract_agreement_id     VARCHAR      NOT NULL
        CONSTRAINT agreement_retirement_contract_agreement_id_pk PRIMARY KEY,
    reason                    VARCHAR(255) NOT NULL,
    agreement_retirement_date BIGINT       NOT NULL
);