import assert from "node:assert/strict";
import test from "node:test";
import { deleteApp, initializeApp } from "firebase-admin/app";
import {
  Timestamp,
  getFirestore,
  type DocumentReference,
  type DocumentSnapshot,
} from "firebase-admin/firestore";
import { FirestoreCatalogProjectionStore } from "./firestore-mini-home-projection.js";
import { FirestoreMiniHomeSnapshotStore } from "./firestore-mini-home-snapshot-store.js";
import { executeLoadMiniHomeSnapshot } from "./mini-home-snapshot.js";

const projectId = process.env.GCLOUD_PROJECT ?? "demo-planterior";
const ownerUid = "catalog-trigger-owner";
const at = Timestamp.fromDate(new Date("2026-08-20T00:00:00Z"));
const pointerPath = "catalogProjectionPointers/current";

test("catalog create update and delete triggers publish and snapshot load rebinds the owner", async () => {
  const app = initializeApp({ projectId }, "catalog-trigger-crud");
  const firestore = getFirestore(app);
  const pointerRef = firestore.doc(pointerPath);
  try {
    await clearCatalog(firestore);

    const created = exactDocument(pointerRef, (snapshot) =>
      snapshot.exists && snapshot.get("itemCount") === 1 && snapshot.get("partial") === false,
    );
    await created.ready;
    await firestore.doc("shopItems/live-a").set(catalogItem("live-a", 1));
    const createPointer = await created.value;
    created.close();
    assertProjection(createPointer, 1, 0, false);

    await firestore.recursiveDelete(firestore.collection("catalogProjectionPointers"));
    await firestore.recursiveDelete(firestore.collection("catalogProjections"));
    const before = await loadSnapshot(firestore);
    assert.equal((await pointerRef.get()).exists, true);
    assert.equal(before.inventory.catalog[0]?.revision, 1);
    assert.equal(before.inventory.owned.length, 0);

    const bootstrappedPointer = await pointerRef.get();
    const updated = exactDocument(pointerRef, (snapshot) =>
      snapshot.exists &&
      snapshot.get("generation") === bootstrappedPointer.get("generation") + 1 &&
      snapshot.get("catalogToken") !== bootstrappedPointer.get("catalogToken"),
    );
    await updated.ready;
    await firestore.doc("shopItems/live-a").set(catalogItem("live-a", 2, "updated"));
    const updatePointer = await updated.value;
    updated.close();
    assertProjection(updatePointer, 1, 0, false);

    const rebound = await loadSnapshot(firestore);
    assert.equal(rebound.inventory.catalog[0]?.revision, 2);
    assert.equal(rebound.inventory.catalog[0]?.description, "updated");
    assert.equal(rebound.inventory.owned.length, 0);
    assert.equal(rebound.snapshotGeneration, before.snapshotGeneration + 1);

    const eligibilityChanged = exactDocument(pointerRef, (snapshot) =>
      snapshot.exists && snapshot.get("generation") === updatePointer.get("generation") + 1,
    );
    await eligibilityChanged.ready;
    await firestore.doc("shopItems/live-a").set({
      ...catalogItem("live-a", 3, "updated"),
      acquisitionCondition: "registered-plant",
    });
    const eligibilityPointer = await eligibilityChanged.value;
    eligibilityChanged.close();
    assertProjection(eligibilityPointer, 1, 0, false);
    assert.equal(
      (await loadSnapshot(firestore)).inventory.catalog[0]?.acquisitionCondition,
      "registered-plant",
    );

    const added = exactDocument(pointerRef, (snapshot) =>
      snapshot.exists && snapshot.get("itemCount") === 2,
    );
    await added.ready;
    await firestore.doc("shopItems/live-b").set(catalogItem("live-b", 1));
    const addPointer = await added.value;
    added.close();
    assertProjection(addPointer, 2, 0, false);
    assert.deepEqual(
      (await loadSnapshot(firestore)).inventory.catalog.map((item) => item.itemId).sort(),
      ["live-a", "live-b"],
    );

    const deleted = exactDocument(pointerRef, (snapshot) =>
      snapshot.exists && snapshot.get("itemCount") === 1,
    );
    await deleted.ready;
    await firestore.doc("shopItems/live-a").delete();
    const deletePointer = await deleted.value;
    deleted.close();
    assertProjection(deletePointer, 1, 0, false);
    assert.deepEqual(
      (await loadSnapshot(firestore)).inventory.catalog.map((item) => item.itemId),
      ["live-b"],
    );
  } finally {
    await clearCatalog(firestore);
    await deleteApp(app);
  }
});

