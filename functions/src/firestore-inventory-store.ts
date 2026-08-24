import { Timestamp, type DocumentSnapshot, type Firestore } from "firebase-admin/firestore";
import { runAccountMutationTransaction } from "./account-mutation-lock.js";
import {
  catalogProjectionForPublishedOwner,
  ownerProjectionDraft,
  projectionOwnedItem,
  projectionSnapshot,
  publishOwnerProjection,
  readCatalogForWriter,
  readOwnerForWriter,
  type ProjectionPublishHooks,
} from "./firestore-mini-home-projection.js";
import {
  INVENTORY_CONTRACT_VERSION,
  InventoryError,
  inventorySnapshotHash,
  type InventoryAcquireCommand,
  type InventoryAcquireResult,
  type InventoryAcquisitionCondition,
  type InventoryCatalogItem,
  type InventoryCategory,
  type InventoryMediaIdentity,
  type InventoryOwnedCatalogSnapshot,
  type InventoryOwnedItem,
  type InventorySnapshot,
  type InventoryStore,
} from "./inventory.js";

const categories: ReadonlySet<string> = new Set(["BACKGROUND", "FURNITURE", "DECORATION"]);
const conditions: ReadonlySet<string> = new Set(["registered-plant"]);
const hash = /^[a-f0-9]{64}$/;

export class FirestoreInventoryStore implements InventoryStore {
  constructor(
    private readonly firestore: Firestore,
    private readonly now: () => Timestamp = Timestamp.now,
    private readonly projectionHooks: ProjectionPublishHooks = {},
  ) {}

  async load(ownerUid: string): Promise<InventorySnapshot> {
    return runAccountMutationTransaction(this.firestore, ownerUid, async (transaction) => {
      const readTime = this.now();
      const catalogSource = await readCatalogForWriter(transaction, this.firestore);
      const owner = await readOwnerForWriter(
        transaction,
        this.firestore,
        ownerUid,
        catalogSource,
        readTime,
      );
      const published = await publishOwnerProjection(
        transaction,
        this.firestore,
        ownerUid,
        owner.prior,
        owner.draft,
        catalogSource,
        readTime,
        this.projectionHooks,
      );
      const catalog = catalogProjectionForPublishedOwner(published, catalogSource);
      transaction.set(
        this.firestore.doc(`users/${ownerUid}/inventoryStates/current`),
        inventoryStatePayload(
          ownerUid,
          published.inventoryGeneration,
          published.inventorySnapshotHash,
          readTime,
        ),
        { merge: false },
      );
      return projectionSnapshot({ owner: published, catalog }, readTime).inventory;
    }, { maxAttempts: 5 });
  }

