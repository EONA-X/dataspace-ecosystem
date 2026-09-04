# DSE Policy Evaluation

EDC extension providing DSE-specific ODRL constraint functions (membership, generic VC claims, list-valued claims via `HAS_PART`) and the plumbing needed for those constraints to actually receive the Verifiable Credentials they evaluate, across contract negotiation, transfer, and the authority's federated catalog visibility filter.

## Table of Contents

- [Overview](#overview)
- [Problem it Solves](#problem-it-solves)
- [How It Works](#how-it-works)
- [Architecture](#architecture)
- [Usage](#usage)
- [Error Reporting](#error-reporting)
- [Wiring / Deployment](#wiring--deployment)
- [Testing](#testing)
- [Functional / Live Testing](#functional--live-testing)

## Overview

Eclipse EDC's ODRL policy engine ships generic operators (`EQ`, `HAS_PART`, ...) but no built-in notion of "does this participant hold a Verifiable Credential of type X with claim Y matching Z" — that has to be supplied as custom `AtomicConstraintRuleFunction`/`DynamicAtomicConstraintRuleFunction` implementations. This module supplies three such functions (membership check, arbitrary claim check, restricted catalog-discovery claim check), plus a `PolicyContext`/scope pair used by the authority's federated catalog to run the same evaluation *before* an offer is even published in the aggregated catalog, and a small patch to EDC's IATP/DCP scope derivation so the right credential types actually get requested from the holder's wallet.

## Problem it Solves

- Contract negotiation/transfer/catalog policies need to gate on VC content ("only a `FullMember`", "only a holder in group `EMDS`") — not expressible with stock ODRL operators alone.
- A dataspace-wide catalog (the authority's federated catalog) needs the *same* per-participant gating applied at discovery time, before negotiation is even attempted — so the same claim-matching logic needs to run against a different kind of context (no active negotiation/transfer, just a resolved `ClaimToken` for the requesting participant).
- EDC's IATP/DCP `RequestPolicyContext` scope derivation only requests VC types it can infer are referenced by policy content; DSE's constraints use custom namespaced left-operands (`eox-policy:GenericClaim...`) that upstream doesn't parse for scope inference, so without a patch the presentation request would never actually ask the holder for the credential the constraint needs to evaluate.
- A claim value isn't always a real `List` by the time it reaches evaluation (JSON-LD singleton-list compaction, or an upstream serialization bug, see [#595](https://gitlab.eona-x.org/eona-x/backlog/-/issues/595)) — `HAS_PART` has to work against both shapes without conflating a genuine single-value claim with a broken multi-value one.

## How It Works

Three constraint functions, all extending `AbstractDynamicCredentialConstraintFunction` (a thin base delegating to `CredentialsUtils` — the module's shared, static-method-only home for `getCredentialList` — reads the `"vc"` claim off the `ParticipantAgent`, i.e. the credentials already verified and attached earlier in the request pipeline — plus `parseListEncodedClaim`, `evaluateClaims`/`evaluateField`/`evaluateCollectionField`, `sanitizeClaims`, and `sanitizeLeftOperand`, used by the two claim-path-based functions below and, directly, by `NonFullMemberDefaultDenyValidator`):

- **`MembershipConstraintFunction`** — left-operand `eox-policy:Membership`, right-operand must be the literal `"active"`; passes if the participant holds *any* `MembershipCredential`, regardless of claim content.
- **`JsonPathCredentialConstraintFunction`** — left-operand `eox-policy:GenericClaim.$.<CredentialType>.<dotted.claim.path>`; supports `EQ`/`NEQ`/`IN`/`HAS_PART` against the named claim inside that credential type's `credentialSubject`.
- **`CatalogDiscoveryConstraintFunction`** — same claim-evaluation logic as `JsonPathCredentialConstraintFunction`, shared via `CredentialsUtils` (see [Testing](#testing)), left-operand `eox-policy:RestrictedDiscoveryClaim.$.<CredentialType>.<path>`. Deliberately bound to `CATALOG_DISCOVERY_SCOPE`/`NEGOTIATION_SCOPE`/`TRANSFER_SCOPE` only, **not** `CATALOG_SCOPE` (see `registerBindings` in `PolicyEvaluationExtension`) — so the same policy used as both `accessPolicyId` and `contractPolicyId` still shows the offer in the provider's own raw catalog (constraint unbound there → dropped by `ScopeFilter` → unconditional permit), while the actual gating happens in the authority's federated-catalog-filter and again at negotiation/transfer.

For any claim value that is a `String`, `CredentialsUtils.evaluateClaims` (used by `JsonPathCredentialConstraintFunction`, `CatalogDiscoveryConstraintFunction`, and `NonFullMemberDefaultDenyValidator`) first tries `parseListEncodedClaim`: if the string looks like a serialized list (`{a,b}` — Postgres array literal — or `["a","b"]` — JSON array), it's parsed back into a `List<String>` and evaluated as a collection; otherwise it's evaluated as a scalar. A scalar claim under `HAS_PART` is treated as "does the singleton match" rather than rejected, since JSON-LD compaction legitimately collapses a real single-element list claim into a bare scalar before it ever reaches this code — a claim that lost its list-ness through a lower-level bug (e.g. a naive DB read of an array column, #595) looks identical by the time it gets here, which is exactly why `parseListEncodedClaim` exists as a second line of defense independent of whatever fixed the value at the source.

**`NonFullMemberDefaultDenyValidator`** is a `PolicyValidatorRule<C>` (not a constraint function — pre-evaluation gate registered via `PolicyEngine.registerPreValidator`, since a constraint function only runs when a policy's leftOperand matches, and this needs to deny when a constraint is *absent*). It inverts the default outcome for non-`FullMember` participants: a dataset's policy implicitly allows any participant satisfying it unless this rule denies first. `membershipType == "FullMember"` → unaffected. `membershipType` present and not `"FullMember"` → denied unless the dataset's policy declares a top-level `AtomicConstraint` `eox-policy:RestrictedDiscoveryClaim.$.MembershipCredential.membership.membershipType EQ "<that type>"` (a constraint nested in a `MultiplicityConstraint`, or using any other operator, doesn't count). No `MembershipCredential` presented at all → hard-denied, allow never overrides this. Registered against `CatalogDiscoveryPolicyContext`, `ContractNegotiationPolicyContext`, and `TransferProcessPolicyContext` in `PolicyEvaluationExtension.registerPreValidators()` — deliberately not against `CatalogPolicyContext`/`CATALOG_SCOPE` (see the `CatalogDiscoveryConstraintFunction` scope-binding note above) and not wired into `IatpPatchExtension`.

Separately, `IatpPatchExtension` registers a `DefaultScopeExtractor` post-validator on every relevant `RequestPolicyContext` (catalog, negotiation, transfer, catalog-discovery, telemetry), forcing the DCP presentation request to always ask for `VerifiableCredential:read` — without this, a participant presenting only the VC types upstream's own scope inference decided to ask for might never hand over the `MembershipCredential`/`groups` claim these constraint functions need.

## Architecture

```
extensions/common/policies/
├── build.gradle.kts
├── src/main/java/org/eclipse/dse/iam/
│   ├── core/
│   │   ├── DefaultScopeExtractor.java        # PolicyValidatorRule: forces default scopes onto every request
│   │   ├── IatpPatchExtension.java           # ServiceExtension: registers DefaultScopeExtractor on 5 request contexts
│   │   └── RequestCatalogDiscoveryContext.java  # RequestPolicyContext + scope for the DCP request behind catalog discovery
│   └── policy/
│       ├── CredentialsUtils.java                 # shared statics: getCredentialList, parseListEncodedClaim, evaluateClaims/evaluateField/evaluateCollectionField, sanitizeClaims, sanitizeLeftOperand
│       ├── AbstractDynamicCredentialConstraintFunction.java  # thin base for the 3 constraint functions below, delegates to CredentialsUtils
│       ├── MembershipConstraintFunction.java     # "Membership" == "active" (holds any MembershipCredential)
│       ├── JsonPathCredentialConstraintFunction.java   # "GenericClaim.$.<Type>.<path>" EQ/NEQ/IN/HAS_PART
│       ├── CatalogDiscoveryConstraintFunction.java     # "RestrictedDiscoveryClaim.$.<Type>.<path>", shared claim-evaluation logic, narrower scope binding
│       ├── CatalogDiscoveryPolicyContext.java    # PolicyContext + CATALOG_DISCOVERY_SCOPE, used by federated-catalog-filter
│       ├── CredentialTypePredicate.java          # Predicate<VerifiableCredential> matching by type suffix
│       ├── NonFullMemberDefaultDenyValidator.java  # PolicyValidatorRule: default-deny non-FullMember participants unless allowed
│       ├── PolicyConstants.java                  # DSE policy namespace constant strings
│       └── PolicyEvaluationExtension.java        # ServiceExtension: registers namespace, functions, scope bindings, pre-validators
├── src/main/resources/META-INF/services/
│   └── org.eclipse.edc.spi.system.ServiceExtension   # IatpPatchExtension, PolicyEvaluationExtension
└── src/test/java/org/eclipse/dse/iam/{core,policy}/
```

Dependencies (`build.gradle.kts`): `spi/common-spi`, `spi/telemetry-service-spi`, `spi/federated-catalog-filter-spi` (all `api`, since `CatalogDiscoveryPolicyContext` and the telemetry request-context type cross module boundaries), plus `edc.spi.{participant,catalog,contract,core,jwt,jsonld,policy.engine,identitytrust}`.

## Usage

Three policy shapes, matching what `test-transfert-playground.sh --policy-type <type>` (`infrastructure/scripts/lourmel/lib/policies.sh`) generates:

**Membership (any active member):**
```json
{"leftOperand": "eox-policy:Membership", "operator": "odrl:eq", "rightOperand": "active"}
```

**Generic claim (e.g. `full-member` — exact membership type):**
```json
{
  "leftOperand": "eox-policy:GenericClaim.$.MembershipCredential.membership.membershipType",
  "operator": "odrl:eq",
  "rightOperand": "FullMember"
}
```

**Restricted catalog discovery (e.g. `groups-emds`, #595 — list-valued claim):**
```json
{
  "leftOperand": "eox-policy:RestrictedDiscoveryClaim.$.MembershipCredential.groups",
  "operator": "odrl:hasPart",
  "rightOperand": "EMDS"
}
```
Matches a holder whose `MembershipCredential.groups` claim contains `"EMDS"`, whether that claim arrives as a real JSON-LD list, a JSON-LD-collapsed scalar (holder has exactly one group), or — defensively — a still-serialized string (Postgres array literal / JSON array text).

## Error Reporting

There is no `Monitor` logging in this module (the `Monitor` injected into `PolicyEvaluationExtension` is unused) — failures surface exclusively through `PolicyContext.reportProblem(...)`, collected on the context and returned to the caller (negotiation/transfer rejection message, or a dropped catalog entry). Distinct problems reported:

- Unsupported operator for the constraint (`checkOperator`)
- Invalid/unhandled left-operand shape
- Right-operand not equal to `"active"` (`MembershipConstraintFunction`)
- No `"vc"` claim, wrong type, or empty VC list on the `ParticipantAgent` (`getCredentialList`)
- No credential of the expected type found, or no `credentialSubject` on it
- Left-operand path with fewer than two dot-separated segments
- Operator not supported for the claim's actual shape (e.g. `EQ` on a list claim, or anything but `HAS_PART` on a list)
- Malformed `IN` right-operand (not a `List` and not a bracketed array string)

## Wiring / Deployment

Included in the Gradle build (`settings.gradle.kts`) and pulled into every launcher whose runtime needs to evaluate DSE policies:

```kotlin
// launchers/control-plane/control-plane-base/build.gradle.kts
// launchers/federated-catalog/federated-catalog-base/build.gradle.kts
// launchers/federated-catalog-filter/federated-catalog-filter-base/build.gradle.kts
// launchers/telemetry-service/telemetry-service-base/build.gradle.kts
runtimeOnly(project(":extensions:common:policies"))

// core/telemetry-agent-core/build.gradle.kts
implementation(project(":extensions:common:policies"))
```

`ServiceLoader` picks up `IatpPatchExtension` and `PolicyEvaluationExtension` automatically on any of those runtimes; no further configuration required.

## Testing

- `CredentialsUtils`-level coverage lives inside each caller's own test class (no direct test of the utility class in isolation).
- `MembershipConstraintFunctionTest`, `CredentialTypePredicateTest`, `DefaultScopeExtractorTest`, `PolicyEvaluationExtensionTest`, `NonFullMemberDefaultDenyValidatorTest` — one test class per remaining class.
- `JsonPathCredentialConstraintFunctionTest` / `CatalogDiscoveryConstraintFunctionTest` — parameterized `evaluate` matrix (EQ/NEQ/IN/HAS_PART, namespaced claim keys, nested paths) plus dedicated cases for: scalar `HAS_PART` (JSON-LD singleton collapse), list `HAS_PART`, Postgres array-literal string claim, JSON array string claim, empty list-encoded string. The two production classes still differ in their `IN` right-operand parsing (`CatalogDiscoveryConstraintFunction.parseToList` vs `JsonPathCredentialConstraintFunction.parseRightOperandForInOperator`/`parseArrayString`) and left-operand namespace constant, but the claim-evaluation core (`evaluateClaims`/`evaluateField`/`evaluateCollectionField`/`sanitizeClaims`/`sanitizeLeftOperand`) that used to be duplicated between them now lives once in `CredentialsUtils` — the two test classes still test it independently through each subclass's `evaluate`.
- `NonFullMemberDefaultDenyValidatorTest` — calls `apply(Policy, C)` directly against fixture `ParticipantAgent`/`Policy`/VC data (same seam style as the constraint-function tests, no live `PolicyEngine`/DCP presentation); covers FullMember with/without allow, non-FullMember with matching/non-matching (wrong operator, wrong value, nested in a `MultiplicityConstraint`) allow, no-`MembershipCredential` with/without allow, and a malformed `vc` claim.

```bash
./gradlew :extensions:common:policies:test
```

## Functional / Live Testing

Unit tests prove the constraint logic in isolation (in-memory `ParticipantAgent`/`VerifiableCredential` fixtures, no real DCP presentation, no real DB-backed claim). They don't prove that a claim genuinely arrives in the shape the fix assumes once it has gone through real Postgres storage, real VC issuance, and a real DCP presentation exchange. That gap was closed against the `david2107` Lourmel playground.

### Reasoning

`groups` is a Postgres `text[]` column (`membership_attestation.groups`). Upstream's generic `DatabaseAttestationSource` reads it via `ResultSet.getString()`, which returns the raw array-literal text (`{EMDS,TOUR}`), not a list — the exact case `parseListEncodedClaim` exists to handle. To actually exercise that code path (not just the unit-test fixture), the playground's `attestation_definitions.attestation_type` was reset to upstream's plain `"database"` (an earlier attempt at fixing this at the attestation-source layer, via a `dse-database` decorator extension, was removed once the evaluator-side fix made it redundant), the `MembershipCredential`s were re-issued so `groups` would genuinely be re-serialized as the raw Postgres literal, and the real transfer flow was run end to end.

### Scenarios covered

| Scenario | `--policy-type` | Participant | Expected | Result |
|---|---|---|---|---|
| Group match | `groups-emds` | eonax (`groups={EMDS}`) | negotiation FINALIZED, transfer STARTED | passed |
| Group mismatch | `groups-emds` | consumer (`groups={}`) | negotiation rejected on `HAS_PART` constraint | passed — provider log: `Contract offer rejected as invalid: ... Constraint '...groups' HAS_PART 'EMDS'` |
| Unrelated claim path (regression) | `full-member` | consumer (`membershipType=FullMember`) | negotiation passes (exercises the `EQ` branch in the same file, untouched by the #595 fix) | passed |

The group-match transfer initially failed with `No dataplane found` (`TransferProcessManagerImpl`, `UnrecoverableEntityStateException`) — unrelated to this fix: the control-plane pods had just been redeployed (new image tag) and lost their in-memory data-plane registration; `kubectl rollout restart deployment <participant>-dataplane` forced re-registration, after which the transfer completed.

### Result

```bash
sudo bash test-transfert-playground.sh --policy-type groups-emds
sudo bash test-transfert-playground.sh --policy-type full-member
```

All three scenarios above passed against the real deployment, confirming `parseListEncodedClaim` behaves the same live as asserted by the unit tests — the risk the unit tests alone couldn't cover (real Postgres array-literal serialization, real DCP presentation, real policy evaluation against a live negotiation) is now closed.
