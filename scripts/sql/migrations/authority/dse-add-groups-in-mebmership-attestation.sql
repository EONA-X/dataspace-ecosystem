BEGIN;

---- migrate membership_attestation table ----

-- 1. Add the new 'groups' column (defaults backfill existing rows)
ALTER TABLE membership_attestation
ADD COLUMN groups varchar DEFAULT '[]';

COMMIT;
