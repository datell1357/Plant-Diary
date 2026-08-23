import { createHash } from "node:crypto";
import {
  Timestamp,
  type DocumentReference,
  type DocumentSnapshot,
  type Firestore,
  type QueryDocumentSnapshot,
  type Transaction,
} from "firebase-admin/firestore";
import {
  INVENTORY_CONTRACT_VERSION,
  InventoryError,
  inventorySnapshotHash,
  type InventoryCatalogItem,
  type InventoryCategory,
  type InventoryMediaIdentity,
  type InventoryOwnedCatalogSnapshot,
  type InventoryOwnedItem,
  type InventorySnapshot,
} from "./inventory.js";
import {
  MiniHomeSnapshotError,
  type MiniHomeSnapshot,
  type MiniHomeSnapshotPlant,
} from "./mini-home-snapshot.js";
import {
  recoverLegacyMiniHomeName,
  type MiniHomeAuthoritativePlacement,
  type MiniHomeLoadResult,
} from "./mini-home.js";

const PROJECTION_SCHEMA_VERSION = 1;
const CATALOG_PROJECTION_SCHEMA_VERSION = 2;
const MAX_CATALOG_ITEMS = 100;
const MAX_OWNED_ITEMS = 100;
const MAX_PLANTS = 200;
const MAX_PLACEMENTS = 20;
const HASH = /^[a-f0-9]{64}$/;
const OPAQUE_ID = /^[A-Za-z0-9_-]{1,128}$/;
const OPERATION_ID = /^[A-Za-z0-9_-]{8,128}$/;
const CATEGORIES: readonly InventoryCategory[] = ["BACKGROUND", "FURNITURE", "DECORATION"];
const MIME_TYPES: readonly InventoryMediaIdentity["mimeType"][] = ["image/png", "image/jpeg", "image/webp"];

export type ProjectionPublishHooks = Readonly<{
  beforePointerSwap?: (projectionId: string) => Promise<void>;
}>;

export type ProjectionReadHooks = Readonly<{
  afterPointerRead?: (generation: number) => Promise<void>;
}>;

export type CatalogProjectionRebuildHooks = Readonly<{
  afterSourceRead?: () => Promise<void>;
  beforePointerSwap?: (projectionId: string) => Promise<void>;
}>;

type CatalogProjection = Readonly<{
  projectionId: string | null;
  generation: number;
  token: string;
  catalog: readonly InventoryCatalogItem[];
  partial: boolean;
  rejectedCount: number;
}>;

type CatalogPublicationPlan = Readonly<{
  published: CatalogProjection;
  projectionRef: DocumentReference | null;
  immutablePayload: Readonly<Record<string, unknown>> | null;
  now: Timestamp;
}>;

type OwnerProjectionDraft = Readonly<{
  layout: MiniHomeLoadResult;
  owned: readonly ProjectionOwnedItem[];
  plants: readonly MiniHomeSnapshotPlant[];
}>;

type ProjectionOwnedItem = Readonly<{
  itemId: string;
  acquiredAtEpochMillis: number;
  applied: boolean;
  revision: number;
  catalogSnapshot: InventoryOwnedCatalogSnapshot | null;
}>;

type PublishedOwnerProjection = Readonly<{
  projectionId: string;
  ownerUid: string;
  generation: number;
  token: string;
  catalogProjectionId: string;
  catalogToken: string;
  layout: MiniHomeLoadResult;
  owned: readonly ProjectionOwnedItem[];
  plants: readonly MiniHomeSnapshotPlant[];
  inventoryGeneration: number;
  inventorySnapshotHash: string;
  partial: boolean;
}>;

type OwnerWriterState = Readonly<{
  prior: PublishedOwnerProjection | null;
  draft: OwnerProjectionDraft;
}>;

export class FirestoreCatalogProjectionStore {
  constructor(
    private readonly firestore: Firestore,
    private readonly now: () => Timestamp = Timestamp.now,
    private readonly hooks: CatalogProjectionRebuildHooks = {},
  ) {}

  async rebuild(): Promise<void> {
    await this.firestore.runTransaction(async (transaction) => {
      await rebuildCatalogProjection(
        transaction,
        this.firestore,
        this.now(),
        this.hooks,
      );
    }, { maxAttempts: 5 });
  }

  async update(itemId: string, patch: Readonly<Record<string, unknown>>): Promise<void> {
    requireOpaqueId(itemId, "itemId");
    await this.firestore.runTransaction(async (transaction) => {
      const reference = this.firestore.doc(`shopItems/${itemId}`);
      const document = await transaction.get(reference);
      if (!document.exists) projectionMalformed("Catalog update target is missing", "itemId");
      const merged = { ...document.data(), ...patch };
      if (merged.publicationState === "PUBLIC") parseCatalogRecord(itemId, merged);
      transaction.set(reference, merged, { merge: false });
    }, { maxAttempts: 5 });
    await this.rebuild();
  }

  async delete(itemId: string): Promise<void> {
    requireOpaqueId(itemId, "itemId");
    await this.firestore.doc(`shopItems/${itemId}`).delete();
    await this.rebuild();
  }
}

export async function rebuildCatalogProjection(
  transaction: Transaction,
  firestore: Firestore,
  now: Timestamp,
  hooks: CatalogProjectionRebuildHooks = {},
): Promise<CatalogProjection> {
  const pointerRef = firestore.doc("catalogProjectionPointers/current");
  const [pointer, documents] = await Promise.all([
    transaction.get(pointerRef),
    transaction.get(
      firestore.collection("shopItems")
        .where("publicationState", "==", "PUBLIC")
        .limit(MAX_CATALOG_ITEMS + 1),
    ),
  ]);
  if (documents.size > MAX_CATALOG_ITEMS) {
    projectionExhausted("Catalog projection exceeds the 100 item bound", "catalog");
  }
  await hooks.afterSourceRead?.();
  let rejectedCount = 0;
  const catalog = documents.docs.flatMap((document) => {
    try {
      return [parseCatalogRecord(document.id, document.data())];
    } catch (error) {
      if (isCatalogRecordRejection(error)) {
        rejectedCount += 1;
        return [];
      }
      throw error;
    }
  }).sort(catalogOrder);
  const partial = rejectedCount > 0;
  const token = catalogToken(catalog, partial, rejectedCount);
  const prior = pointer.exists
    ? catalogPointerState(pointer)
    : {
        projectionId: null,
        generation: 0,
        token: catalogToken([], false, 0),
        catalog: [] as readonly InventoryCatalogItem[],
        partial: false,
        rejectedCount: 0,
      };
  if (
    pointer.exists &&
    prior.token === token &&
    pointer.get("itemCount") === catalog.length &&
    pointer.get("partial") === partial &&
    pointer.get("rejectedCount") === rejectedCount
  ) {
    if (prior.projectionId === null) {
      projectionMalformed("Catalog projection pointer identity is missing", "catalogProjectionPointer");
    }
    const projection = parseCatalogProjectionDocument(
      await transaction.get(firestore.doc(`catalogProjections/${prior.projectionId}`)),
      prior.projectionId,
    );
    if (
      projection.token !== token ||
      projection.partial !== partial ||
      projection.rejectedCount !== rejectedCount ||
      projection.catalog.length !== catalog.length
    ) {
      projectionMalformed("Catalog projection differs from current source", "catalogProjection");
    }
    return projection;
  }
  return publishCatalogProjection(
    transaction,
    firestore,
    prior,
    catalog,
    partial,
    rejectedCount,
    now,
    hooks,
  );
}

