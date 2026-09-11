# Shared Migration Flyway

Migrations for database objects that no single store module owns, because several upstream modules
declare them with `CREATE TABLE IF NOT EXISTS`.

The scripts are Flyway-versioned (`V<version>__<name>.sql`) and applied by `flyway-core`. The module
keeps a neutral name because it is one of the migration *sets*, alongside `control-plane-migration`
and the others, not part of the engine.

Today that means `edc_lease`, declared by `contract-negotiation-store-sql`,
`transfer-process-store-sql`, `data-plane-instance-store-sql`, `policy-monitor-store-sql` and
`data-plane-store-sql` — four of which reach the control-plane launcher through
`controlplane-feature-sql-bom`, not through an explicit dependency.

Every launcher writing to a database that holds such an object depends on **this** module. It must
never be copy-pasted per launcher: divergent copies produce different Flyway checksums against the
shared history table, and the second runtime to start fails its boot.

## When a table belongs here

Multi-module `CREATE TABLE IF NOT EXISTS` alone is not enough to justify moving a table here --
`edc_jti_validation` (control-plane, identity-hub, issuer-service) and `edc_edr_entry`
(control-plane, data-plane) are both declared by more than one module's baseline and stay put,
because every one of those declarations is a plain, idempotent `CREATE TABLE IF NOT EXISTS`: any
runtime can run it in any order, no coordination needed.

`edc_lease` moved here because a migration on it needs to be **destructive** (see
`V20260804.1__recreate_edc_lease.sql`: `DROP TABLE ... CASCADE` then recreate with a different
primary key). A drop-and-recreate is only safe once every runtime that still holds a pending,
checksum-frozen migration against the old shape has finished applying it -- otherwise that runtime's
migration can fail, or worse, silently target a table that no longer matches what it expected. That
is what `MigrationBarrier` enforces (see `flyway-core/README.md`), and why the table needs to live in
one Flyway-versioned set every dependent runtime shares, instead of N independent copies.

So the actual test is: **multi-module ownership** (no single module can make the call alone) **and**
a migration on it that is not naturally idempotent (drop, rename, incompatible constraint change,
etc.). A table that only ever gets `IF NOT EXISTS`'d has no such moment to coordinate, so it can stay
declared independently in each owning module even if several of them declare it.

| Setting | Default | Description |
|---|---|---|
| `edc.migration.datasource.shared` | `default` | Datasource the shared migrations are applied to. |

See `flyway-core/README.md` for the migration conventions and the rationale.
