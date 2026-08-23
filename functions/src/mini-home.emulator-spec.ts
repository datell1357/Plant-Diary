import assert from "node:assert/strict";
import test from "node:test";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { Timestamp, getFirestore } from "firebase-admin/firestore";
import { FirestoreMiniHomeLayoutStore } from "./firestore-mini-home-store.js";
import { executeSaveMiniHomeLayout, MiniHomeError } from "./mini-home.js";

const projectId = "demo-planterior";

const request = {
  expectedOwnerUid: "user-a",
  miniHomeId: "home-a",
  expectedRevision: 0,
  idempotencyKey: "mini-home-emulator-0001",
  name: "나의 미니 식물원",
  placements: [
    { placementId: "plant-placement", plantId: "plant-a", itemId: null, normalizedX: 0.1, normalizedY: 0.125, zIndex: 0 },
    { placementId: "decor-placement", plantId: null, itemId: "decor-a", normalizedX: 0.3, normalizedY: 0.125, zIndex: 1 },
  ],
};

test("mini-home transaction persists one owner-scoped revision and replays exactly", async () => {
  const app = initializeApp({ projectId }, "mini-home-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreMiniHomeLayoutStore(firestore);
  try {
    await clear(firestore);
    await firestore.doc("users/user-a/personalPlants/plant-a").set({ ownerUid: "user-a" });
    await firestore.doc("users/user-a/ownedItems/decor-a").set(ownedItem("decor-a"));
    await firestore.doc("shopItems/decor-a").set({ publicationState: "PUBLIC", category: "DECORATION" });

    await assert.rejects(
      () => executeSaveMiniHomeLayout(
        { uid: "user-a" },
        { ...request, name: " invalid " },
        store,
      ),
      (error: unknown) =>
        error instanceof MiniHomeError &&
        error.code === "invalid-argument" &&
        error.reason === "INVALID_REQUEST" &&
        error.details?.field === "name",
    );
    assert.equal((await firestore.collection("users/user-a/miniHomes").get()).size, 0);
    assert.equal((await firestore.collection("users/user-a/operations").get()).size, 0);

    const concurrent = await Promise.all([
      executeSaveMiniHomeLayout({ uid: "user-a" }, request, store),
      executeSaveMiniHomeLayout({ uid: "user-a" }, request, store),
    ]);
    assert.deepEqual(
      concurrent.map((result) => result.kind).sort(),
      ["applied", "duplicate"],
    );
    assert.deepEqual(
      concurrent.map((result) => result.kind === "conflict" ? result.actualRevision : result.revision),
      [1, 1],
    );
    const home = await firestore.doc("users/user-a/miniHomes/home-a").get();
    const placements = await firestore.collection("users/user-a/placements").get();
    assert.equal(home.get("revision"), 1);
    assert.equal(home.get("placedPlantCount"), 1);
    assert.match(home.get("requestHash") as string, /^[a-f0-9]{64}$/);
    assert.equal(placements.size, 2);
    assert.deepEqual(placements.docs.map((document) => document.get("layoutRevision")), [1, 1]);

    await assert.rejects(
      () => executeSaveMiniHomeLayout(
        { uid: "user-a" },
        { ...request, name: "altered replay" },
        store,
      ),
      (error: unknown) =>
        error instanceof MiniHomeError &&
        error.code === "invalid-argument" &&
        error.reason === "PAYLOAD_MISMATCH" &&
        error.details?.committedOperationId === request.idempotencyKey &&
        error.details.committedExpectedRevision === 0 &&
        error.details.committedRevision === 1 &&
        error.details.committedPayloadHash === home.get("requestHash"),
    );
    assert.deepEqual(
      await executeSaveMiniHomeLayout(
        { uid: "user-a" },
        { ...request, idempotencyKey: "mini-home-emulator-0002" },
        store,
      ),
      { kind: "conflict", actualRevision: 1 },
    );
    await assert.rejects(
      () => executeSaveMiniHomeLayout(
        { uid: "user-a" },
        {
          ...request,
          idempotencyKey: "mini-home-emulator-0003",
          expectedRevision: 1,
          placements: [{ ...request.placements[0], plantId: "foreign-plant" }],
        },
        store,
      ),
      (error: unknown) =>
        error instanceof MiniHomeError &&
        error.code === "failed-precondition" &&
        error.reason === "UNAVAILABLE_ENTITY",
    );
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("authoritative bootstrap persists generation one and serializes concurrent create and delete", async () => {
  const app = initializeApp({ projectId }, "mini-home-bootstrap-emulator");
  const firestore = getFirestore(app);
  const writer = new FirestoreMiniHomeLayoutStore(firestore);
  let releaseCreateRead!: () => void;
  let releaseDeleteRead!: () => void;
  try {
    await clear(firestore);
    const initial = await writer.load("user-a");
    assert.equal(initial.kind, "missing");
    if (initial.kind !== "missing") throw new Error("Expected initial missing state");
    assert.equal(initial.generation, 1);
    assert.equal(initial.tombstoneId, "initial-missing");
    const initialState = await firestore.doc("users/user-a/miniHomeStates/current").get();
    assert.equal(initialState.get("revision"), 1);
    assert.equal(initialState.get("expectedRevision"), 0);
    assert.equal(initialState.get("state"), "DELETED");
    assert.deepEqual(await writer.load("user-a"), initial);

    await clear(firestore);
    await firestore.doc("users/user-a/personalPlants/plant-a").set({ ownerUid: "user-a" });
    await firestore.doc("users/user-a/ownedItems/decor-a").set(ownedItem("decor-a"));
    await firestore.doc("shopItems/decor-a").set({ publicationState: "PUBLIC", category: "DECORATION" });
    await executeSaveMiniHomeLayout({ uid: "user-a" }, request, writer);
    await firestore.doc("users/user-a/miniHomeStates/current").delete();
    const legacyPresent = present(await writer.load("user-a"));
    assert.equal(legacyPresent.generation, 1);
    assert.equal(legacyPresent.revision, 1);
    const bootstrappedActive = await firestore.doc("users/user-a/miniHomeStates/current").get();
    assert.equal(bootstrappedActive.get("state"), "ACTIVE");
    assert.equal(bootstrappedActive.get("revision"), 1);
    assert.equal(bootstrappedActive.get("layoutRevision"), 1);

    await clear(firestore);
    await firestore.doc("users/user-a/personalPlants/plant-a").set({ ownerUid: "user-a" });
    await firestore.doc("users/user-a/ownedItems/decor-a").set(ownedItem("decor-a"));
    await firestore.doc("shopItems/decor-a").set({ publicationState: "PUBLIC", category: "DECORATION" });
    let createReadObserved!: () => void;
    const createReadWasObserved = new Promise<void>((resolve) => { createReadObserved = resolve; });
    const createReadCanContinue = new Promise<void>((resolve) => { releaseCreateRead = resolve; });
    const createReader = new FirestoreMiniHomeLayoutStore(firestore, {
      afterHomeRead: async (attempt) => {
        if (attempt === 1) {
          createReadObserved();
          await createReadCanContinue;
        }
      },
    });
    const concurrentCreateRead = createReader.load("user-a");
    await createReadWasObserved;
    const concurrentCreate = executeSaveMiniHomeLayout({ uid: "user-a" }, request, writer);
    releaseCreateRead();
    const [beforeCreate, createResult] = await Promise.all([
      concurrentCreateRead,
      concurrentCreate,
    ]);
    assert.equal(beforeCreate.kind, "missing");
    if (beforeCreate.kind !== "missing") throw new Error("Expected pre-create state");
    assert.equal(beforeCreate.generation, 1);
    assert.equal(beforeCreate.tombstoneId, "initial-missing");
    assert.deepEqual(createResult, { kind: "applied", revision: 1 });
    const created = present(await writer.load("user-a"));
    assert.equal(created.generation, 2);
    assert.equal(created.revision, 1);

    await clear(firestore);
    let deleteReadObserved!: () => void;
    const deleteReadWasObserved = new Promise<void>((resolve) => { deleteReadObserved = resolve; });
    const deleteReadCanContinue = new Promise<void>((resolve) => { releaseDeleteRead = resolve; });
    const deleteReader = new FirestoreMiniHomeLayoutStore(firestore, {
      afterHomeRead: async (attempt) => {
        if (attempt === 1) {
          deleteReadObserved();
          await deleteReadCanContinue;
        }
      },
    });
    const concurrentDeleteRead = deleteReader.load("user-a");
    await deleteReadWasObserved;
    const concurrentDelete =
      writer.delete({
        ownerUid: "user-a",
        expectedGeneration: 1,
        tombstoneId: "concurrent-delete-0001",
      });
    releaseDeleteRead();
    const [beforeDelete, deleteResult] = await Promise.all([
      concurrentDeleteRead,
      concurrentDelete,
    ]);
    assert.equal(beforeDelete.kind, "missing");
    if (beforeDelete.kind !== "missing") throw new Error("Expected pre-delete state");
    assert.equal(beforeDelete.generation, 1);
    assert.equal(beforeDelete.tombstoneId, "initial-missing");
    assert.deepEqual(deleteResult, {
      kind: "deleted",
      generation: 2,
      tombstoneId: "concurrent-delete-0001",
    });
    const deleted = await writer.load("user-a");
    assert.equal(deleted.kind, "missing");
    if (deleted.kind !== "missing") throw new Error("Expected deleted state");
    assert.equal(deleted.generation, 2);
    assert.equal(deleted.tombstoneId, "concurrent-delete-0001");
  } finally {
    releaseCreateRead?.();
    releaseDeleteRead?.();
    await clear(firestore);
    await deleteApp(app);
  }
});

test("authoritative read stays whole during a deterministic save race and next read returns exact revision two", async () => {
  const app = initializeApp({ projectId }, "mini-home-consistent-read-emulator");
  const firestore = getFirestore(app);
  let homeRead!: () => void;
  const homeWasRead = new Promise<void>((resolve) => { homeRead = resolve; });
  let releaseRead!: () => void;
  const readCanContinue = new Promise<void>((resolve) => { releaseRead = resolve; });
  const racingReader = new FirestoreMiniHomeLayoutStore(firestore, {
    afterHomeRead: async (attempt) => {
      if (attempt === 1) {
        homeRead();
        await readCanContinue;
      }
    },
  });
  const writer = new FirestoreMiniHomeLayoutStore(firestore);
  try {
    await clear(firestore);
    await firestore.doc("users/user-a/personalPlants/plant-a").set({ ownerUid: "user-a" });
    await firestore.doc("users/user-a/personalPlants/plant-b").set({ ownerUid: "user-a" });
    await firestore.doc("users/user-a/ownedItems/decor-a").set(ownedItem("decor-a"));
    await firestore.doc("shopItems/decor-a").set({ publicationState: "PUBLIC", category: "DECORATION" });
    await executeSaveMiniHomeLayout({ uid: "user-a" }, request, writer);

    const racedRead = racingReader.load("user-a");
    await homeWasRead;
    const concurrentSave = executeSaveMiniHomeLayout(
      { uid: "user-a" },
      {
        ...request,
        expectedRevision: 1,
        idempotencyKey: "mini-home-emulator-0002",
        name: "revision two",
        placements: [
          { placementId: "revision-two-placement", plantId: "plant-b", itemId: null, normalizedX: 0.1, normalizedY: 0.125, zIndex: 0 },
        ],
      },
      writer,
    );
    releaseRead();

    const [racedReadResult] = await Promise.all([racedRead, concurrentSave]);
    const racedSnapshot = present(racedReadResult);
    assert.equal(racedSnapshot.revision, 1);
    assert.deepEqual(
      racedSnapshot.placements.map((placement) => placement.placementId),
      ["plant-placement", "decor-placement"],
    );
    assert.deepEqual(racedSnapshot.placements.map((placement) => placement.layoutRevision), [1, 1]);

    const revisionTwo = present(await writer.load("user-a"));
    assert.equal(revisionTwo.revision, 2);
    assert.equal(revisionTwo.name, "revision two");
    assert.deepEqual(revisionTwo.placements.map((placement) => placement.placementId), ["revision-two-placement"]);
    assert.deepEqual(revisionTwo.placements.map((placement) => placement.layoutRevision), [2]);
    assert.equal(revisionTwo.placedPlantCount, 1);

    await executeSaveMiniHomeLayout(
      { uid: "user-a" },
      {
        ...request,
        expectedRevision: 2,
        idempotencyKey: "mini-home-emulator-0003",
        name: "empty revision three",
        placements: [],
      },
      writer,
    );
    const empty = present(await writer.load("user-a"));
    assert.equal(empty.revision, 3);
    assert.deepEqual(empty.placements, []);
    assert.equal(empty.placedPlantCount, 0);

    const homeRef = firestore.doc("users/user-a/miniHomes/home-a");
    await homeRef.update({ placedPlantCount: 1 });
    await assert.rejects(
      () => writer.load("user-a"),
      (error: unknown) =>
        error instanceof MiniHomeError &&
        error.code === "data-loss" &&
        error.reason === "MALFORMED_RESPONSE" &&
        error.details?.field === "placedPlantCount",
    );
    await homeRef.update({ placedPlantCount: 0, placementCount: 1, placementIds: ["missing-row"] });
    await assert.rejects(
      () => writer.load("user-a"),
      (error: unknown) =>
        error instanceof MiniHomeError &&
        error.code === "data-loss" &&
        error.reason === "MALFORMED_RESPONSE" &&
        error.details?.field === "placementCount",
    );
    await homeRef.update({ placementCount: 0, placementIds: [] });

    let deletionHomeRead!: () => void;
    const deletionHomeWasRead = new Promise<void>((resolve) => { deletionHomeRead = resolve; });
    let releaseDeletionRead!: () => void;
    const deletionReadCanContinue = new Promise<void>((resolve) => { releaseDeletionRead = resolve; });
    const deletingReader = new FirestoreMiniHomeLayoutStore(firestore, {
      afterHomeRead: async (attempt) => {
        if (attempt === 1) {
          deletionHomeRead();
          await deletionReadCanContinue;
        }
      },
    });
    const beforeDelete = deletingReader.load("user-a");
    await deletionHomeWasRead;
    const concurrentDelete = writer.delete({
      ownerUid: "user-a",
      expectedGeneration: 3,
      tombstoneId: "mini-home-delete-0001",
    });
    releaseDeletionRead();
    const [wholeBeforeDeleteResult] = await Promise.all([beforeDelete, concurrentDelete]);
    const wholeBeforeDelete = present(wholeBeforeDeleteResult);
    assert.equal(wholeBeforeDelete.revision, 3);
    assert.deepEqual(wholeBeforeDelete.placements, []);
    assert.deepEqual(await writer.load("user-a"), {
      kind: "missing",
      ownerUid: "user-a",
      generation: 4,
      tombstoneId: "mini-home-delete-0001",
      updatedAtEpochMillis: (await firestore.doc("users/user-a/miniHomeStates/current").get()).get("updatedAt").toMillis(),
    });
  } finally {
    releaseRead();
    await clear(firestore);
    await deleteApp(app);
  }
});

test("legacy stored names migrate on server read while receipt hashes remain exact", async () => {
  const app = initializeApp({ projectId }, "mini-home-legacy-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreMiniHomeLayoutStore(firestore);
  const homeRef = firestore.doc("users/user-a/miniHomes/home-a");
  try {
    await clear(firestore);
    await firestore.doc("users/user-a/personalPlants/plant-a").set({ ownerUid: "user-a" });
    await homeRef.set({ ownerUid: "user-a", name: "e\u0301", revision: 4 });

    assert.deepEqual(
      await executeSaveMiniHomeLayout(
        { uid: "user-a" },
        { ...request, expectedRevision: 3, idempotencyKey: "legacy-read-conflict-0001", name: "é", placements: [request.placements[0]] },
        store,
      ),
      { kind: "conflict", actualRevision: 4 },
    );
    assert.equal((await homeRef.get()).get("name"), "é");

    const legacyHash = "a".repeat(64);
    await firestore.doc("users/user-a/operations/legacy-receipt-0001").set({
      ownerUid: "user-a",
      documentPath: homeRef.path,
      requestHash: legacyHash,
      revision: 4,
      expectedRevision: 3,
      idempotencyKey: "legacy-receipt-0001",
    });
    await assert.rejects(
      () => store.save({
        ownerUid: "user-a",
        miniHomeId: "home-a",
        expectedRevision: 3,
        idempotencyKey: "legacy-receipt-0001",
        requestHash: "b".repeat(64),
        name: "é",
        placements: [],
      }),
      (error: unknown) =>
        error instanceof MiniHomeError &&
        error.reason === "PAYLOAD_MISMATCH" &&
        error.details?.committedPayloadHash === legacyHash,
    );

    await homeRef.update({ name: "A\u202EB", revision: 5 });
    await assert.rejects(
      () => executeSaveMiniHomeLayout(
        { uid: "user-a" },
        { ...request, expectedRevision: 5, idempotencyKey: "legacy-invalid-read-0001", name: "안전한 이름", placements: [request.placements[0]] },
        store,
      ),
      (error: unknown) =>
        error instanceof MiniHomeError &&
        error.code === "data-loss" &&
        error.reason === "MALFORMED_RESPONSE" &&
        error.details?.field === "storedName",
    );
    assert.equal((await homeRef.get()).get("name"), "A\u202EB");
    assert.equal((await firestore.doc("users/user-a/operations/legacy-invalid-read-0001").get()).exists, false);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

function ownedItem(itemId: string) {
  const acquiredAt = Timestamp.fromDate(new Date("2026-08-12T00:00:00Z"));
  return {
    ownerUid: "user-a",
    itemId,
    acquiredAt,
    applied: false,
    revision: 1,
    expectedRevision: 0,
    idempotencyKey: "inventory-fixture-0001",
    updatedAt: acquiredAt,
  };
}

function present(read: Awaited<ReturnType<FirestoreMiniHomeLayoutStore["load"]>>) {
  assert.equal(read.kind, "present");
  if (read.kind !== "present") throw new Error("Expected a present mini-home layout");
  return read.layout;
}

async function clear(firestore: ReturnType<typeof getFirestore>): Promise<void> {
  for (const collection of [
    "personalPlants",
    "ownedItems",
    "miniHomes",
    "miniHomeStates",
    "placements",
    "operations",
    "miniHomeProjectionPointers",
    "miniHomeProjections",
    "inventoryStates",
  ]) {
    const snapshot = await firestore.collection(`users/user-a/${collection}`).get();
    await Promise.all(snapshot.docs.map((document) => document.ref.delete()));
  }
  await firestore.doc("users/user-a").delete();
  await firestore.recursiveDelete(firestore.collection("catalogProjections"));
  await firestore.doc("catalogProjectionPointers/current").delete();
  await firestore.recursiveDelete(firestore.collection("shopItems"));
}