export async function readCatalogForWriter(
  transaction: Transaction,
  firestore: Firestore,
): Promise<CatalogProjection> {
  const pointerRef = firestore.doc("catalogProjectionPointers/current");
  const pointer = await transaction.get(pointerRef);
  if (pointer.exists) return readCatalogFromPointer(transaction, firestore, pointer);

  const documents = await transaction.get(
    firestore.collection("shopItems").where("publicationState", "==", "PUBLIC").limit(MAX_CATALOG_ITEMS + 1),
  );
  if (documents.size > MAX_CATALOG_ITEMS) projectionExhausted("Catalog projection exceeds the 100 item bound", "catalog");
  let rejectedCount = 0;
  const catalog = documents.docs.flatMap((document) => {
    try {
      return [parseCatalogRecord(document.id, document.data())];
    } catch (error) {
      if (isCatalogRecordRejection(error)) {
        rejectedCount += 1;
        return [];
      }
      throw error;
    }
  }).sort(catalogOrder);
  return {
    projectionId: null,
    generation: 0,
    token: catalogToken(catalog, rejectedCount > 0, rejectedCount),
    catalog,
    partial: rejectedCount > 0,
    rejectedCount,
  };
}

export async function ensureCatalogPublished(
  transaction: Transaction,
  firestore: Firestore,
  current: CatalogProjection,
  now: Timestamp,
): Promise<CatalogProjection> {
  if (current.projectionId !== null) return current;
  return publishCatalogProjection(
    transaction,
    firestore,
    current,
    current.catalog,
    current.partial,
    current.rejectedCount,
    now,
  );
}

export async function readOwnerForWriter(
  transaction: Transaction,
  firestore: Firestore,
  ownerUid: string,
  currentCatalog: CatalogProjection,
  now: Timestamp,
): Promise<OwnerWriterState> {
  const pointer = await transaction.get(ownerPointerRef(firestore, ownerUid));
  if (pointer.exists) {
    const prior = await readOwnerFromPointer(transaction, firestore, ownerUid, pointer);
    const priorCatalog = parseCatalogProjectionDocument(
      await transaction.get(firestore.doc(`catalogProjections/${prior.catalogProjectionId}`)),
      prior.catalogProjectionId,
    );
    if (priorCatalog.token !== prior.catalogToken) {
      projectionMalformed("Owner projection catalog token differs from its immutable catalog", "catalogToken");
    }
    validateOwnerProjection(prior, priorCatalog);
    return {
      prior,
      draft: { layout: prior.layout, owned: prior.owned, plants: prior.plants },
    };
  }
  return {
    prior: null,
    draft: await readLegacyOwnerDraft(transaction, firestore, ownerUid, currentCatalog, now),
  };
}

export async function readPublishedOwnerProjection(
  transaction: Transaction,
  firestore: Firestore,
  ownerUid: string,
  hooks: ProjectionReadHooks = {},
): Promise<Readonly<{ owner: PublishedOwnerProjection; catalog: CatalogProjection }>> {
  const pointer = await transaction.get(ownerPointerRef(firestore, ownerUid));
  if (!pointer.exists) projectionMalformed("Mini-home projection pointer is missing", "projectionPointer");
  const generation = positiveInteger(pointer.get("generation"), "projectionGeneration");
  await hooks.afterPointerRead?.(generation);
  const owner = await readOwnerFromPointer(transaction, firestore, ownerUid, pointer);
  const catalogDocument = await transaction.get(
    firestore.doc(`catalogProjections/${owner.catalogProjectionId}`),
  );
  const catalog = parseCatalogProjectionDocument(catalogDocument, owner.catalogProjectionId);
  if (catalog.token !== owner.catalogToken) {
    projectionMalformed("Owner projection catalog token differs from its immutable catalog", "catalogToken");
  }
  validateOwnerProjection(owner, catalog);
  return { owner, catalog };
}

export async function readAndRefreshPublishedOwnerProjection(
  transaction: Transaction,
  firestore: Firestore,
  ownerUid: string,
  now: Timestamp,
  hooks: ProjectionReadHooks = {},
): Promise<Readonly<{ owner: PublishedOwnerProjection; catalog: CatalogProjection }>> {
  const catalogSource = await readCatalogForWriter(transaction, firestore);
  const owner = await readOwnerForWriter(
    transaction,
    firestore,
    ownerUid,
    catalogSource,
    now,
  );
  await hooks.afterPointerRead?.(owner.prior?.generation ?? 0);
  const published = await publishOwnerProjection(
    transaction,
    firestore,
    ownerUid,
    owner.prior,
    owner.draft,
    catalogSource,
    now,
  );
  const catalog = catalogProjectionForPublishedOwner(published, catalogSource);
  return { owner: published, catalog };
}

export async function publishOwnerProjection(
  transaction: Transaction,
  firestore: Firestore,
  ownerUid: string,
  prior: PublishedOwnerProjection | null,
  draft: OwnerProjectionDraft,
  catalogInput: CatalogProjection,
  now: Timestamp,
  hooks: ProjectionPublishHooks = {},
): Promise<PublishedOwnerProjection> {
  const catalogPlan = await prepareCatalogPublication(
    transaction,
    firestore,
    catalogInput,
    catalogInput.catalog,
    catalogInput.partial,
    catalogInput.rejectedCount,
    now,
  );
  const catalog = catalogPlan.published;
  if (catalog.projectionId === null) projectionMalformed("Catalog projection was not published", "catalogProjectionId");
  const normalizedOwned = [...draft.owned].sort(ownedOrder);
  const normalizedPlants = [...draft.plants].sort((left, right) => left.plantId.localeCompare(right.plantId));
  validateDraft(ownerUid, draft.layout, normalizedOwned, normalizedPlants);
  const inventory = inventoryContent(ownerUid, catalog, normalizedOwned, normalizedPlants.length, now, prior?.inventoryGeneration ?? 0);
  const inventoryGeneration =
    prior === null ? 1 : prior.inventorySnapshotHash === inventory.snapshotHash ? prior.inventoryGeneration : prior.inventoryGeneration + 1;
  const projectionToken = ownerToken(ownerUid, draft.layout, inventoryGeneration, inventory.snapshotHash, normalizedPlants);
  if (
    prior !== null &&
    prior.token === projectionToken &&
    prior.catalogProjectionId === catalog.projectionId &&
    prior.catalogToken === catalog.token
  ) {
    await commitCatalogPublication(transaction, firestore, catalogPlan);
    return prior;
  }
  const generation = (prior?.generation ?? 0) + 1;
  const projectionId = `${generation}-${projectionToken}`;
  const published: PublishedOwnerProjection = {
    projectionId,
    ownerUid,
    generation,
    token: projectionToken,
    catalogProjectionId: catalog.projectionId,
    catalogToken: catalog.token,
    layout: draft.layout,
    owned: normalizedOwned,
    plants: normalizedPlants,
    inventoryGeneration,
    inventorySnapshotHash: inventory.snapshotHash,
    partial: catalog.partial || inventory.partial,
  };
  const projectionRef = firestore.doc(`users/${ownerUid}/miniHomeProjections/${projectionId}`);
  const existing = await transaction.get(projectionRef);
  if (existing.exists) {
    const parsed = parseOwnerProjectionDocument(existing, ownerUid, projectionId);
    validateOwnerProjection(parsed, catalog);
    if (canonicalJson(parsed) !== canonicalJson(published)) {
      projectionMalformed("Immutable Mini-home projection collides with the candidate", "projection");
    }
  }
  await commitCatalogPublication(transaction, firestore, catalogPlan);
  if (!existing.exists) {
    transaction.create(projectionRef, {
      schemaVersion: PROJECTION_SCHEMA_VERSION,
      ownerUid,
      projectionId,
      generation,
      projectionToken,
      catalogProjectionId: catalog.projectionId,
      catalogToken: catalog.token,
      layout: draft.layout,
      owned: normalizedOwned,
      plants: normalizedPlants,
      layoutPlacementCount: draft.layout.kind === "present" ? draft.layout.placements.length : 0,
      ownedCount: normalizedOwned.length,
      plantCount: normalizedPlants.length,
      inventoryGeneration,
      inventorySnapshotHash: inventory.snapshotHash,
      partial: catalog.partial || inventory.partial,
      createdAt: now,
    });
  }
  await hooks.beforePointerSwap?.(projectionId);
  transaction.set(ownerPointerRef(firestore, ownerUid), {
    schemaVersion: PROJECTION_SCHEMA_VERSION,
    ownerUid,
    projectionId,
    generation,
    projectionToken,
    catalogProjectionId: catalog.projectionId,
    catalogToken: catalog.token,
    updatedAt: now,
  }, { merge: false });
  return published;
}

