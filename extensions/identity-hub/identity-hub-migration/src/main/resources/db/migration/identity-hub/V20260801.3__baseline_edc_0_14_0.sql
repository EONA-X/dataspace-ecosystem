-- Baseline for edc.sql.schema.autocreate=false (ticket #605): flyway, not SqlSchemaBootstrapper,
-- now owns table creation. Content below is the upstream Connector/IdentityHub tag v0.14.0
-- schema.sql resources for every SQL store this launcher's classpath carries, copied verbatim
-- (CREATE TABLE IF NOT EXISTS everywhere, same as the fixture at
-- flyway-core/src/test/resources/fixtures/schema-0.14.sql).
--
-- On an empty database this recreates exactly what SqlSchemaBootstrapper used to create. On an
-- existing 0.14 database every statement is a no-op (tables already there) -- that is what makes it
-- safe to add on top of a production database without a prior baseline being recorded.
--
-- Five of these tables (credentials, did, keypairs, participant-context, plus edc_lease) are also
-- created by the issuer-service, which carries this migration set too (see
-- issuer-service-postgresql-hashicorpvault/build.gradle.kts) -- whichever runtime boots first does
-- the work.

-- ---- credentials-schema.sql
/*
 *  Copyright (c) 2023 Metaform Systems, Inc.
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Metaform Systems, Inc. - initial API and implementation
 *
 */

-- only intended for and tested with Postgres!
CREATE TABLE IF NOT EXISTS credential_resource
(
    id                    VARCHAR PRIMARY KEY NOT NULL, -- ID of the VC, duplicated here for indexing purposes
    create_timestamp      BIGINT              NOT NULL, -- POSIX timestamp of the creation of the VC
    issuer_id             VARCHAR             NOT NULL,
    holder_id             VARCHAR             NOT NULL,
    vc_state              INTEGER             NOT NULL,
    metadata              JSON DEFAULT '{}',
    issuance_policy       JSON,
    reissuance_policy     JSON,
    raw_vc                VARCHAR,                      -- Representation of the VC exactly as it was received by the issuer. Can be JWT or JSON(-LD)
    vc_format             INTEGER             NOT NULL, -- 0 = JSON-LD, 1 = JWT
    verifiable_credential JSON                NOT NULL, -- JSON-representation of the verifiable credential
    participant_context_id        VARCHAR               -- ID of the ParticipantContext that owns this credentials
);
CREATE UNIQUE INDEX IF NOT EXISTS credential_resource_credential_id_uindex ON credential_resource USING btree (id);
COMMENT ON COLUMN credential_resource.id IS 'ID of the VC, duplicated here for indexing purposes';
COMMENT ON COLUMN credential_resource.raw_vc IS 'Representation of the VC exactly as it was received by the issuer. Can be JWT or JSON(-LD) ';
COMMENT ON COLUMN credential_resource.vc_format IS '0 = JSON-LD, 1 = JWT';
COMMENT ON COLUMN credential_resource.verifiable_credential IS 'JSON-representation of the VerifiableCredential';

-- ---- did-schema.sql
/*
 *  Copyright (c) 2023 Metaform Systems, Inc.
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Metaform Systems, Inc. - initial API and implementation
 *
 */

-- only intended for and tested with Postgres!
CREATE TABLE IF NOT EXISTS did_resources
(
    did              VARCHAR NOT NULL,
    create_timestamp BIGINT  NOT NULL,
    state_timestamp  BIGINT  NOT NULL,
    state            INT     NOT NULL,
    did_document     JSON    NOT NULL,
    participant_context_id   VARCHAR,
    PRIMARY KEY (did)
);

-- ---- keypairs-schema.sql
/*
 *  Copyright (c) 2024 Metaform Systems, Inc.
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Metaform Systems, Inc. - initial API and implementation
 *
 */

-- only intended for and tested with Postgres!
CREATE TABLE IF NOT EXISTS keypair_resource
(
    id                    VARCHAR PRIMARY KEY NOT NULL,               -- primary key
    participant_context_id        VARCHAR,                                    -- ID of the owning ParticipantContext. this is a loose business key, not a FK!
    timestamp             BIGINT              NOT NULL,               -- creation timestamp
    key_id                VARCHAR             NOT NULL,               -- name/key-id of this key pair. for use in JWTs etc.
    group_name            VARCHAR,
    is_default_pair       BOOLEAN                      DEFAULT FALSE, -- whether this keypair is the default one for a participant context
    use_duration          BIGINT,                                     -- maximum time this keypair can be active before it gets rotated
    rotation_duration     BIGINT,                                     -- duration during which this keypair is in a transitional state (rotated, not yet deactivated)
    serialized_public_key VARCHAR             NOT NULL,               -- serialized public key (PEM, JWK,...)
    private_key_alias     VARCHAR             NOT NULL,               -- alias under which the private key is stored in the HSM/Vault
    state                 INT                 NOT NULL DEFAULT 100,   -- KeyPairState
    key_context           VARCHAR                                     --the key context, will end up in the VerificationMethod of the DID Document
);

-- ---- participant-context-schema.sql
/*
 *  Copyright (c) 2024 Metaform Systems, Inc.
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Metaform Systems, Inc. - initial API and implementation
 *
 */

-- only intended for and tested with Postgres!
CREATE TABLE IF NOT EXISTS participant_context
(
    participant_context_id     VARCHAR PRIMARY KEY NOT NULL, -- ID of the ParticipantContext
    created_date       BIGINT              NOT NULL, -- POSIX timestamp of the creation of the PC
    last_modified_date BIGINT,                       -- POSIX timestamp of the last modified date
    state              INTEGER             NOT NULL, -- 0 = CREATED, 1 = ACTIVE, 2 = DEACTIVATED
    api_token_alias    VARCHAR             NOT NULL, -- alias under which this PC's api token is stored in the vault
    did                VARCHAR,                      -- the DID with which this participant is identified
    roles              JSON,                         -- JSON array containing all the roles a user has. may be empty
    properties         JSON DEFAULT '{}'             -- JSON object containing additional information, such as OAuth2 client secret aliases
);
CREATE UNIQUE INDEX IF NOT EXISTS participant_context_participant_context_id_uindex ON participant_context USING btree (participant_context_id);

-- ---- sts-client-schema.sql
-- THIS SCHEMA HAS BEEN WRITTEN AND TESTED ONLY FOR POSTGRES

-- table: edc_sts_client

CREATE TABLE IF NOT EXISTS edc_sts_client
(
   id                                   VARCHAR NOT NULL PRIMARY KEY,
   client_id                            VARCHAR NOT NULL,
   did                                  VARCHAR NOT NULL,
   name                                 VARCHAR NOT NULL,
   secret_alias                         VARCHAR NOT NULL,
   private_key_alias                    VARCHAR NOT NULL,
   public_key_reference                 VARCHAR NOT NULL,
   created_at                           BIGINT  NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS sts_client_client_id_index ON edc_sts_client (client_id);

-- ---- credential-offer-schema.sql
/*
 *  Copyright (c) 2025 Metaform Systems Inc.
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Metaform Systems Inc. - initial API and implementation
 *
 */

-- only intended for and tested with Postgres!
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


CREATE TABLE IF NOT EXISTS edc_credential_offers
(
    id                     VARCHAR NOT NULL PRIMARY KEY, -- this is also the holderPid
    state                  INTEGER NOT NULL,
    state_count            INTEGER          DEFAULT 0 NOT NULL,
    state_time_stamp       BIGINT,
    created_at             BIGINT  NOT NULL,
    updated_at             BIGINT  NOT NULL,
    trace_context          JSON,
    error_detail           VARCHAR,
    lease_id               VARCHAR
        CONSTRAINT credreq_lease_lease_id_fk REFERENCES edc_lease ON DELETE SET NULL,
    participant_context_id VARCHAR NOT NULL,
    issuer_did             VARCHAR NOT NULL,
    credentials            JSON    NOT NULL DEFAULT '{}'
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
CREATE INDEX IF NOT EXISTS credential_offer_state ON edc_credential_offers (state, state_time_stamp);

-- ---- holder-credential-request-schema.sql
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


CREATE TABLE IF NOT EXISTS edc_holder_credentialrequest
(
    id                     VARCHAR           NOT NULL PRIMARY KEY, -- this is also the holderPid
    state                  INTEGER           NOT NULL,
    state_count            INTEGER DEFAULT 0 NOT NULL,
    state_time_stamp       BIGINT,
    created_at             BIGINT            NOT NULL,
    updated_at             BIGINT            NOT NULL,
    trace_context          JSON,
    error_detail           VARCHAR,
    pending                BOOLEAN DEFAULT FALSE,
    lease_id               VARCHAR
        CONSTRAINT credreq_lease_lease_id_fk REFERENCES edc_lease ON DELETE SET NULL,
    participant_context_id VARCHAR           NOT NULL,
    issuer_did             VARCHAR           NOT NULL,
    ids_and_formats        JSON              NOT NULL,
    issuer_pid             VARCHAR
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
CREATE INDEX IF NOT EXISTS issuance_process_state ON edc_holder_credentialrequest (state, state_time_stamp);


-- ---- jti-validation-schema.sql
CREATE TABLE IF NOT EXISTS edc_jti_validation
(
    token_id   VARCHAR NOT NULL PRIMARY KEY,
    expires_at BIGINT -- expiry time in epoch millis
);
