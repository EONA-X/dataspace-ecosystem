# Issuer Service Migration

Flyway migrations for the tables only the issuer-service creates. Registered with the
`MigrationRegistry` provided by
[`flyway-core`](../../common/migration/flyway-core/README.md), which explains how the sets are
applied and why the migrations are written the way they are.

| Setting | Default | Description |
|---|---|---|
| `edc.migration.datasource.issuerservice` | `default` | Datasource the issuer-service migrations are applied to. |

## Scope

This set is deliberately small. The issuer-service database also holds `credential_resource`,
`keypair_resource` and `edc_sts_client`, but those are declared by store modules shared with the
identity-hub, so they are migrated by
[`identity-hub-migration`](../../identity-hub/identity-hub-migration/README.md) — which is wired into
this runtime as well. `edc_lease` belongs to the shared module.

What is left here:

| Table | 0.15 change |
|---|---|
| `holders` | `anonymous BOOLEAN NOT NULL DEFAULT FALSE`, `properties JSON DEFAULT '{}'` |
| `edc_issuance_process` | `lease_id` dropped, along with its FK to `edc_lease` |

Both `holders` columns carry a default, so they are added in a single statement each and existing
rows are backfilled by the default rather than by an `UPDATE`. `anonymous` marks holders created on
the fly by a credential request: rows that predate 0.15 were all created explicitly, so `FALSE` is
the correct value for them and not merely a safe one.

## Where the 0.15 delta comes from

Not ported from MR !69, which only covered the control-plane and data-plane. It was derived from the
DDL diff between the upstream tags `v0.14.0` and `v0.15.0` of the IdentityHub repository.