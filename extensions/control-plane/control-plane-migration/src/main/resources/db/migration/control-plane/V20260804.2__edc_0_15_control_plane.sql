-- EDC 0.14.0 -> 0.15.0: control-plane store tables.
-- Ported from sql-migrations-v0.15/control-plane-migration.sql (MR !69).
--
-- edc_lease is handled by the shared migration module (V20260804.1), which runs first and drops the
-- FK constraints these tables hold on lease_id.
--
-- ${participantContextId} resolves to this runtime's edc.participant.id. This assumes the database
-- holds data for exactly one participant, which is true for every DSE deployment as of this writing.
-- The pre-0.15 schema recorded no participant id anywhere, so a wrong value here is silent: it
-- cannot be detected from the data itself.
--
-- Every statement is idempotent, so this migration converges from either a 0.14 database or a
-- freshly autocreated 0.15 one. That is what makes it safe to apply to a database whose prior state
-- was never recorded.

---- edc_transfer_process ----

-- The per-table lease FK column: edc_lease no longer has a lease_id to point at.
ALTER TABLE edc_transfer_process DROP COLUMN IF EXISTS lease_id;

ALTER TABLE edc_transfer_process ADD COLUMN IF NOT EXISTS participant_context_id VARCHAR;
UPDATE edc_transfer_process SET participant_context_id = '${participantContextId}'
WHERE participant_context_id IS NULL;
ALTER TABLE edc_transfer_process ALTER COLUMN participant_context_id SET NOT NULL;

-- Nullable, no backfill needed, but must exist: the app's query templates reference it
-- unconditionally.
ALTER TABLE edc_transfer_process ADD COLUMN IF NOT EXISTS dataplane_metadata JSON;

-- Dropped upstream in 0.15.0: it duplicates the primary key index on the same column. A destructive
-- statement needs a reason why nothing is lost -- here, the redundancy itself: no data is touched and
-- the primary key index covers the same lookups.
DROP INDEX IF EXISTS transfer_process_id_uindex;

---- edc_contract_negotiation ----

ALTER TABLE edc_contract_negotiation DROP COLUMN IF EXISTS lease_id;

ALTER TABLE edc_contract_negotiation ADD COLUMN IF NOT EXISTS participant_context_id VARCHAR;
UPDATE edc_contract_negotiation SET participant_context_id = '${participantContextId}'
WHERE participant_context_id IS NULL;
ALTER TABLE edc_contract_negotiation ALTER COLUMN participant_context_id SET NOT NULL;

-- New in 0.15.0. The upstream DDL only creates it on a database that does not have the table yet, so
-- an existing database never gets it without this statement.
CREATE INDEX IF NOT EXISTS contract_negotiation_agreement_id_index
    ON edc_contract_negotiation (agreement_id);

---- edc_contract_agreement ----

-- agr_agreement_id is the business agreement id (what ContractAgreement.getId() returns); agr_id,
-- the existing primary key, already holds exactly that value in every pre-0.15.0 row -- there was
-- only ever one id concept before this migration -- so the backfill is a straight copy, not a guess.
ALTER TABLE edc_contract_agreement ADD COLUMN IF NOT EXISTS agr_agreement_id VARCHAR;
UPDATE edc_contract_agreement SET agr_agreement_id = agr_id WHERE agr_agreement_id IS NULL;
ALTER TABLE edc_contract_agreement ALTER COLUMN agr_agreement_id SET NOT NULL;

ALTER TABLE edc_contract_agreement ADD COLUMN IF NOT EXISTS agr_participant_context_id VARCHAR;
UPDATE edc_contract_agreement SET agr_participant_context_id = '${participantContextId}'
WHERE agr_participant_context_id IS NULL;
ALTER TABLE edc_contract_agreement ALTER COLUMN agr_participant_context_id SET NOT NULL;

-- PostgreSQL has no ADD CONSTRAINT IF NOT EXISTS, and the constraint already exists on a database
-- autocreated by 0.15.0 -- hence the explicit existence check.
--
-- The check is on the constrained columns, not on the constraint name: 0.15.0 declares the
-- constraint inline (UNIQUE (...)) and lets PostgreSQL generate the name, so matching on a name
-- would miss it and add a duplicate constraint. As a result the constraint carries a different name
-- depending on whether the database was migrated or autocreated -- same shape, different label.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint c
                 JOIN pg_class t ON t.oid = c.conrelid
        WHERE t.relname = 'edc_contract_agreement'
          AND c.contype = 'u'
          AND (SELECT array_agg(a.attname::text ORDER BY a.attname)
               FROM unnest(c.conkey) AS k
                        JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k)
              = ARRAY ['agr_agreement_id', 'agr_participant_context_id']
    ) THEN
        ALTER TABLE edc_contract_agreement
            ADD CONSTRAINT edc_contract_agreement_agr_agreement_id_agr_participant_c_key
            UNIQUE (agr_agreement_id, agr_participant_context_id);
    END IF;
END $$;

---- edc_policy_monitor ----

ALTER TABLE edc_policy_monitor DROP COLUMN IF EXISTS lease_id;

---- edc_asset ----

ALTER TABLE edc_asset ADD COLUMN IF NOT EXISTS participant_context_id VARCHAR;
UPDATE edc_asset SET participant_context_id = '${participantContextId}'
WHERE participant_context_id IS NULL;
ALTER TABLE edc_asset ALTER COLUMN participant_context_id SET NOT NULL;

ALTER TABLE edc_asset ADD COLUMN IF NOT EXISTS dataplane_metadata JSON;

---- edc_policydefinitions ----

ALTER TABLE edc_policydefinitions ADD COLUMN IF NOT EXISTS participant_context_id VARCHAR;
UPDATE edc_policydefinitions SET participant_context_id = '${participantContextId}'
WHERE participant_context_id IS NULL;
ALTER TABLE edc_policydefinitions ALTER COLUMN participant_context_id SET NOT NULL;

---- edc_contract_definitions ----

ALTER TABLE edc_contract_definitions ADD COLUMN IF NOT EXISTS participant_context_id VARCHAR;
UPDATE edc_contract_definitions SET participant_context_id = '${participantContextId}'
WHERE participant_context_id IS NULL;
ALTER TABLE edc_contract_definitions ALTER COLUMN participant_context_id SET NOT NULL;

---- edc_edr_entry ----
-- Also present in the data-plane database (see the data-plane migration): EDRs are tracked on both
-- sides. When both runtimes share one database, whichever migration runs first does the work and the
-- other one is a no-op.

ALTER TABLE edc_edr_entry ADD COLUMN IF NOT EXISTS participant_context_id VARCHAR;
UPDATE edc_edr_entry SET participant_context_id = '${participantContextId}'
WHERE participant_context_id IS NULL;
ALTER TABLE edc_edr_entry ALTER COLUMN participant_context_id SET NOT NULL;
