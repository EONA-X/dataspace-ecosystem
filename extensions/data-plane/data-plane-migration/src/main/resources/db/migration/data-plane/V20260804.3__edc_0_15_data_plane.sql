-- EDC 0.14.0 -> 0.15.0: data-plane store tables.
-- Ported from sql-migrations-v0.15/data-plane-migration.sql (MR !69).
--
-- edc_lease is handled by the shared migration module (V20260804.1), which runs first and drops the
-- FK constraints these tables hold on lease_id.
--
-- Every statement is idempotent, so this migration converges from either a 0.14 database or a
-- freshly autocreated 0.15 one. That is what makes it safe to apply to a database whose prior state
-- was never recorded.

---- edc_telemetry_record ----
-- DSE's own store (extensions/common/store/sql/telemetry-store-sql), migrated here because the table
-- lives in the data-plane database. It belongs in a telemetry migration module -- to be moved when
-- the remaining store modules get their migration sets (follow-up ticket to #605).

ALTER TABLE edc_telemetry_record DROP COLUMN IF EXISTS lease_id;

---- edc_data_plane ----

ALTER TABLE edc_data_plane DROP COLUMN IF EXISTS lease_id;

-- Nullable and defaulted, no backfill needed, but must exist: the app's query templates reference it
-- unconditionally.
ALTER TABLE edc_data_plane ADD COLUMN IF NOT EXISTS resource_definitions JSON DEFAULT '[]';

---- edc_data_plane_instance ----
-- Only present if this launcher uses the data-plane-selector / instance registry. DROP COLUMN IF
-- EXISTS still errors when the table itself is missing, so the table is checked explicitly.

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_name = 'edc_data_plane_instance'
                 AND table_schema = current_schema()) THEN
        ALTER TABLE edc_data_plane_instance DROP COLUMN IF EXISTS lease_id;
    END IF;
END $$;

---- edc_edr_entry ----
-- Also present in the control-plane database. When both runtimes share one database, whichever
-- migration runs first does the work and the other one is a no-op.

ALTER TABLE edc_edr_entry ADD COLUMN IF NOT EXISTS participant_context_id VARCHAR;
UPDATE edc_edr_entry SET participant_context_id = '${participantContextId}'
WHERE participant_context_id IS NULL;
ALTER TABLE edc_edr_entry ALTER COLUMN participant_context_id SET NOT NULL;
