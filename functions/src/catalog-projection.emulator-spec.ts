import assert from "node:assert/strict";
import test from "node:test";
import { deleteApp, initializeApp } from "firebase-admin/app";
import {
  Timestamp,
  getFirestore,
  type DocumentSnapshot,
} from "firebase-admin/firestore";
import { subscribeToExactSnapshot } from "./exact-snapshot-test-harness.js";
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

    const created = exactCatalog(firestore, [["live-a", 1]], 0, false);
    await created.ready;
    await firestore.doc("shopItems/live-a").set(catalogItem("live-a", 1));
    const createPointer = await created.value;
    created.close();
    assertProjection(createPointer, 1, 0, false);

    const before = await loadSnapshot(firestore);
    assert.equal((await pointerRef.get()).exists, true);
    assert.equal(before.inventory.catalog[0]?.revision, 1);
    assert.equal(before.inventory.owned.length, 0);

    const updated = exactCatalog(firestore, [["live-a", 2]], 0, false);
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

    const eligibilityChanged = exactCatalog(firestore, [["live-a", 3]], 0, false);
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

    const added = exactCatalog(firestore, [["live-a", 3], ["live-b", 1]], 0, false);
    await added.ready;
    await firestore.doc("shopItems/live-b").set(catalogItem("live-b", 1));
    const addPointer = await added.value;
    added.close();
    assertProjection(addPointer, 2, 0, false);
    assert.deepEqual(
      (await loadSnapshot(firestore)).inventory.catalog.map((item) => item.itemId).sort(),
      ["live-a", "live-b"],
    );

    const deleted = exactCatalog(firestore, [["live-b", 1]], 0, false);
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

    const malformed = exactCatalog(firestore, [], 1, true);
    await malformed.ready;
    await firestore.doc("shopItems/broken-a").set({
      ...catalogItem("broken-a", 1),
      assetSha256: "not-a-digest",
    });
    const malformedPointer = await malformed.value;
    malformed.close();
    assertProjection(malformedPointer, 0, 1, true);

    const repaired = exactCatalog(firestore, [["broken-a", 2]], 0, false);
    await repaired.ready;
    await firestore.doc("shopItems/broken-a").set(catalogItem("broken-a", 2, "repaired"));
    const repairedPointer = await repaired.value;
    repaired.close();
    assertProjection(repairedPointer, 1, 0, false);

    const secondMalformed = exactCatalog(firestore, [["broken-a", 2]], 1, true);
    await secondMalformed.ready;
    await firestore.doc("shopItems/broken-b").set({
      ...catalogItem("broken-b", 1),
      assetWidth: 0,
    });
    await secondMalformed.value;
    secondMalformed.close();

    const twoRejected = exactCatalog(firestore, [["broken-a", 2]], 2, true);
    await twoRejected.ready;
    await firestore.doc("shopItems/broken-c").set({
      ...catalogItem("broken-c", 1),
      name: "",
    });
    const twoRejectedPointer = await twoRejected.value;
    twoRejected.close();
    assertProjection(twoRejectedPointer, 1, 2, true);

    const oneRejected = exactCatalog(firestore, [["broken-a", 2]], 1, true);
    await oneRejected.ready;
    await firestore.doc("shopItems/broken-b").delete();
    await oneRejected.value;
    oneRejected.close();

    const removed = exactCatalog(firestore, [["broken-a", 2]], 0, false);
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
    const initial = exactCatalog(firestore, [["race-a", 1]], 0, false);
    await initial.ready;
    await firestore.doc("shopItems/race-a").set(catalogItem("race-a", 1));
    await initial.value;
    initial.close();
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
    const latest = exactCatalog(firestore, [["race-a", 2]], 0, false);
    await latest.ready;
    const sourceUpdate = firestore.doc("shopItems/race-a").set(
      catalogItem("race-a", 2, "latest"),
    );
    release();
    await Promise.all([blocked, sourceUpdate, latest.value]);
    latest.close();
    await new FirestoreCatalogProjectionStore(firestore, () => at).rebuild();

    const after = await firestore.doc(pointerPath).get();
    assert.equal(after.get("generation") >= before.get("generation") + 1, true);
    const projection = await firestore.doc(`catalogProjections/${after.get("projectionId") as string}`).get();
    assert.equal(projection.get("catalog")[0].revision, 2);
    assert.equal(projection.get("catalog")[0].description, "latest");
    assert.equal(attempts >= 1, true);

    const stableGeneration = after.get("generation");
    const stableToken = after.get("catalogToken");
    await Promise.all(Array.from({ length: 32 }, () =>
      new FirestoreCatalogProjectionStore(firestore, () => at).rebuild(),
    ));
    const stable = await firestore.doc(pointerPath).get();
    assert.equal(stable.get("projectionId"), after.get("projectionId"));
    assert.equal(stable.get("generation"), stableGeneration);
    assert.equal(stable.get("catalogToken"), stableToken);
    assert.equal(await pointerHasCatalog(firestore, stable, [["race-a", 2]], 0, false), true);
  } finally {
    release?.();
    await clearCatalog(firestore);
    await deleteApp(app);
  }
});