  async acquire(command: InventoryAcquireCommand): Promise<InventoryAcquireResult> {
    return runAccountMutationTransaction(this.firestore, command.ownerUid, async (transaction) => {
      const ownerRoot = `users/${command.ownerUid}`;
      const operationRef = this.firestore.doc(`${ownerRoot}/inventoryOperations/${command.operationId}`);
      const operation = await transaction.get(operationRef);
      if (operation.exists) return replayOperation(operation, command);

      const acquiredAt = this.now();
      const catalogProjection = await readCatalogForWriter(transaction, this.firestore);
      const owner = await readOwnerForWriter(
        transaction,
        this.firestore,
        command.ownerUid,
        catalogProjection,
        acquiredAt,
      );
      const catalog = catalogProjection.catalog.find((item) => item.itemId === command.itemId);
      if (catalog === undefined) {
        throw new InventoryError("not-found", "The catalog item is not available", "ITEM_UNAVAILABLE", { field: "itemId" });
      }
      if (catalog.revision !== command.expectedCatalogRevision) {
        throw new InventoryError(
          "failed-precondition",
          "The catalog item changed",
          "CATALOG_CHANGED",
          { expectedRevision: command.expectedCatalogRevision, actualRevision: catalog.revision },
        );
      }

      const ownedRef = this.firestore.doc(`${ownerRoot}/ownedItems/${command.itemId}`);
      const stateRef = this.firestore.doc(`${ownerRoot}/inventoryStates/current`);
      const [ownedDocument] = await Promise.all([
        transaction.get(ownedRef),
        transaction.get(stateRef),
      ]);
      let result: InventoryAcquireResult;
      let nextOwned = owner.draft.owned;
      let ownershipPayload: Readonly<Record<string, unknown>> | null = null;
      if (ownedDocument.exists) {
        const owned = projectionOwnedItem(ownedDocument, command.ownerUid);
        result = {
          kind: "already-owned",
          ownerUid: command.ownerUid,
          itemId: command.itemId,
          catalogRevision: catalog.revision,
          ownershipRevision: owned.revision,
          acquiredAtEpochMillis: owned.acquiredAtEpochMillis,
          mediaIdentity: owned.catalogSnapshot?.mediaIdentity ?? catalog.mediaIdentity,
        };
      } else if (
        catalog.acquisitionCondition === "registered-plant" &&
        owner.draft.plants.length === 0
      ) {
        result = {
          kind: "condition-not-met",
          ownerUid: command.ownerUid,
          itemId: command.itemId,
          catalogRevision: catalog.revision,
          condition: "registered-plant",
        };
      } else {
        result = {
          kind: "acquired",
          ownerUid: command.ownerUid,
          itemId: command.itemId,
          catalogRevision: catalog.revision,
          ownershipRevision: 1,
          acquiredAtEpochMillis: acquiredAt.toMillis(),
          mediaIdentity: catalog.mediaIdentity,
        };
        const projectedOwned = {
          itemId: command.itemId,
          acquiredAtEpochMillis: acquiredAt.toMillis(),
          applied: false,
          revision: 1,
          catalogSnapshot: catalogSnapshot(catalog),
        };
        nextOwned = [
          ...owner.draft.owned.filter((item) => item.itemId !== command.itemId),
          projectedOwned,
        ];
        ownershipPayload = {
          ownerUid: command.ownerUid,
          itemId: command.itemId,
          acquiredAt,
          applied: false,
          nameSnapshot: catalog.name,
          categorySnapshot: catalog.category,
          assetPathSnapshot: catalog.mediaIdentity.path,
          assetSha256Snapshot: catalog.mediaIdentity.sha256,
          assetByteSizeSnapshot: catalog.mediaIdentity.byteSize,
          assetMimeTypeSnapshot: catalog.mediaIdentity.mimeType,
          assetWidthSnapshot: catalog.mediaIdentity.width,
          assetHeightSnapshot: catalog.mediaIdentity.height,
          assetMediaRevisionSnapshot: catalog.mediaIdentity.mediaRevision,
          catalogRevisionSnapshot: catalog.revision,
          revision: 1,
          expectedRevision: 0,
          idempotencyKey: command.operationId,
          updatedAt: acquiredAt,
        };
      }

      const published = await publishOwnerProjection(
        transaction,
        this.firestore,
        command.ownerUid,
        owner.prior,
        ownerProjectionDraft(owner.draft.layout, nextOwned, owner.draft.plants),
        catalogProjection,
        acquiredAt,
        this.projectionHooks,
      );
      if (ownershipPayload !== null) transaction.create(ownedRef, ownershipPayload);
      transaction.set(
        stateRef,
        inventoryStatePayload(
          command.ownerUid,
          published.inventoryGeneration,
          published.inventorySnapshotHash,
          acquiredAt,
        ),
        { merge: false },
      );
      transaction.create(operationRef, operationPayload(command, result, acquiredAt));
      return result;
    }, { maxAttempts: 5 });
  }
}

export function parseCatalogItem(document: DocumentSnapshot): InventoryCatalogItem {
  const publicationState = document.get("publicationState");
  if (publicationState !== "PUBLIC") throw malformed("Catalog item is not public", "publicationState");
  const name = boundedString(document, "name", 1, 100);
  const description = boundedString(document, "description", 1, 500);
  const category = document.get("category");
  if (typeof category !== "string" || !categories.has(category)) throw malformed("Catalog category is malformed", "category");
  const mediaIdentity = parseCatalogMediaIdentity(document);
  const condition = document.get("acquisitionCondition");
  if (condition !== null && (typeof condition !== "string" || !conditions.has(condition))) {
    throw malformed("Catalog acquisition condition is unsupported", "acquisitionCondition");
  }
  const revision = positiveInteger(document.get("revision"), "revision");
  const updatedAt = timestamp(document.get("updatedAt"), "updatedAt");
  return {
    itemId: document.id,
    name,
    description,
    category: category as InventoryCategory,
    mediaIdentity,
    acquisitionCondition: condition as InventoryAcquisitionCondition | null,
    revision,
    updatedAtEpochMillis: updatedAt.toMillis(),
  };
}

function parseCatalogMediaIdentity(document: DocumentSnapshot): InventoryMediaIdentity {
  return parseMediaIdentity({
    path: document.get("assetPath"),
    sha256: document.get("assetSha256"),
    byteSize: document.get("assetByteSize"),
    mimeType: document.get("assetContentType"),
    width: document.get("assetWidth"),
    height: document.get("assetHeight"),
    mediaRevision: document.get("assetMediaRevision"),
  }, document.id, "assetIdentity");
}

