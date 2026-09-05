# Planterior iOS Firebase backend

Local Firebase Functions package for project `planterior-helper-ios`. This directory is independent
of the preserved `android-app/` tree. Nothing in this package has been deployed by this change.

## Contract

The Node 22, `us-central1` codebase exports:

- `previewAccountDeletion` (callable, authenticated, App Check enforced)
- `requestAccountDeletion` (callable, authenticated, App Check enforced, auth age at most 5 minutes)
- `cancelAccountDeletion` (callable, authenticated, App Check enforced)
- `recoverAccountDeletion` (callable, opaque request capability, App Check enforced)
- `loadInventory` (callable, authenticated, App Check enforced)
- `acquireInventoryItem` (callable, authenticated, App Check enforced)
- `loadMiniHome` (callable, authenticated, App Check enforced)
- `saveMiniHome` (callable, authenticated, App Check enforced)
- `identifyPlant` (HTTPS POST, authenticated, limited-use App Check enforced)
- `executeDueAccountDeletions` (scheduled every 15 minutes)

Authenticated callables require `ownerID == auth.uid`; recovery instead requires the exact opaque
owner/request capability. The canonical v1 scope is owner-bound and SHA-256 hashed over the ordered
machine categories. A request is transactionally idempotent while active, and its immutable schedule
is exactly 604,800 seconds after receipt. Cancellation is atomic and is allowed only while the request
is `RECEIVED` and before `scheduledAt`.

Inventory v3 is server-owned: public, available catalog documents live at `inventoryCatalog/{itemId}`;
server-only ownership, operation, and state documents live below
`users/{uid}/ownedItems`, `users/{uid}/inventoryOperations`, and `users/{uid}/inventoryState`.
Both inventory callables derive the owner exclusively from `request.auth.uid`, reject a mismatched
`expectedOwnerUid`, and atomically record a SHA-256 snapshot generation plus operation receipt.
`docs/ios/inventory-contract-v3.fixture.json` is consumed by the backend parity test and iOS decoder
tests, pinning callable names, strict v3 snapshot wire keys, both ownership receipt kinds, and the
lowercase `InventorySnapshotHasher` digest.

MiniHome v1 is a focused callable-only CAS authority at `users/{uid}/miniHomes/current`, with
idempotency receipts at `users/{uid}/miniHomeOperations/{operationId}`. Save transactions read the
operation first, reject changed replays, return the exact current snapshot on revision conflict
without writing a receipt, validate personal-plant and owned-item references, and commit a
server-timestamped revision plus lowercase canonical SHA-256. Direct Firestore access remains denied.
The byte-stable encoder and shared `docs/ios/minihome-contract-v1.fixture.json` pin request and
snapshot bytes independently of input placement order.

Plant identification accepts the existing raw JSON body at
`https://us-central1-planterior-helper-ios.cloudfunctions.net/identifyPlant`. The iOS client sends
a Firebase ID token in `Authorization: Bearer ...` and a limited-use App Check token in
`X-Firebase-AppCheck`; the function verifies both before decoding or forwarding the 1–5 private
images. Each normalized image is limited to 4 MB so a five-image base64 request remains below the
Cloud Functions request-size boundary. The image order is part of the idempotency hash.
The Plant.id key is available only through the function-bound `PLANT_ID_API_KEY` Secret Manager
secret. `PLANT_ID_ALLOWED_IOS_APP_ID` must equal the iOS Firebase app's `GOOGLE_APP_ID` deployment
parameter. Owner-scoped Firestore receipts below
`users/{uid}/plantIdentificationOperations/{idempotencyKey}` prevent concurrent duplicate provider
calls and replay completed results without retaining the images. Because Plant.id has no provider-side
idempotency key, a function crash after provider receipt but before Firestore finalization can still
cause a later retry after the lease expires.

The iOS client retains only the pending owner and opaque request IDs. It checks status while
foregrounded and can recover an authoritative completion after Auth deletion without an Auth token.
A mismatched capability reveals no workflow.

The scheduled worker claims due requests with a 10-minute lease. It removes the account-owned user
document tree, global notification/share records selected by exact owner fields, known Storage
prefixes, and finally Firebase Auth. Auth is never removed after an earlier category fails. Failed
categories remain retryable, successful categories are not repeated, and the request receipt stays
outside `users/{uid}` so interrupted cleanup can resume. Completed receipts receive an exact 30-day
expiry and the same scheduled worker removes them at or after that deadline.

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

