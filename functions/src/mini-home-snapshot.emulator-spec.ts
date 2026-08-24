import assert from "node:assert/strict";
import test from "node:test";
import { deleteApp, initializeApp } from "firebase-admin/app";
import {
  Timestamp,
  getFirestore,
  type DocumentSnapshot,
} from "firebase-admin/firestore";
import { FirestoreInventoryStore } from "./firestore-inventory-store.js";
import { FirestoreMiniHomeLayoutStore } from "./firestore-mini-home-store.js";
import { FirestoreCatalogProjectionStore } from "./firestore-mini-home-projection.js";
import { FirestoreMiniHomeSnapshotStore } from "./firestore-mini-home-snapshot-store.js";
import { executeAcquireInventoryItem } from "./inventory.js";
import { executeLoadMiniHomeSnapshot, MiniHomeSnapshotError } from "./mini-home-snapshot.js";
import { executeSaveMiniHomeLayout } from "./mini-home.js";

const projectId = process.env.GCLOUD_PROJECT ?? "demo-planterior";
const ownerUid = "snapshot-owner";
const readRequest = { expectedOwnerUid: ownerUid };
const at = Timestamp.fromDate(new Date("2026-08-20T00:00:00Z"));

// Reproduces the historical multi-call gap while one writer stages every changed domain.
test("one pointer swap publishes coherent layout inventory and catalog projections", async () => {
  const app = initializeApp({ projectId }, "mini-home-snapshot-race");
  const firestore = getFirestore(app);
  let staged!: () => void;
  let release!: () => void;
  const projectionStaged = new Promise<void>((resolve) => { staged = resolve; });
  const allowPointerSwap = new Promise<void>((resolve) => { release = resolve; });
  try {
    await clear(firestore);
    await seedItem(firestore, "decor-a");
    const initialStore = new FirestoreMiniHomeLayoutStore(firestore);
    assert.deepEqual(
      await executeSaveMiniHomeLayout(
        { uid: ownerUid },
        saveRequest(0, "snapshot-layout-save-0001", [placement("decor-a")]),
        initialStore,
      ),
      { kind: "applied", revision: 1 },
    );
    const pointerRef = firestore.doc(`users/${ownerUid}/miniHomeProjectionPointers/current`);
    const oldProjectionId = (await pointerRef.get()).get("projectionId") as string;
    const oldProjectionRef = firestore.doc(`users/${ownerUid}/miniHomeProjections/${oldProjectionId}`);
    const oldProjection = await oldProjectionRef.get();

    await new FirestoreCatalogProjectionStore(firestore, () => at)
      .update("decor-a", { description: "revision two", revision: 2, updatedAt: at });
    const writer = new FirestoreMiniHomeLayoutStore(firestore, {
      beforePointerSwap: async () => {
        staged();
        await allowPointerSwap;
      },
    });
    const write = executeSaveMiniHomeLayout(
      { uid: ownerUid },
      saveRequest(1, "snapshot-layout-save-0002", []),
      writer,
    );
    await projectionStaged;

    assert.equal((await pointerRef.get()).get("projectionId"), oldProjectionId);
    assert.equal(oldProjection.get("layout").revision, 1);
    assert.equal(oldProjection.get("owned")[0].applied, true);

    release();
    await write;
    const after = await executeLoadMiniHomeSnapshot(
      { uid: ownerUid },
      readRequest,
      new FirestoreMiniHomeSnapshotStore(firestore),
    );
    const afterOwned = after.inventory.owned.find((item) => item.itemId === "decor-a");
    assert.equal(after.layout.kind, "present");
    if (after.layout.kind === "present") assert.equal(after.layout.revision, 2);
    assert.equal(afterOwned?.applied, false);
    assert.equal(afterOwned?.catalogSnapshot?.catalogRevision, 2);
    assert.equal(after.inventory.catalog[0]?.revision, 2);
    assert.notEqual(after.snapshotToken, oldProjection.get("projectionToken"));
    assert.equal(after.snapshotGeneration, oldProjection.get("generation") + 1);
    assert.deepEqual((await oldProjectionRef.get()).data(), oldProjection.data());
  } finally {
    release?.();
    await clear(firestore);
    await deleteApp(app);
  }
});

