-- EDC 0.14.0 -> 0.15.0: edc_lease changes shape.
--
-- Old shape: single lease_id VARCHAR primary key, referenced by a lease_id FK column on every
-- lease-using table. New shape: composite (resource_id, resource_kind) primary key, no FK.
--
-- edc_lease belongs to no store module: nine schemas on the runtime classpath declare it with
-- CREATE TABLE IF NOT EXISTS -- contract-negotiation, transfer-process, data-plane,
-- data-plane-instance, policy-monitor, credential-offer, holder-credential-request,
-- issuance-process, and our own telemetry-record. It is therefore owned by the shared migration
-- module, so it is created exactly once per database.
--
-- telemetry-record is the one that is ours, and the one to watch: the telemetry-agent writes to
-- every participant database, acquires leases, and carries no migration module. It has nothing of
-- its own to migrate, but it must be upgraded in the same wave as the other runtimes -- a 0.14
-- agent acquiring a lease against a migrated table looks for a lease_id column that is gone.
--
-- Recreating rather than migrating rows is deliberate: leases live for seconds and are re-acquired
-- by the runtime, so nothing of value is destroyed. A destructive migration needs a reason like this
-- one, not just IF EXISTS -- and this reason holds for leases and for nothing else.
--
-- CASCADE drops the FK constraints the 0.14 tables still hold on lease_id. The lease_id columns
-- themselves are dropped by the per-module migrations.

DROP TABLE IF EXISTS edc_lease CASCADE;

CREATE TABLE edc_lease
(
    leased_by      VARCHAR NOT NULL,
    leased_at      BIGINT,
    lease_duration INTEGER NOT NULL,
    resource_id    VARCHAR NOT NULL,
    resource_kind  VARCHAR NOT NULL,
    PRIMARY KEY (resource_id, resource_kind)
);

COMMENT ON COLUMN edc_lease.leased_at IS 'posix timestamp of lease';
COMMENT ON COLUMN edc_lease.lease_duration IS 'duration of lease in milliseconds';
