# Dataspace Ecosystem / EDC Upgrade Guide: 0.14.0 → 0.15.0

Guide for the **0.14.0 → 0.15.0** Eclipse EDC upgrade (branch `test/edc-0.15.0`), the first
upgrade done under this format. Groups the diff by *kind* of change rather than by file, so
whoever does the *next* EDC upgrade knows what categories of work to expect and what to check.

For the next EDC version bump, copy this file to `EDC_UPGRADE_GUIDE_0.15.0_to_0.X.0.md` and
adapt it — keep the "Checklist" and "Category 5 / process note" sections as-is unless the
process itself changed, they're not specific to 0.15.0.

---

## Category 1 — Mechanical API renames/removals

The bulk of the diff (7+ files touched purely for this) is upstream renaming or removing
methods/classes. These are low-risk: the compiler catches every call site. The work is
purely mechanical, but it's easy to miss a call site that only breaks a **test**
source set (see Category 5).

Confirmed renames/removals in 0.14.0 → 0.15.0:

| Old | New | Notes                                                                                                                                                                                                                                                                                                  |
|---|---|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ServiceExtensionContext.getParticipantId()` | `context.getSetting("edc.participant.id", ServiceExtensionContext.ANONYMOUS_PARTICIPANT)` | Removed outright. Touched 7 extensions (control-plane, data-plane, identity-hub, federated-catalog). Search for `getParticipantId()` first when upgrading again — if it reappears or changes again, grep across `datasapce-ecosystem/` for every call site, this pattern repeats every runtime module. |
| `IdentityService.obtainClientCredentials(TokenParameters)` | `obtainClientCredentials(String participantContextId, TokenParameters)` | New first param is the *participant context id*, not the audience/authority DID. In this single-participant-per-runtime codebase, `participantContextId == edc.participant.id == own DID`, so callers pass their own DID.                                                                              |
| `DataFlowController.provision(...)` | `.prepare(...)` | Simple rename, `@Override` catches it.                                                                                                                                                                                                                                                                 |
| `ParticipantManifest.Builder.participantId(...)` | `.participantContextId(...)` |                                                                                                                                                                                                                                                                                                        |
| `ParticipantManifest.Builder.getKey()` / single `KeyDescriptor` | `.getKeys()` / `Set<KeyDescriptor>` | Manifests can now carry multiple keys.                                                                                                                                                                                                                                                                 |
| `identity-trust-*` Maven artifacts | `decentralized-claims-*` | Artifact rename only, same packages/classes as far as we've used them. See `gradle/libs.versions.toml`.                                                                                                                                                                                                |
| `org.eclipse.edc.sql.lease.LeaseStatements` (package) | `org.eclipse.edc.sql.lease.spi.LeaseStatements` | Moved to a `-spi` module as part of the lease model split — see Category 3.                                                                                                                                                                                                                            |
| `DataPlaneAuthorizationService.createEndpointDataReference(...)` / `.revokeEndpointDataReference(...)` | removed from the interface entirely | If your implementation only threw `UnsupportedOperationException` for these (ours did), just delete the overrides — and delete the tests that asserted the exception (they no longer compile, and there's nothing left to test).                                                                       |
| `org.eclipse.edc.web.spi.configuration.ApiContext.PUBLIC` | removed from the interface | Only `MANAGEMENT`, `CONTROL`, `SIGNALING`, `PROTOCOL` remain. Any extension that registered a resource under `ApiContext.PUBLIC` needs its own local `private static final String PUBLIC_CONTEXT = "public";` — the string value is unchanged, only the shared constant is gone.                       |

**How to find these on the next upgrade**: bump `edcGradlePluginsVersion` +
`annotationProcessorVersion` in `gradle.properties` and `edc` in `libs.versions.toml` first,
then run `./gradlew compileJava` across the whole repo (not per-module) and work through the
compiler errors module by module. Don't try to read EDC's changelog first — the compiler is a
more complete list than any changelog.

---

## Category 2 — New Participant Context architecture

EDC 0.15.0 introduced `ParticipantContext` as an explicit, first-class concept for *every*
runtime (control-plane, data-plane, issuer-service), not just IdentityHub. Previously a
runtime's identity was just a config string; now every runtime has a `ParticipantContext`
(identity + config) resolved through pluggable SPIs (`participant-context-single-spi`,
`participant-context-config-spi`, `connector-participant-context-spi`).

This is **not optional** — it's how the Vault client (`HashicorpVaultExtension`) now resolves
per-runtime Vault connection settings (`edc.vault.hashicorp.url`/`.token`), via
`ParticipantContextConfig.getSensitiveString(...)` reading from `privateEntries()`.

What this branch added to cope with it:

- **`extensions/common/participant-context-single-vault-seed`** (new extension) — replaces
  upstream's `participant-context-single-core` for single-participant runtimes
  (control-plane, data-plane, issuer-service). Upstream's default implementation has a bug:
  it never populates `privateEntries()`, so per-participant Vault config resolution silently
  fails. This extension is read-only (`save()` throws) — it's only valid for runtimes that
  never *create* a participant context.
- **identity-hub is different**: it actually creates participant contexts (via
  `ParticipantContextService.createParticipantContext()`, which calls `configService.save()`),
  so it can't use the read-only single-vault-seed extension. It needs the real writable
  `participant-context-config-core` instead.
- **`extensions/identity-hub/superuser-seed`** was extended to reseed the Vault config on
  every boot (`reseedVaultConfig()`), because `ParticipantContextConfigStore` is in-memory
  only at 0.15.0 (no SQL-backed variant shipped upstream) — every pod restart wipes it, and
  without the reseed, Vault config resolution breaks on the second boot onward.

**Database side**: making `ParticipantContext` first-class also means most state-holding
tables gained a `participant_context_id VARCHAR NOT NULL` column with no default —
`edc_asset`, `edc_policydefinitions`, `edc_contract_definitions`, `edc_edr_entry` (control-plane
and data-plane), on top of the ones already covered under Category 3's lease migration
(`edc_transfer_process`, `edc_contract_negotiation`, `edc_contract_agreement`). Confirmed by
diffing the actual bundled `schema.sql` between the 0.14.0 and 0.15.0 jars for every store, not
assumed. See `V20260804.2__edc_0_15_control_plane.sql` and `V20260804.3__edc_0_15_data_plane.sql`
(the Flyway migrations that apply this, tested against a real Postgres with old-shape seed data).

Two related identity-hub tables still need this and are **not yet migrated**: `edc_credential_resource`
and `edc_keypair_resource` gained a `usage NOT NULL` column that needs real classification per
row (not a mechanical copy), and `edc_sts_client` gained `participant_context_id` but also
**dropped** `private_key_alias`/`public_key_reference` — figure out where that data needs to
go before writing that migration, it's a potential secret-handling issue, not routine.

**Checklist for adding a new runtime/launcher on this EDC version:**

1. Does it create participant contexts (identity-hub-style, multi-tenant) or does it have one
   fixed identity per deployment (control-plane/data-plane/issuer-service-style)?
   - Fixed identity → depend on `participant-context-single-vault-seed`, **and** exclude
     `participant-context-single-core` from whichever bom you pull, *if and only if* that bom
     actually ships it (`./gradlew :your:module:dependencyInsight --dependency
     participant-context-single-core --configuration runtimeClasspath` to check — some boms
     don't pull it at all, in which case the exclude is a no-op; `dataplane-base-bom` and
     `issuerservice-bom` don't, `controlplane-base-bom` does).
   - Multi-tenant (creates contexts) → depend on the real `participant-context-core` +
     `participant-context-config-core`, not the vault-seed extension.
2. If it seeds a participant on boot (like `superuser-seed`) and relies on a persistent
   `Vault`, make sure the reseed happens on *every* boot, not just on first creation — the
   config store doesn't survive restarts.

---

## Category 3 — SQL lease model migration

Upstream replaced per-store lease tables (`edc_lease` scoped to one store, `lease_id` FK) with
a single shared `edc_lease` table per schema, keyed by `(resource_id, resource_kind)`, via new
`sql-lease-spi`/`sql-lease-core` modules. Every custom SQL store in `dataspace-ecosystem/` that implements
its own lease-backed `nextNotLeased()`/`findByIdAndLease()` needs this migration — currently
that's `telemetry-store-sql`; check for others before the next upgrade
(`grep -rl "getInsertLeaseTemplate\|getDeleteLeaseTemplate" extensions/`).

What changes, mechanically:

- Store constructor: stop building `SqlLeaseContextBuilder` internally from `Clock` +
  `leaseHolderName`; take an already-built `SqlLeaseContextBuilder` as a constructor param
  instead (built by the extension via the new `@Inject SqlLeaseContextBuilderProvider`).
- `LeaseStatements` methods (`getInsertLeaseTemplate`/`getUpdateLeaseTemplate`/
  `getDeleteLeaseTemplate`/`getFindLeaseByEntityTemplate`) go away; replace with a single
  `createNextNotLeasedQuery(QuerySpec)` that does a `LEFT JOIN` against the shared lease table
  filtered by `resource_kind`. Copy this pattern from an already-migrated upstream store
  (`transfer-process-store-sql`'s `BaseSqlDialectStatements` is the cleanest reference) rather
  than inventing it — the join/filter shape is subtle to get right from scratch.
- `acquireLease`/`breakLease` **return a `StoreResult<Void>` instead of throwing**. This is
  the sharpest edge in this migration: **grep every call site of `.breakLease(` and
  `.acquireLease(` in your store and make sure the result is checked.** We found (and fixed)
  a real regression where `save()`'s `.breakLease(id)` call was migrated to the new
  `SqlLeaseContextBuilder` API but its return value was left unchecked — `update()` ran
  regardless of whether the lease-break actually succeeded, silently letting a write through
  even when the record was leased by another node. If you have an existing test asserting
  `assertThatThrownBy(() -> store.save(...)).isInstanceOf(IllegalStateException.class)` for
  the "leased by someone else" case, **keep running it** — it's exactly the regression
  detector for this bug, and it will fail loudly (not silently) if this gets re-introduced.
- Schema: `edc_lease` gets `resource_id`/`resource_kind` composite PK instead of
  `lease_id`; the per-store table drops its `lease_id` FK column entirely (lease association
  is now implicit via id/kind match, not a foreign key).

**This is not limited to `telemetry-store-sql`.** Every upstream SQL store that uses leasing
went through the exact same `edc_lease` shape change — confirmed by diffing the actual
`schema.sql` bundled in the 0.14.0 vs 0.15.0 jars for every lease-using store across every
launcher: `transfer-process-store-sql`, `contract-negotiation-store-sql`, `data-plane-store-sql`,
`policy-monitor-store-sql`, `holder-credential-offer/request-store-sql`,
`issuance-process-store-sql`. Because the bootstrap script only ever does
`CREATE TABLE IF NOT EXISTS`, none of this self-heals on a database that already has the old
shape.

This used to require running a set of manual SQL scripts against every environment before
deploying the 0.15.0 build (`sql-migrations-v0.15/`, MR !69) — superseded by #605: every
launcher now runs its own Flyway migrations at boot (`extensions/*/*-migration/`), coordinated
across the runtimes sharing one database by a two-phase barrier
(`extensions/common/migration/flyway-core`) so the shared `edc_lease` reshape can't run ahead
of a sibling's still-pending baseline. No manual step needed before deploying the 0.15.0
build — the migration runs automatically on first boot against the existing schema.

---

## Category 4 — Known upstream gaps we're carrying workarounds for

These aren't things we chose to build — they're upstream bugs/missing features in EDC
0.15.0's freshly-introduced Participant Context model. Worth checking on each future upgrade
whether upstream has fixed them yet (search `eclipse-edc/Connector` and
`eclipse-edc/IdentityHub` issues/changelog), so the workaround can be deleted instead of
carried forward indefinitely.

1. **`participant-context-single-core`'s `privateEntries()` bug** — worked around by
   `participant-context-single-vault-seed` (Category 2). Confirmed by reading the upstream
   class: it only ever populates `entries()`, never `privateEntries()`, even though
   `HashicorpVaultExtension` reads Vault config exclusively via `privateEntries()`.
2. **No persistent (SQL-backed) `ParticipantContextConfigStore`** at 0.15.0 — worked around
   by `superuser-seed`'s `reseedVaultConfig()` (Category 2). Confirmed: only
   `participant-context-config-spi` and `-config-core` (in-memory) resolve on the classpath,
   no `-sql` variant exists.
3. **Race condition in `ParticipantContextEventCoordinator`** — on `ParticipantContextCreated`,
   the keypair gets added (firing `KeyPairAdded`, which tries to publish the DID document)
   *before* the participant is activated, so the DID publish can fail while the participant is
   still `CREATED`, and that failure is only logged as a warning, not retried. Worked around by
   `extensions/identity-hub/participant-activation-healer`, which polls and force-activates
   stuck participants. Confirmed still present in a local checkout of IdentityHub **v0.17.0**
   (two minor versions ahead of what we pin) — this looks like a persistent upstream
   architectural issue, not something fixed in the interim. Worth filing upstream.

---

## Category 5 — Test-suite migration debt (process note)

**Every single test-compilation failure found during this upgrade's review was a case where
`compileJava` passed but `compileTestJava` silently didn't get run/checked.** Main and test
are separate source sets — a broken test file does not fail `./gradlew build` if nobody runs
the `test` task for that specific module, and this repo's CI splits unit vs. Postgres/SQL
integration tests into different jobs (`unit-tests.gradle` vs `integration-tests.gradle`, see
`.gitlab-ci.yml`), which makes it even easier for a module's tests to silently stop compiling
without any job noticing during the PR that broke them.

We found 4 modules with broken tests this way: `telemetry-store-sql`, `telemetry-agent-core`,
`superuser-seed` (compiled but NPE'd at runtime — same root cause, different symptom), and
`data-plane-public-api-v2`.

**For the next EDC upgrade**: after fixing all `compileJava` errors, run
`./gradlew compileTestJava` **repo-wide** (not per-module) before considering the upgrade
done — don't rely on a green `build.gradle.kts` diff or a passing CI job on default settings
to mean the tests still compile. For SQL-store modules specifically, also run the actual
tests against a real Postgres:

```
./gradlew :extensions:common:store:sql:<module>:test -DrunAllTests=true --tests "*Postgres*"
```

(`-DrunAllTests=true` is required locally — the default `edc-build` convention excludes the
`IntegrationTest` tag unless this system property or `-DincludeTags=...` is set; the
`integration-tests.gradle` init script alone does not set it, it only adds filename-based
filtering on top.)

---

## Checklist for the next EDC upgrade

1. Bump `edcGradlePluginsVersion`, `annotationProcessorVersion` in `gradle.properties` **and**
   `version` (keep the project version in sync with the EDC version we track, don't let it
   drift — it was found one version behind on this upgrade).
2. Bump `edc` in `gradle/libs.versions.toml`.
3. `./gradlew compileJava` repo-wide, fix every error (Category 1 — mostly mechanical).
4. `./gradlew compileTestJava` repo-wide, fix every error (Category 5 — don't skip this).
5. For each launcher (`-base` + its `-postgresql-hashicorpvault`/etc. variants), re-check
   whether `participant-context-single-core` needs excluding from its bom
   (`dependencyInsight`, Category 2) — this can change between EDC versions as boms are
   restructured.
6. For each custom SQL store, re-check the lease query pattern still matches upstream's
   current convention (Category 3) — compare against whichever upstream store was the
   reference last time, in case upstream itself changed the pattern again.
7. Run the full test suite, including Postgres integration tests with `-DrunAllTests=true`.
8. Diff every upstream SQL store's bundled `schema.sql` between the old and new EDC jar
   versions (`unzip -p <jar> '*.sql'`) for every launcher we ship, not just our own custom
   stores — write and test (against a real Postgres, with seed data matching the old shape)
   a migration script for every environment with pre-existing data, per Category 3 and
   `sql-migrations-v0.<X>/README.md`. Don't assume "we didn't touch that store's Java code"
   means "no schema migration needed" — most of the schema changes this time came from
   upstream stores we never modify directly.
9. Check whether any of the Category 4 upstream gaps got fixed — if so, delete the
   corresponding workaround extension and simplify the affected launchers.
10. Update this guide with anything new.
