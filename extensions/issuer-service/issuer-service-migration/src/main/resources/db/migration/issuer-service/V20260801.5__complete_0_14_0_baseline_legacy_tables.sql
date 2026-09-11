-- membership_attestation and domain_attestation were never part of the 0.14.0 baseline
-- (V20260801.4) -- they still lived exclusively in schema.sql, bootstrapped at runtime via
-- SqlSchemaBootstrapperExtension (edc.sql.schema.autocreate=true). #605 (78a6b27) claimed
-- Flyway owns the full 0.14.0 baseline with autocreate=false, but missed these two -- any
-- fresh boot with autocreate=false never gets them created. Copied verbatim from
-- membership-attestation-store-sql/src/main/resources/membership-attestation-schema.sql and
-- domain-attestation-store-sql/src/main/resources/domain-attestation-schema.sql.

CREATE TABLE IF NOT EXISTS membership_attestation
(
    membership_type       varchar                             not null,
    holder_id             varchar                             not null,
    name                  varchar                             not null,
    membership_start_date timestamp default now()             not null,
    groups                varchar   default '[]'              not null,
    id                    varchar   default gen_random_uuid() not null
        constraint attestations_pk
            primary key
);

CREATE TABLE IF NOT EXISTS domain_attestation
(
    holder_id varchar                             not null,
    domain    varchar                             not null,
    id        varchar   default gen_random_uuid() not null,
    constraint domain_attestations_pk primary key (id)
);
