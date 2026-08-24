import assert from "node:assert/strict";
import test from "node:test";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { Timestamp, getFirestore } from "firebase-admin/firestore";
import { FirestoreCatalogProjectionStore } from "./firestore-mini-home-projection.js";
import { FirestoreMiniHomeLayoutStore } from "./firestore-mini-home-store.js";
import {
  executeDeleteMiniHomeLayout,
  executeSaveMiniHomeLayout,
  MiniHomeError,
} from "./mini-home.js";

const projectId = process.env.GCLOUD_PROJECT ?? "demo-planterior";
const acquiredAt = Timestamp.fromDate(new Date("2026-08-12T00:00:00Z"));

test("mini-home save atomically preserves ownership while applying and removing an acquired item", async () => {
  const app = initializeApp({ projectId }, "inventory-mini-home-applied");
  const firestore = getFirestore(app);
  try {
    await clear(firestore);
    await seedItem(firestore, "decor-a", "DECORATION");
    const store = new FirestoreMiniHomeLayoutStore(firestore);
    const applied = request([placement("decor-a", 0)]);
    assert.deepEqual(await executeSaveMiniHomeLayout({ uid: "user-a" }, applied, store), { kind: "applied", revision: 1 });
    let owned = await firestore.doc("users/user-a/ownedItems/decor-a").get();
    assert.equal(owned.get("applied"), true);
    assert.equal(owned.get("revision"), 2);
    assert.equal((await firestore.doc("users/user-a/inventoryStates/current").get()).get("generation"), 1);

    const removed = { ...request([]), expectedRevision: 1, idempotencyKey: "inventory-layout-save-0002" };
    assert.deepEqual(await executeSaveMiniHomeLayout({ uid: "user-a" }, removed, store), { kind: "applied", revision: 2 });
    owned = await firestore.doc("users/user-a/ownedItems/decor-a").get();
    assert.equal(owned.exists, true);
    assert.equal(owned.get("applied"), false);
    assert.equal(owned.get("revision"), 3);
    assert.equal((await firestore.doc("users/user-a/inventoryStates/current").get()).get("generation"), 2);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("replacement derives every applied flag from the full layout and leaves no stale ownership drift", async () => {
  const app = initializeApp({ projectId }, "inventory-mini-home-replace");
  const firestore = getFirestore(app);
  try {
    await clear(firestore);
    await Promise.all([
      seedItem(firestore, "decor-a", "DECORATION"),
      seedItem(firestore, "decor-b", "DECORATION"),
      seedItem(firestore, "legacy-stale", "FURNITURE", "user-a", true),
    ]);
    const store = new FirestoreMiniHomeLayoutStore(firestore);
    await executeSaveMiniHomeLayout({ uid: "user-a" }, request([placement("decor-a", 0)]), store);

    const replacement = {
      ...request([placement("decor-b", 0)]),
      expectedRevision: 1,
      idempotencyKey: "inventory-layout-replace-0002",
    };
    assert.deepEqual(
      await executeSaveMiniHomeLayout({ uid: "user-a" }, replacement, store),
      { kind: "applied", revision: 2 },
    );
    const owned = await firestore.collection("users/user-a/ownedItems").get();
    assert.deepEqual(
      Object.fromEntries(owned.docs.map((document) => [document.id, document.get("applied")])),
      { "decor-a": false, "decor-b": true, "legacy-stale": false },
    );
    await firestore.doc("users/user-a/ownedItems/decor-a").update({ applied: true });
    assert.deepEqual(
      await executeSaveMiniHomeLayout({ uid: "user-a" }, replacement, store),
      { kind: "duplicate", revision: 2 },
    );
    assert.equal((await firestore.doc("users/user-a/ownedItems/decor-a").get()).get("applied"), false);
    assert.equal((await firestore.doc("users/user-a/inventoryStates/current").get()).get("generation"), 3);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("delete clears prior and stale applied items, preserves ownership, replays, and isolates accounts", async () => {
  const app = initializeApp({ projectId }, "inventory-mini-home-delete");
  const firestore = getFirestore(app);
  try {
    await clear(firestore);
    await Promise.all([
      seedItem(firestore, "decor-a", "DECORATION"),
      seedItem(firestore, "stale-a", "FURNITURE", "user-a", true),
      seedItem(firestore, "foreign", "DECORATION", "user-b", true),
    ]);
    const store = new FirestoreMiniHomeLayoutStore(firestore);
    await executeSaveMiniHomeLayout({ uid: "user-a" }, request([placement("decor-a", 0)]), store);
    const deletion = {
      expectedOwnerUid: "user-a",
      expectedGeneration: 1,
      tombstoneId: "inventory-layout-delete-0001",
    };
    const expected = { kind: "deleted" as const, generation: 2, tombstoneId: deletion.tombstoneId };
    assert.deepEqual(await executeDeleteMiniHomeLayout({ uid: "user-a" }, deletion, store), expected);
    assert.equal((await firestore.doc("users/user-a/ownedItems/decor-a").get()).get("applied"), false);
    assert.equal((await firestore.doc("users/user-a/ownedItems/stale-a").get()).get("applied"), false);
    assert.equal((await firestore.doc("users/user-b/ownedItems/foreign").get()).get("applied"), true);
    assert.equal((await firestore.doc("users/user-a/ownedItems/decor-a").get()).exists, true);

    await firestore.doc("users/user-a/ownedItems/stale-a").update({ applied: true });
    assert.deepEqual(await executeDeleteMiniHomeLayout({ uid: "user-a" }, deletion, store), expected);
    assert.equal((await firestore.doc("users/user-a/ownedItems/stale-a").get()).get("applied"), false);
    assert.equal((await firestore.doc("users/user-a/inventoryStates/current").get()).get("generation"), 3);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("malformed ownership aborts replacement without partially changing layout or flags", async () => {
  const app = initializeApp({ projectId }, "inventory-mini-home-atomic-failure");
  const firestore = getFirestore(app);
  try {
    await clear(firestore);
    await Promise.all([
      seedItem(firestore, "decor-a", "DECORATION"),
      seedItem(firestore, "decor-b", "DECORATION"),
      seedItem(firestore, "malformed", "FURNITURE", "user-a", true),
    ]);
    const store = new FirestoreMiniHomeLayoutStore(firestore);
    await executeSaveMiniHomeLayout({ uid: "user-a" }, request([placement("decor-a", 0)]), store);
    await firestore.doc("users/user-a/ownedItems/malformed").update({ revision: "bad" });
    await assert.rejects(
      () => executeSaveMiniHomeLayout(
        { uid: "user-a" },
        {
          ...request([placement("decor-b", 0)]),
          expectedRevision: 1,
          idempotencyKey: "inventory-layout-failure-0002",
        },
        store,
      ),
      (error: unknown) => error instanceof MiniHomeError && error.details?.field === "ownedItems",
    );
    assert.equal((await firestore.doc("users/user-a/miniHomes/home-a").get()).get("revision"), 1);
    assert.equal((await firestore.doc("users/user-a/ownedItems/decor-a").get()).get("applied"), true);
    assert.equal((await firestore.doc("users/user-a/ownedItems/decor-b").get()).get("applied"), false);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("mini-home transaction enforces one background ten furniture and ten decoration limits", async () => {
  for (const [category, count] of [["BACKGROUND", 2], ["FURNITURE", 11], ["DECORATION", 11]] as const) {
    const app = initializeApp({ projectId }, `inventory-limit-${category.toLowerCase()}`);
    const firestore = getFirestore(app);
    try {
      await clear(firestore);
      const itemIds = Array.from({ length: count }, (_, index) => `${category.toLowerCase()}-${index}`);
      await Promise.all(itemIds.map((itemId) => seedItem(firestore, itemId, category)));
      const placements = itemIds.map((itemId, index) => placement(itemId, index)).sort(depthOrder).map((item, index) => ({ ...item, zIndex: index }));
      await assert.rejects(
        () => executeSaveMiniHomeLayout({ uid: "user-a" }, request(placements), new FirestoreMiniHomeLayoutStore(firestore)),
        (error: unknown) => error instanceof MiniHomeError && error.details?.field === "placements",
      );
      assert.equal((await firestore.collection("users/user-a/placements").get()).size, 0);
      const owned = await firestore.collection("users/user-a/ownedItems").get();
      assert.equal(owned.docs.filter((document) => document.get("applied") === true).length, 0);
    } finally {
      await clear(firestore);
      await deleteApp(app);
    }
  }
});

function request(placements: ReturnType<typeof placement>[]) {
  return {
    expectedOwnerUid: "user-a",
    miniHomeId: "home-a",
    expectedRevision: 0,
    idempotencyKey: "inventory-layout-save-0001",
    name: "나의 미니 식물원",
    placements,
  };
}

function placement(itemId: string, index: number) {
  const column = index % 5;
  const row = Math.floor(index / 5);
  return {
    placementId: `placement-${itemId}`,
    plantId: null,
    itemId,
    normalizedX: (column + 0.5) / 5,
    normalizedY: (row + 0.5) / 4,
    zIndex: index,
  };
}

function depthOrder(left: ReturnType<typeof placement>, right: ReturnType<typeof placement>) {
  const depth = left.normalizedX + left.normalizedY - right.normalizedX - right.normalizedY;
  if (depth !== 0) return depth;
  const horizontal = left.normalizedX - left.normalizedY - (right.normalizedX - right.normalizedY);
  if (horizontal !== 0) return horizontal;
  return left.placementId.localeCompare(right.placementId);
}

async function seedItem(
  firestore: ReturnType<typeof getFirestore>,
  itemId: string,
  category: "BACKGROUND" | "FURNITURE" | "DECORATION",
  ownerUid = "user-a",
  applied = false,
) {
  await firestore.doc(`users/${ownerUid}/ownedItems/${itemId}`).set({
    ownerUid,
    itemId,
    acquiredAt,
    applied,
    revision: 1,
    expectedRevision: 0,
    idempotencyKey: `acquire-${itemId}`,
    updatedAt: acquiredAt,
  });
  const digest = "a".repeat(64);
  await firestore.doc(`shopItems/${itemId}`).set({
    name: itemId,
    description: `Catalog fixture ${itemId}`,
    category,
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
    updatedAt: acquiredAt,
  });
}

async function clear(firestore: ReturnType<typeof getFirestore>) {
  await firestore.recursiveDelete(firestore.collection("shopItems"));
  await new FirestoreCatalogProjectionStore(firestore, () => acquiredAt).rebuild();
  await firestore.recursiveDelete(firestore.collection("users"));
}
