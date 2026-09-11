# Data Plane Migration

Flyway migrations for the data-plane store tables: `edc_data_plane`, `edc_data_plane_instance`,
`edc_edr_entry`, plus `edc_telemetry_record`.

`edc_lease` is **not** here: it is declared by several upstream modules and belongs to
`migration-shared`.

`edc_telemetry_record` is DSE's own store (`extensions/common/store/sql/telemetry-store-sql`),
migrated here because the table lives in the data-plane database. It belongs in a telemetry migration
module — to be moved when the remaining store modules get their migration sets (follow-up ticket to
#605).

| Setting | Default | Description |
|---|---|---|
| `edc.migration.datasource.dataplane` | `default` | Datasource the data-plane migrations are applied to. |

## Content

- `V20260804.3__edc_0_15_data_plane.sql` — EDC 0.14.0 → 0.15.0, ported from
  `sql-migrations-v0.15/data-plane-migration.sql` (MR !69).

See `extensions/common/migration/flyway-core/README.md` for the migration conventions.
