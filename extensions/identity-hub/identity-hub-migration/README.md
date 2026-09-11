# Identity Hub Migration

Flyway migrations for the identity-hub store tables. Registered with the `MigrationRegistry` provided
by [`flyway-core`](../../common/migration/flyway-core/README.md), which explains how the sets
are applied and why the migrations are written the way they are.

| Setting | Default | Description |
|---|---|---|
| `edc.migration.datasource.identityhub` | `default` | Datasource the identity-hub migrations are applied to. |

## Wired into two runtimes

This set belongs to the issuer-service launcher as well as the identity-hub one. Five upstream store
modules sit in both SQL BOMs:

```
identity-hub-credentials-store-sql      -> credential_resource
identity-hub-did-store-sql              -> did resources
identity-hub-keypair-store-sql          -> keypair_resource
identity-hub-participantcontext-store-sql -> participant_context
sts-client-store-sql                    -> edc_sts_client
```

Both runtimes therefore create those tables, in the same database, and either may boot first. Wiring
the set into only one of them would make the migration depend on boot order.

The two tables only the identity-hub creates — `edc_credential_offers`,
`edc_holder_credentialrequest` — are guarded on table existence, so the set is a no-op against a
database that has no identity-hub.

## Where the 0.15 delta comes from

Not ported from MR !69, which only covered the control-plane and data-plane. It was derived from the
DDL diff between the upstream tags `v0.14.0` and `v0.15.0` of the IdentityHub repository.

## Backfill decisions

Three columns cannot simply be added, and the value chosen for existing rows is a judgement call
rather than a mechanical one. They are the parts of this module most worth reviewing.

| Column | Value | Reasoning |
|---|---|---|
| `credential_resource.usage` | `StatusList` when the VC type contains `BitstringStatusListCredential`, otherwise `Holder` | The value is read back with `CredentialUsage.valueOf()`, so it must match the enum constant exactly — not the lowercase spelling in the upstream column comment. The third constant, `IssuanceTracking`, is new in 0.15 and no 0.14 code path could have produced it. |
| `keypair_resource.usage` | `["sign_credentials","sign_presentation","sign_token"]` | 0.14 key pairs carried no usage restriction and were eligible for every operation. Granting less would silently disable signing paths that worked before the upgrade. `KeyPairResource.Builder` rejects an empty set, so leaving it empty is not an option. The JSON names come from `KeyPairUsage`'s `@JsonProperty`. |
| `edc_sts_client.participant_context_id` | the row's own `id` | Derived from the data, not from configuration: 0.14 created the account with `.id(manifest.getParticipantId())`, so the primary key already holds the participant context id. 0.15 keeps the same convention for `name`, so the two agree after the upgrade. |

`PostgresFlywayMigrationTest.backfills_fillEveryExistingRow` asserts each of these against seeded
rows, including both branches of the `credential_resource` case.

## Destructive step

`edc_sts_client.private_key_alias` and `public_key_reference` are dropped. Both fields were removed
from the `StsAccount` model in 0.15 — key material is held by the keypair resource now — so nothing
reads them after the upgrade, and the aliases they pointed at are still in the vault. It remains the
only place in these migrations where a column holding a pointer to vault material is dropped rather
than migrated, and it is worth a second look before the 0.15 rollout.