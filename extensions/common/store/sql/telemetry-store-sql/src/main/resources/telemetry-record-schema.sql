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
    leased_by      VARCHAR               NOT NULL,
    leased_at      BIGINT,
    lease_duration INTEGER DEFAULT 60000 NOT NULL,
    resource_id    VARCHAR NOT NULL,
    resource_kind  VARCHAR NOT NULL,
    PRIMARY KEY (resource_id, resource_kind)
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
    error_detail       VARCHAR
);

COMMENT ON COLUMN edc_telemetry_record.properties IS 'Telemetry Record properties serialized as JSON';

COMMENT ON COLUMN edc_telemetry_record.trace_context IS 'Java Map serialized as JSON';

CREATE UNIQUE INDEX IF NOT EXISTS record_id_uindex
    ON edc_telemetry_record (record_id);

CREATE INDEX IF NOT EXISTS telemetry_state ON edc_telemetry_record (state,state_time_stamp);