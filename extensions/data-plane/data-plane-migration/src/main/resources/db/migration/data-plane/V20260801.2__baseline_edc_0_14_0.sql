-- Baseline for edc.sql.schema.autocreate=false (ticket #605): flyway, not SqlSchemaBootstrapper,
-- now owns table creation. Content below is the upstream Connector tag v0.14.0 schema.sql resources
-- (plus DSE's own telemetry-record-schema.sql) for every SQL store this launcher's classpath
-- carries, copied verbatim (CREATE TABLE IF NOT EXISTS everywhere, same as the fixture at
-- flyway-core/src/test/resources/fixtures/schema-0.14.sql).
--
-- On an empty database this recreates exactly what SqlSchemaBootstrapper used to create. On an
-- existing 0.14 database every statement is a no-op (tables already there) -- that is what makes it
-- safe to add on top of a production database without a prior baseline being recorded.
--
-- edr-index-schema.sql also ships on the control-plane baseline: both runtimes declare it, and
-- whichever boots first creates it. edc_lease is declared redundantly (once per upstream module,
-- same as upstream ships it) in its original 0.14 shape -- the reshape to the 0.15 composite key is
-- a separate migration (migration-shared, added on the EDC 0.15 upgrade branch), not this one.

-- ---- dataplane-schema.sql
-- Statements are designed for and tested with Postgres only!

CREATE TABLE IF NOT EXISTS edc_lease
(
    leased_by      VARCHAR NOT NULL,
    leased_at      BIGINT,
    lease_duration INTEGER NOT NULL,
    lease_id       VARCHAR NOT NULL
        CONSTRAINT lease_pk
            PRIMARY KEY
);

COMMENT ON COLUMN edc_lease.leased_at IS 'posix timestamp of lease';
COMMENT ON COLUMN edc_lease.lease_duration IS 'duration of lease in milliseconds';

CREATE TABLE IF NOT EXISTS edc_data_plane
(
    process_id           VARCHAR NOT NULL PRIMARY KEY,
    state                INTEGER NOT NULL            ,
    created_at           BIGINT  NOT NULL            ,
    updated_at           BIGINT  NOT NULL            ,
    state_count          INTEGER DEFAULT 0 NOT NULL,
    state_time_stamp     BIGINT,
    trace_context        JSON,
    error_detail         VARCHAR,
    callback_address     VARCHAR,
    lease_id             VARCHAR
        CONSTRAINT data_plane_lease_lease_id_fk
                    REFERENCES edc_lease
                    ON DELETE SET NULL,
    source               JSON,
    destination          JSON,
    properties           JSON,
    flow_type            VARCHAR,
    transfer_type_destination VARCHAR,
    runtime_id           VARCHAR,
    resource_definitions JSON DEFAULT '[]'
);

COMMENT ON COLUMN edc_data_plane.trace_context IS 'Java Map serialized as JSON';
COMMENT ON COLUMN edc_data_plane.source IS 'DataAddress serialized as JSON';
COMMENT ON COLUMN edc_data_plane.destination IS 'DataAddress serialized as JSON';
COMMENT ON COLUMN edc_data_plane.properties IS 'Java Map serialized as JSON';

-- This will help to identify states that need to be transitioned without a table scan when the entries grow
CREATE INDEX IF NOT EXISTS data_plane_state ON edc_data_plane (state,state_time_stamp);

-- ---- edr-index-schema.sql

CREATE TABLE IF NOT EXISTS edc_edr_entry
(
   transfer_process_id           VARCHAR NOT NULL PRIMARY KEY,
   agreement_id                  VARCHAR NOT NULL,
   asset_id                      VARCHAR NOT NULL,
   provider_id                   VARCHAR NOT NULL,
   contract_negotiation_id       VARCHAR,
   created_at                    BIGINT  NOT NULL
);

-- ---- telemetry-record-schema.sql
--
--  Copyright (c) 2024 Amadeus SA
--
--  This program and the accompanying materials are made available under the
--  terms of the Apache License, Version 2.0 which is available at
--  https://www.apache.org/licenses/LICENSE-2.0
--
--  SPDX-License-Identifier: Apache-2.0
--
--  Contributors:
--       Amadeus SA - Initial SQL Query

-- THIS SCHEMA HAS BEEN WRITTEN AND TESTED ONLY FOR POSTGRES

-- table: edc_lease
CREATE TABLE IF NOT EXISTS edc_lease
(
    leased_by      VARCHAR NOT NULL,
    leased_at      BIGINT,
    lease_duration INTEGER NOT NULL,
    lease_id       VARCHAR NOT NULL
                   CONSTRAINT lease_pk
                   PRIMARY KEY
);

COMMENT ON COLUMN edc_lease.leased_at IS 'posix timestamp of lease';

COMMENT ON COLUMN edc_lease.lease_duration IS 'duration of lease in milliseconds';

-- table: edc_telemetry_record
CREATE TABLE IF NOT EXISTS edc_telemetry_record
(
    record_id          VARCHAR NOT NULL
                       CONSTRAINT record_pk
                       PRIMARY KEY,
    type               VARCHAR NOT NULL,
    properties         JSON DEFAULT '{}',
    state              INTEGER NOT NULL,
    state_count        INTEGER DEFAULT 0 NOT NULL,
    state_time_stamp   BIGINT,
    created_at         BIGINT NOT NULL,
    updated_at         BIGINT NOT NULL,
    trace_context      JSON,
    error_detail       VARCHAR,
    lease_id           VARCHAR
                       CONSTRAINT participant_lease_lease_id_fk
                       REFERENCES edc_lease
                       ON DELETE SET NULL
);

COMMENT ON COLUMN edc_telemetry_record.properties IS 'Telemetry Record properties serialized as JSON';

COMMENT ON COLUMN edc_telemetry_record.trace_context IS 'Java Map serialized as JSON';

CREATE UNIQUE INDEX IF NOT EXISTS record_id_uindex
    ON edc_telemetry_record (record_id);

-- Guarded: on a database where the shared migration (V20260804.1) has already reshaped edc_lease --
-- a different runtime sharing this database can apply that set before this baseline runs -- the
-- lease_id column no longer exists, and this index has nothing left to create.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'edc_lease' AND column_name = 'lease_id'
                 AND table_schema = current_schema()) THEN
        CREATE UNIQUE INDEX IF NOT EXISTS lease_lease_id_uindex ON edc_lease (lease_id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS telemetry_state ON edc_telemetry_record (state,state_time_stamp);


-- ---- accesstoken-data-schema.sql
CREATE TABLE IF NOT EXISTS edc_accesstokendata
(
    id           VARCHAR NOT NULL PRIMARY KEY,
    claim_token  JSON    NOT NULL,
    data_address JSON    NOT NULL,
    additional_properties JSON DEFAULT '{}'
);

COMMENT ON COLUMN edc_accesstokendata.claim_token IS 'ClaimToken serialized as JSON map';
COMMENT ON COLUMN edc_accesstokendata.data_address IS 'DataAddress serialized as JSON map';
COMMENT ON COLUMN edc_accesstokendata.additional_properties IS 'Optional Additional properties serialized as JSON map';
