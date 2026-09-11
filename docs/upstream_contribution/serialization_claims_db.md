# Options for fixing DB array claim serialization (Postgres array → VC)

## Background

`MembershipCredential.groups` is stored as a Postgres `varchar[]` column. When read generically over JDBC (`ResultSet.getString()` on an array column), the driver does not return a list but the raw Postgres array-literal text (`{EMDS,TOUR}`, or `{"EMDS"}` for a single element, quoted by pgjdbc). If that raw value ends up as-is in the `groups` claim of the issued VC, a `HAS_PART 'EMDS'` policy silently fails: it compares `"{EMDS}"` to `"EMDS"`, never equal.

Two repos are involved:
- **`dse/`** — EonaX custom extensions, freely modifiable.
- **Upstream EDC repos** (`Connector/`, `IdentityHub/`, `FederatedCatalog/`) — pinned to version 0.14.0, not modified directly per project convention.

---

## Part 1 — Solutions without modifying upstream EDC repos

**Foundation, non-optional**: the dse store that persists the attestation (`SqlMembershipAttestationStore`) serializes `groups` as JSON (`["EMDS"]`) instead of via a native Postgres `Array`, and reads it back with `getString()` + JSON deserialization. This removes format variance for anything going through this store, but only covers that specific write/read path — a generic `attestation_type` that reads the table directly through the upstream component (independent of this store) can still produce the raw literal. Hence a second layer is needed, and here it's a binary choice, not a combination:

### Option A — dse extension keyed on attestation type

A dse extension hooks into the attestation source provider (the component that decides, for a given `attestation_type`, how to read the DB), with two possible sub-variants for activating it:

- **A1 — new custom type**: the extension registers a dedicated value (e.g. `"dse-database"`), activated only if `attestation_definitions.attestation_type` in the database explicitly points to it. Requires a data migration in every environment to activate the fixed behavior.
- **A2 — override of the existing type**: the extension replaces, without introducing a new value, the entry already registered by upstream for `"database"` in the factory registry (`factories` — a plain `HashMap`, see Part 2). No data migration, but relies on the override running after the upstream registration has happened — depends on a lifecycle hook whose documentation explicitly discourages service-registration use (needs solid verification before relying on it).

In both sub-variants: the claim shape is fixed before issuance, the produced VC is clean.

### Option B — dse extension based on reading at the operator level

Nothing is touched on the attestation-source side. The dse functions that evaluate ODRL operators (`HAS_PART`, `IN`) against VC content become tolerant of a list claim's serialized shape (Postgres literal `{...}` or JSON array `[...]`) in addition to a genuine JSON-LD list: if the claim read is a string with that shape, it's re-parsed into a list right before the operator is applied.

Independent of the data's origin (regardless of `attestation_type`, store, or reading component) and with no dependency on a DB value — but it does not fix the VC itself: the claim remains malformed in the issued JWT, so a third party consuming it for anything other than access-control evaluation (display, audit, export) would still see the raw broken value.

**Direct comparison**: A fixes the data at the source (clean VC everywhere), at the cost of coupling (data migration for A1, fragile lifecycle hook for A2). B has no dependency on DB/deployment state and covers any serialization variant, at the cost of only repairing the access-control checkpoint, not the VC itself.

---

## Part 2 — Solutions requiring modification of upstream EDC repos

### 2.1 Direct patch of the generic reading component (IdentityHub)

The exact bug location: `DatabaseAttestationSource.mapGenericResult()`, `IdentityHub/extensions/issuance/issuerservice-database-attestations/.../database/DatabaseAttestationSource.java:86-93`:
```java
private Map<String, Object> mapGenericResult(ResultSet resultSet) {
    ...
    var value = resultSet.getString(columnName);   // line 93 — uniform for every column, including arrays
    ...
}
```
An upstream patch would make this read type-aware (detect the column's actual SQL type, use `getArray()` for an array instead of a blanket `getString()`).

- **Advantage**: root-cause fix, single source of truth, every consumer (policy, display, export, any other EDC-based dataspace) benefits without a workaround.
- **Constraints**: forbidden by project convention (upstream Eclipse repos never modified directly, strict alignment with the upstream version); requires either an independently maintained fork (ongoing maintenance burden, drift on every upstream version bump) or acceptance of a contribution by the Eclipse EDC project.

### 2.2 Upstream contribution (PR against the Eclipse EDC repo)

Same code location as 2.1 (`DatabaseAttestationSource.java:86-93`), but proposed through the Eclipse EDC project's standard contribution process instead of an internal fork.

- **Advantage**: no fork to maintain if accepted; benefits every EDC consumer, not just DSE.
- **Constraints**: timeline and acceptance out of our control; fixes nothing until merged and published in an official release; requires a subsequent EDC version bump on the DSE side (`edcGradlePluginsVersion`) to benefit from it.

### 2.3 Full replacement of the upstream component with a dse reimplementation

Instead of patching `DatabaseAttestationSource`, stop using it: provide an attestation type entirely maintained on the dse side, with its own SQL query and column mapping — manually duplicating logic already present in `DatabaseAttestationSourceFactory`/`DatabaseAttestationSource` and the registration done in `DatabaseAttestationExtension.initialize()` (`DatabaseAttestationExtension.java:49-51`, `registry.registerFactory(DATABASE_ATTESTATION_TYPE, ...)`, where `registry` is the `AttestationSourceFactoryRegistry` whose implementation (`AttestationPipelineImpl.java:44-57`) is just a `HashMap<String, AttestationSourceFactory>` — this implementation detail is what makes option A2 in Part 1 technically possible without touching this repo).

- **Advantage**: no line of upstream code modified in the strict sense.
- **Limitation**: duplicates a significant portion of upstream logic (SQL query, transaction handling, column mapping) just to fix the handling of a single column type — growing dse maintenance surface, functional duplication, possible drift if upstream behavior changes elsewhere.