export function ownerProjectionDraft(
  layout: MiniHomeLoadResult,
  owned: readonly ProjectionOwnedItem[],
  plants: readonly MiniHomeSnapshotPlant[],
): OwnerProjectionDraft {
  return { layout, owned, plants };
}

export function projectionOwnedItem(document: DocumentSnapshot, ownerUid: string): ProjectionOwnedItem {
  if (!document.exists || document.get("ownerUid") !== ownerUid || document.get("itemId") !== document.id) {
    projectionMalformed("Stored ownership identity is malformed", "ownedItems");
  }
  return {
    itemId: requireOpaqueId(document.id, "itemId"),
    acquiredAtEpochMillis: timestampMillis(document.get("acquiredAt"), "acquiredAt"),
    applied: boolean(document.get("applied"), "applied"),
    revision: positiveInteger(document.get("revision"), "ownershipRevision"),
    catalogSnapshot: parseOwnedCatalogSnapshot(document),
  };
}

export function projectionPlant(
  plantId: string,
  value: Readonly<Record<string, unknown>>,
  ownerUid: string,
): MiniHomeSnapshotPlant {
  if (value.ownerUid !== ownerUid) projectionMalformed("Stored personal plant owner is malformed", "plants");
  const displayName = boundedString(value.displayName, "displayName", 1, 100);
  const photo = value.representativePhotoPath;
  if (!(photo === null || photo === undefined || (typeof photo === "string" && photo.length > 0 && photo.length <= 500))) {
    projectionMalformed("Stored personal plant photo is malformed", "plants");
  }
  return {
    plantId: requireOpaqueId(plantId, "plantId"),
    ownerUid,
    displayName,
    representativePhotoPath: typeof photo === "string" ? photo : null,
    revision: positiveInteger(value.revision, "plantRevision"),
    updatedAtEpochMillis: timestampMillis(value.updatedAt, "updatedAt"),
  };
}

export function projectionSnapshot(
  published: Readonly<{ owner: PublishedOwnerProjection; catalog: CatalogProjection }>,
  readTime: Timestamp,
): MiniHomeSnapshot {
  const inventory = inventoryContent(
    published.owner.ownerUid,
    published.catalog,
    published.owner.owned,
    published.owner.plants.length,
    readTime,
    published.owner.inventoryGeneration,
  );
  if (inventory.snapshotHash !== published.owner.inventorySnapshotHash) {
    projectionMalformed("Published inventory hash is corrupt", "inventorySnapshotHash");
  }
  return {
    contractVersion: 1,
    ownerUid: published.owner.ownerUid,
    snapshotToken: published.owner.token,
    snapshotGeneration: published.owner.generation,
    serverReadTimeEpochMillis: readTime.toMillis(),
    layout: published.owner.layout,
    inventory,
    plants: published.owner.plants,
  };
}

export const MINI_HOME_PROJECTION_MAX_DOCUMENT_READS = 5;
export const MINI_HOME_PROJECTION_BOOTSTRAP_MAX_DOCUMENT_READS = 431;

async function publishCatalogProjection(
  transaction: Transaction,
  firestore: Firestore,
  prior: CatalogProjection,
  catalog: readonly InventoryCatalogItem[],
  partial: boolean,
  rejectedCount: number,
  now: Timestamp,
  hooks: CatalogProjectionRebuildHooks = {},
): Promise<CatalogProjection> {
  const plan = await prepareCatalogPublication(
    transaction,
    firestore,
    prior,
    catalog,
    partial,
    rejectedCount,
    now,
  );
  await commitCatalogPublication(transaction, firestore, plan, hooks);
  return plan.published;
}

async function prepareCatalogPublication(
  transaction: Transaction,
  firestore: Firestore,
  prior: CatalogProjection,
  catalog: readonly InventoryCatalogItem[],
  partial: boolean,
  rejectedCount: number,
  now: Timestamp,
): Promise<CatalogPublicationPlan> {
  if (catalog.length > MAX_CATALOG_ITEMS) projectionExhausted("Catalog projection exceeds the 100 item bound", "catalog");
  const ordered = [...catalog].sort(catalogOrder);
  if (
    !Number.isSafeInteger(rejectedCount) || rejectedCount < 0 ||
    partial !== (rejectedCount > 0)
  ) {
    projectionMalformed("Catalog rejection metadata is malformed", "rejectedCount");
  }
  const token = catalogToken(ordered, partial, rejectedCount);
  if (prior.projectionId !== null && prior.token === token) {
    return { published: prior, projectionRef: null, immutablePayload: null, now };
  }
  const generation = prior.generation + 1;
  const projectionId = `${generation}-${token}`;
  const published = { projectionId, generation, token, catalog: ordered, partial, rejectedCount };
  const projectionRef = firestore.doc(`catalogProjections/${projectionId}`);
  const existing = await transaction.get(projectionRef);
  if (existing.exists) {
    const parsed = parseCatalogProjectionDocument(existing, projectionId);
    if (
      parsed.generation !== generation ||
      parsed.token !== token ||
      parsed.partial !== partial ||
      parsed.rejectedCount !== rejectedCount ||
      canonicalJson(parsed.catalog) !== canonicalJson(ordered)
    ) {
      projectionMalformed("Immutable catalog projection collides with the candidate", "catalogProjection");
    }
  }
  return {
    published,
    projectionRef,
    immutablePayload: existing.exists ? null : {
      schemaVersion: CATALOG_PROJECTION_SCHEMA_VERSION,
      projectionId,
      generation,
      catalogToken: token,
      itemCount: ordered.length,
      rejectedCount,
      partial,
      catalog: ordered.map((item) => ({
        itemId: item.itemId,
        name: item.name,
        description: item.description,
        category: item.category,
        assetPath: item.mediaIdentity.path,
        assetSha256: item.mediaIdentity.sha256,
        assetByteSize: item.mediaIdentity.byteSize,
        assetContentType: item.mediaIdentity.mimeType,
        assetWidth: item.mediaIdentity.width,
        assetHeight: item.mediaIdentity.height,
        assetMediaRevision: item.mediaIdentity.mediaRevision,
        acquisitionCondition: item.acquisitionCondition,
        publicationState: "PUBLIC",
        revision: item.revision,
        updatedAt: Timestamp.fromMillis(item.updatedAtEpochMillis),
      })),
      createdAt: now,
    },
    now,
  };
}

