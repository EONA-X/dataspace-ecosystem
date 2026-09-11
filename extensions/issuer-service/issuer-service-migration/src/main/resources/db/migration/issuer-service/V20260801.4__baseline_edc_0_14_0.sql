-- Baseline for edc.sql.schema.autocreate=false (ticket #605): flyway, not SqlSchemaBootstrapper,
-- now owns table creation. Content below is the upstream IssuerService tag v0.14.0 schema.sql
-- resources for the SQL stores exclusive to this launcher, copied verbatim (CREATE TABLE IF NOT
-- EXISTS everywhere, same as the fixture at flyway-core/src/test/resources/fixtures/schema-0.14.sql).
--
-- The five stores this runtime shares with identity-hub (credentials, did, keypairs,
-- participant-context, edc_lease) are NOT repeated here: this launcher also carries the
-- identity-hub-migration set (see issuer-service-postgresql-hashicorpvault/build.gradle.kts), which
-- owns them.
--
-- On an empty database this recreates exactly what SqlSchemaBootstrapper used to create. On an
-- existing 0.14 database every statement is a no-op (tables already there) -- that is what makes it
-- safe to add on top of a production database without a prior baseline being recorded.

-- ---- holder-schema.sql
/*
 *  Copyright (c) 2025 Cofinity-X
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Cofinity-X - initial API and implementation
 *
 */

-- only intended for and tested with Postgres!
CREATE TABLE IF NOT EXISTS holders
(
    holder_id                    VARCHAR PRIMARY KEY NOT NULL, -- ID of the Holder
    participant_context_id       VARCHAR NOT NULL,             -- the DID with which this holder is identified
    did                          VARCHAR NOT NULL,             -- the DID with which this holder is identified
    holder_name                  VARCHAR,                      -- the display name of the holder
    created_date       BIGINT    NOT NULL,                     -- POSIX timestamp of the creation of the PC
    last_modified_date BIGINT                                  -- POSIX timestamp of the last modified date
);
CREATE UNIQUE INDEX IF NOT EXISTS holders_holder_id_uindex ON holders USING btree (holder_id);

-- ---- attestation-definition-schema.sql
/*
 *  Copyright (c) 2025 Cofinity-X
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Cofinity-X - initial API and implementation
 *
 */

-- only intended for and tested with Postgres!
CREATE TABLE IF NOT EXISTS attestation_definitions
(
    id                           VARCHAR    NOT NULL        ,
    participant_context_id       VARCHAR    NOT NULL        ,
    attestation_type             VARCHAR    NOT NULL        ,
    configuration                JSON       DEFAULT '{}'    ,
    created_date                 BIGINT     NOT NULL        ,
    last_modified_date           BIGINT     NOT NULL        ,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS attestation_definition_ix
    ON attestation_definitions (id);

-- ---- credential-definition-schema.sql
/*
 *  Copyright (c) 2025 Cofinity-X
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Cofinity-X - initial API and implementation
 *
 */

-- only intended for and tested with Postgres!
CREATE TABLE IF NOT EXISTS credential_definitions
(
    id                     VARCHAR NOT NULL,
    participant_context_id VARCHAR NOT NULL,
    credential_type        VARCHAR NOT NULL UNIQUE,
    attestations           JSON    NOT NULL DEFAULT '[]',
    rules                  JSON    NOT NULL DEFAULT '[]',
    mappings               JSON    NOT NULL DEFAULT '[]',
    json_schema            JSON,
    json_schema_url        VARCHAR,
    validity               BIGINT  NOT NULL,
    format                 VARCHAR NOT NULL,
    created_date           BIGINT  NOT NULL, -- POSIX timestamp of the creation of the PC
    last_modified_date     BIGINT,           -- POSIX timestamp of the last modified date
    PRIMARY KEY (id)
);

-- ---- issuance-process-schema.sql
/*
 *  Copyright (c) 2025 Cofinity-X
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Cofinity-X - initial API and implementation
 *
 */

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

CREATE TABLE IF NOT EXISTS edc_issuance_process
(
    id                          VARCHAR           NOT NULL PRIMARY KEY,
    state                       INTEGER           NOT NULL,
    state_count                 INTEGER DEFAULT 0 NOT NULL,
    state_time_stamp            BIGINT,
    created_at                  BIGINT            NOT NULL,
    updated_at                  BIGINT            NOT NULL,
    trace_context               JSON,
    error_detail                VARCHAR,
    pending                     BOOLEAN  DEFAULT FALSE,
    lease_id                    VARCHAR CONSTRAINT issuance_process_lease_lease_id_fk REFERENCES edc_lease ON DELETE SET NULL,
    holder_id                   VARCHAR           NOT NULL,
    participant_context_id      VARCHAR           NOT NULL,
    holder_pid                  VARCHAR           NOT NULL,
    claims                      JSON              NOT NULL,
    credential_definitions      JSONB             NOT NULL,
    credential_formats          JSONB             NOT NULL
);

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
CREATE INDEX IF NOT EXISTS issuance_process_state ON edc_issuance_process (state,state_time_stamp);


-- ---- jti-validation-schema.sql
CREATE TABLE IF NOT EXISTS edc_jti_validation
(
    token_id   VARCHAR NOT NULL PRIMARY KEY,
    expires_at BIGINT -- expiry time in epoch millis
);
