import assert from "node:assert/strict";
import test from "node:test";
import {
  Timestamp,
  type Firestore,
  type Transaction,
} from "firebase-admin/firestore";
import {
  MINI_HOME_PROJECTION_MAX_DOCUMENT_READS,
  readAndRefreshPublishedOwnerProjection,
  readCatalogForWriter,
  readOwnerForWriter,
  rebuildCatalogProjection,
  ownerProjectionDraft,
  publishOwnerProjection,
  type ProjectionPublishHooks,
} from "./firestore-mini-home-projection.js";
import { FirestoreMiniHomeSnapshotStore } from "./firestore-mini-home-snapshot-store.js";
import { executeLoadMiniHomeSnapshot, MiniHomeSnapshotError } from "./mini-home-snapshot.js";

const at = Timestamp.fromDate(new Date("2026-08-20T00:00:00Z"));
const ownerUid = "projection-owner";

test("catalog pointer loss repoints generation 2 for identical content and publishes generation 3 for changed content", async () => {
  const fake = new TransactionalFirestoreFake();
  fake.put("shopItems/item-a", catalogItem("item-a", 1));
  await rebuild(fake);
  fake.put("shopItems/item-a", catalogItem("item-a", 2));
  await rebuild(fake);
  const generation2 = fake.data("catalogProjectionPointers/current");
  assert.equal(generation2.generation, 2);

  fake.delete("catalogProjectionPointers/current");
  const createsBeforeRepair = fake.createCount;
  await rebuild(fake);
  assert.equal(fake.data("catalogProjectionPointers/current").projectionId, generation2.projectionId);
  assert.equal(fake.data("catalogProjectionPointers/current").generation, 2);
  assert.equal(fake.createCount, createsBeforeRepair);

  fake.delete("catalogProjectionPointers/current");
  fake.put("shopItems/item-a", catalogItem("item-a", 3));
  await rebuild(fake);
  assert.equal(fake.data("catalogProjectionPointers/current").generation, 3);
});

test("catalog pointer loss rejects a corrupt highest projection and an ambiguous highest-generation tie", async () => {
  const corrupt = await catalogAtGeneration2();
  const corruptPointer = corrupt.data("catalogProjectionPointers/current");
  corrupt.patch(`catalogProjections/${String(corruptPointer.projectionId)}`, { itemCount: 99 });
  corrupt.delete("catalogProjectionPointers/current");
  await assertCode("data-loss", () => rebuild(corrupt));
  assert.equal(corrupt.has("catalogProjectionPointers/current"), false);

  const ambiguous = await catalogAtGeneration2();
  const projections = ambiguous.paths("catalogProjections");
  const generation1Path = projections.find((path) => ambiguous.data(path).generation === 1);
  assert.ok(generation1Path);
  const generation1 = ambiguous.data(generation1Path);
  const collisionId = `2-${String(generation1.catalogToken)}`;
  ambiguous.put(`catalogProjections/${collisionId}`, {
    ...generation1,
    generation: 2,
    projectionId: collisionId,
  });
  ambiguous.delete("catalogProjectionPointers/current");
  await assertCode("data-loss", () => rebuild(ambiguous));
  assert.equal(ambiguous.has("catalogProjectionPointers/current"), false);
});

test("an exact generation 1 orphan is strictly reused without creating an immutable document", async () => {
  const fake = new TransactionalFirestoreFake();
  fake.put("shopItems/item-a", catalogItem("item-a", 1));
  await rebuild(fake);
  const pointer = fake.data("catalogProjectionPointers/current");
  const creates = fake.createCount;
  fake.delete("catalogProjectionPointers/current");
  await rebuild(fake);
  assert.equal(fake.data("catalogProjectionPointers/current").projectionId, pointer.projectionId);
  assert.equal(fake.createCount, creates);
});

test("catalog bound accepts exactly 100 items and rejects item 101 without changing the pointer", async () => {
  const fake = new TransactionalFirestoreFake();
  for (let index = 0; index < 100; index += 1) {
    const itemId = `bound-${index.toString().padStart(3, "0")}`;
    fake.put(`shopItems/${itemId}`, catalogItem(itemId, 1));
  }
  await rebuild(fake);
  const pointer = fake.data("catalogProjectionPointers/current");
  assert.equal(pointer.itemCount, 100);

  fake.put("shopItems/bound-100", catalogItem("bound-100", 1));
  await assertCode("resource-exhausted", () => rebuild(fake));
  assert.deepEqual(fake.data("catalogProjectionPointers/current"), pointer);
});

