import assert from "node:assert/strict";
import test from "node:test";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { Timestamp, getFirestore, type Firestore, type Transaction } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { ACCOUNT_DELETION_GRACE_MILLIS } from "./account-deletion-contract.js";
import { requestAccountDeletion } from "./account-deletion-service.js";
import { FirebaseAccountDeletionCleaner } from "./firebase-account-deletion-cleaner.js";
import { FirestoreAccountDeletionStore } from "./firestore-account-deletion-store.js";
import { FirestoreCatalogProjectionStore } from "./firestore-mini-home-projection.js";
import { FirestoreMiniHomeShareStore } from "./firestore-mini-home-share-store.js";
import { FirestoreMiniHomeLayoutStore } from "./firestore-mini-home-store.js";
import {
  MINI_HOME_SHARE_LIFETIME_MILLIS,
  MiniHomeShareError,
  executeCreateMiniHomeShareLink,
  executeRevokeMiniHomeShareLink,
} from "./mini-home-share.js";
import { executeSaveMiniHomeLayout } from "./mini-home.js";

const projectId = "demo-planterior";
const ownerUid = "share-owner";
const tokenKey = "emulator-mini-home-share-token-key-1234567890";
const at = Timestamp.fromDate(new Date("2026-08-22T00:00:00.000Z"));
const publicEndpoint = "https://example.test/publicMiniHomeShare";

function placement(x = 0.1) {
  return {
    placementId: "private-placement-id",
    plantId: "private-plant-id",
    itemId: null,
    normalizedX: x,
    normalizedY: 0.125,
    zIndex: 0,
  };
}

function exactSignal(): Readonly<{ wait: Promise<void>; emit: () => void }> {
  let emitSignal: (() => void) | undefined;
  const wait = new Promise<void>((resolve, reject) => {
    const safety = setTimeout(
      () => reject(new Error("Timed out waiting for exact test signal")),
      10_000,
    );
    emitSignal = () => {
      clearTimeout(safety);
      resolve();
    };
  });
  return {
    wait,
    emit: () => {
      if (emitSignal === undefined) throw new TypeError("Signal is not subscribed");
      emitSignal();
    },
  };
}

function pauseTransactionAfterRead(
  firestore: Firestore,
  path: string,
  paused: ReturnType<typeof exactSignal>,
  release: ReturnType<typeof exactSignal>,
): Firestore {
  let shouldPause = true;
  return new Proxy(firestore, {
    get(target, property) {
      if (property !== "runTransaction") {
        const value = Reflect.get(target, property, target) as unknown;
        return typeof value === "function" ? value.bind(target) : value;
      }
      return async <T>(
        operation: (transaction: Transaction) => Promise<T>,
        options?: Readonly<{ maxAttempts?: number }>,
      ): Promise<T> => target.runTransaction(async (transaction) => {
        const instrumented = new Proxy(transaction, {
          get(transactionTarget, transactionProperty) {
            if (transactionProperty !== "get") {
              const value = Reflect.get(
                transactionTarget,
                transactionProperty,
                transactionTarget,
              ) as unknown;
              return typeof value === "function" ? value.bind(transactionTarget) : value;
            }
            return async (reference: FirebaseFirestore.DocumentReference) => {
              const snapshot = await transactionTarget.get(reference);
              if (shouldPause && reference.path === path) {
                shouldPause = false;
                paused.emit();
                await release.wait;
              }
              return snapshot;
            };
          },
        });
        return operation(instrumented);
      }, options);
    },
  });
}

function saveRequest(expectedRevision: number, operation: string, x = 0.1) {
  return {
    expectedOwnerUid: ownerUid,
    miniHomeId: "private-home-id",
    expectedRevision,
    idempotencyKey: operation,
    name: "Private Mini-home name",
    placements: [placement(x)],
  };
}

async function seedSavedLayout(firestore: ReturnType<typeof getFirestore>) {
  await firestore.doc(`users/${ownerUid}/personalPlants/private-plant-id`).set({
    ownerUid,
    displayName: "Private plant name",
    representativePhotoPath: `plant-photos/${ownerUid}/private-plant-id/private.webp`,
    note: "private note",
    location: "private room",
    revision: 1,
    updatedAt: at,
  });
  await executeSaveMiniHomeLayout(
    { uid: ownerUid },
    saveRequest(0, "share-layout-save-0001"),
    new FirestoreMiniHomeLayoutStore(firestore, { now: () => at }),
  );
}