async function commitCatalogPublication(
  transaction: Transaction,
  firestore: Firestore,
  plan: CatalogPublicationPlan,
  hooks: CatalogProjectionRebuildHooks = {},
): Promise<void> {
  if (plan.projectionRef === null || plan.published.projectionId === null) return;
  if (plan.immutablePayload !== null) transaction.create(plan.projectionRef, plan.immutablePayload);
  await hooks.beforePointerSwap?.(plan.published.projectionId);
  transaction.set(firestore.doc("catalogProjectionPointers/current"), {
    schemaVersion: CATALOG_PROJECTION_SCHEMA_VERSION,
    projectionId: plan.published.projectionId,
    generation: plan.published.generation,
    catalogToken: plan.published.token,
    itemCount: plan.published.catalog.length,
    rejectedCount: plan.published.rejectedCount,
    partial: plan.published.partial,
    updatedAt: plan.now,
  }, { merge: false });
}

export function catalogProjectionForPublishedOwner(
  owner: PublishedOwnerProjection,
  source: CatalogProjection,
): CatalogProjection {
  const separator = owner.catalogProjectionId.indexOf("-");
  const generation = Number(owner.catalogProjectionId.slice(0, separator));
  if (
    !Number.isSafeInteger(generation) || generation < 1 ||
    owner.catalogProjectionId !== `${generation}-${owner.catalogToken}`
  ) {
    projectionMalformed("Published owner catalog identity is malformed", "catalogProjectionId");
  }
  return {
    ...source,
    projectionId: owner.catalogProjectionId,
    generation,
    token: owner.catalogToken,
  };
}

function catalogPointerState(pointer: DocumentSnapshot): CatalogProjection {
  const schemaVersion = pointer.get("schemaVersion");
  if (schemaVersion !== PROJECTION_SCHEMA_VERSION && schemaVersion !== CATALOG_PROJECTION_SCHEMA_VERSION) {
    projectionMalformed("Catalog projection pointer schema is unsupported", "catalogProjectionPointer");
  }
  const partial = schemaVersion === CATALOG_PROJECTION_SCHEMA_VERSION
    ? boolean(pointer.get("partial"), "catalogPartial")
    : false;
  const rejectedCount = schemaVersion === CATALOG_PROJECTION_SCHEMA_VERSION
    ? nonNegativeInteger(pointer.get("rejectedCount"), "rejectedCount")
    : 0;
  if (partial !== (rejectedCount > 0)) {
    projectionMalformed("Catalog projection pointer rejection metadata is torn", "rejectedCount");
  }
  const projectionId = boundedProjectionId(
    pointer.get("projectionId"),
    "catalogProjectionId",
  );
  const generation = positiveInteger(pointer.get("generation"), "catalogGeneration");
  const token = requireHash(pointer.get("catalogToken"), "catalogToken");
  if (projectionId !== `${generation}-${token}`) {
    projectionMalformed("Catalog projection pointer identity is torn", "catalogProjectionPointer");
  }
  return {
    projectionId,
    generation,
    token,
    catalog: [],
    partial,
    rejectedCount,
  };
}

async function readCatalogFromPointer(
  transaction: Transaction,
  firestore: Firestore,
  pointer: DocumentSnapshot,
): Promise<CatalogProjection> {
  const pointerState = catalogPointerState(pointer);
  if (pointerState.projectionId === null) {
    projectionMalformed("Catalog projection pointer identity is missing", "catalogProjectionPointer");
  }
  const projection = await transaction.get(
    firestore.doc(`catalogProjections/${pointerState.projectionId}`),
  );
  const parsed = parseCatalogProjectionDocument(projection, pointerState.projectionId);
  if (
    pointer.get("projectionId") !== parsed.projectionId ||
    pointer.get("generation") !== parsed.generation ||
    pointer.get("catalogToken") !== parsed.token ||
    pointer.get("itemCount") !== parsed.catalog.length ||
    (pointer.get("schemaVersion") === CATALOG_PROJECTION_SCHEMA_VERSION &&
      (pointer.get("partial") !== parsed.partial ||
       pointer.get("rejectedCount") !== parsed.rejectedCount))
  ) {
    projectionMalformed("Catalog projection pointer is torn", "catalogProjectionPointer");
  }
  return parsed;
}

function parseCatalogProjectionDocument(document: DocumentSnapshot, projectionId: string): CatalogProjection {
  if (!document.exists) projectionMalformed("Catalog projection document is missing", "catalogProjection");
  const catalogValue = document.get("catalog");
  if (!Array.isArray(catalogValue)) projectionMalformed("Catalog projection payload is malformed", "catalog");
  if (catalogValue.length > MAX_CATALOG_ITEMS) projectionExhausted("Catalog projection exceeds the 100 item bound", "catalog");
  const catalog = catalogValue.map((value, index) => {
    if (!isRecord(value)) projectionMalformed("Catalog projection item is malformed", `catalog[${index}]`);
    return parseCatalogRecord(requireOpaqueId(value.itemId, "itemId"), value);
  }).sort(catalogOrder);
  const generation = positiveInteger(document.get("generation"), "catalogGeneration");
  const partial = boolean(document.get("partial"), "catalogPartial");
  const token = requireHash(document.get("catalogToken"), "catalogToken");
  const schemaVersion = document.get("schemaVersion");
  if (schemaVersion !== PROJECTION_SCHEMA_VERSION && schemaVersion !== CATALOG_PROJECTION_SCHEMA_VERSION) {
    projectionMalformed("Catalog projection schema is unsupported", "catalogProjection");
  }
  const rejectedCount = schemaVersion === CATALOG_PROJECTION_SCHEMA_VERSION
    ? nonNegativeInteger(document.get("rejectedCount"), "rejectedCount")
    : partial ? 1 : 0;
  const expectedToken = schemaVersion === CATALOG_PROJECTION_SCHEMA_VERSION
    ? catalogToken(catalog, partial, rejectedCount)
    : legacyCatalogToken(catalog, partial);
  if (
    projectionId !== `${generation}-${token}` ||
    document.get("projectionId") !== projectionId ||
    document.get("itemCount") !== catalog.length ||
    partial !== (rejectedCount > 0) ||
    token !== expectedToken
  ) {
    projectionMalformed("Catalog projection count or hash is corrupt", "catalogProjection");
  }
  return { projectionId, generation, token, catalog, partial, rejectedCount };
}

async function readOwnerFromPointer(
  transaction: Transaction,
  firestore: Firestore,
  ownerUid: string,
  pointer: DocumentSnapshot,
): Promise<PublishedOwnerProjection> {
  const projectionId = boundedProjectionId(pointer.get("projectionId"), "projectionId");
  const document = await transaction.get(
    firestore.doc(`users/${ownerUid}/miniHomeProjections/${projectionId}`),
  );
  const parsed = parseOwnerProjectionDocument(document, ownerUid, projectionId);
  if (
    pointer.get("schemaVersion") !== PROJECTION_SCHEMA_VERSION ||
    pointer.get("ownerUid") !== ownerUid ||
    pointer.get("projectionId") !== projectionId ||
    pointer.get("generation") !== parsed.generation ||
    pointer.get("projectionToken") !== parsed.token ||
    pointer.get("catalogProjectionId") !== parsed.catalogProjectionId ||
    pointer.get("catalogToken") !== parsed.catalogToken
  ) {
    projectionMalformed("Mini-home projection pointer is torn", "projectionPointer");
  }
  return parsed;
}