test("copied cleanup IDs cannot delete an orphan published after the cleanup snapshot", async () => {
  const fake = new TransactionalFirestoreFake();
  const staleIds = fake.paths("catalogProjections");
  fake.put("shopItems/item-a", catalogItem("item-a", 1));
  await rebuild(fake);
  const orphanId = String(fake.data("catalogProjectionPointers/current").projectionId);
  fake.delete("catalogProjectionPointers/current");
  for (const path of staleIds) fake.delete(path);
  assert.equal(fake.has(`catalogProjections/${orphanId}`), true);
  await rebuild(fake);
  assert.equal(fake.data("catalogProjectionPointers/current").projectionId, orphanId);
});

test("owner pointer recovery keeps snapshot and inventory generations monotonic", async () => {
  const fake = new TransactionalFirestoreFake();
  await refreshOwner(fake);
  const plant1 = projectionPlant(1);
  fake.put(`users/${ownerUid}/personalPlants/plant-a`, storedPlant(1));
  await fake.run(async (transaction, firestore) => {
    const catalog = await readCatalogForWriter(transaction, firestore);
    const owner = await readOwnerForWriter(transaction, firestore, ownerUid, catalog, at);
    await publishOwnerProjection(
      transaction,
      firestore,
      ownerUid,
      owner.prior,
      ownerProjectionDraft(owner.draft.layout, owner.draft.owned, [plant1]),
      catalog,
      at,
    );
  });
  const pointer2 = fake.data(`users/${ownerUid}/miniHomeProjectionPointers/current`);
  const projection2 = fake.data(`users/${ownerUid}/miniHomeProjections/${String(pointer2.projectionId)}`);
  assert.equal(pointer2.generation, 2);
  assert.equal(projection2.inventoryGeneration, 2);

  fake.delete(`users/${ownerUid}/miniHomeProjectionPointers/current`);
  await refreshOwner(fake);
  assert.equal(fake.data(`users/${ownerUid}/miniHomeProjectionPointers/current`).generation, 2);

  fake.delete(`users/${ownerUid}/miniHomeProjectionPointers/current`);
  fake.delete(`users/${ownerUid}/personalPlants/plant-a`);
  await refreshOwner(fake);
  const pointer3 = fake.data(`users/${ownerUid}/miniHomeProjectionPointers/current`);
  const projection3 = fake.data(`users/${ownerUid}/miniHomeProjections/${String(pointer3.projectionId)}`);
  assert.equal(pointer3.generation, 3);
  assert.equal(projection3.inventoryGeneration, 3);
});

test("uncommitted owner publication is invisible until the fake transaction commits", async () => {
  const fake = new TransactionalFirestoreFake();
  const pointerPath = `users/${ownerUid}/miniHomeProjectionPointers/current`;
  await publishLayout(fake, 1);
  const oldPointer = fake.data(pointerPath);
  const oldProjectionPath = `users/${ownerUid}/miniHomeProjections/${String(oldPointer.projectionId)}`;
  const oldProjection = fake.data(oldProjectionPath);
  const oldSnapshot = await loadSnapshot(fake);
  assert.equal(oldSnapshot.layout.kind, "present");
  if (oldSnapshot.layout.kind === "present") assert.equal(oldSnapshot.layout.revision, 1);
  assert.equal(oldSnapshot.snapshotGeneration, oldPointer.generation);
  assert.equal(oldSnapshot.snapshotToken, oldPointer.projectionToken);

  const projectionStaged = deferred<string>();
  const allowCommit = deferred<void>();
  const writer = publishLayout(fake, 2, {
    beforePointerSwap: async (projectionId) => {
      projectionStaged.resolve(projectionId);
      await allowCommit.promise;
    },
  });
  let stagedProjectionId!: string;
  try {
    stagedProjectionId = await boundedSignal(projectionStaged.promise, "owner projection staged");
    assert.deepEqual(fake.data(pointerPath), oldPointer);
    assert.equal(fake.has(`users/${ownerUid}/miniHomeProjections/${stagedProjectionId}`), false);
    assert.deepEqual(fake.data(oldProjectionPath), oldProjection);
    const duringPublication = await loadSnapshot(fake);
    assert.equal(duringPublication.layout.kind, "present");
    if (duringPublication.layout.kind === "present") assert.equal(duringPublication.layout.revision, 1);
    assert.equal(duringPublication.snapshotGeneration, oldSnapshot.snapshotGeneration);
    assert.equal(duringPublication.snapshotToken, oldSnapshot.snapshotToken);
  } finally {
    allowCommit.resolve();
    await writer;
  }

  const newPointer = fake.data(pointerPath);
  assert.equal(newPointer.projectionId, stagedProjectionId);
  assert.notEqual(newPointer.projectionId, oldPointer.projectionId);
  const newProjection = fake.data(`users/${ownerUid}/miniHomeProjections/${stagedProjectionId}`);
  assert.equal(newPointer.generation, newProjection.generation);
  assert.equal(newPointer.projectionToken, newProjection.projectionToken);
  const afterPublication = await loadSnapshot(fake);
  assert.equal(afterPublication.layout.kind, "present");
  if (afterPublication.layout.kind === "present") assert.equal(afterPublication.layout.revision, 2);
  assert.equal(afterPublication.snapshotGeneration, newPointer.generation);
  assert.equal(afterPublication.snapshotToken, newPointer.projectionToken);
  assert.deepEqual(fake.data(oldProjectionPath), oldProjection);
});

