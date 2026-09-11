-- Baseline for edc.sql.schema.autocreate=false (ticket #605): flyway, not SqlSchemaBootstrapper,
-- now owns table creation. Content below is the upstream Connector tag v0.14.0 schema.sql resources
-- for every SQL store this launcher's classpath carries, copied verbatim (CREATE TABLE IF NOT EXISTS
-- everywhere, same as the fixture at flyway-core/src/test/resources/fixtures/schema-0.14.sql).
--
-- On an empty database this recreates exactly what SqlSchemaBootstrapper used to create. On an
-- existing 0.14 database every statement is a no-op (tables already there) -- that is what makes it
-- safe to add on top of a production database without a prior baseline being recorded.
--
-- edc_lease is declared redundantly below (once per upstream module, same as upstream ships it) in
-- its original 0.14 shape. The reshape to the 0.15 composite key is a separate migration
-- (migration-shared, added on the EDC 0.15 upgrade branch), not this one.

-- ---- transfer-process-schema.sql
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

CREATE TABLE IF NOT EXISTS edc_transfer_process
(
    transferprocess_id       VARCHAR           NOT NULL
        CONSTRAINT transfer_process_pk
            PRIMARY KEY,
    type                       VARCHAR           NOT NULL,
    state                      INTEGER           NOT NULL,
    state_count                INTEGER DEFAULT 0 NOT NULL,
    state_time_stamp           BIGINT,
    created_at                 BIGINT            NOT NULL,
    updated_at                 BIGINT            NOT NULL,
    trace_context              JSON,
    error_detail               VARCHAR,
    resource_manifest          JSON,
    provisioned_resource_set   JSON,
    content_data_address       JSON,
    deprovisioned_resources    JSON,
    private_properties         JSON,
    callback_addresses         JSON,
    pending                    BOOLEAN  DEFAULT FALSE,
    transfer_type              VARCHAR,
    protocol_messages          JSON,
    data_plane_id              VARCHAR,
    correlation_id             VARCHAR,
    counter_party_address      VARCHAR,
    protocol                   VARCHAR,
    asset_id                   VARCHAR,
    contract_id                VARCHAR,
    data_destination           JSON,
    lease_id                   VARCHAR
            CONSTRAINT transfer_process_lease_lease_id_fk
                REFERENCES edc_lease
                ON DELETE SET NULL
);

COMMENT ON COLUMN edc_transfer_process.trace_context IS 'Java Map serialized as JSON';

COMMENT ON COLUMN edc_transfer_process.resource_manifest IS 'java ResourceManifest serialized as JSON';

COMMENT ON COLUMN edc_transfer_process.provisioned_resource_set IS 'ProvisionedResourceSet serialized as JSON';

COMMENT ON COLUMN edc_transfer_process.content_data_address IS 'DataAddress serialized as JSON';

COMMENT ON COLUMN edc_transfer_process.deprovisioned_resources IS 'List of deprovisioned resources, serialized as JSON';


CREATE UNIQUE INDEX IF NOT EXISTS transfer_process_id_uindex
    ON edc_transfer_process (transferprocess_id);

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

-- This will help to identify states that need to be transitioned without a table scan when the entries grow
CREATE INDEX IF NOT EXISTS transfer_process_state ON edc_transfer_process (state,state_time_stamp);

-- ---- contract-negotiation-schema.sql
-- Statements are designed for and tested with Postgres only!

CREATE TABLE IF NOT EXISTS edc_lease
(
    leased_by      VARCHAR               NOT NULL,
    leased_at      BIGINT,
    lease_duration INTEGER DEFAULT 60000 NOT NULL,
    lease_id       VARCHAR               NOT NULL
        CONSTRAINT lease_pk
            PRIMARY KEY
);

COMMENT ON COLUMN edc_lease.leased_at IS 'posix timestamp of lease';

COMMENT ON COLUMN edc_lease.lease_duration IS 'duration of lease in milliseconds';


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



CREATE TABLE IF NOT EXISTS edc_contract_agreement
(
    agr_id            VARCHAR NOT NULL
        CONSTRAINT contract_agreement_pk
            PRIMARY KEY,
    provider_agent_id VARCHAR,
    consumer_agent_id VARCHAR,
    signing_date      BIGINT,
    start_date        BIGINT,
    end_date          INTEGER,
    asset_id          VARCHAR NOT NULL,
    policy            JSON
);