function parseOwnerProjectionDocument(
  document: DocumentSnapshot,
  ownerUid: string,
  projectionId: string,
): PublishedOwnerProjection {
  if (!document.exists) projectionMalformed("Mini-home projection document is missing", "projection");
  const generation = positiveInteger(document.get("generation"), "projectionGeneration");
  const token = requireHash(document.get("projectionToken"), "projectionToken");
  const catalogProjectionId = boundedProjectionId(document.get("catalogProjectionId"), "catalogProjectionId");
  const catalogTokenValue = requireHash(document.get("catalogToken"), "catalogToken");
  const layout = parseStoredLayout(document.get("layout"), ownerUid);
  const ownedValue = document.get("owned");
  const plantsValue = document.get("plants");
  if (!Array.isArray(ownedValue) || !Array.isArray(plantsValue)) projectionMalformed("Mini-home projection arrays are malformed", "projection");
  if (ownedValue.length > MAX_OWNED_ITEMS) projectionExhausted("Ownership projection exceeds the 100 item bound", "owned");
  if (plantsValue.length > MAX_PLANTS) projectionExhausted("Plant projection exceeds the 200 item bound", "plants");
  const owned = ownedValue.map((value, index) => parseStoredOwned(value, index));
  const plants = plantsValue.map((value, index) => parseStoredPlant(value, ownerUid, index));
  const inventoryGeneration = positiveInteger(document.get("inventoryGeneration"), "inventoryGeneration");
  const inventorySnapshotHash = requireHash(document.get("inventorySnapshotHash"), "inventorySnapshotHash");
  const partial = boolean(document.get("partial"), "partial");
  const placementCount = layout.kind === "present" ? layout.placements.length : 0;
  if (
    document.get("schemaVersion") !== PROJECTION_SCHEMA_VERSION ||
    document.get("ownerUid") !== ownerUid ||
    document.get("projectionId") !== projectionId ||
    projectionId !== `${generation}-${token}` ||
    document.get("layoutPlacementCount") !== placementCount ||
    document.get("ownedCount") !== owned.length ||
    document.get("plantCount") !== plants.length
  ) {
    projectionMalformed("Mini-home projection count, identity, or owner is corrupt", "projection");
  }
  return {
    projectionId,
    ownerUid,
    generation,
    token,
    catalogProjectionId,
    catalogToken: catalogTokenValue,
    layout,
    owned: owned.sort(ownedOrder),
    plants: plants.sort((left, right) => left.plantId.localeCompare(right.plantId)),
    inventoryGeneration,
    inventorySnapshotHash,
    partial,
  };
}

async function readLegacyOwnerDraft(
  transaction: Transaction,
  firestore: Firestore,
  ownerUid: string,
  catalog: CatalogProjection,
  now: Timestamp,
): Promise<OwnerProjectionDraft> {
  const root = `users/${ownerUid}`;
  const [homes, state, placements, owned, plants] = await Promise.all([
    transaction.get(firestore.collection(`${root}/miniHomes`).limit(2)),
    transaction.get(firestore.doc(`${root}/miniHomeStates/current`)),
    transaction.get(firestore.collection(`${root}/placements`).limit(MAX_PLACEMENTS + 1)),
    transaction.get(firestore.collection(`${root}/ownedItems`).limit(MAX_OWNED_ITEMS + 1)),
    transaction.get(firestore.collection(`${root}/personalPlants`).limit(MAX_PLANTS + 1)),
  ]);
  if (owned.size > MAX_OWNED_ITEMS) projectionExhausted("Ownership projection exceeds the 100 item bound", "owned");
  if (plants.size > MAX_PLANTS) projectionExhausted("Plant projection exceeds the 200 item bound", "plants");
  if (placements.size > MAX_PLACEMENTS) projectionExhausted("Layout projection exceeds the 20 placement bound", "placements");
  let layout: MiniHomeLoadResult;
  try {
    layout = parseLegacyLayout(ownerUid, homes.docs, state, placements.docs, now);
  } catch (error) {
    if (!(error instanceof MiniHomeSnapshotError)) throw error;
    layout = {
      kind: "missing",
      ownerUid,
      generation: 1,
      tombstoneId: "legacy-unpublished",
      updatedAtEpochMillis: now.toMillis(),
    };
  }
  return {
    layout,
    owned: owned.docs.map((document) => projectionOwnedItem(document, ownerUid)).sort(ownedOrder),
    plants: plants.docs.map((document) => legacyProjectionPlant(document, ownerUid, now))
      .sort((left, right) => left.plantId.localeCompare(right.plantId)),
  };
}

function legacyProjectionPlant(
  document: QueryDocumentSnapshot,
  ownerUid: string,
  now: Timestamp,
): MiniHomeSnapshotPlant {
  if (document.get("ownerUid") !== ownerUid) {
    projectionMalformed("Stored personal plant owner is malformed", "plants");
  }
  const displayName = document.get("displayName");
  const photo = document.get("representativePhotoPath");
  const revision = document.get("revision");
  const updatedAt = document.get("updatedAt");
  return {
    plantId: requireOpaqueId(document.id, "plantId"),
    ownerUid,
    displayName:
      typeof displayName === "string" && [...displayName].length >= 1 && [...displayName].length <= 100
        ? displayName
        : document.id,
    representativePhotoPath:
      typeof photo === "string" && photo.length > 0 && photo.length <= 500 ? photo : null,
    revision:
      typeof revision === "number" && Number.isSafeInteger(revision) && revision >= 1
        ? revision
        : 1,
    updatedAtEpochMillis:
      updatedAt instanceof Timestamp ? updatedAt.toMillis() : now.toMillis(),
  };
}

