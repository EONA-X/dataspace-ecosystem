# Control Plane Migration

Flyway migrations for the control-plane store tables: `edc_transfer_process`,
`edc_contract_negotiation`, `edc_contract_agreement`, `edc_policy_monitor`, `edc_asset`,
`edc_policydefinitions`, `edc_contract_definitions`, `edc_edr_entry`.

`edc_lease` is **not** here: it is declared by several upstream modules and belongs to
`migration-shared`.

| Setting | Default | Description |
|---|---|---|
| `edc.migration.datasource.controlplane` | `default` | Datasource the control-plane migrations are applied to. |

## Content

- `V20260804.2__edc_0_15_control_plane.sql` — EDC 0.14.0 → 0.15.0, ported from the manual
  migration script originally proposed in MR !69 (superseded by this Flyway migration, no
  longer in the repo).

### Deviations from the !69 script

Two index statements have no counterpart in the source script. They were added because the
convergence test compares indexes, and without them a migrated database does not match a freshly
autocreated 0.15 one:

- `CREATE INDEX IF NOT EXISTS contract_negotiation_agreement_id_index` — new in 0.15.0. The upstream
  DDL only creates it on a database that does not have the table yet, so an existing database never
  gets it. Missing it costs a sequential scan on agreement lookups, silently.
- `DROP INDEX IF EXISTS transfer_process_id_uindex` — dropped upstream in 0.15.0, redundant with the
  primary key index on the same column.

The `UNIQUE` constraint on `edc_contract_agreement` is also guarded differently: the source script
adds it unconditionally, which fails on a database that already has it. See the comment in the
migration.

The `participant_context_id` backfills use the `${participantContextId}` placeholder, resolved from
`edc.participant.id`. This assumes the database holds data for exactly one participant — true for
every DSE deployment as of this writing. The pre-0.15 schema recorded no participant id anywhere, so
a wrong value cannot be detected from the data.

See `extensions/common/migration/flyway-core/README.md` for the migration conventions.
