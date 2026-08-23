import assert from "node:assert/strict";
import test from "node:test";
import {
  MINI_HOME_SNAPSHOT_BOOTSTRAP_MAX_DOCUMENT_READS,
  MINI_HOME_SNAPSHOT_MAX_DOCUMENT_READS,
} from "./firestore-mini-home-snapshot-store.js";
import {
  executeLoadMiniHomeSnapshot,
  MiniHomeSnapshotError,
  type MiniHomeSnapshot,
  type MiniHomeSnapshotStore,
} from "./mini-home-snapshot.js";
import { inventorySnapshotHash } from "./inventory.js";

const ownerUid = "snapshot-owner";
const inventoryContent = { ownerUid, catalog: [], owned: [], registeredPlantCount: 0, partial: false };
const snapshot: MiniHomeSnapshot = {
  contractVersion: 1,
  ownerUid,
  snapshotToken: "a".repeat(64),
  snapshotGeneration: 4,
  serverReadTimeEpochMillis: 100,
  layout: { kind: "missing", ownerUid, generation: 2, tombstoneId: "initial-missing", updatedAtEpochMillis: 50 },
  inventory: {
    contractVersion: 3,
    ...inventoryContent,
    loadedAtEpochMillis: 100,
    inventoryGeneration: 3,
    snapshotHash: inventorySnapshotHash(inventoryContent),
  },
  plants: [],
};

class RecordingStore implements MiniHomeSnapshotStore {
  owners: string[] = [];
  constructor(private readonly result: MiniHomeSnapshot = snapshot) {}
  async load(owner: string): Promise<MiniHomeSnapshot> {
    this.owners.push(owner);
    return this.result;
  }
}

test("combined snapshot derives owner and preserves the typed envelope", async () => {
  const store = new RecordingStore();
  assert.deepEqual(
    await executeLoadMiniHomeSnapshot({ uid: ownerUid }, { expectedOwnerUid: ownerUid }, store),
    snapshot,
  );
  assert.deepEqual(store.owners, [ownerUid]);
});

test("combined snapshot rejects missing auth spoofing and unknown fields before Firestore", async () => {
  for (const [auth, input] of [
    [null, { expectedOwnerUid: ownerUid }],
    [{ uid: ownerUid }, { expectedOwnerUid: "another-owner" }],
    [{ uid: ownerUid }, { expectedOwnerUid: ownerUid, legacy: true }],
  ] as const) {
    const store = new RecordingStore();
    await assert.rejects(
      () => executeLoadMiniHomeSnapshot(auth, input, store),
      (error: unknown) => error instanceof MiniHomeSnapshotError,
    );
    assert.deepEqual(store.owners, []);
  }
});

test("combined snapshot refreshes current catalog binding within five reads and orphan-safe legacy bootstrap remains bounded by 431", () => {
  assert.equal(MINI_HOME_SNAPSHOT_MAX_DOCUMENT_READS, 5);
  assert.equal(MINI_HOME_SNAPSHOT_BOOTSTRAP_MAX_DOCUMENT_READS, 431);
});
