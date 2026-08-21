# iOS account-deletion backend

Local Firebase Functions package for project `planterior-helper-ios`. This directory is independent
of the preserved `android-app/` tree. Nothing in this package has been deployed by this change.

## Contract

The Node 22, `us-central1` codebase exports:

- `previewAccountDeletion` (callable, authenticated, App Check enforced)
- `requestAccountDeletion` (callable, authenticated, App Check enforced, auth age at most 5 minutes)
- `cancelAccountDeletion` (callable, authenticated, App Check enforced)
- `executeDueAccountDeletions` (scheduled every 15 minutes)

Every callable requires `ownerID == auth.uid`. The canonical v1 scope is owner-bound and SHA-256
hashed over the ordered machine categories. A request is transactionally idempotent while active,
and its immutable schedule is exactly 604,800 seconds after receipt. Cancellation is atomic and is
allowed only while the request is `RECEIVED` and before `scheduledAt`.

The scheduled worker claims due requests with a 10-minute lease. It removes the account-owned user
document tree, global notification/share records selected by exact owner fields, known Storage
prefixes, and finally Firebase Auth. Auth is never removed after an earlier category fails. Failed
categories remain retryable, successful categories are not repeated, and the request receipt stays
outside `users/{uid}` so interrupted cleanup can resume.

## Local verification

```sh
cd firebase-backend/functions
pnpm install --frozen-lockfile
pnpm run verify

cd ..
pnpm --dir functions run build
METADATA_SERVER_DETECTION=none firebase \
  --config firebase.emulator.json \
  --project demo-planterior-ios-deletion \
  emulators:exec --only auth,firestore,functions,pubsub,storage \
  'pnpm --dir functions test:emulator'
```

The emulator project ID starts with `demo-`; Firebase CLI blocks fallback to live services. Emulator
rules deny client access, while Admin SDK adapters exercise real local Firestore, Storage, and Auth.

## External deployment (not run)

Prerequisites:

1. The operator is authenticated to `planterior-helper-ios` and authorized to deploy Functions and
   act as the runtime service account.
2. The project is on Blaze and already has Cloud Functions, Cloud Build, Artifact Registry,
   Eventarc/Pub/Sub, and Cloud Scheduler APIs available. Do not rely on this task to enable them.
3. The runtime service account can read/write Firestore, delete Firebase Auth users, and delete
   objects in the project's default Storage bucket.
4. The default Firestore database, Firebase Auth, default Storage bucket, and matching iOS App Check
   registration exist. Firestore rules must deny direct client access to `accountDeletionRequests`.
5. Run from a clean checkout with Node 22, pnpm 11.9.0, Firebase CLI 15.20.0, and the committed lock.

Exact command:

```sh
cd firebase-backend
pnpm --dir functions install --frozen-lockfile
firebase deploy \
  --config firebase.json \
  --project planterior-helper-ios \
  --only functions:ios-account-deletion
```

## Known limits

- This repository run proves local and emulator behavior only; there is no live deployment,
  Cloud Scheduler execution, production App Check, IAM, bucket, or database receipt.
- Each scan claims at most 20 accounts. A larger due backlog completes over later 15-minute scans.
- v1 deletes only the documented `users/{uid}` tree, `notificationEndpointOwners`, `publicShares`,
  and Storage prefixes `identification-originals/{uid}/`, `plant-photos/{uid}/`, and
  `share-images/{uid}/`. New global collections or buckets must be added to a new scope version.
- Completed operational receipts remain in `accountDeletionRequests`; no retention/TTL policy is
  configured by this functions-only package.
- The current iOS client must observe `COMPLETED` before Auth deletion invalidates its session to run
  its completion-gated local cleanup; this package does not add client polling.
