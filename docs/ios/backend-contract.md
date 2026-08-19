# Provisional Firebase Boundary

## Pin and status

Contract version `android-firebase-boundary/v1` pins Android commit
`8f362c4de2bc76d16875ac80d0c8ad794e950340`. The machine-readable source of truth is
`backend-contract-v1.json`. This boundary is provisional: unavailable integrations and policies
must fail closed and must never be presented as live Firebase completion.

| Android file | SHA-256 |
|---|---|
| `firebase.json` | `6994857f9ae052d2ccdd068c202eec7c26d481e76d3e87cb7cddd69ada48885a` |
| `firestore.indexes.json` | `f06b33aebd2dac2a045754460f935f579cce4dd7aa606c6fffe044870ff569ef` |
| `firestore.rules` | `5347dc93d463d09803c0e4d4cac137779fcf967bbca8bb78e2fbc2a0fa6a41b8` |
| `storage.rules` | `bc8608904da0e171c4e90e5f791c3608e797e08177c89c5eed3c4c806cdf2d35` |
| `functions/src/contracts.ts` | `331506c0ce569a616807a24a5840ce602f1be2848ec1d3e8c1e52427ae3e88c1` |

## Ownership and paths

- Owner documents live below `users/{uid}`. Owners may read/write profile and owner collections.
- `users/{uid}/operations/{operationId}` is trusted-server create-only.
- notification deliveries, weather snapshots/risks, deletion requests, owned items, and share links
  are server-only writes.
- managed content is publicly readable only in `PUBLIC`; content-admin writes it.
- `publicShares` is readable only while public, unrevoked, and unexpired.
- Storage owner prefixes are `identification-originals`, `plant-photos`, and `share-images`.
- Public share images are not anonymously readable at the pin; image resolution remains unavailable.

## Wire and mutation contract

Uppercase enums remain closed. Owner create uses revision 1 and expected revision 0; update requires
the stored revision and increments by one. Receipts are per-user and keyed across collections.
Retrying the same key and document returns the original revision. A stale revision returns conflict
without a write or receipt.

The TypeScript callable validator is the strict payload boundary where it exists. Known differences
with rules and Kotlin are preserved in `compatibilityNotes`, including placement XOR, server
metadata, temporal representation, direct-write receipts, and public-share Storage access.

## Explicitly unavailable

Plant identification, canonical weather refresh, notification registration/delivery, item
acquisition/application, share create/revoke/image resolution, deletion preview/request/cancel/job,
progression approved-event submission/reward claim, and identification cleanup have
`integrationStatus: unavailable`.

The 24-hour identification retention, 3-hour weather staleness, 30-day share lifetime, and 7-day
deletion grace policies are also unavailable until server enforcement exists.

## Verification boundary

Swift Codable tests load a versioned iOS fixture envelope derived from the pinned inline
TypeScript, Kotlin, and emulator fixtures. They round-trip valid owner mutation data, reject
forbidden cases, and exercise duplicate/conflict behavior through a fake. The QA gate independently
recomputes every pinned Git blob digest and runs the pinned Android Firebase emulator suite; iOS
does not modify or replace rules, Functions, indexes, or schemas.