function parseMediaIdentity(
  value: Readonly<Record<string, unknown>>,
  itemId: string,
  field: string,
): InventoryMediaIdentity {
  const path = value.path;
  const sha256 = value.sha256;
  const byteSize = value.byteSize;
  const mimeType = value.mimeType;
  const width = value.width;
  const height = value.height;
  const mediaRevision = value.mediaRevision;
  if (
    typeof path !== "string" || typeof sha256 !== "string" || !hash.test(sha256) ||
    !validCatalogAssetPath(itemId, path, sha256) ||
    typeof byteSize !== "number" || !Number.isSafeInteger(byteSize) || byteSize < 1 || byteSize > 8 * 1024 * 1024 ||
    !(["image/png", "image/jpeg", "image/webp"] as readonly unknown[]).includes(mimeType) ||
    typeof width !== "number" || !Number.isSafeInteger(width) || width < 1 || width > 32_768 ||
    typeof height !== "number" || !Number.isSafeInteger(height) || height < 1 || height > 32_768 ||
    typeof mediaRevision !== "number" || !Number.isSafeInteger(mediaRevision) || mediaRevision < 1 ||
    (path.endsWith(".png") ? mimeType !== "image/png" : path.endsWith(".webp") ? mimeType !== "image/webp" : mimeType !== "image/jpeg") ||
    width * height > 64 * 1024 * 1024 || width * height * 4 > 256 * 1024 * 1024 ||
    width > height * 32 || height > width * 32
  ) {
    throw malformed("Catalog media identity is malformed", field);
  }
  return { path, sha256, byteSize, mimeType: mimeType as InventoryMediaIdentity["mimeType"], width, height, mediaRevision };
}

export type ParsedOwnedItem = Omit<InventoryOwnedItem, "availability">;

export function parseOwnedItem(document: DocumentSnapshot, ownerUid: string): ParsedOwnedItem {
  if (document.get("ownerUid") !== ownerUid) throw malformed("Stored ownership owner is malformed", "ownerUid");
  if (document.get("itemId") !== document.id) throw malformed("Stored ownership item identity is malformed", "itemId");
  return {
    itemId: document.id,
    acquiredAtEpochMillis: timestamp(document.get("acquiredAt"), "acquiredAt").toMillis(),
    applied: boolean(document.get("applied"), "applied"),
    revision: positiveInteger(document.get("revision"), "revision"),
    catalogSnapshot: parseOwnedCatalogSnapshot(document),
  };
}

function parseOwnedCatalogSnapshot(document: DocumentSnapshot): InventoryOwnedCatalogSnapshot | null {
  const values = [
    document.get("nameSnapshot"),
    document.get("categorySnapshot"),
    document.get("assetPathSnapshot"),
    document.get("assetSha256Snapshot"),
    document.get("assetByteSizeSnapshot"),
    document.get("assetMimeTypeSnapshot"),
    document.get("assetWidthSnapshot"),
    document.get("assetHeightSnapshot"),
    document.get("assetMediaRevisionSnapshot"),
    document.get("catalogRevisionSnapshot"),
  ];
  if (values.every((value) => value === undefined)) return null;
  const [name, category, path, sha256, byteSize, mimeType, width, height, mediaRevision, catalogRevision] = values;
  if (
    typeof name !== "string" || [...name].length < 1 || [...name].length > 100 ||
    typeof category !== "string" || !categories.has(category) ||
    typeof catalogRevision !== "number" || !Number.isSafeInteger(catalogRevision) || catalogRevision < 1
  ) {
    return null;
  }
  try {
    return {
      name,
      category: category as InventoryCategory,
      mediaIdentity: parseMediaIdentity(
        { path, sha256, byteSize, mimeType, width, height, mediaRevision },
        document.id,
        "catalogSnapshot",
      ),
      catalogRevision,
    };
  } catch {
    return null;
  }
}

export function catalogSnapshot(item: InventoryCatalogItem): InventoryOwnedCatalogSnapshot {
  return {
    name: item.name,
    category: item.category,
    mediaIdentity: item.mediaIdentity,
    catalogRevision: item.revision,
  };
}

function validCatalogAssetPath(itemId: string, assetPath: string, sha256: string): boolean {
  return new RegExp(`^catalog-assets/${itemId}/${sha256}\\.(?:png|jpe?g|webp)$`).test(assetPath);
}