test("malformed public items set an exact rejected count and repair or deletion clears partial", async () => {
  const app = initializeApp({ projectId }, "catalog-trigger-partial-repair");
  const firestore = getFirestore(app);
  const pointerRef = firestore.doc(pointerPath);
  try {
    await clearCatalog(firestore);

    const malformed = exactDocument(pointerRef, (snapshot) =>
      snapshot.exists && snapshot.get("partial") === true && snapshot.get("rejectedCount") === 1,
    );
    await malformed.ready;
    await firestore.doc("shopItems/broken-a").set({
      ...catalogItem("broken-a", 1),
      assetSha256: "not-a-digest",
    });
    const malformedPointer = await malformed.value;
    malformed.close();
    assertProjection(malformedPointer, 0, 1, true);

    const repaired = exactDocument(pointerRef, (snapshot) =>
      snapshot.exists && snapshot.get("partial") === false && snapshot.get("itemCount") === 1,
    );
    await repaired.ready;
    await firestore.doc("shopItems/broken-a").set(catalogItem("broken-a", 2, "repaired"));
    const repairedPointer = await repaired.value;
    repaired.close();
    assertProjection(repairedPointer, 1, 0, false);

    const secondMalformed = exactDocument(pointerRef, (snapshot) =>
      snapshot.exists && snapshot.get("partial") === true && snapshot.get("rejectedCount") === 1,
    );
    await secondMalformed.ready;
    await firestore.doc("shopItems/broken-b").set({
      ...catalogItem("broken-b", 1),
      assetWidth: 0,
    });
    await secondMalformed.value;
    secondMalformed.close();

    const twoRejected = exactDocument(pointerRef, (snapshot) =>
      snapshot.exists && snapshot.get("partial") === true && snapshot.get("rejectedCount") === 2,
    );
    await twoRejected.ready;
    await firestore.doc("shopItems/broken-c").set({
      ...catalogItem("broken-c", 1),
      name: "",
    });
    const twoRejectedPointer = await twoRejected.value;
    twoRejected.close();
    assertProjection(twoRejectedPointer, 1, 2, true);

    const oneRejected = exactDocument(pointerRef, (snapshot) =>
      snapshot.exists && snapshot.get("partial") === true && snapshot.get("rejectedCount") === 1,
    );
    await oneRejected.ready;
    await firestore.doc("shopItems/broken-b").delete();
    await oneRejected.value;
    oneRejected.close();

    const removed = exactDocument(pointerRef, (snapshot) =>
      snapshot.exists && snapshot.get("partial") === false && snapshot.get("rejectedCount") === 0,
    );
    await removed.ready;
    await firestore.doc("shopItems/broken-c").delete();
    const removedPointer = await removed.value;
    removed.close();
    assertProjection(removedPointer, 1, 0, false);
  } finally {
    await clearCatalog(firestore);
    await deleteApp(app);
  }
});

test("rebuild retries against source changes and concurrent or old events converge idempotently", async () => {
  const app = initializeApp({ projectId }, "catalog-trigger-convergence");
  const firestore = getFirestore(app);
  let sourceRead!: () => void;
  let release!: () => void;
  const firstSourceRead = new Promise<void>((resolve) => { sourceRead = resolve; });
  const allowRebuild = new Promise<void>((resolve) => { release = resolve; });
  let attempts = 0;
  try {
    await clearCatalog(firestore);
    await firestore.doc("shopItems/race-a").set(catalogItem("race-a", 1));
    await new FirestoreCatalogProjectionStore(firestore, () => at).rebuild();
    const before = await firestore.doc(pointerPath).get();

    const blocked = new FirestoreCatalogProjectionStore(firestore, () => at, {
      afterSourceRead: async () => {
        attempts += 1;
        if (attempts === 1) {
          sourceRead();
          await allowRebuild;
        }
      },
    }).rebuild();
    await firstSourceRead;
    const sourceUpdate = firestore.doc("shopItems/race-a").set(
      catalogItem("race-a", 2, "latest"),
    );
    release();
    await Promise.all([blocked, sourceUpdate]);
    await new FirestoreCatalogProjectionStore(firestore, () => at).rebuild();

    const after = await firestore.doc(pointerPath).get();
    assert.equal(after.get("generation") >= before.get("generation") + 1, true);
    const projection = await firestore.doc(`catalogProjections/${after.get("projectionId") as string}`).get();
    assert.equal(projection.get("catalog")[0].revision, 2);
    assert.equal(projection.get("catalog")[0].description, "latest");
    assert.equal(attempts >= 1, true);

    const stableGeneration = after.get("generation");
    const stableToken = after.get("catalogToken");
    await firestore.doc("shopItems/race-a").set(catalogItem("race-a", 2, "latest"));
    await new FirestoreCatalogProjectionStore(firestore, () => at).rebuild();
    await Promise.all(Array.from({ length: 8 }, () =>
      new FirestoreCatalogProjectionStore(firestore, () => at).rebuild(),
    ));
    await new FirestoreCatalogProjectionStore(firestore, () => at).rebuild();
    const stable = await firestore.doc(pointerPath).get();
    assert.equal(stable.get("generation"), stableGeneration);
    assert.equal(stable.get("catalogToken"), stableToken);
    assert.equal(
      (await firestore.collection("catalogProjections").where("catalogToken", "==", stableToken).get()).size,
      1,
    );
  } finally {
    release?.();
    await clearCatalog(firestore);
    await deleteApp(app);
  }
});

