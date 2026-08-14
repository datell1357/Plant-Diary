# DoneClaim: Todo 7 release photo handoff

## Claim

The release camera path now awaits an authenticated, owner/request-scoped pre-identification handoff before navigating to identification. Explicit disclosure approval remains the only trigger for photo read, Storage upload, Firestore request creation, or later callable invocation.

## Contract delivered

- Uploads the approved app-private bytes to `identification-originals/{ownerUid}/{requestId}/original.{mime}` using the existing `StorageContract`.
- Writes `users/{ownerUid}/identificationRequests/{requestId}` with the existing `IdentificationRequestDto` envelope and Firestore `Timestamp` values.
- Carries owner/request/expiry Storage metadata and an exact 24-hour request expiry.
- Derives ownership only from authenticated Firebase state; unauthenticated and mismatched existing-owner requests fail before upload/create.
- Serializes and memoizes the owner/request handoff so double approval/submission performs one upload and one request write.
- Converts photo, upload, and request backend errors to typed sanitized failures; the camera exposes only `SubmissionFailed`.
- Keeps debug handoff local/no-op so existing deterministic debug identification fixtures remain external-network-free.
- Leaves the Function schema unchanged; the production request store retrieves the created request and Storage bytes before the injected local provider transport is invoked.

## TDD and verification receipts

- RED: `01-red-approved-handoff.log` / `.status` - missing handoff symbols and timestamp contract failed compilation before implementation.
- Targeted Android GREEN: `17-final-targeted-android.log` - app handoff, camera disclosure/double-submit, and identify unit suites.
- Functions GREEN: `07-functions-node22-unit.log` - 30 Node 22 tests, including missing request => `not_found`, provider calls 0.
- Executable local emulator GREEN: `19-final-local-emulator-handoff.log` - real Firestore request retrieval, real Storage byte/metadata retrieval, 24-hour expiry, no external Plant.id transport; missing request remains `not_found` with provider calls 0.
- Ownership rules GREEN: `10-firebase-rules.log` - authenticated owner succeeds; unauthenticated and foreign Firestore/Storage access fails.
- Release/debug and strict dependencies GREEN: `14-strict-debug-release-apk.log`.
- Release auth/signing contract GREEN: `12-release-auth-contract.log`.
- Touched-source formatting GREEN: `16-touched-format.log`; TypeScript strict/no-excuse GREEN: `18-final-typescript.log`.
- APK signature/secret scan GREEN: `15-secret-apk-scan.log`.
- Owned resources cleaned: `19-final-local-emulator-cleanup.txt` and `21-cleanup-receipt.txt`.

## Scope and architecture review

No plan, Boulder, ledger, ULW state, unrelated routes, top-3 behavior, App Check behavior, or lifecycle UI was changed. New production files remain under 200 pure LOC and each owns one concern: handoff orchestration or Firebase release adaptation. Inputs become typed owner/request/DTO values at the boundary; no type suppression, fixed sleeps, polling, or external provider call was introduced.