function parseLegacyLayout(
  ownerUid: string,
  homes: readonly QueryDocumentSnapshot[],
  state: DocumentSnapshot,
  placements: readonly QueryDocumentSnapshot[],
  now: Timestamp,
): MiniHomeLoadResult {
  if (homes.length === 0) {
    if (placements.length !== 0) projectionMalformed("Deleted mini-home retains placements", "placements");
    if (!state.exists) {
      return {
        kind: "missing",
        ownerUid,
        generation: 1,
        tombstoneId: "initial-missing",
        updatedAtEpochMillis: now.toMillis(),
      };
    }
    if (state.get("ownerUid") !== ownerUid || state.get("state") !== "DELETED") {
      projectionMalformed("Stored Mini-home deletion state is malformed", "layoutState");
    }
    return {
      kind: "missing",
      ownerUid,
      generation: positiveInteger(state.get("revision"), "layoutGeneration"),
      tombstoneId: requireOperationId(state.get("tombstoneId"), "tombstoneId"),
      updatedAtEpochMillis: timestampMillis(state.get("updatedAt"), "updatedAt"),
    };
  }
  if (homes.length !== 1) projectionMalformed("Owner has multiple Mini-homes", "miniHomeId");
  const home = homes[0];
  if (home === undefined || home.get("ownerUid") !== ownerUid) projectionMalformed("Stored Mini-home owner is malformed", "ownerUid");
  const revision = positiveInteger(home.get("revision"), "layoutRevision");
  const expectedRevision = nonNegativeInteger(home.get("expectedRevision"), "expectedRevision");
  if (expectedRevision !== revision - 1) projectionMalformed("Stored Mini-home revision lineage is malformed", "expectedRevision");
  const miniHomeId = requireOpaqueId(home.id, "miniHomeId");
  const idempotencyKey = requireOperationId(home.get("idempotencyKey"), "idempotencyKey");
  const requestHash = requireHash(home.get("requestHash"), "requestHash");
  const name = recoverLegacyMiniHomeName(home.get("name"));
  if (name === null) projectionMalformed("Stored Mini-home name is malformed", "name");
  const placementCount = nonNegativeInteger(home.get("placementCount"), "placementCount");
  const placedPlantCount = nonNegativeInteger(home.get("placedPlantCount"), "placedPlantCount");
  if (placementCount !== placements.length) projectionMalformed("Stored Mini-home placement rows are partial", "placementCount");
  const placementIds = home.get("placementIds");
  if (!Array.isArray(placementIds) || placementIds.length !== placementCount) projectionMalformed("Stored placement IDs are malformed", "placementIds");
  const parsedPlacements = placements.map((document): MiniHomeAuthoritativePlacement => {
    if (document.get("ownerUid") !== ownerUid || document.get("miniHomeId") !== miniHomeId) {
      projectionMalformed("Stored placement owner is malformed", "placements");
    }
    const plantId = nullableOpaqueId(document.get("plantId"), "plantId");
    const itemId = nullableOpaqueId(document.get("itemId"), "itemId");
    if ((plantId === null) === (itemId === null)) projectionMalformed("Stored placement target is malformed", "placements");
    return {
      placementId: requireOpaqueId(document.id, "placementId"),
      ownerUid,
      miniHomeId,
      layoutRevision: positiveInteger(document.get("layoutRevision"), "layoutRevision"),
      plantId,
      itemId,
      normalizedX: coordinate(document.get("normalizedX"), "normalizedX"),
      normalizedY: coordinate(document.get("normalizedY"), "normalizedY"),
      zIndex: nonNegativeInteger(document.get("zIndex"), "zIndex"),
      revision: positiveInteger(document.get("revision"), "placementRevision"),
      expectedRevision: nonNegativeInteger(document.get("expectedRevision"), "placementExpectedRevision"),
      idempotencyKey: requireOperationId(document.get("idempotencyKey"), "placementOperation"),
      updatedAtEpochMillis: timestampMillis(document.get("updatedAt"), "updatedAt"),
    };
  }).sort((left, right) => left.zIndex - right.zIndex || left.placementId.localeCompare(right.placementId));
  if (parsedPlacements.some((placement, index) =>
    placement.layoutRevision !== revision || placement.revision !== revision ||
    placement.expectedRevision !== expectedRevision || placement.idempotencyKey !== idempotencyKey ||
    placement.zIndex !== index || placement.placementId !== placementIds[index]
  )) projectionMalformed("Stored placements differ from their committed layout", "placements");
  if (parsedPlacements.filter((placement) => placement.plantId !== null).length !== placedPlantCount) {
    projectionMalformed("Stored placed plant count is malformed", "placedPlantCount");
  }
  const generation = state.exists ? positiveInteger(state.get("revision"), "layoutGeneration") : revision;
  return {
    kind: "present",
    ownerUid,
    generation,
    miniHomeId,
    name,
    placedPlantCount,
    placementCount,
    revision,
    expectedRevision,
    idempotencyKey,
    requestHash,
    updatedAtEpochMillis: timestampMillis(home.get("updatedAt"), "updatedAt"),
    placements: parsedPlacements,
  };
}

function validateOwnerProjection(owner: PublishedOwnerProjection, catalog: CatalogProjection): void {
  validateDraft(owner.ownerUid, owner.layout, owner.owned, owner.plants);
  const inventory = inventoryContent(
    owner.ownerUid,
    catalog,
    owner.owned,
    owner.plants.length,
    Timestamp.fromMillis(0),
    owner.inventoryGeneration,
  );
  if (
    owner.inventorySnapshotHash !== inventory.snapshotHash ||
    owner.token !== ownerToken(owner.ownerUid, owner.layout, owner.inventoryGeneration, inventory.snapshotHash, owner.plants) ||
    owner.partial !== (catalog.partial || inventory.partial)
  ) {
    projectionMalformed("Mini-home projection token or inventory hash is corrupt", "projectionToken");
  }
}

function validateDraft(
  ownerUid: string,
  layout: MiniHomeLoadResult,
  owned: readonly ProjectionOwnedItem[],
  plants: readonly MiniHomeSnapshotPlant[],
): void {
  if (layout.ownerUid !== ownerUid) projectionMalformed("Layout projection owner is malformed", "layout");
  if (layout.kind === "present" && layout.placements.length > MAX_PLACEMENTS) projectionExhausted("Layout exceeds 20 placements", "placements");
  if (owned.length > MAX_OWNED_ITEMS) projectionExhausted("Ownership exceeds 100 items", "owned");
  if (plants.length > MAX_PLANTS) projectionExhausted("Plants exceed 200 items", "plants");
  if (new Set(owned.map((item) => item.itemId)).size !== owned.length) projectionMalformed("Ownership IDs are duplicated", "owned");
  if (new Set(plants.map((item) => item.plantId)).size !== plants.length) projectionMalformed("Plant IDs are duplicated", "plants");
}

function inventoryContent(
  ownerUid: string,
  catalogProjection: CatalogProjection,
  ownedProjection: readonly ProjectionOwnedItem[],
  registeredPlantCount: number,
  readTime: Timestamp,
  inventoryGeneration: number,
): InventorySnapshot {
  const byId = new Map(catalogProjection.catalog.map((item) => [item.itemId, item] as const));
  const owned: InventoryOwnedItem[] = ownedProjection.map((item) => {
    const available = byId.get(item.itemId);
    return {
      ...item,
      availability: available === undefined ? "UNAVAILABLE" as const : "AVAILABLE" as const,
      catalogSnapshot: available === undefined ? item.catalogSnapshot : catalogSnapshot(available),
    };
  }).sort(ownedOrder);
  const content = {
    ownerUid,
    catalog: catalogProjection.catalog,
    owned,
    registeredPlantCount,
    partial: catalogProjection.partial || owned.some((item) => item.availability === "UNAVAILABLE"),
  };
  return {
    contractVersion: INVENTORY_CONTRACT_VERSION,
    ...content,
    loadedAtEpochMillis: readTime.toMillis(),
    inventoryGeneration: Math.max(1, inventoryGeneration),
    snapshotHash: inventorySnapshotHash(content),
  };
}

function ownerToken(
  ownerUid: string,
  layout: MiniHomeLoadResult,
  inventoryGeneration: number,
  inventorySnapshotHashValue: string,
  plants: readonly MiniHomeSnapshotPlant[],
): string {
  return sha256({
    ownerUid,
    layout,
    inventoryGeneration,
    inventorySnapshotHash: inventorySnapshotHashValue,
    plants: [...plants].sort((left, right) => left.plantId.localeCompare(right.plantId)),
  });
}

function catalogToken(
  catalog: readonly InventoryCatalogItem[],
  partial: boolean,
  rejectedCount: number,
): string {
  return sha256({
    catalog: [...catalog].sort(catalogOrder),
    partial,
    rejectedCount,
  });
}

function legacyCatalogToken(catalog: readonly InventoryCatalogItem[], partial: boolean): string {
  return sha256({ catalog: [...catalog].sort(catalogOrder), partial });
}

