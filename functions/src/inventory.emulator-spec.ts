import assert from "node:assert/strict";
import test from "node:test";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { Timestamp, getFirestore } from "firebase-admin/firestore";
import { FirestoreInventoryStore } from "./firestore-inventory-store.js";
import { FirestoreMutationStore } from "./firestore-store.js";
import { FirestoreCatalogProjectionStore } from "./firestore-mini-home-projection.js";
import { InventoryError, executeAcquireInventoryItem, executeLoadInventory } from "./inventory.js";

const projectId = process.env.GCLOUD_PROJECT ?? "demo-planterior";
const now = Timestamp.fromDate(new Date("2026-08-12T00:00:00Z"));
const mediaDigest = "a".repeat(64);
const mediaFields = (itemId: string, digest = mediaDigest, mediaRevision = 1) => ({
  assetPath: `catalog-assets/${itemId}/${digest}.webp`,
  assetSha256: digest,
  assetContentType: "image/webp",
  assetByteSize: 3,
  assetWidth: 96,
  assetHeight: 64,
  assetMediaRevision: mediaRevision,
});

async function fixture(name: string) {
  const app = initializeApp({ projectId }, name);
  const firestore = getFirestore(app);
  await clear(firestore);
  await firestore.doc("shopItems/free-background").set({
    name: "햇살 벽지",
    description: "방을 환하게 꾸며요.",
    category: "BACKGROUND",
    ...mediaFields("free-background"),
    acquisitionCondition: null,
    publicationState: "PUBLIC",
    revision: 3,
    updatedAt: now,
  });
  await firestore.doc("shopItems/plant-lamp").set({
    name: "새싹 조명",
    description: "첫 식물과 함께 켜는 조명이에요.",
    category: "DECORATION",
    ...mediaFields("plant-lamp", "b".repeat(64)),
    acquisitionCondition: "registered-plant",
    publicationState: "PUBLIC",
    revision: 4,
    updatedAt: now,
  });
  await firestore.doc("shopItems/private-item").set({
    name: "숨긴 아이템",
    description: "노출하지 않아요.",
    category: "FURNITURE",
    ...mediaFields("private-item", "c".repeat(64)),
    acquisitionCondition: null,
    publicationState: "PRIVATE",
    revision: 1,
    updatedAt: now,
  });
  await new FirestoreCatalogProjectionStore(firestore, () => now).rebuild();
  return { app, firestore, store: new FirestoreInventoryStore(firestore, () => now) };
}

const freeRequest = {
  expectedOwnerUid: "user-a",
  itemId: "free-background",
  expectedCatalogRevision: 3,
  operationId: "inventory-emulator-0001",
};