test("stale catalog rebind reuses an exact owner orphan in exactly six committed point reads", async () => {
  // Given
  const fixture = await staleCatalogRebindWithOwnerOrphan();

  // When
  const published = await fixture.fake.run((transaction, firestore) =>
    readAndRefreshPublishedOwnerProjection(transaction, firestore, ownerUid, at),
  );

  // Then
  assert.equal(published.owner.projectionId, fixture.owner2ProjectionId);
  assert.equal(fixture.fake.createCount, fixture.createCount);
  assert.deepEqual(
    fixture.fake.committedDocumentReadPaths,
    [
      "catalogProjectionPointers/current",
      `catalogProjections/${fixture.catalog1ProjectionId}`,
      `catalogProjections/${fixture.catalog2ProjectionId}`,
      fixture.ownerPointerPath,
      fixture.owner1ProjectionPath,
      fixture.owner2ProjectionPath,
    ].sort(),
  );
  assert.equal(fixture.fake.committedDocumentReadPaths.length, 6);
  assert.ok(
    fixture.fake.committedDocumentReadPaths.length <= MINI_HOME_PROJECTION_MAX_DOCUMENT_READS,
    `${fixture.fake.committedDocumentReadPaths.length} <= ${MINI_HOME_PROJECTION_MAX_DOCUMENT_READS}`,
  );
});

test("stale catalog rebind rejects a corrupt exact owner orphan without replacing the stale pointer", async () => {
  // Given
  const fixture = await staleCatalogRebindWithOwnerOrphan();
  fixture.fake.patch(fixture.owner2ProjectionPath, { ownedCount: 1 });

  // When
  const action = () => fixture.fake.run((transaction, firestore) =>
    readAndRefreshPublishedOwnerProjection(transaction, firestore, ownerUid, at),
  );

  // Then
  await assertCode("data-loss", action);
  assert.deepEqual(fixture.fake.data(fixture.ownerPointerPath), fixture.owner1Pointer);
});

test("32 concurrent pointer-loss publishers converge on one generation and one immutable candidate", async () => {
  const fake = await catalogAtGeneration2();
  fake.delete("catalogProjectionPointers/current");
  fake.put("shopItems/item-a", catalogItem("item-a", 3));
  const creates = fake.createCount;
  await Promise.all(Array.from({ length: 32 }, () => rebuild(fake)));
  const pointer = fake.data("catalogProjectionPointers/current");
  assert.equal(pointer.generation, 3);
  assert.equal(fake.paths("catalogProjections").filter((path) => fake.data(path).generation === 3).length, 1);
  assert.equal(fake.createCount, creates + 1);
  assert.equal(fake.retryConflictCount > 0, true);
});