function parseCatalogRecord(itemId: string, value: Readonly<Record<string, unknown>>): InventoryCatalogItem {
  if (value.publicationState !== "PUBLIC") throw inventoryMalformed("Catalog item is not public", "publicationState");
  const category = value.category;
  if (typeof category !== "string" || !CATEGORIES.includes(category as InventoryCategory)) {
    throw inventoryMalformed("Catalog category is malformed", "category");
  }
  const acquisitionCondition = value.acquisitionCondition;
  if (!(acquisitionCondition === null || acquisitionCondition === "registered-plant")) {
    throw inventoryMalformed("Catalog condition is malformed", "acquisitionCondition");
  }
  return {
    itemId: requireOpaqueId(itemId, "itemId"),
    name: boundedString(value.name, "name", 1, 100),
    description: boundedString(value.description, "description", 1, 500),
    category: category as InventoryCategory,
    mediaIdentity: parseMediaIdentity(itemId, value),
    acquisitionCondition,
    revision: positiveInteger(value.revision, "catalogRevision"),
    updatedAtEpochMillis: timestampMillis(value.updatedAt, "updatedAt"),
  };
}

function parseMediaIdentity(itemId: string, value: Readonly<Record<string, unknown>>): InventoryMediaIdentity {
  const path = value.assetPath ?? value.path;
  const sha256Value = value.assetSha256 ?? value.sha256;
  const byteSize = value.assetByteSize ?? value.byteSize;
  const mimeType = value.assetContentType ?? value.mimeType;
  const width = value.assetWidth ?? value.width;
  const height = value.assetHeight ?? value.height;
  const mediaRevision = value.assetMediaRevision ?? value.mediaRevision;
  if (
    typeof path !== "string" || typeof sha256Value !== "string" || !HASH.test(sha256Value) ||
    !new RegExp(`^catalog-assets/${itemId}/${sha256Value}\\.(?:png|jpe?g|webp)$`).test(path) ||
    typeof byteSize !== "number" || !Number.isSafeInteger(byteSize) || byteSize < 1 || byteSize > 8 * 1024 * 1024 ||
    typeof mimeType !== "string" || !MIME_TYPES.includes(mimeType as InventoryMediaIdentity["mimeType"]) ||
    typeof width !== "number" || !Number.isSafeInteger(width) || width < 1 || width > 32_768 ||
    typeof height !== "number" || !Number.isSafeInteger(height) || height < 1 || height > 32_768 ||
    typeof mediaRevision !== "number" || !Number.isSafeInteger(mediaRevision) || mediaRevision < 1 ||
    width * height > 64 * 1024 * 1024 || width * height * 4 > 256 * 1024 * 1024 ||
    width > height * 32 || height > width * 32 ||
    (path.endsWith(".png") ? mimeType !== "image/png" : path.endsWith(".webp") ? mimeType !== "image/webp" : mimeType !== "image/jpeg")
  ) throw inventoryMalformed("Catalog media identity is malformed", "assetIdentity");
  return {
    path,
    sha256: sha256Value,
    byteSize,
    mimeType: mimeType as InventoryMediaIdentity["mimeType"],
    width,
    height,
    mediaRevision,
  };
}

function parseOwnedCatalogSnapshot(document: DocumentSnapshot): InventoryOwnedCatalogSnapshot | null {
  const value = {
    name: document.get("nameSnapshot"),
    category: document.get("categorySnapshot"),
    assetPath: document.get("assetPathSnapshot"),
    assetSha256: document.get("assetSha256Snapshot"),
    assetByteSize: document.get("assetByteSizeSnapshot"),
    assetContentType: document.get("assetMimeTypeSnapshot"),
    assetWidth: document.get("assetWidthSnapshot"),
    assetHeight: document.get("assetHeightSnapshot"),
    assetMediaRevision: document.get("assetMediaRevisionSnapshot"),
    catalogRevision: document.get("catalogRevisionSnapshot"),
  };
  if (Object.values(value).every((item) => item === undefined)) return null;
  try {
    const category = value.category;
    if (typeof category !== "string" || !CATEGORIES.includes(category as InventoryCategory)) return null;
    return {
      name: boundedString(value.name, "nameSnapshot", 1, 100),
      category: category as InventoryCategory,
      mediaIdentity: parseMediaIdentity(document.id, value),
      catalogRevision: positiveInteger(value.catalogRevision, "catalogRevisionSnapshot"),
    };
  } catch {
    return null;
  }
}

function parseStoredLayout(value: unknown, ownerUid: string): MiniHomeLoadResult {
  if (!isRecord(value) || value.ownerUid !== ownerUid || (value.kind !== "present" && value.kind !== "missing")) {
    projectionMalformed("Stored projection layout is malformed", "layout");
  }
  if (value.kind === "missing") {
    return {
      kind: "missing",
      ownerUid,
      generation: positiveInteger(value.generation, "layoutGeneration"),
      tombstoneId: requireOperationId(value.tombstoneId, "tombstoneId"),
      updatedAtEpochMillis: nonNegativeInteger(value.updatedAtEpochMillis, "updatedAtEpochMillis"),
    };
  }
  if (!Array.isArray(value.placements) || value.placements.length > MAX_PLACEMENTS) {
    projectionMalformed("Stored projection placements are malformed", "placements");
  }
  const placements = value.placements.map((placement, index) => parseStoredPlacement(placement, ownerUid, index));
  return {
    kind: "present",
    ownerUid,
    generation: positiveInteger(value.generation, "layoutGeneration"),
    miniHomeId: requireOpaqueId(value.miniHomeId, "miniHomeId"),
    name: boundedString(value.name, "name", 1, 100),
    placedPlantCount: nonNegativeInteger(value.placedPlantCount, "placedPlantCount"),
    placementCount: nonNegativeInteger(value.placementCount, "placementCount"),
    revision: positiveInteger(value.revision, "layoutRevision"),
    expectedRevision: nonNegativeInteger(value.expectedRevision, "expectedRevision"),
    idempotencyKey: requireOperationId(value.idempotencyKey, "idempotencyKey"),
    requestHash: requireHash(value.requestHash, "requestHash"),
    updatedAtEpochMillis: nonNegativeInteger(value.updatedAtEpochMillis, "updatedAtEpochMillis"),
    placements,
  };
}

function parseStoredPlacement(value: unknown, ownerUid: string, index: number): MiniHomeAuthoritativePlacement {
  if (!isRecord(value) || value.ownerUid !== ownerUid) projectionMalformed("Stored projection placement is malformed", `placements[${index}]`);
  return {
    placementId: requireOpaqueId(value.placementId, "placementId"),
    ownerUid,
    miniHomeId: requireOpaqueId(value.miniHomeId, "miniHomeId"),
    layoutRevision: positiveInteger(value.layoutRevision, "layoutRevision"),
    plantId: nullableOpaqueId(value.plantId, "plantId"),
    itemId: nullableOpaqueId(value.itemId, "itemId"),
    normalizedX: coordinate(value.normalizedX, "normalizedX"),
    normalizedY: coordinate(value.normalizedY, "normalizedY"),
    zIndex: nonNegativeInteger(value.zIndex, "zIndex"),
    revision: positiveInteger(value.revision, "placementRevision"),
    expectedRevision: nonNegativeInteger(value.expectedRevision, "placementExpectedRevision"),
    idempotencyKey: requireOperationId(value.idempotencyKey, "placementOperation"),
    updatedAtEpochMillis: nonNegativeInteger(value.updatedAtEpochMillis, "updatedAtEpochMillis"),
  };
}

function parseStoredOwned(value: unknown, index: number): ProjectionOwnedItem {
  if (!isRecord(value)) projectionMalformed("Stored projection ownership is malformed", `owned[${index}]`);
  return {
    itemId: requireOpaqueId(value.itemId, "itemId"),
    acquiredAtEpochMillis: nonNegativeInteger(value.acquiredAtEpochMillis, "acquiredAtEpochMillis"),
    applied: boolean(value.applied, "applied"),
    revision: positiveInteger(value.revision, "ownershipRevision"),
    catalogSnapshot: parseStoredOwnedSnapshot(value.catalogSnapshot),
  };
}