CREATE TABLE IF NOT EXISTS edc_contract_negotiation
(
    id                   VARCHAR           NOT NULL
        CONSTRAINT contract_negotiation_pk
            PRIMARY KEY,
    created_at           BIGINT            NOT NULL,
    updated_at           BIGINT            NOT NULL,
    correlation_id       VARCHAR,
    counterparty_id      VARCHAR           NOT NULL,
    counterparty_address VARCHAR           NOT NULL,
    protocol             VARCHAR           NOT NULL,
    type                 VARCHAR           NOT NULL,
    state                INTEGER DEFAULT 0 NOT NULL,
    state_count          INTEGER DEFAULT 0,
    state_timestamp      BIGINT,
    error_detail         VARCHAR,
    agreement_id         VARCHAR
        CONSTRAINT contract_negotiation_contract_agreement_id_fk
            REFERENCES edc_contract_agreement,
    contract_offers      JSON,
    callback_addresses   JSON,
    trace_context        JSON,
    pending              BOOLEAN DEFAULT FALSE,
    protocol_messages    JSON,
    lease_id             VARCHAR
        CONSTRAINT contract_negotiation_lease_lease_id_fk
            REFERENCES edc_lease
            ON DELETE SET NULL
);

COMMENT ON COLUMN edc_contract_negotiation.agreement_id IS 'ContractAgreement serialized as JSON';

COMMENT ON COLUMN edc_contract_negotiation.contract_offers IS 'List<ContractOffer> serialized as JSON';

COMMENT ON COLUMN edc_contract_negotiation.trace_context IS 'Map<String,String> serialized as JSON';


CREATE INDEX IF NOT EXISTS contract_negotiation_correlationid_index
    ON edc_contract_negotiation (correlation_id);

CREATE UNIQUE INDEX IF NOT EXISTS contract_negotiation_id_uindex
    ON edc_contract_negotiation (id);

CREATE UNIQUE INDEX IF NOT EXISTS contract_agreement_id_uindex
    ON edc_contract_agreement (agr_id);


-- This will help to identify states that need to be transitioned without a table scan when the entries grow
CREATE INDEX IF NOT EXISTS contract_negotiation_state ON edc_contract_negotiation (state,state_timestamp);

-- ---- asset-index-schema.sql
--
--  Copyright (c) 2022 - 2023 Daimler TSS GmbH
--
--  This program and the accompanying materials are made available under the
--  terms of the Apache License, Version 2.0 which is available at
--  https://www.apache.org/licenses/LICENSE-2.0
--
--  SPDX-License-Identifier: Apache-2.0
--
--  Contributors:
--       Daimler TSS GmbH - Initial SQL Query
--       Bayerische Motoren Werke Aktiengesellschaft (BMW AG) - improvements
--

-- THIS SCHEMA HAS BEEN WRITTEN AND TESTED ONLY FOR POSTGRES

-- table: edc_asset
CREATE TABLE IF NOT EXISTS edc_asset
(
    asset_id           VARCHAR NOT NULL,
    created_at         BIGINT  NOT NULL,
    properties         JSON    DEFAULT '{}',
    private_properties JSON    DEFAULT '{}',
    data_address       JSON    DEFAULT '{}',
    PRIMARY KEY (asset_id)
);

COMMENT ON COLUMN edc_asset.properties IS 'Asset properties serialized as JSON';
COMMENT ON COLUMN edc_asset.private_properties IS 'Asset private properties serialized as JSON';
COMMENT ON COLUMN edc_asset.data_address IS 'Asset DataAddress serialized as JSON';

-- ---- policy-definition-schema.sql
--
--  Copyright (c) 2022 ZF Friedrichshafen AG
--
--  This program and the accompanying materials are made available under the
--  terms of the Apache License, Version 2.0 which is available at
--  https://www.apache.org/licenses/LICENSE-2.0
--
--  SPDX-License-Identifier: Apache-2.0
--
--  Contributors:
--       ZF Friedrichshafen AG - Initial SQL Query
--

-- Statements are designed for and tested with Postgres only!

