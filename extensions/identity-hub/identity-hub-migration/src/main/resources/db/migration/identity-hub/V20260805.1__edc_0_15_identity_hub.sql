-- EDC 0.14.0 -> 0.15.0: identity-hub store tables.
--
-- Not ported from MR !69: that MR only covered the control-plane and data-plane. This delta was
-- derived from the DDL diff between the upstream tags v0.14.0 and v0.15.0.
--
-- edc_lease is handled by the shared migration module (V20260804.1), which drops the FK constraints
-- the tables below hold on lease_id.
--
-- This set runs in the issuer-service runtime too: credential_resource, keypair_resource and
-- edc_sts_client are declared by store modules present in both SQL BOMs, so either runtime may boot
-- first and find the table already there. The tables only the identity-hub creates are guarded
-- explicitly, so running this set against a database that has no identity-hub is a no-op.
--
-- Every statement is idempotent, so this migration converges from either a 0.14 database or a
-- freshly autocreated 0.15 one.

---- credential_resource ----
-- 0.15 adds a NOT NULL 'usage' with no default, so it cannot be added in one statement to a
-- populated table: add nullable, backfill, then constrain.
--
-- The value is read back with CredentialUsage.valueOf(), so it must match the enum constant name
-- exactly -- 'Holder', not the lowercase spelling used in the upstream column comment.
--
-- Backfill rule: a 0.14 database can only hold Holder and StatusList credentials. The third
-- constant, IssuanceTracking, is new in 0.15 and no 0.14 code path could produce it. StatusList
-- credentials are identifiable by their VC type.

ALTER TABLE credential_resource ADD COLUMN IF NOT EXISTS usage VARCHAR;

UPDATE credential_resource
SET usage = CASE
                WHEN verifiable_credential::text LIKE '%BitstringStatusListCredential%' THEN 'StatusList'
                ELSE 'Holder'
    END
WHERE usage IS NULL;

ALTER TABLE credential_resource ALTER COLUMN usage SET NOT NULL;

---- keypair_resource ----
-- Same three-step pattern. This column holds a JSON array, read back with
-- fromJson(.., Set<KeyPairUsage>), and KeyPairUsage serialises through its @JsonProperty names:
-- sign_credentials, sign_presentation, sign_token.
--
-- Backfill rule: 0.14 key pairs carried no usage restriction and were eligible for every operation,
-- so all three usages are granted. Narrowing them would silently disable signing paths that worked
-- before the upgrade. KeyPairResource.Builder rejects an empty usage set, so an empty array is not
-- an option.

ALTER TABLE keypair_resource ADD COLUMN IF NOT EXISTS usage VARCHAR;

UPDATE keypair_resource
SET usage = '["sign_credentials","sign_presentation","sign_token"]'
WHERE usage IS NULL;

ALTER TABLE keypair_resource ALTER COLUMN usage SET NOT NULL;

---- edc_sts_client ----
-- 0.15 adds participant_context_id NOT NULL and drops private_key_alias / public_key_reference.
--
-- The backfill is derived from the data, not from configuration: in 0.14 the account was created
-- with .id(manifest.getParticipantId()), so the primary key already holds the participant context
-- id. 0.15 keeps the same convention for the 'name' column, so the two agree after the upgrade.
--
-- The two dropped columns are gone from the StsAccount model in 0.15 -- the key material is held by
-- the keypair resource now -- so dropping them discards a pointer upstream no longer reads. It is
-- still a destructive step: see the module README.

ALTER TABLE edc_sts_client ADD COLUMN IF NOT EXISTS participant_context_id VARCHAR;

UPDATE edc_sts_client SET participant_context_id = id WHERE participant_context_id IS NULL;

ALTER TABLE edc_sts_client ALTER COLUMN participant_context_id SET NOT NULL;

ALTER TABLE edc_sts_client DROP COLUMN IF EXISTS private_key_alias;
ALTER TABLE edc_sts_client DROP COLUMN IF EXISTS public_key_reference;

---- edc_credential_offers, edc_holder_credentialrequest ----
-- Only created by the identity-hub. DROP COLUMN IF EXISTS still errors when the table itself is
-- missing, so each table is checked before being touched.
--
-- lease_lease_id_uindex was declared alongside the old edc_lease by these modules. The shared
-- migration drops and recreates edc_lease, which takes the index with it; the statement below only
-- matters for a database where the shared set has not run yet.

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_name = 'edc_credential_offers'
                 AND table_schema = current_schema()) THEN
        ALTER TABLE edc_credential_offers DROP COLUMN IF EXISTS lease_id;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_name = 'edc_holder_credentialrequest'
                 AND table_schema = current_schema()) THEN
        ALTER TABLE edc_holder_credentialrequest DROP COLUMN IF EXISTS lease_id;
    END IF;
END $$;

DROP INDEX IF EXISTS lease_lease_id_uindex;