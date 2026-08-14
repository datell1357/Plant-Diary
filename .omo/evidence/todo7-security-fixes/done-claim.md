# DoneClaim: Todo 7 security fixes

## Claim

The two Todo 7 runtime contract blockers are closed:

1. `identifyPlant` sets Firebase callable `enforceAppCheck: true`; supported compiled-endpoint HTTP tests prove missing and invalid App Check requests are rejected before the handler.
2. Functions stored-response parsing, Android callable parsing, and the Android candidate value contract enforce a maximum of three candidates while preserving the existing confidence-descending invariant. Provider output remains sorted and truncated before persistence.

## Owned production hunks

- `functions/src/index.ts` — exported symbol `identifyPlant`: added only `enforceAppCheck: true` to its existing `onCall` options.
- `functions/src/plant-identification-runtime.ts` — function `storedResponse`, `case "candidates"`: changed only the upper bound from `> 5` to `> 3`.
- `feature/identify/src/main/kotlin/com/planterior/helper/feature/identify/FirebaseIdentificationGateway.kt` — function `parseCandidates`: changed only `1..5` to `1..3`.
- `feature/identify/src/main/kotlin/com/planterior/helper/feature/identify/IdentificationContracts.kt` — exact overlapping shared-file hunk: `IdentificationResult.Candidates.init`, line `require(candidates.size in 1..5)` changed to `require(candidates.size in 1..3)`.

No lifecycle, saveability, restoration, controller shape, runtime UI/recreation, debug fixture, plan, Boulder, ledger, or ULW state was changed.

## Cherry-pick conflict risk

`IdentificationContracts.kt` is shared with runtime work. Cherry-picking may conflict specifically at `IdentificationResult.Candidates.init` if another commit edited the adjacent confidence-order requirement or candidate constructor. Preserve the incoming `1..3` bound and the existing confidence-descending `zipWithNext` requirement; do not take unrelated lifecycle/restoration changes from this commit because none exist.

## Evidence

- RED: `.omo/evidence/todo7-security-fixes/red-phase.md`
- GREEN and audit gates: `.omo/evidence/todo7-security-fixes/verification.md`