test("published projection is immutable and a missing owner pointer bootstraps from bounded source", async () => {
  const app = initializeApp({ projectId }, "mini-home-projection-pointer");
  const firestore = getFirestore(app);
  try {
    await clear(firestore);
    await seedItem(firestore, "decor-a");
    const layoutStore = new FirestoreMiniHomeLayoutStore(firestore);
    assert.deepEqual(
      await executeSaveMiniHomeLayout(
        { uid: ownerUid },
        saveRequest(0, "projection-layout-save-0001", [placement("decor-a")]),
        layoutStore,
      ),
      { kind: "applied", revision: 1 },
    );
    const pointerRef = firestore.doc(`users/${ownerUid}/miniHomeProjectionPointers/current`);
    const pointer = await pointerRef.get();
    assert.equal(pointer.exists, true);
    const projectionId = pointer.get("projectionId") as string;
    const projectionRef = firestore.doc(`users/${ownerUid}/miniHomeProjections/${projectionId}`);
    const original = await projectionRef.get();
    assert.equal(original.exists, true);
    assert.equal((await firestore.collection(`users/${ownerUid}/miniHomeProjections`).get()).size, 1);

    await executeSaveMiniHomeLayout(
      { uid: ownerUid },
      saveRequest(1, "projection-layout-save-0002", []),
      layoutStore,
    );
    assert.deepEqual((await projectionRef.get()).data(), original.data());
    assert.equal((await firestore.collection(`users/${ownerUid}/miniHomeProjections`).get()).size, 2);
    const pointerAfterSecondSave = (await pointerRef.get()).data();
    await executeSaveMiniHomeLayout(
      { uid: ownerUid },
      saveRequest(1, "projection-layout-save-0002", []),
      layoutStore,
    );
    assert.deepEqual((await pointerRef.get()).data(), pointerAfterSecondSave);
    assert.equal((await firestore.collection(`users/${ownerUid}/miniHomeProjections`).get()).size, 2);

    await pointerRef.delete();
    const bootstrapped = await executeLoadMiniHomeSnapshot(
      { uid: ownerUid },
      readRequest,
      new FirestoreMiniHomeSnapshotStore(firestore),
    );
    assert.equal(bootstrapped.layout.kind, "present");
    if (bootstrapped.layout.kind === "present") assert.equal(bootstrapped.layout.revision, 2);
    assert.equal((await pointerRef.get()).exists, true);
    await verifyExactOwnerOrphanReuse();
    await verifyCorruptOwnerOrphanRejection();
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

async function verifyExactOwnerOrphanReuse(): Promise<void> {
  const app = initializeApp({ projectId }, "owner-orphan-reuse");
  const firestore = getFirestore(app);
  const pointerRef = firestore.doc(`users/${ownerUid}/miniHomeProjectionPointers/current`);
  try {
    await clear(firestore);
    const store = new FirestoreMiniHomeSnapshotStore(firestore, { now: () => at });
    const original = await executeLoadMiniHomeSnapshot({ uid: ownerUid }, readRequest, store);
    const pointer = await pointerRef.get();
    const projectionId = pointer.get("projectionId") as string;
    const projectionRef = firestore.doc(`users/${ownerUid}/miniHomeProjections/${projectionId}`);
    const originalProjection = await projectionRef.get();

    await pointerRef.delete();
    const repaired = await executeLoadMiniHomeSnapshot({ uid: ownerUid }, readRequest, store);

    assert.equal(repaired.snapshotToken, original.snapshotToken);
    assert.equal(repaired.snapshotGeneration, 1);
    assert.equal((await pointerRef.get()).get("projectionId"), projectionId);
    assert.deepEqual((await projectionRef.get()).data(), originalProjection.data());
    assert.equal((await firestore.collection(`users/${ownerUid}/miniHomeProjections`).get()).size, 1);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
}

async function verifyCorruptOwnerOrphanRejection(): Promise<void> {
  const app = initializeApp({ projectId }, "owner-orphan-corrupt");
  const firestore = getFirestore(app);
  const pointerRef = firestore.doc(`users/${ownerUid}/miniHomeProjectionPointers/current`);
  try {
    await clear(firestore);
    const store = new FirestoreMiniHomeSnapshotStore(firestore, { now: () => at });
    await executeLoadMiniHomeSnapshot({ uid: ownerUid }, readRequest, store);
    const pointer = await pointerRef.get();
    const projectionRef = firestore.doc(
      `users/${ownerUid}/miniHomeProjections/${pointer.get("projectionId") as string}`,
    );
    const projection = await projectionRef.get();
    const projectionData = projection.data();
    assert.ok(projectionData);
    await projectionRef.set({ ...projectionData, plantCount: 99 });
    await pointerRef.delete();

    await assert.rejects(
      () => executeLoadMiniHomeSnapshot({ uid: ownerUid }, readRequest, store),
      (error: unknown) => error instanceof MiniHomeSnapshotError && error.code === "data-loss",
    );
    assert.equal((await pointerRef.get()).exists, false);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
}

test("projection pointer count and digest corruption fail closed", async () => {
  const app = initializeApp({ projectId }, "mini-home-projection-corruption");
  const firestore = getFirestore(app);
  try {
    await clear(firestore);
    await seedItem(firestore, "decor-a");
    assert.deepEqual(
      await executeSaveMiniHomeLayout(
        { uid: ownerUid },
        saveRequest(0, "corruption-layout-save-0001", [placement("decor-a")]),
        new FirestoreMiniHomeLayoutStore(firestore),
      ),
      { kind: "applied", revision: 1 },
    );
    const snapshotStore = new FirestoreMiniHomeSnapshotStore(firestore);
    const pointerRef = firestore.doc(`users/${ownerUid}/miniHomeProjectionPointers/current`);
    const pointer = await pointerRef.get();
    const pointerData = pointer.data();
    assert.ok(pointerData);
    const projectionRef = firestore.doc(
      `users/${ownerUid}/miniHomeProjections/${pointer.get("projectionId") as string}`,
    );
    const projection = await projectionRef.get();
    const projectionData = projection.data();
    assert.ok(projectionData);
    const catalogRef = firestore.doc(
      `catalogProjections/${pointer.get("catalogProjectionId") as string}`,
    );
    const catalog = await catalogRef.get();
    const catalogData = catalog.data();
    assert.ok(catalogData);

    for (const corrupt of [
      async () => pointerRef.set({ ...pointerData, catalogToken: "b".repeat(64) }),
      async () => projectionRef.set({ ...projectionData, ownedCount: 99 }),
      async () => catalogRef.set({ ...catalogData, catalogToken: "c".repeat(64) }),
    ]) {
      await corrupt();
      await assert.rejects(
        () => executeLoadMiniHomeSnapshot({ uid: ownerUid }, readRequest, snapshotStore),
        (error: unknown) => error instanceof MiniHomeSnapshotError && error.code === "data-loss",
      );
      await pointerRef.set(pointerData);
      await projectionRef.set(projectionData);
      await catalogRef.set(catalogData);
    }
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("completed owner publication atomically advances the pointer to an immutable generation", async () => {
  const app = initializeApp({ projectId }, "mini-home-projection-linearization");
  const firestore = getFirestore(app);
  try {
    await clear(firestore);
    await seedItem(firestore, "decor-a");
    const layoutStore = new FirestoreMiniHomeLayoutStore(firestore);
    assert.deepEqual(
      await executeSaveMiniHomeLayout(
        { uid: ownerUid },
        saveRequest(0, "linearization-layout-save-0001", [placement("decor-a")]),
        layoutStore,
      ),
      { kind: "applied", revision: 1 },
    );
    const pointerRef = firestore.doc(`users/${ownerUid}/miniHomeProjectionPointers/current`);
    const oldPointer = await pointerRef.get();
    const oldProjectionId = oldPointer.get("projectionId") as string;
    const oldProjectionRef = firestore.doc(`users/${ownerUid}/miniHomeProjections/${oldProjectionId}`);
    const oldProjection = await oldProjectionRef.get();
    const oldSnapshot = await executeLoadMiniHomeSnapshot(
      { uid: ownerUid },
      readRequest,
      new FirestoreMiniHomeSnapshotStore(firestore),
    );
    assert.equal(oldSnapshot.layout.kind, "present");
    if (oldSnapshot.layout.kind === "present") assert.equal(oldSnapshot.layout.revision, 1);
    assert.equal(oldSnapshot.snapshotGeneration, oldPointer.get("generation"));
    assert.equal(oldSnapshot.snapshotToken, oldPointer.get("projectionToken"));

    await executeSaveMiniHomeLayout(
      { uid: ownerUid },
      saveRequest(1, "linearization-layout-save-0002", []),
      layoutStore,
    );

    const newPointer = await pointerRef.get();
    const newProjectionId = newPointer.get("projectionId") as string;
    assert.notEqual(newProjectionId, oldProjectionId);
    const newProjection = await firestore
      .doc(`users/${ownerUid}/miniHomeProjections/${newProjectionId}`)
      .get();
    assert.equal(newProjection.exists, true);
    assert.equal(newPointer.get("generation"), newProjection.get("generation"));
    assert.equal(newPointer.get("projectionToken"), newProjection.get("projectionToken"));
    const newSnapshot = await executeLoadMiniHomeSnapshot(
      { uid: ownerUid },
      readRequest,
      new FirestoreMiniHomeSnapshotStore(firestore),
    );
    assert.equal(newSnapshot.layout.kind, "present");
    if (newSnapshot.layout.kind === "present") assert.equal(newSnapshot.layout.revision, 2);
    assert.equal(newSnapshot.snapshotGeneration, newPointer.get("generation"));
    assert.equal(newSnapshot.snapshotToken, newPointer.get("projectionToken"));
    assert.deepEqual((await oldProjectionRef.get()).data(), oldProjection.data());
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("catalog publication swaps an immutable pointer and the owner remains bound until an owner writer", async () => {
  const app = initializeApp({ projectId }, "mini-home-catalog-projection");
  const firestore = getFirestore(app);
  try {
    await clear(firestore);
    await seedItem(firestore, "decor-a");
    const layoutStore = new FirestoreMiniHomeLayoutStore(firestore);
    assert.deepEqual(
      await executeSaveMiniHomeLayout(
        { uid: ownerUid },
        saveRequest(0, "catalog-layout-save-0001", [placement("decor-a")]),
        layoutStore,
      ),
      { kind: "applied", revision: 1 },
    );
    const before = await executeLoadMiniHomeSnapshot(
      { uid: ownerUid },
      readRequest,
      new FirestoreMiniHomeSnapshotStore(firestore),
    );
    const catalogStore = new FirestoreCatalogProjectionStore(firestore, () => at);
    await catalogStore.update("decor-a", { description: "revision two", revision: 2, updatedAt: at });
    const reboundOnLoad = await executeLoadMiniHomeSnapshot(
      { uid: ownerUid },
      readRequest,
      new FirestoreMiniHomeSnapshotStore(firestore),
    );
    assert.notEqual(reboundOnLoad.snapshotToken, before.snapshotToken);
    assert.equal(reboundOnLoad.inventory.catalog[0]?.revision, 2);
    assert.equal(reboundOnLoad.inventory.owned.length, before.inventory.owned.length);

    await executeSaveMiniHomeLayout(
      { uid: ownerUid },
      saveRequest(1, "catalog-layout-save-0002", []),
      layoutStore,
    );
    const rebound = await executeLoadMiniHomeSnapshot(
      { uid: ownerUid },
      readRequest,
      new FirestoreMiniHomeSnapshotStore(firestore),
    );
    assert.equal(rebound.inventory.catalog[0]?.revision, 2);
    assert.equal(rebound.layout.kind, "present");
    if (rebound.layout.kind === "present") assert.equal(rebound.layout.revision, 2);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("snapshot rebuilds absent state, preserves deleted ownership, and enforces one hundred item bound", async () => {
  const app = initializeApp({ projectId }, "mini-home-snapshot-bounds");
  const firestore = getFirestore(app);
  const store = new FirestoreMiniHomeSnapshotStore(firestore, { now: () => at });
  try {
    await clear(firestore);
    const missing = await executeLoadMiniHomeSnapshot({ uid: ownerUid }, readRequest, store);
    assert.equal(missing.layout.kind, "missing");
    assert.equal(missing.inventory.catalog.length, 0);
    assert.equal(missing.snapshotGeneration, 1);
    assert.equal((await firestore.doc(`users/${ownerUid}/miniHomeProjectionPointers/current`).get()).exists, true);
    assert.equal((await firestore.doc("catalogProjectionPointers/current").get()).exists, true);

    await seedCatalog(firestore, "deleted-item");
    const catalogStore = new FirestoreCatalogProjectionStore(firestore, () => at);
    await catalogStore.update("deleted-item", {});
    await executeAcquireInventoryItem(
      { uid: ownerUid },
      {
        expectedOwnerUid: ownerUid,
        itemId: "deleted-item",
        expectedCatalogRevision: 1,
        operationId: "deleted-item-acquire-0001",
      },
      new FirestoreInventoryStore(firestore, () => at),
    );
    await catalogStore.delete("deleted-item");
    await new FirestoreInventoryStore(firestore, () => at).load(ownerUid);
    const deleted = await executeLoadMiniHomeSnapshot({ uid: ownerUid }, readRequest, store);
    assert.equal(deleted.inventory.owned[0]?.availability, "UNAVAILABLE");
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

function saveRequest(expectedRevision: number, idempotencyKey: string, placements: ReturnType<typeof placement>[]) {
  return { expectedOwnerUid: ownerUid, miniHomeId: "home-a", expectedRevision, idempotencyKey, name: "Atomic home", placements };
}

function placement(itemId: string) {
  return { placementId: `placement-${itemId}`, plantId: null, itemId, normalizedX: 0.1, normalizedY: 0.125, zIndex: 0 };
}

function plant(plantId: string) {
  return { ownerUid, displayName: plantId, representativePhotoPath: null, revision: 1, updatedAt: at };
}

async function seedCatalog(firestore: ReturnType<typeof getFirestore>, itemId: string) {
  const published = exactCatalog(firestore, [[itemId, 1]]);
  await published.ready;
  const digest = "a".repeat(64);
  try {
    await firestore.doc(`shopItems/${itemId}`).set({
      name: itemId,
      description: `Catalog ${itemId}`,
      category: "DECORATION",
      assetPath: `catalog-assets/${itemId}/${digest}.webp`,
      assetSha256: digest,
      assetContentType: "image/webp",
      assetByteSize: 3,
      assetWidth: 96,
      assetHeight: 64,
      assetMediaRevision: 1,
      acquisitionCondition: null,
      publicationState: "PUBLIC",
      revision: 1,
      updatedAt: at,
    });
    await published.value;
  } finally {
    published.close();
  }
}

async function seedItem(firestore: ReturnType<typeof getFirestore>, itemId: string) {
  await seedCatalog(firestore, itemId);
  await firestore.doc(`users/${ownerUid}/ownedItems/${itemId}`).set({
    ownerUid,
    itemId,
    acquiredAt: at,
    applied: false,
    nameSnapshot: itemId,
    categorySnapshot: "DECORATION",
    assetPathSnapshot: `catalog-assets/${itemId}/${"a".repeat(64)}.webp`,
    assetSha256Snapshot: "a".repeat(64),
    assetByteSizeSnapshot: 3,
    assetMimeTypeSnapshot: "image/webp",
    assetWidthSnapshot: 96,
    assetHeightSnapshot: 64,
    assetMediaRevisionSnapshot: 1,
    catalogRevisionSnapshot: 1,
    revision: 1,
    expectedRevision: 0,
    idempotencyKey: `seed-${itemId}`,
    updatedAt: at,
  });
}

function exactCatalog(
  firestore: ReturnType<typeof getFirestore>,
  expected: readonly (readonly [itemId: string, revision: number])[],
): Readonly<{
  ready: Promise<void>;
  value: Promise<DocumentSnapshot>;
  close: () => void;
}> {
  const reference = firestore.doc("catalogProjectionPointers/current");
  let readyResolve!: () => void;
  let valueResolve!: (snapshot: DocumentSnapshot) => void;
  let valueReject!: (error: unknown) => void;
  let readyObserved = false;
  let settled = false;
  const ready = new Promise<void>((resolve) => { readyResolve = resolve; });
  const value = new Promise<DocumentSnapshot>((resolve, reject) => {
    valueResolve = resolve;
    valueReject = reject;
  });
  const timeout = setTimeout(() => {
    if (!settled) valueReject(new Error("Timed out waiting for exact catalog projection"));
  }, 10_000);
  timeout.unref();
  const unsubscribe = reference.onSnapshot(
    (snapshot) => {
      if (!readyObserved) {
        readyObserved = true;
        readyResolve();
      }
      if (settled) return;
      void isExactCatalog(firestore, snapshot, expected)
        .then((matches) => {
          if (!settled && matches) {
            settled = true;
            clearTimeout(timeout);
            valueResolve(snapshot);
          }
        })
        .catch((error: unknown) => {
          if (!settled) {
            settled = true;
            clearTimeout(timeout);
            valueReject(error);
          }
        });
    },
    (error) => {
      if (!settled) {
        settled = true;
        clearTimeout(timeout);
        valueReject(error);
      }
    },
  );
  return {
    ready,
    value,
    close: () => {
      clearTimeout(timeout);
      unsubscribe();
    },
  };
}

async function isExactCatalog(
  firestore: ReturnType<typeof getFirestore>,
  pointer: DocumentSnapshot,
  expected: readonly (readonly [itemId: string, revision: number])[],
): Promise<boolean> {
  if (
    !pointer.exists ||
    pointer.get("itemCount") !== expected.length ||
    pointer.get("rejectedCount") !== 0 ||
    pointer.get("partial") !== false
  ) return false;
  const projectionId = pointer.get("projectionId");
  if (typeof projectionId !== "string") return false;
  const projection = await firestore.doc(`catalogProjections/${projectionId}`).get();
  if (
    !projection.exists ||
    projection.get("projectionId") !== projectionId ||
    projection.get("generation") !== pointer.get("generation") ||
    projection.get("catalogToken") !== pointer.get("catalogToken") ||
    projection.get("itemCount") !== expected.length ||
    projection.get("rejectedCount") !== 0 ||
    projection.get("partial") !== false
  ) return false;
  const catalog = projection.get("catalog");
  if (!Array.isArray(catalog)) return false;
  const actual = catalog.map((item: unknown) => {
    if (item === null || typeof item !== "object") return null;
    const record = item as Readonly<Record<string, unknown>>;
    return [record.itemId, record.revision] as const;
  });
  return JSON.stringify(actual) === JSON.stringify(expected);
}

async function clear(firestore: ReturnType<typeof getFirestore>) {
  const empty = exactCatalog(firestore, []);
  await empty.ready;
  try {
    await firestore.recursiveDelete(firestore.collection("shopItems"));
    await new FirestoreCatalogProjectionStore(firestore, () => at).rebuild();
    await empty.value;
  } finally {
    empty.close();
  }

  await firestore.recursiveDelete(firestore.doc(`users/${ownerUid}`));
  const [layouts, pointer] = await Promise.all([
    firestore.collection(`users/${ownerUid}/miniHomes`).limit(1).get(),
    firestore.doc(`users/${ownerUid}/miniHomeProjectionPointers/current`).get(),
  ]);
  assert.equal(layouts.empty, true);
  assert.equal(pointer.exists, false);
}