test("trigger-backed rebuild publishes exactly one hundred items", async () => {
  const app = initializeApp({ projectId }, "catalog-trigger-bound");
  const firestore = getFirestore(app);
  const store = new FirestoreCatalogProjectionStore(firestore, () => at);
  try {
    await clearCatalog(firestore);
    const expected = Array.from({ length: 100 }, (_, index) =>
      [`bound-${index.toString().padStart(3, "0")}`, 1] as const,
    );
    const published = exactCatalog(firestore, expected, 0, false);
    await published.ready;
    const batch = firestore.batch();
    for (const [itemId] of expected) {
      batch.set(firestore.doc(`shopItems/${itemId}`), catalogItem(itemId, 1));
    }
    await batch.commit();
    await store.rebuild();
    const pointer = await published.value;
    published.close();
    assertProjection(pointer, 100, 0, false);
  } finally {
    await clearCatalog(firestore);
    await deleteApp(app);
  }
});

function exactCatalog(
  firestore: ReturnType<typeof getFirestore>,
  expected: readonly (readonly [itemId: string, revision: number])[],
  rejectedCount: number,
  partial: boolean,
): Readonly<{
  ready: Promise<void>;
  value: Promise<DocumentSnapshot>;
  close: () => void;
}> {
  const reference = firestore.doc(pointerPath);
  return subscribeToExactSnapshot({
    label: reference.path,
    subscribe: (onSnapshot, onError) => reference.onSnapshot(onSnapshot, onError),
    matches: (snapshot) => pointerHasCatalog(
      firestore,
      snapshot,
      expected,
      rejectedCount,
      partial,
    ),
  });
}

async function pointerHasCatalog(
  firestore: ReturnType<typeof getFirestore>,
  pointer: DocumentSnapshot,
  expected: readonly (readonly [itemId: string, revision: number])[],
  rejectedCount: number,
  partial: boolean,
): Promise<boolean> {
  if (
    !pointer.exists ||
    pointer.get("itemCount") !== expected.length ||
    pointer.get("rejectedCount") !== rejectedCount ||
    pointer.get("partial") !== partial
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
    projection.get("rejectedCount") !== rejectedCount ||
    projection.get("partial") !== partial
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
  const empty = exactCatalog(firestore, [], 0, false);
  await empty.ready;
  // Delete trigger sources first, then retain the canonical empty pointer so
  // every already-dispatched event is an idempotent rebuild.
  await firestore.recursiveDelete(firestore.collection("shopItems"));
  await new FirestoreCatalogProjectionStore(firestore, () => at).rebuild();
  await empty.value;
  empty.close();
  await firestore.recursiveDelete(firestore.collection("users"));
}