async function publishLayout(
  fake: TransactionalFirestoreFake,
  revision: number,
  hooks: ProjectionPublishHooks = {},
) {
  return fake.run(async (transaction, firestore) => {
    const catalog = await readCatalogForWriter(transaction, firestore);
    const owner = await readOwnerForWriter(transaction, firestore, ownerUid, catalog, at);
    return publishOwnerProjection(
      transaction,
      firestore,
      ownerUid,
      owner.prior,
      ownerProjectionDraft({
        kind: "present",
        ownerUid,
        generation: revision,
        miniHomeId: "projection-home",
        name: "Projection Home",
        placedPlantCount: 0,
        placementCount: 0,
        revision,
        expectedRevision: revision - 1,
        idempotencyKey: `projection-layout-save-${revision.toString().padStart(4, "0")}`,
        requestHash: revision.toString(16).repeat(64),
        updatedAtEpochMillis: at.toMillis() + revision,
        placements: [],
      }, owner.draft.owned, owner.draft.plants),
      catalog,
      at,
      hooks,
    );
  });
}

function loadSnapshot(fake: TransactionalFirestoreFake) {
  return executeLoadMiniHomeSnapshot(
    { uid: ownerUid },
    { expectedOwnerUid: ownerUid },
    new FirestoreMiniHomeSnapshotStore(fake.firestore, { now: () => at }),
  );
}

type Deferred<T> = Readonly<{ promise: Promise<T>; resolve: (value: T) => void }>;

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

async function boundedSignal<T>(signal: Promise<T>, label: string): Promise<T> {
  let timeout!: NodeJS.Timeout;
  const expired = new Promise<never>((_resolve, reject) => {
    timeout = setTimeout(() => reject(new Error(`Timed out waiting for ${label}`)), 5_000);
    timeout.unref();
  });
  try {
    return await Promise.race([signal, expired]);
  } finally {
    clearTimeout(timeout);
  }
}

async function staleCatalogRebindWithOwnerOrphan() {
  const fake = new TransactionalFirestoreFake();
  const ownerPointerPath = `users/${ownerUid}/miniHomeProjectionPointers/current`;

  fake.put("shopItems/item-a", catalogItem("item-a", 1));
  await rebuild(fake);
  const catalog1ProjectionId = String(fake.data("catalogProjectionPointers/current").projectionId);
  await refreshOwner(fake);
  const owner1Pointer = fake.data(ownerPointerPath);
  const owner1ProjectionPath = `users/${ownerUid}/miniHomeProjections/${String(owner1Pointer.projectionId)}`;

  fake.put("shopItems/item-a", catalogItem("item-a", 2));
  await rebuild(fake);
  const catalog2ProjectionId = String(fake.data("catalogProjectionPointers/current").projectionId);
  await refreshOwner(fake);
  const owner2Pointer = fake.data(ownerPointerPath);
  const owner2ProjectionId = String(owner2Pointer.projectionId);
  const owner2ProjectionPath = `users/${ownerUid}/miniHomeProjections/${owner2ProjectionId}`;
  const createCount = fake.createCount;

  fake.put(ownerPointerPath, owner1Pointer);
  return {
    fake,
    catalog1ProjectionId,
    catalog2ProjectionId,
    ownerPointerPath,
    owner1Pointer,
    owner1ProjectionPath,
    owner2ProjectionId,
    owner2ProjectionPath,
    createCount,
  };
}

async function catalogAtGeneration2(): Promise<TransactionalFirestoreFake> {
  const fake = new TransactionalFirestoreFake();
  fake.put("shopItems/item-a", catalogItem("item-a", 1));
  await rebuild(fake);
  fake.put("shopItems/item-a", catalogItem("item-a", 2));
  await rebuild(fake);
  return fake;
}

async function rebuild(fake: TransactionalFirestoreFake): Promise<void> {
  await fake.run((transaction, firestore) => rebuildCatalogProjection(transaction, firestore, at));
}

async function refreshOwner(fake: TransactionalFirestoreFake): Promise<void> {
  await fake.run((transaction, firestore) =>
    readAndRefreshPublishedOwnerProjection(transaction, firestore, ownerUid, at).then(() => undefined),
  );
}

function projectionPlant(revision: number) {
  return {
    plantId: "plant-a",
    ownerUid,
    displayName: "Plant A",
    representativePhotoPath: null,
    revision,
    updatedAtEpochMillis: at.toMillis() + revision,
  };
}

function storedPlant(revision: number) {
  return {
    ownerUid,
    displayName: "Plant A",
    representativePhotoPath: null,
    revision,
    updatedAt: Timestamp.fromMillis(at.toMillis() + revision),
  };
}

