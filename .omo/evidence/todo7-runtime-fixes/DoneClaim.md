# DoneClaim — Todo 7 runtime fixes

Status: DONE
Base: `43a4dea08eea733367b10c96332073da9911c608`
Branch: `feat-day/todo7-runtime-fixes`

## Fixed blockers

- B1: `IdentificationRoute` now saves/restores its typed controller snapshot, retry attempt, and operation id. A restored explicit candidate selection is reapplied only when the replayed candidate result contains it. Confirmation is still an explicit user action.
- B1: `IdentificationRegistrationHandoff` now has a typed `Bundle` saver and `PlanteriorNavHost` owns it with `rememberSaveable`, so the confirmed candidate reaches Registration after Activity/NavHost recreation.
- B2: `successFixture()` now returns exactly three deterministic candidates in confidence-descending order: 0.93, 0.67, 0.41.

## Exact production symbols changed

- `feature/identify/.../IdentificationRoute.kt`
  - `IdentificationRoute`
  - private `controllerSaver`
  - private `IdentificationUiState.toBundle`
  - private `Bundle.toIdentificationUiState`
- `app/.../IdentificationRegistrationHandoff.kt`
  - `IdentificationRegistrationHandoff.Saver`
- `app/.../PlanteriorNavHost.kt`
  - `registrationHandoff` initialization only (`remember` -> `rememberSaveable`)
- `app/src/debug/.../DebugIdentificationGateway.kt`
  - private `successFixture`

## IdentificationContracts.kt overlap receipt

- Final changed hunks: **none**.
- `feature/identify/src/main/kotlin/com/planterior/helper/feature/identify/IdentificationContracts.kt` is byte-for-byte unchanged from base.
- Cherry-pick conflict risk with the security worker in this shared file: **none from this commit**.
- No max-three parser/storage validation, App Check, Functions/backend contract, plan, Boulder, ledger, or ULW state changes were made.

## TDD evidence

- RED selected second candidate recreation: `red-selected-candidate.log` — `IdentificationRouteRestorationTest` failed at the restored confirm state.
- RED confirmed handoff recreation: `red-confirmed-handoff.log` — `IdentificationLifecycleRestorationTest` lost the selected candidate text after restoration.
- RED two-candidate fixture: `red-top-three-fixture.log` — expected 3, observed 2.
- GREEN route restoration: `green-selected-candidate.log` — `BUILD SUCCESSFUL`.
- GREEN full affected unit/build gate: `final-green.log` — feature/app unit tests plus app and test APK assembly, `BUILD SUCCESSFUL`.

## Real API recreation evidence

`IdentificationMainActivityTest.selectionAndConfirmedHandoffSurviveRealActivityRecreation` uses exact NavController destination subscriptions, an Activity monitor armed before each recreation, and Compose accessibility semantics. It verifies:

1. all three debug candidates render;
2. the second candidate remains selected after Activity recreation;
3. Registration is not reached before explicit confirmation;
4. the confirmed candidate handoff remains after a second Activity recreation.

- API 29, dedicated `emulator-5620`: `api29-recreation.log` — `OK (1 test)`.
- API 37, dedicated `emulator-5622`: `api37-recreation.log` — `OK (1 test)`.

## Formatting, compile, and lint

- `format.log`: Spotless formatter completed successfully; unrelated formatter drift was reverted before staging.
- `unit-build.log` and `final-green.log`: Kotlin/JVM tests, Android test compilation, debug APK, and androidTest APK succeeded.
- `final-gates.log`: app lint ran and reported two pre-existing unused strings introduced by the base commit (`screen_identification`, `screen_identification_description`). They are outside this frozen scope; no lint suppression or unrelated resource edit was made.

## Cleanup

Task-owned emulators, the temporary API 29 AVD, emulator logs/PID files, and debug journal/exclude entry are removed before commit. The unrelated emulator on port 5554 is untouched.