## Free simulator workflow

The Development iOS configuration targets the local endpoint
`http://127.0.0.1:5201/planterior-helper-ios/us-central1/identifyPlant`. Release stays fail-closed
until a production endpoint is deliberately configured.

One-time local setup:

1. Put the existing Plant.id key in `functions/.secret.local` as
   `PLANT_ID_API_KEY=...`. This file is ignored by Git and must never be committed.
2. Put the Firebase iOS app ID in `functions/.env.local` as
   `PLANT_ID_ALLOWED_IOS_APP_ID=1:399546775599:ios:5e5ec4fdd70816a5b52eda`. Firebase CLI also
   creates this ignored file when the value is entered at its first prompt.
3. Build the Functions package, then start only the local services required by identification:

```sh
cd firebase-backend/functions
pnpm run build

cd ..
METADATA_SERVER_DETECTION=none firebase \
  --config firebase.emulator.json \
  --project planterior-helper-ios \
  emulators:start --only firestore,functions
```

Debug builds use fixed local Auth and App Check markers so simulator testing does not require an
Apple Developer membership, App Attest registration, or a Google account in the simulator. The
backend accepts those markers only when Firebase sets `FUNCTIONS_EMULATOR=true`; deployed Functions
always delegate both values to Firebase Admin verification. A successful live scan still uploads the
selected image batch to Plant.id and consumes a Plant.id credit, so trigger it only with approved test
images.

## External deployment (not run)

Prerequisites:

1. The operator is authenticated to `planterior-helper-ios` and authorized to deploy Functions and
   act as the runtime service account.
2. The project is on Blaze and already has Cloud Functions, Cloud Build, Artifact Registry,
   Eventarc/Pub/Sub, and Cloud Scheduler APIs available. Do not rely on this task to enable them.
3. The runtime service account can read/write Firestore, delete Firebase Auth users, and delete
   objects in the project's default Storage bucket.
4. The default Firestore database, Firebase Auth, default Storage bucket, and matching iOS App Check
   registration exist. Register the Debug App Check token before simulator testing. Firestore rules
   must deny direct client access to `accountDeletionRequests` and `plantIdentificationOperations`.
5. Run from a clean checkout with Node 22, pnpm 11.9.0, Firebase CLI 15.20.0, and the committed lock.
6. Set `PLANT_ID_API_KEY` in Secret Manager and supply `PLANT_ID_ALLOWED_IOS_APP_ID` from the
   external iOS `GoogleService-Info.plist` when Firebase CLI prompts for the deployment parameter.

The following Secret Manager mutation is intentionally an operator step and was not run while
preparing this integration:

```sh
cd firebase-backend
firebase functions:secrets:set PLANT_ID_API_KEY --project planterior-helper-ios
```

Exact command:

```sh
cd firebase-backend
pnpm --dir functions install --frozen-lockfile
firebase deploy \
  --config firebase.json \
  --project planterior-helper-ios \
  --only functions:ios-account-deletion
```

After deployment, use the exact URL printed by Firebase CLI. It must match the pinned iOS endpoint
`https://us-central1-planterior-helper-ios.cloudfunctions.net/identifyPlant` before a live image is
submitted.

## Known limits

- This repository run proves local and emulator behavior only; there is no live deployment,
  Cloud Scheduler execution, production App Check, IAM, bucket, or database receipt.
- Plant.id source wiring is not evidence that the secret, allowed iOS App ID, function, App Check,
  billing quota, or live provider request has been configured successfully.
- Each scan claims at most 20 accounts. A larger due backlog completes over later 15-minute scans.
- v1 deletes only the documented `users/{uid}` tree, `notificationEndpointOwners`, `publicShares`,
  and Storage prefixes `identification-originals/{uid}/`, `plant-photos/{uid}/`, and
  `share-images/{uid}/`. New global collections or buckets must be added to a new scope version.
- Completed operational receipts remain recoverable for 30 days, then scheduled scans permanently
  remove them. This is application-enforced retention rather than a separately configured Firestore
  TTL policy.
