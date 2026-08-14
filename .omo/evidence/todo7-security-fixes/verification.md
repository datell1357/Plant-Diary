# Verification receipt

All commands ran in `/Users/yeoreum/Documents/Planterior_Helper-worktrees/todo7-security-fixes` on 2026-08-14.

| Gate | Command | Exit / result |
|---|---|---|
| Functions build and tests | `cd functions && npm test` | `0`; 29 passed, 0 failed |
| Functions dependency audit | `cd functions && npm audit --audit-level=high` | `0`; 0 vulnerabilities |
| Android identify/app tests with dependency verification | `./gradlew :feature:identify:testDebugUnitTest :app:testDebugUnitTest --tests 'com.planterior.helper.identify.DebugIdentificationGatewayTest' --tests 'com.planterior.helper.navigation.IdentificationRegistrationHandoffTest' --dependency-verification strict --console=plain` | `0`; BUILD SUCCESSFUL |
| Android changed-domain compilation with dependency verification | `./gradlew :feature:identify:compileDebugKotlin :feature:identify:compileDebugUnitTestKotlin :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --dependency-verification strict --console=plain` | `0`; BUILD SUCCESSFUL |
| Whitespace validation | `git diff --check` | `0` |
| Secret scan | gitleaks when available, otherwise added-line credential-pattern scan | `0`; no findings |
| Pure LOC | changed TS/Kotlin source and tests measured with `awk` | all <=225 pure LOC |

## Behavior locked

- The exported compiled `identifyPlant` HTTP callable rejects missing and verifier-rejected App Check tokens with Firebase `UNAUTHENTICATED` before entering the user handler.
- Stored candidate responses accept 1..3 and reject 4.
- Plant.id suggestions are confidence-sorted, truncated to three, and the exact normalized result is persisted.
- Android callable parsing and `IdentificationResult.Candidates` accept 1..3 and reject 4.

## Validator notes

- LSP diagnostics were unavailable because the workstation LSP daemon socket never became reachable; TypeScript `tsc` and Kotlin/Java Gradle compilation passed instead.
- Repository-wide `spotlessCheck` is pre-existing red in unrelated files, including the prohibited debug candidate fixture and runtime/navigation files. No broad formatter application was retained. The two changed Android tests were formatted through Spotless's IDE-hook target; production hunks remain one-token bound changes and `git diff --check` passes.
- The TypeScript no-excuse checker reports two pre-existing catch-narrowing findings in `functions/src/index.ts` lines unrelated to this change. No suppression or unrelated catch refactor was added.
- `firebase-functions@7.3.2` applies `enforceAppCheck` in the supported HTTP callable wrapper but does not serialize that runtime option into `ManifestEndpoint.callableTrigger`; Firebase CLI 15.20.0 permits only `genkitAction` there. Tests therefore exercise the compiled endpoint's supported HTTP surface rather than adding unsupported manifest metadata.