function catalogItem(itemId: string, revision: number) {
  const digest = String.fromCharCode(96 + revision).repeat(64);
  return {
    name: itemId,
    description: `revision ${revision}`,
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
    updatedAt: Timestamp.fromMillis(at.toMillis() + revision),
  };
}

async function assertCode(code: "data-loss" | "resource-exhausted", action: () => Promise<unknown>): Promise<void> {
  await assert.rejects(
    action,
    (error: unknown) => error instanceof MiniHomeSnapshotError && error.code === code,
  );
}

type Stored = Readonly<{ data: Record<string, unknown>; version: number }>;
type Write =
  | Readonly<{ kind: "create"; path: string; data: Record<string, unknown> }>
  | Readonly<{ kind: "set"; path: string; data: Record<string, unknown> }>;

class RetryConflict extends Error {}

class FakeDocumentReference {
  readonly kind = "document";
  constructor(readonly path: string) {}
  get id(): string { return this.path.slice(this.path.lastIndexOf("/") + 1); }
}

class FakeQuery {
  readonly kind = "query";
  constructor(
    readonly path: string,
    readonly filters: readonly Readonly<{ field: string; value: unknown }>[] = [],
    readonly ordering: Readonly<{ field: string; direction: "asc" | "desc" }> | null = null,
    readonly maximum = Number.MAX_SAFE_INTEGER,
  ) {}
  where(field: string, operator: string, value: unknown): FakeQuery {
    assert.equal(operator, "==");
    return new FakeQuery(this.path, [...this.filters, { field, value }], this.ordering, this.maximum);
  }
  orderBy(field: string, direction: "asc" | "desc" = "asc"): FakeQuery {
    return new FakeQuery(this.path, this.filters, { field, direction }, this.maximum);
  }
  limit(maximum: number): FakeQuery {
    return new FakeQuery(this.path, this.filters, this.ordering, maximum);
  }
}

class FakeDocumentSnapshot {
  constructor(readonly ref: FakeDocumentReference, private readonly stored: Stored | undefined) {}
  get id(): string { return this.ref.id; }
  get exists(): boolean { return this.stored !== undefined; }
  data(): Record<string, unknown> | undefined { return this.stored === undefined ? undefined : clone(this.stored.data); }
  get(field: string): unknown { return this.stored?.data[field]; }
}

class FakeQuerySnapshot {
  constructor(readonly docs: readonly FakeDocumentSnapshot[]) {}
  get size(): number { return this.docs.length; }
}

class FakeTransaction {
  private readonly documentReads = new Map<string, number>();
  private readonly queryReads = new Map<string, number>();
  private readonly writes: Write[] = [];
  constructor(
    private readonly owner: TransactionalFirestoreFake,
    private readonly snapshot: ReadonlyMap<string, Stored>,
    private readonly collectionVersions: ReadonlyMap<string, number>,
  ) {}

  async get(target: FakeDocumentReference | FakeQuery): Promise<FakeDocumentSnapshot | FakeQuerySnapshot> {
    if (this.writes.length > 0) throw new Error("transaction read after write");
    if (target instanceof FakeDocumentReference) {
      const stored = this.snapshot.get(target.path);
      this.documentReads.set(target.path, stored?.version ?? 0);
      return new FakeDocumentSnapshot(target, stored);
    }
    this.queryReads.set(target.path, this.collectionVersions.get(target.path) ?? 0);
    let rows = [...this.snapshot.entries()]
      .filter(([path]) => directChild(path, target.path))
      .filter(([, stored]) => target.filters.every((filter) => stored.data[filter.field] === filter.value));
    if (target.ordering !== null) {
      const { field, direction } = target.ordering;
      rows.sort(([leftPath, left], [rightPath, right]) => {
        const leftValue = left.data[field];
        const rightValue = right.data[field];
        const compared = typeof leftValue === "number" && typeof rightValue === "number"
          ? leftValue - rightValue
          : String(leftValue).localeCompare(String(rightValue));
        return (direction === "desc" ? -compared : compared) || leftPath.localeCompare(rightPath);
      });
    } else {
      rows.sort(([left], [right]) => left.localeCompare(right));
    }
    return new FakeQuerySnapshot(rows.slice(0, target.maximum).map(([path, stored]) =>
      new FakeDocumentSnapshot(new FakeDocumentReference(path), stored),
    ));
  }