test("pointer-missing rebuild bootstraps up to one hundred items and rejects the one hundred first", async () => {
  const app = initializeApp({ projectId }, "catalog-trigger-bound");
  const firestore = getFirestore(app);
  const store = new FirestoreCatalogProjectionStore(firestore, () => at);
  try {
    await clearCatalog(firestore);
    const batch = firestore.batch();
    for (let index = 0; index < 100; index += 1) {
      const itemId = `bound-${index.toString().padStart(3, "0")}`;
      batch.set(firestore.doc(`shopItems/${itemId}`), catalogItem(itemId, 1));
    }
    await batch.commit();
    await store.rebuild();
    const pointer = await firestore.doc(pointerPath).get();
    assertProjection(pointer, 100, 0, false);

    await firestore.doc("shopItems/bound-100").set(catalogItem("bound-100", 1));
    await assert.rejects(
      () => store.rebuild(),
      (error: unknown) =>
        error instanceof Error && "code" in error && error.code === "resource-exhausted",
    );
    const unchanged = await firestore.doc(pointerPath).get();
    assert.equal(unchanged.get("projectionId"), pointer.get("projectionId"));
    assert.equal(unchanged.get("generation"), pointer.get("generation"));
  } finally {
    await clearCatalog(firestore);
    await deleteApp(app);
  }
});

function exactDocument(
  reference: DocumentReference,
  predicate: (snapshot: DocumentSnapshot) => boolean,
): Readonly<{
  ready: Promise<void>;
  value: Promise<DocumentSnapshot>;
  close: () => void;
}> {
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
  const timer = setTimeout(() => {
    if (!settled) valueReject(new Error(`Timed out waiting for ${reference.path}`));
  }, 10_000);
  const unsubscribe = reference.onSnapshot(
    (snapshot) => {
      if (!readyObserved) {
        readyObserved = true;
        readyResolve();
      }
      if (!settled && predicate(snapshot)) {
        settled = true;
        clearTimeout(timer);
        valueResolve(snapshot);
      }
    },
    (error) => {
      if (!settled) {
        settled = true;
        clearTimeout(timer);
        valueReject(error);
      }
    },
  );
  return {
    ready,
    value,
    close: () => {
      clearTimeout(timer);
      unsubscribe();
    },
  };
}

function assertProjection(
  pointer: DocumentSnapshot,
  itemCount: number,
  rejectedCount: number,
  partial: boolean,
): void {
  assert.equal(pointer.exists, true);
  assert.equal(pointer.get("itemCount"), itemCount);
  assert.equal(pointer.get("rejectedCount"), rejectedCount);
  assert.equal(pointer.get("partial"), partial);
  assert.match(pointer.get("catalogToken"), /^[a-f0-9]{64}$/);
}

function catalogItem(itemId: string, revision: number, description = `Catalog ${itemId}`) {
  const digest = revision === 1 ? "a".repeat(64) : "b".repeat(64);
  return {
    name: itemId,
    description,
    category: "DECORATION",
    assetPath: `catalog-assets/${itemId}/${digest}.webp`,
    assetSha256: digest,
    assetContentType: "image/webp",
    assetByteSize: 3,
    assetWidth: 96,
    assetHeight: 64,
    assetMediaRevision: revision,
    acquisitionCondition: null,
    publicationState: "PUBLIC",
    revision,
    updatedAt: at,
  };
}

async function loadSnapshot(firestore: ReturnType<typeof getFirestore>) {
  return executeLoadMiniHomeSnapshot(
    { uid: ownerUid },
    { expectedOwnerUid: ownerUid },
    new FirestoreMiniHomeSnapshotStore(firestore, { now: () => at }),
  );
}

async function clearCatalog(firestore: ReturnType<typeof getFirestore>): Promise<void> {
  await firestore.recursiveDelete(firestore.collection("users"));
  await firestore.recursiveDelete(firestore.collection("shopItems"));
  await new FirestoreCatalogProjectionStore(firestore, () => at).rebuild();
  await firestore.recursiveDelete(firestore.collection("catalogProjectionPointers"));
  await firestore.recursiveDelete(firestore.collection("catalogProjections"));
}