test("inventory transaction filters public catalog and atomically acquires exactly once", async () => {
  const { app, firestore, store } = await fixture("inventory-emulator-acquire");
  try {
    const loaded = await executeLoadInventory({ uid: "user-a" }, { expectedOwnerUid: "user-a" }, store);
    assert.equal(loaded.contractVersion, 3);
    assert.deepEqual(Object.keys(loaded).sort(), [
      "catalog",
      "contractVersion",
      "inventoryGeneration",
      "loadedAtEpochMillis",
      "owned",
      "ownerUid",
      "partial",
      "registeredPlantCount",
      "snapshotHash",
    ]);
    assert.deepEqual(loaded.catalog.map((item) => item.itemId), ["free-background", "plant-lamp"]);
    const acquired = await executeAcquireInventoryItem({ uid: "user-a" }, freeRequest, store);
    assert.equal(acquired.kind, "acquired");
    assert.deepEqual(await executeAcquireInventoryItem({ uid: "user-a" }, freeRequest, store), acquired);
    const owned = await firestore.collection("users/user-a/ownedItems").get();
    assert.equal(owned.size, 1);
    assert.equal(owned.docs[0]?.get("applied"), false);
    assert.equal(owned.docs[0]?.get("nameSnapshot"), "햇살 벽지");
    assert.equal(owned.docs[0]?.get("categorySnapshot"), "BACKGROUND");
    assert.equal(
      owned.docs[0]?.get("assetPathSnapshot"),
      mediaFields("free-background").assetPath,
    );
    assert.equal(owned.docs[0]?.get("catalogRevisionSnapshot"), 3);
    assert.equal((await firestore.collection("users/user-a/inventoryOperations").get()).size, 1);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("response replay rejects altered item or catalog revision without a second ownership write", async () => {
  const { app, firestore, store } = await fixture("inventory-emulator-replay");
  try {
    await executeAcquireInventoryItem({ uid: "user-a" }, freeRequest, store);
    for (const altered of [
      { ...freeRequest, itemId: "plant-lamp" },
      { ...freeRequest, expectedCatalogRevision: 2 },
    ]) {
      await assert.rejects(
        () => executeAcquireInventoryItem({ uid: "user-a" }, altered, store),
        (error: unknown) => error instanceof InventoryError && error.reason === "IDEMPOTENCY_MISMATCH",
      );
    }
    assert.equal((await firestore.collection("users/user-a/ownedItems").get()).size, 1);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("registered plant condition is authoritative and a new operation can acquire after fulfillment", async () => {
  const { app, firestore, store } = await fixture("inventory-emulator-condition");
  const request = {
    expectedOwnerUid: "user-a",
    itemId: "plant-lamp",
    expectedCatalogRevision: 4,
    operationId: "inventory-condition-0001",
  };
  try {
    assert.deepEqual(await executeAcquireInventoryItem({ uid: "user-a" }, request, store), {
      kind: "condition-not-met",
      ownerUid: "user-a",
      itemId: "plant-lamp",
      catalogRevision: 4,
      condition: "registered-plant",
    });
    await new FirestoreMutationStore(firestore, () => now).applyOwnerMutation({
      ownerUid: "user-a",
      collection: "personalPlants",
      documentId: "plant-a",
      documentPath: "users/user-a/personalPlants/plant-a",
      mutationType: "CREATE",
      expectedRevision: 0,
      idempotencyKey: "plant-create-0001",
      requestHash: "b".repeat(64),
      payload: { displayName: "Plant A", registrationMethod: "MANUAL" },
    });
    const acquired = await executeAcquireInventoryItem(
      { uid: "user-a" },
      { ...request, operationId: "inventory-condition-0002" },
      store,
    );
    assert.equal(acquired.kind, "acquired");
    assert.equal((await firestore.collection("users/user-a/ownedItems").get()).size, 1);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("mixed public unavailable deleted and malformed catalog returns a partial usable inventory", async () => {
  const { app, firestore, store } = await fixture("inventory-emulator-partial");
  try {
    await firestore.doc("shopItems/malformed-public").set({
      name: "깨진 공개 아이템",
      description: "격리되어야 해요.",
      category: "UNKNOWN",
      assetPath: "catalog-assets/malformed-public/preview.webp",
      acquisitionCondition: null,
      publicationState: "PUBLIC",
      revision: 1,
      updatedAt: now,
    });
    await firestore.doc("shopItems/malformed-media").set({
      name: "과도한 공개 이미지",
      description: "디코드 예산을 초과해요.",
      category: "BACKGROUND",
      ...mediaFields("malformed-media"),
      assetWidth: 32768,
      assetHeight: 32768,
      acquisitionCondition: null,
      publicationState: "PUBLIC",
      revision: 1,
      updatedAt: now,
    });
    await Promise.all([
      seedOwnedSnapshot(firestore, "private-item", "숨긴 아이템", "FURNITURE"),
      seedOwnedSnapshot(firestore, "deleted-item", "삭제된 러그", "DECORATION"),
      seedOwnedSnapshot(firestore, "malformed-public", "이전 조명", "DECORATION"),
      firestore.doc("users/user-a/ownedItems/legacy-item").set({
        ownerUid: "user-a",
        itemId: "legacy-item",
        acquiredAt: now,
        applied: true,
        revision: 1,
      }),
    ]);
    await new FirestoreCatalogProjectionStore(firestore, () => now).rebuild();

    const loaded = await executeLoadInventory({ uid: "user-a" }, { expectedOwnerUid: "user-a" }, store);
    assert.equal(loaded.contractVersion, 3);
    assert.equal(loaded.partial, true);
    assert.deepEqual(loaded.catalog.map((item) => item.itemId), ["free-background", "plant-lamp"]);
    assert.equal(loaded.owned.length, 4);
    assert.deepEqual(
      Object.fromEntries(loaded.owned.map((item) => [item.itemId, item.availability])),
      {
        "deleted-item": "UNAVAILABLE",
        "legacy-item": "UNAVAILABLE",
        "malformed-public": "UNAVAILABLE",
        "private-item": "UNAVAILABLE",
      },
    );
    assert.deepEqual(
      loaded.owned.find((item) => item.itemId === "deleted-item")?.catalogSnapshot,
      {
        name: "삭제된 러그",
        category: "DECORATION",
        mediaIdentity: {
          path: mediaFields("deleted-item").assetPath,
          sha256: mediaDigest,
          byteSize: 3,
          mimeType: "image/webp",
          width: 96,
          height: 64,
          mediaRevision: 1,
        },
        catalogRevision: 1,
      },
    );
    assert.equal(
      loaded.owned.find((item) => item.itemId === "legacy-item")?.catalogSnapshot,
      null,
    );
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("owner generation serializes concurrent loads acquisition and catalog representation changes", async () => {
  const { app, firestore, store } = await fixture("inventory-emulator-generation");
  try {
    const initial = await Promise.all(
      Array.from({ length: 8 }, () =>
        executeLoadInventory({ uid: "user-a" }, { expectedOwnerUid: "user-a" }, store)),
    );
    assert.deepEqual(new Set(initial.map((snapshot) => snapshot.inventoryGeneration)), new Set([1]));
    assert.equal(new Set(initial.map((snapshot) => snapshot.snapshotHash)).size, 1);

    const acquired = await executeAcquireInventoryItem({ uid: "user-a" }, freeRequest, store);
    assert.equal(acquired.kind, "acquired");
    assert.equal((await firestore.doc("users/user-a/inventoryStates/current").get()).get("generation"), 2);
    await executeAcquireInventoryItem({ uid: "user-a" }, freeRequest, store);
    assert.equal((await firestore.doc("users/user-a/inventoryStates/current").get()).get("generation"), 2);

    const afterAcquire = await executeLoadInventory(
      { uid: "user-a" },
      { expectedOwnerUid: "user-a" },
      store,
    );
    assert.equal(afterAcquire.inventoryGeneration, 2);
    assert.deepEqual(afterAcquire.owned.map((item) => item.itemId), ["free-background"]);

    await new FirestoreCatalogProjectionStore(firestore, () => Timestamp.fromMillis(now.toMillis() + 1))
      .update("free-background", {
        publicationState: "PRIVATE",
        revision: 4,
        updatedAt: Timestamp.fromMillis(now.toMillis() + 1),
      });
    const afterUnpublish = await Promise.all(
      Array.from({ length: 8 }, () =>
        executeLoadInventory({ uid: "user-a" }, { expectedOwnerUid: "user-a" }, store)),
    );
    assert.deepEqual(new Set(afterUnpublish.map((snapshot) => snapshot.inventoryGeneration)), new Set([3]));
    assert.equal(new Set(afterUnpublish.map((snapshot) => snapshot.snapshotHash)).size, 1);
    assert.equal(afterUnpublish[0]?.owned[0]?.availability, "UNAVAILABLE");

    const otherOwner = await executeLoadInventory(
      { uid: "user-b" },
      { expectedOwnerUid: "user-b" },
      store,
    );
    assert.equal(otherOwner.inventoryGeneration, 1);
    assert.equal(otherOwner.owned.length, 0);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("inactive missing and changed catalog reject before ownership or operation writes", async () => {
  const { app, firestore, store } = await fixture("inventory-emulator-unavailable");
  try {
    for (const request of [
      { ...freeRequest, itemId: "private-item", expectedCatalogRevision: 1 },
      { ...freeRequest, itemId: "missing-item", expectedCatalogRevision: 1 },
      { ...freeRequest, expectedCatalogRevision: 2 },
    ]) {
      await assert.rejects(() => executeAcquireInventoryItem({ uid: "user-a" }, request, store), InventoryError);
    }
    assert.equal((await firestore.collection("users/user-a/ownedItems").get()).size, 0);
    assert.equal((await firestore.collection("users/user-a/inventoryOperations").get()).size, 0);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

async function seedOwnedSnapshot(
  firestore: FirebaseFirestore.Firestore,
  itemId: string,
  name: string,
  category: "BACKGROUND" | "FURNITURE" | "DECORATION",
): Promise<void> {
  const media = mediaFields(itemId);
  await firestore.doc(`users/user-a/ownedItems/${itemId}`).set({
    ownerUid: "user-a",
    itemId,
    acquiredAt: now,
    applied: false,
    revision: 1,
    nameSnapshot: name,
    categorySnapshot: category,
    assetPathSnapshot: media.assetPath,
    assetSha256Snapshot: media.assetSha256,
    assetByteSizeSnapshot: media.assetByteSize,
    assetMimeTypeSnapshot: media.assetContentType,
    assetWidthSnapshot: media.assetWidth,
    assetHeightSnapshot: media.assetHeight,
    assetMediaRevisionSnapshot: media.assetMediaRevision,
    catalogRevisionSnapshot: 1,
  });
}

async function clear(firestore: FirebaseFirestore.Firestore): Promise<void> {
  for (const collection of [
    "shopItems",
    "users",
    "catalogProjectionPointers",
    "catalogProjections",
  ]) {
    await firestore.recursiveDelete(firestore.collection(collection));
  }
}