  create(reference: FakeDocumentReference, data: Record<string, unknown>): void {
    this.writes.push({ kind: "create", path: reference.path, data: clone(data) });
  }

  set(reference: FakeDocumentReference, data: Record<string, unknown>): void {
    this.writes.push({ kind: "set", path: reference.path, data: clone(data) });
  }

  commit(): void { this.owner.commit(this.documentReads, this.queryReads, this.writes); }
}

class TransactionalFirestoreFake {
  private readonly documents = new Map<string, Stored>();
  private readonly collectionVersions = new Map<string, number>();
  private version = 0;
  private latestCommittedDocumentReadPaths: readonly string[] = [];
  createCount = 0;
  createCollisionCount = 0;
  retryConflictCount = 0;

  readonly firestore = {
    doc: (path: string) => new FakeDocumentReference(path),
    collection: (path: string) => new FakeQuery(path),
    runTransaction: async <T>(body: (transaction: Transaction) => Promise<T>, options?: { maxAttempts?: number }) =>
      this.run((transaction) => body(transaction), options?.maxAttempts),
  } as unknown as Firestore;

  async run<T>(body: (transaction: Transaction, firestore: Firestore) => Promise<T>, maxAttempts = 40): Promise<T> {
    for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
      const transaction = new FakeTransaction(
        this,
        new Map([...this.documents].map(([path, stored]) => [path, cloneStored(stored)])),
        new Map(this.collectionVersions),
      );
      try {
        const result = await body(transaction as unknown as Transaction, this.firestore);
        transaction.commit();
        return result;
      } catch (error) {
        if (!(error instanceof RetryConflict) || attempt === maxAttempts) throw error;
        this.retryConflictCount += 1;
      }
    }
    throw new Error("transaction retry bound exhausted");
  }

  get committedDocumentReadPaths(): readonly string[] { return this.latestCommittedDocumentReadPaths; }

  put(path: string, data: Record<string, unknown>): void { this.write(path, data); }
  patch(path: string, patch: Record<string, unknown>): void { this.write(path, { ...this.data(path), ...patch }); }
  delete(path: string): void { if (this.documents.delete(path)) this.bumpCollection(path); }
  has(path: string): boolean { return this.documents.has(path); }

  data(path: string): Record<string, unknown> {
    const stored = this.documents.get(path);
    assert.ok(stored, `missing fake document ${path}`);
    return clone(stored.data);
  }

  paths(collection: string): string[] {
    return [...this.documents.keys()].filter((path) => directChild(path, collection)).sort();
  }

  commit(documentReads: ReadonlyMap<string, number>, queryReads: ReadonlyMap<string, number>, writes: readonly Write[]): void {
    for (const [path, version] of documentReads) {
      if ((this.documents.get(path)?.version ?? 0) !== version) throw new RetryConflict();
    }
    for (const [path, version] of queryReads) {
      if ((this.collectionVersions.get(path) ?? 0) !== version) throw new RetryConflict();
    }
    for (const write of writes) {
      if (write.kind === "create" && this.documents.has(write.path)) {
        this.createCollisionCount += 1;
        throw new RetryConflict();
      }
    }
    for (const write of writes) {
      if (write.kind === "create") this.createCount += 1;
      this.write(write.path, write.data);
    }
    this.latestCommittedDocumentReadPaths = [...documentReads.keys()].sort();
  }

  private write(path: string, data: Record<string, unknown>): void {
    this.version += 1;
    this.documents.set(path, { data: clone(data), version: this.version });
    this.bumpCollection(path);
  }

  private bumpCollection(documentPath: string): void {
    const collectionPath = documentPath.slice(0, documentPath.lastIndexOf("/"));
    this.collectionVersions.set(collectionPath, (this.collectionVersions.get(collectionPath) ?? 0) + 1);
  }
}

function directChild(documentPath: string, collectionPath: string): boolean {
  if (!documentPath.startsWith(`${collectionPath}/`)) return false;
  return !documentPath.slice(collectionPath.length + 1).includes("/");
}

function cloneStored(stored: Stored): Stored { return { data: clone(stored.data), version: stored.version }; }

function clone<T>(value: T): T {
  if (value instanceof Timestamp) return Timestamp.fromMillis(value.toMillis()) as T;
  if (Array.isArray(value)) return value.map(clone) as T;
  if (value !== null && typeof value === "object") {
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, clone(item)])) as T;
  }
  return value;
}
