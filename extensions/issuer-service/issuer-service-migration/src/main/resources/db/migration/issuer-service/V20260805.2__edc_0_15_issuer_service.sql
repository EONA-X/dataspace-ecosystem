-- EDC 0.14.0 -> 0.15.0: the tables only the issuer-service creates.
--
-- Not ported from MR !69: that MR only covered the control-plane and data-plane. This delta was
-- derived from the DDL diff between the upstream tags v0.14.0 and v0.15.0.
--
-- credential_resource, keypair_resource and edc_sts_client also live in this runtime's database, but
-- they are declared by store modules shared with the identity-hub and are migrated by the
-- identity-hub set (V20260805.1), which is wired into this runtime as well. edc_lease is handled by
-- the shared module (V20260804.1).
--
-- Every statement is idempotent, so this migration converges from either a 0.14 database or a
-- freshly autocreated 0.15 one.

---- holders ----
-- Both columns carry a default, so they can be added in a single statement each: existing rows are
-- backfilled by the default rather than by an UPDATE.
--
-- 'anonymous' marks holders created on the fly by a credential request. Rows that predate 0.15 were
-- all created explicitly, so FALSE is the correct value for them and not merely a safe one.

ALTER TABLE holders ADD COLUMN IF NOT EXISTS anonymous BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE holders ADD COLUMN IF NOT EXISTS properties JSON DEFAULT '{}';

---- edc_issuance_process ----
-- The shared migration recreates edc_lease and drops the FK this column carried.

ALTER TABLE edc_issuance_process DROP COLUMN IF EXISTS lease_id;