test("share creation rejects no saved layout and revision mismatch without artifacts", async () => {
  const app = initializeApp({ projectId }, "mini-home-share-preconditions");
  const firestore = getFirestore(app);
  const store = new FirestoreMiniHomeShareStore(firestore);
  try {
    await clear(firestore);
    await assert.rejects(
      () => executeCreateMiniHomeShareLink(
        { uid: ownerUid },
        { operationId: "share-operation-0001", expectedRevision: 1 },
        store,
        tokenKey,
        at.toDate(),
        publicEndpoint,
      ),
      (error: unknown) => error instanceof MiniHomeShareError && error.code === "failed-precondition",
    );
    await seedSavedLayout(firestore);
    await assert.rejects(
      () => executeCreateMiniHomeShareLink(
        { uid: ownerUid },
        { operationId: "share-operation-0001", expectedRevision: 2 },
        store,
        tokenKey,
        at.toDate(),
        publicEndpoint,
      ),
      (error: unknown) =>
        error instanceof MiniHomeShareError &&
        error.code === "failed-precondition" &&
        error.details?.actualRevision === 1,
    );
    assert.equal((await firestore.collection(`users/${ownerUid}/shareLinks`).get()).size, 0);
    assert.equal((await firestore.collection("publicShares").get()).size, 0);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("atomic share lifecycle is idempotent, immutable, private at rest, bounded, and exactly 30 days", async () => {
  const app = initializeApp({ projectId }, "mini-home-share-lifecycle");
  const firestore = getFirestore(app);
  const store = new FirestoreMiniHomeShareStore(firestore);
  try {
    await clear(firestore);
    await seedSavedLayout(firestore);
    const request = { operationId: "share-operation-0001", expectedRevision: 1 };
    const first = await executeCreateMiniHomeShareLink(
      { uid: ownerUid }, request, store, tokenKey, at.toDate(), publicEndpoint,
    );
    const replay = await executeCreateMiniHomeShareLink(
      { uid: ownerUid }, request, store, tokenKey, new Date(at.toMillis() + 1234), publicEndpoint,
    );
    assert.deepEqual(replay, first);
    assert.equal(Date.parse(first.expiresAt) - Date.parse(first.createdAt), MINI_HOME_SHARE_LIFETIME_MILLIS);
    const token = new URL(first.url).searchParams.get("token");
    if (token === null) throw new Error("Share URL token is missing");
    assert.match(token, /^[A-Za-z0-9_-]{43}$/);

    await assert.rejects(
      () => executeCreateMiniHomeShareLink(
        { uid: ownerUid },
        { operationId: request.operationId, expectedRevision: 2 },
        store,
        tokenKey,
        at.toDate(),
        publicEndpoint,
      ),
      (error: unknown) => error instanceof MiniHomeShareError && error.code === "already-exists",
    );
    const distinct = await executeCreateMiniHomeShareLink(
      { uid: ownerUid },
      { operationId: "share-operation-0002", expectedRevision: 1 },
      store,
      tokenKey,
      at.toDate(),
      publicEndpoint,
    );
    assert.notEqual(first.url, distinct.url);
    assert.notEqual(first.shareId, distinct.shareId);

    const metadata = await firestore.doc(`users/${ownerUid}/shareLinks/${first.shareId}`).get();
    const publicShares = await firestore.collection("publicShares").get();
    assert.equal(publicShares.size, 2);
    const tokenHash = metadata.get("tokenHash") as string;
    const publicDocument = await firestore.doc(`publicShares/${tokenHash}`).get();
    const stored = JSON.stringify({ metadata: metadata.data(), public: publicDocument.data() });
    assert.equal(stored.includes(token!), false);
    for (const forbidden of [
      ownerUid,
      "private-home-id",
      "private-placement-id",
      "private-plant-id",
      "Private plant name",
      "private.webp",
      "private note",
      "private room",
      "share-operation-0001",
    ]) assert.equal(JSON.stringify(publicDocument.data()).includes(forbidden), false, forbidden);
    assert.equal(publicDocument.get("snapshot.placements").length, 1);
    const originalSnapshot = publicDocument.get("snapshot");

    await executeSaveMiniHomeLayout(
      { uid: ownerUid },
      saveRequest(1, "share-layout-save-0002", 0.3),
      new FirestoreMiniHomeLayoutStore(firestore, { now: () => Timestamp.fromMillis(at.toMillis() + 10_000) }),
    );
    assert.deepEqual((await firestore.doc(`publicShares/${tokenHash}`).get()).get("snapshot"), originalSnapshot);
    assert.deepEqual(await store.loadPublic(tokenHash, new Date(at.toMillis() + MINI_HOME_SHARE_LIFETIME_MILLIS - 1)), originalSnapshot);
    assert.equal(await store.loadPublic(tokenHash, new Date(at.toMillis() + MINI_HOME_SHARE_LIFETIME_MILLIS)), null);
    assert.equal(await store.loadPublic("f".repeat(64), at.toDate()), null);

    await assert.rejects(
      () => executeRevokeMiniHomeShareLink({ uid: "another-owner" }, { shareId: first.shareId }, store, at.toDate()),
      (error: unknown) => error instanceof MiniHomeShareError && error.code === "not-found",
    );
    const firstRevoke = await executeRevokeMiniHomeShareLink(
      { uid: ownerUid }, { shareId: first.shareId }, store, at.toDate(),
    );
    const replayedRevoke = await executeRevokeMiniHomeShareLink(
      { uid: ownerUid }, { shareId: first.shareId }, store, new Date(at.toMillis() + 20_000),
    );
    assert.deepEqual(firstRevoke, { shareId: first.shareId, revokedAt: at.toDate().toISOString() });
    assert.deepEqual(replayedRevoke, firstRevoke);
    assert.deepEqual(Object.keys(firstRevoke).sort(), ["revokedAt", "shareId"]);
    assert.equal(await store.loadPublic(tokenHash, at.toDate()), null);
    const revokedMetadata = await firestore.doc(`users/${ownerUid}/shareLinks/${first.shareId}`).get();
    const revokedPublic = await firestore.doc(`publicShares/${tokenHash}`).get();
    assert.equal(revokedMetadata.get("revokedAt").toMillis(), revokedPublic.get("revokedAt").toMillis());
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("a share transaction preflighted before deletion cannot commit after PUBLIC_SHARES cleanup", async () => {
  const app = initializeApp({ projectId }, "mini-home-share-deletion-fence");
  const firestore = getFirestore(app);
  const paused = exactSignal();
  const release = exactSignal();
  const operationId = "share-operation-deletion-race-0001";
  const shareStore = new FirestoreMiniHomeShareStore(
    pauseTransactionAfterRead(
      firestore,
      `accountDeletionRequests/${ownerUid}`,
      paused,
      release,
    ),
  );
  const deletionStore = new FirestoreAccountDeletionStore(firestore);
  try {
    await clear(firestore);
    await seedSavedLayout(firestore);
    const creation = executeCreateMiniHomeShareLink(
      { uid: ownerUid },
      { operationId, expectedRevision: 1 },
      shareStore,
      tokenKey,
      at.toDate(),
      publicEndpoint,
    );
    await paused.wait;
    const requestedAt = at.toMillis() - ACCOUNT_DELETION_GRACE_MILLIS;
    const deletionRequest = requestAccountDeletion(
      { uid: ownerUid, authTimeSeconds: requestedAt / 1_000 },
      {
        expectedOwnerUid: ownerUid,
        confirmed: true,
        idempotencyKey: "share-deletion-race-request-0001",
      },
      {
        store: deletionStore,
        nowMillis: () => requestedAt,
        requestId: () => "share-deletion-race-request",
      },
    );
    release.emit();
    await deletionRequest;
    const claimed = await deletionStore.claimDue({
      nowMillis: at.toMillis(),
      leaseExpiresAtMillis: at.toMillis() + 60_000,
      limit: 1,
    });
    assert.equal(claimed.length, 1);
    await new FirebaseAccountDeletionCleaner(
      firestore,
      getStorage(app),
      getAuth(app),
    ).clean(ownerUid, "PUBLIC_SHARES");
    await creation;
    assert.equal((await firestore.collection(`users/${ownerUid}/shareLinks`).get()).size, 1);
    assert.equal((await firestore.collection("publicShares").get()).size, 0);
  } finally {
    release.emit();
    await clear(firestore);
    await deleteApp(app);
  }
});

test("revoke rejects malformed timestamps and a torn public mirror as data loss", async () => {
  const app = initializeApp({ projectId }, "mini-home-share-revoke-malformed");
  const firestore = getFirestore(app);
  const store = new FirestoreMiniHomeShareStore(firestore);
  try {
    await clear(firestore);
    await seedSavedLayout(firestore);
    const share = await executeCreateMiniHomeShareLink(
      { uid: ownerUid },
      { operationId: "share-operation-malformed-0001", expectedRevision: 1 },
      store,
      tokenKey,
      at.toDate(),
      publicEndpoint,
    );
    const metadataRef = firestore.doc(`users/${ownerUid}/shareLinks/${share.shareId}`);
    const tokenHash = (await metadataRef.get()).get("tokenHash") as string;
    const publicRef = firestore.doc(`publicShares/${tokenHash}`);

    await metadataRef.update({ revokedAt: "not-a-timestamp" });
    await assert.rejects(
      () => executeRevokeMiniHomeShareLink({ uid: ownerUid }, { shareId: share.shareId }, store, at.toDate()),
      (error: unknown) => error instanceof MiniHomeShareError && error.code === "data-loss",
    );

    await metadataRef.update({ revokedAt: at });
    await publicRef.update({ state: "REVOKED", revokedAt: Timestamp.fromMillis(at.toMillis() + 1) });
    await assert.rejects(
      () => executeRevokeMiniHomeShareLink({ uid: ownerUid }, { shareId: share.shareId }, store, at.toDate()),
      (error: unknown) => error instanceof MiniHomeShareError && error.code === "data-loss",
    );

    await publicRef.update({ revokedAt: "not-a-timestamp" });
    await assert.rejects(
      () => executeRevokeMiniHomeShareLink({ uid: ownerUid }, { shareId: share.shareId }, store, at.toDate()),
      (error: unknown) => error instanceof MiniHomeShareError && error.code === "data-loss",
    );
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("public read normalizes malformed and torn records to absence", async () => {
  const app = initializeApp({ projectId }, "mini-home-share-malformed");
  const firestore = getFirestore(app);
  const store = new FirestoreMiniHomeShareStore(firestore);
  try {
    await clear(firestore);
    await firestore.doc(`publicShares/${"e".repeat(64)}`).set({
      schemaVersion: 1,
      tokenHash: "e".repeat(64),
      state: "ACTIVE",
      revokedAt: null,
      expiresAt: Timestamp.fromMillis(at.toMillis() + MINI_HOME_SHARE_LIFETIME_MILLIS),
      createdAt: at,
      snapshot: { contractVersion: 1, placements: [] },
    });
    assert.equal(await store.loadPublic("e".repeat(64), at.toDate()), null);

    await seedSavedLayout(firestore);
    await firestore.doc(`users/${ownerUid}/miniHomeProjectionPointers/current`).update({ projectionToken: "f".repeat(64) });
    await assert.rejects(
      () => executeCreateMiniHomeShareLink(
        { uid: ownerUid },
        { operationId: "share-operation-0003", expectedRevision: 1 },
        store,
        tokenKey,
        at.toDate(),
        publicEndpoint,
      ),
      (error: unknown) => error instanceof MiniHomeShareError && error.code === "data-loss",
    );
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

async function clear(firestore: ReturnType<typeof getFirestore>) {
  await firestore.recursiveDelete(firestore.collection("shopItems"));
  await new FirestoreCatalogProjectionStore(firestore, () => at).rebuild();
  await firestore.recursiveDelete(firestore.collection("users"));
  await firestore.recursiveDelete(firestore.collection("publicShares"));
  await firestore.recursiveDelete(firestore.collection("accountDeletionRequests"));
  await firestore.recursiveDelete(firestore.collection("accountDeletionReceipts"));
}