-- table: edc_policydefinitions
CREATE TABLE IF NOT EXISTS edc_policydefinitions
(
    policy_id             VARCHAR NOT NULL,
    created_at            BIGINT  NOT NULL,
    permissions           JSON,
    prohibitions          JSON,
    duties                JSON,
    profiles              JSON,
    extensible_properties JSON,
    inherits_from         VARCHAR,
    assigner              VARCHAR,
    assignee              VARCHAR,
    target                VARCHAR,
    policy_type           VARCHAR NOT NULL,
    private_properties    JSON,
    PRIMARY KEY (policy_id)
);

COMMENT ON COLUMN edc_policydefinitions.permissions IS 'Java List<Permission> serialized as JSON';
COMMENT ON COLUMN edc_policydefinitions.prohibitions IS 'Java List<Prohibition> serialized as JSON';
COMMENT ON COLUMN edc_policydefinitions.duties IS 'Java List<Duty> serialized as JSON';
COMMENT ON COLUMN edc_policydefinitions.profiles IS 'Java List<String> serialized as JSON';
COMMENT ON COLUMN edc_policydefinitions.extensible_properties IS 'Java Map<String, Object> serialized as JSON';
COMMENT ON COLUMN edc_policydefinitions.policy_type IS 'Java PolicyType serialized as JSON';

CREATE UNIQUE INDEX IF NOT EXISTS edc_policydefinitions_id_uindex
    ON edc_policydefinitions (policy_id);

-- ---- contract-definition-schema.sql
--
--  Copyright (c) 2022 Daimler TSS GmbH
--
--  This program and the accompanying materials are made available under the
--  terms of the Apache License, Version 2.0 which is available at
--  https://www.apache.org/licenses/LICENSE-2.0
--
--  SPDX-License-Identifier: Apache-2.0
--
--  Contributors:
--       Daimler TSS GmbH - Initial SQL Query
--       Microsoft Corporation - refactoring
--       SAP SE - add private properties to contract definition
--

-- table: edc_contract_definitions
-- only intended for and tested with H2 and Postgres!
CREATE TABLE IF NOT EXISTS edc_contract_definitions
(
    created_at             BIGINT  NOT NULL,
    contract_definition_id VARCHAR NOT NULL,
    access_policy_id       VARCHAR NOT NULL,
    contract_policy_id     VARCHAR NOT NULL,
    assets_selector        JSON    NOT NULL,
    private_properties     JSON,
    PRIMARY KEY (contract_definition_id)
);

-- ---- policy-monitor-schema.sql
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

CREATE TABLE IF NOT EXISTS edc_policy_monitor
(
    entry_id             VARCHAR NOT NULL PRIMARY KEY,
    state                INTEGER NOT NULL            ,
    created_at           BIGINT  NOT NULL            ,
    updated_at           BIGINT  NOT NULL            ,
    state_count          INTEGER DEFAULT 0 NOT NULL,
    state_time_stamp     BIGINT,
    trace_context        JSON,
    error_detail         VARCHAR,
    lease_id             VARCHAR
        CONSTRAINT policy_monitor_lease_lease_id_fk
                    REFERENCES edc_lease
                    ON DELETE SET NULL,
    properties           JSON,
    contract_id          VARCHAR
);


-- This will help to identify states that need to be transitioned without a table scan when the entries grow
CREATE INDEX IF NOT EXISTS policy_monitor_state ON edc_policy_monitor (state,state_time_stamp);

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

-- ---- dataplane-instance-schema.sql
CREATE TABLE IF NOT EXISTS edc_lease
(
    leased_by      VARCHAR NOT NULL,
    leased_at      BIGINT,
    lease_duration INTEGER NOT NULL,
    lease_id       VARCHAR NOT NULL
        CONSTRAINT lease_pk
            PRIMARY KEY
);


CREATE TABLE IF NOT EXISTS edc_data_plane_instance
(
    id                   VARCHAR NOT NULL PRIMARY KEY,
    data                 JSON,
    lease_id             VARCHAR
        CONSTRAINT data_plane_instance_lease_id_fk
                    REFERENCES edc_lease
                    ON DELETE SET NULL
);


-- ---- jti-validation-schema.sql
CREATE TABLE IF NOT EXISTS edc_jti_validation
(
    token_id   VARCHAR NOT NULL PRIMARY KEY,
    expires_at BIGINT -- expiry time in epoch millis
);