function replayOperation(operation: DocumentSnapshot, command: InventoryAcquireCommand): InventoryAcquireResult {
  if (
    operation.get("ownerUid") !== command.ownerUid ||
    operation.get("itemId") !== command.itemId ||
    operation.get("expectedCatalogRevision") !== command.expectedCatalogRevision ||
    operation.get("requestHash") !== command.requestHash
  ) {
    throw new InventoryError(
      "failed-precondition",
      "The operation ID was already used for another acquisition",
      "IDEMPOTENCY_MISMATCH",
      { field: "operationId" },
    );
  }
  const kind = operation.get("result");
  const base = {
    ownerUid: command.ownerUid,
    itemId: command.itemId,
    catalogRevision: positiveInteger(operation.get("catalogRevision"), "catalogRevision"),
  };
  if (kind === "condition-not-met") {
    const condition = operation.get("condition");
    if (condition !== "registered-plant") throw malformed("Stored operation condition is malformed", "condition");
    return { kind, ...base, condition };
  }
  if (kind === "acquired" || kind === "already-owned") {
    return {
      kind,
      ...base,
      ownershipRevision: positiveInteger(operation.get("ownershipRevision"), "ownershipRevision"),
      acquiredAtEpochMillis: timestamp(operation.get("acquiredAt"), "acquiredAt").toMillis(),
      mediaIdentity: parseOperationMediaIdentity(operation, command.itemId),
    };
  }
  throw malformed("Stored operation result is malformed", "result");
}

function parseOperationMediaIdentity(
  operation: DocumentSnapshot,
  itemId: string,
): InventoryMediaIdentity {
  const value = operation.get("mediaIdentity");
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw malformed("Stored operation media identity is malformed", "mediaIdentity");
  }
  return parseMediaIdentity(value as Readonly<Record<string, unknown>>, itemId, "mediaIdentity");
}

function operationPayload(
  command: InventoryAcquireCommand,
  result: InventoryAcquireResult,
  createdAt: Timestamp,
): Readonly<Record<string, unknown>> {
  const common = {
    ownerUid: command.ownerUid,
    itemId: command.itemId,
    expectedCatalogRevision: command.expectedCatalogRevision,
    requestHash: command.requestHash,
    result: result.kind,
    catalogRevision: result.catalogRevision,
    createdAt,
  };
  return result.kind === "condition-not-met"
    ? { ...common, condition: result.condition }
    : {
        ...common,
        ownershipRevision: result.ownershipRevision,
        acquiredAt: Timestamp.fromMillis(result.acquiredAtEpochMillis),
        mediaIdentity: result.mediaIdentity,
      };
}

export type InventoryState = Readonly<{ generation: number; snapshotHash: string | null }>;

export function parseInventoryState(document: DocumentSnapshot, ownerUid: string): InventoryState | null {
  if (!document.exists) return null;
  const generation = document.get("generation");
  const snapshotHash = document.get("snapshotHash");
  if (
    document.get("ownerUid") !== ownerUid ||
    typeof generation !== "number" ||
    !Number.isSafeInteger(generation) ||
    generation < 1 ||
    generation >= Number.MAX_SAFE_INTEGER ||
    (snapshotHash !== null && (typeof snapshotHash !== "string" || !hash.test(snapshotHash)))
  ) {
    throw malformed("Stored inventory state is malformed", "inventoryState");
  }
  return { generation, snapshotHash };
}

export function inventoryStatePayload(
  ownerUid: string,
  generation: number,
  snapshotHash: string | null,
  updatedAt: Timestamp,
): Readonly<Record<string, unknown>> {
  return { ownerUid, generation, snapshotHash, updatedAt };
}

function boundedString(document: DocumentSnapshot, field: string, minimum: number, maximum: number): string {
  const value = document.get(field);
  if (typeof value !== "string" || [...value].length < minimum || [...value].length > maximum) {
    throw malformed(`Catalog ${field} is malformed`, field);
  }
  return value;
}

function positiveInteger(value: unknown, field: string): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 1) throw malformed(`${field} is malformed`, field);
  return value;
}

function timestamp(value: unknown, field: string): Timestamp {
  if (!(value instanceof Timestamp)) throw malformed(`${field} is malformed`, field);
  return value;
}

function boolean(value: unknown, field: string): boolean {
  if (typeof value !== "boolean") throw malformed(`${field} is malformed`, field);
  return value;
}

function malformed(message: string, field: string): InventoryError {
  return new InventoryError("data-loss", message, "MALFORMED_RESPONSE", { field });
}

export function catalogOrder(left: InventoryCatalogItem, right: InventoryCatalogItem): number {
  const categoriesInOrder: readonly InventoryCategory[] = ["BACKGROUND", "FURNITURE", "DECORATION"];
  return categoriesInOrder.indexOf(left.category) - categoriesInOrder.indexOf(right.category) ||
    left.name.localeCompare(right.name) || left.itemId.localeCompare(right.itemId);
}