function parseStoredOwnedSnapshot(value: unknown): InventoryOwnedCatalogSnapshot | null {
  if (value === null) return null;
  if (!isRecord(value) || !isRecord(value.mediaIdentity)) projectionMalformed("Stored ownership catalog snapshot is malformed", "catalogSnapshot");
  const category = value.category;
  if (typeof category !== "string" || !CATEGORIES.includes(category as InventoryCategory)) projectionMalformed("Stored ownership category is malformed", "catalogSnapshot");
  return {
    name: boundedString(value.name, "name", 1, 100),
    category: category as InventoryCategory,
    mediaIdentity: parseStoredMediaIdentity(value.mediaIdentity),
    catalogRevision: positiveInteger(value.catalogRevision, "catalogRevision"),
  };
}

function parseStoredMediaIdentity(value: Readonly<Record<string, unknown>>): InventoryMediaIdentity {
  const mimeType = value.mimeType;
  if (
    typeof value.path !== "string" || typeof value.sha256 !== "string" || !HASH.test(value.sha256) ||
    typeof value.byteSize !== "number" || !Number.isSafeInteger(value.byteSize) || value.byteSize < 1 ||
    typeof mimeType !== "string" || !MIME_TYPES.includes(mimeType as InventoryMediaIdentity["mimeType"]) ||
    typeof value.width !== "number" || !Number.isSafeInteger(value.width) || value.width < 1 ||
    typeof value.height !== "number" || !Number.isSafeInteger(value.height) || value.height < 1 ||
    typeof value.mediaRevision !== "number" || !Number.isSafeInteger(value.mediaRevision) || value.mediaRevision < 1
  ) projectionMalformed("Stored media identity is malformed", "mediaIdentity");
  return {
    path: value.path,
    sha256: value.sha256,
    byteSize: value.byteSize,
    mimeType: mimeType as InventoryMediaIdentity["mimeType"],
    width: value.width,
    height: value.height,
    mediaRevision: value.mediaRevision,
  };
}

function parseStoredPlant(value: unknown, ownerUid: string, index: number): MiniHomeSnapshotPlant {
  if (!isRecord(value) || value.ownerUid !== ownerUid) projectionMalformed("Stored projection plant is malformed", `plants[${index}]`);
  const photo = value.representativePhotoPath;
  if (!(photo === null || (typeof photo === "string" && photo.length > 0 && photo.length <= 500))) {
    projectionMalformed("Stored projection plant photo is malformed", `plants[${index}]`);
  }
  return {
    plantId: requireOpaqueId(value.plantId, "plantId"),
    ownerUid,
    displayName: boundedString(value.displayName, "displayName", 1, 100),
    representativePhotoPath: photo,
    revision: positiveInteger(value.revision, "plantRevision"),
    updatedAtEpochMillis: nonNegativeInteger(value.updatedAtEpochMillis, "updatedAtEpochMillis"),
  };
}

function catalogSnapshot(item: InventoryCatalogItem): InventoryOwnedCatalogSnapshot {
  return {
    name: item.name,
    category: item.category,
    mediaIdentity: item.mediaIdentity,
    catalogRevision: item.revision,
  };
}

function ownerPointerRef(firestore: Firestore, ownerUid: string) {
  return firestore.doc(`users/${ownerUid}/miniHomeProjectionPointers/current`);
}

function ownedOrder(left: ProjectionOwnedItem, right: ProjectionOwnedItem): number {
  return right.acquiredAtEpochMillis - left.acquiredAtEpochMillis || left.itemId.localeCompare(right.itemId);
}

function catalogOrder(left: InventoryCatalogItem, right: InventoryCatalogItem): number {
  return CATEGORIES.indexOf(left.category) - CATEGORIES.indexOf(right.category) ||
    left.name.localeCompare(right.name) || left.itemId.localeCompare(right.itemId);
}

function sha256(value: unknown): string {
  return createHash("sha256").update(canonicalJson(value), "utf8").digest("hex");
}

function canonicalJson(value: unknown): string {
  if (value === null || typeof value === "boolean" || typeof value === "number" || typeof value === "string") return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  const entries = Object.entries(value as Readonly<Record<string, unknown>>).sort(([left], [right]) => left.localeCompare(right));
  return `{${entries.map(([key, item]) => `${JSON.stringify(key)}:${canonicalJson(item)}`).join(",")}}`;
}

function boundedProjectionId(value: unknown, field: string): string {
  if (typeof value !== "string" || !/^[1-9][0-9]{0,15}-[a-f0-9]{64}$/.test(value)) projectionMalformed(`Stored ${field} is malformed`, field);
  return value;
}

function requireHash(value: unknown, field: string): string {
  if (typeof value !== "string" || !HASH.test(value)) projectionMalformed(`Stored ${field} is malformed`, field);
  return value;
}

function requireOpaqueId(value: unknown, field: string): string {
  if (typeof value !== "string" || !OPAQUE_ID.test(value)) projectionMalformed(`Stored ${field} is malformed`, field);
  return value;
}

function requireOperationId(value: unknown, field: string): string {
  if (typeof value !== "string" || !OPERATION_ID.test(value)) projectionMalformed(`Stored ${field} is malformed`, field);
  return value;
}

function nullableOpaqueId(value: unknown, field: string): string | null {
  if (value === null) return null;
  return requireOpaqueId(value, field);
}

function boundedString(value: unknown, field: string, minimum: number, maximum: number): string {
  if (typeof value !== "string" || [...value].length < minimum || [...value].length > maximum) {
    projectionMalformed(`Stored ${field} is malformed`, field);
  }
  return value;
}

function positiveInteger(value: unknown, field: string): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 1 || value >= Number.MAX_SAFE_INTEGER) {
    projectionMalformed(`Stored ${field} is malformed`, field);
  }
  return value;
}

function nonNegativeInteger(value: unknown, field: string): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0 || value >= Number.MAX_SAFE_INTEGER) {
    projectionMalformed(`Stored ${field} is malformed`, field);
  }
  return value;
}

function boolean(value: unknown, field: string): boolean {
  if (typeof value !== "boolean") projectionMalformed(`Stored ${field} is malformed`, field);
  return value;
}

function coordinate(value: unknown, field: string): number {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0 || value > 1) projectionMalformed(`Stored ${field} is malformed`, field);
  return value;
}

function timestampMillis(value: unknown, field: string): number {
  if (value === null || typeof value !== "object" || !("toMillis" in value) || typeof value.toMillis !== "function") {
    projectionMalformed(`Stored ${field} is malformed`, field);
  }
  return nonNegativeInteger(value.toMillis(), field);
}

function isRecord(value: unknown): value is Readonly<Record<string, unknown>> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function isCatalogRecordRejection(error: unknown): boolean {
  return error instanceof InventoryError ||
    (error instanceof MiniHomeSnapshotError && error.code === "data-loss");
}

function inventoryMalformed(message: string, field: string): InventoryError {
  return new InventoryError("data-loss", message, "MALFORMED_RESPONSE", { field });
}

function projectionMalformed(message: string, field: string): never {
  throw new MiniHomeSnapshotError("data-loss", message, "MALFORMED_RESPONSE", { field });
}

function projectionExhausted(message: string, field: string): never {
  throw new MiniHomeSnapshotError("resource-exhausted", message, "MALFORMED_RESPONSE", { field });
}
