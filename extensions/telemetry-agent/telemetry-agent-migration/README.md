# Telemetry Agent Migration

Contributes no migration scripts. This module exists solely to give the telemetry-agent a signal key
on the migration barrier (`edc.migration.barrier.expected-modules`), because it is not part of the
barrier at all otherwise -- see `TelemetryAgentMigrationExtension`'s javadoc for why that matters.

The telemetry-agent owns no table of its own. Its store (`extensions/common/store/sql/telemetry-store-sql`,
`edc_telemetry_record`) is migrated by `data-plane-migration`, not by this module -- see the coverage
table in `extensions/common/migration/flyway-core/README.md`.

| Setting | Default | Description |
|---|---|---|
| `edc.migration.datasource.telemetryagent` | `default` | Datasource this (empty) migration set is registered against. |

## Why the telemetry-agent needs to be a barrier participant at all

The telemetry-agent writes to `edc_lease` on every participant database it serves, acquiring leases
through `LeaseStatements` generated for the EDC version its own jar was built against. Two failure
modes without this module:

- A telemetry-agent still on pre-barrier code has no signal key, so nothing holds back a sibling
  runtime's shared reshape (`migration-shared`'s `DROP TABLE edc_lease CASCADE` and recreate) while
  that telemetry-agent is still mid-request against the old shape.
- A telemetry-agent that does carry this module, but not `migration-shared` itself, would clear the
  barrier wait as soon as every sibling has finished its own (non-shared) phase, without actually
  blocking on the shared reshape's completion -- Flyway's lock, not the barrier, is what makes that
  wait real, and only a runtime that carries `migration-shared` on its own classpath contends on that
  lock. This is why the launcher depends on both this module and `migration-shared`, the same pairing
  `control-plane`/`data-plane`/`identity-hub` already carry.

See `extensions/common/migration/flyway-core/README.md` for the migration conventions.
