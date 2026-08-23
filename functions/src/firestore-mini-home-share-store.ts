import { createHash } from "node:crypto";
import { Timestamp, type DocumentSnapshot, type Firestore } from "firebase-admin/firestore";
import { readPublishedOwnerProjection, projectionSnapshot } from "./firestore-mini-home-projection.js";
import {
  MINI_HOME_SHARE_CONTRACT_VERSION,
  MINI_HOME_SHARE_LIFETIME_MILLIS,
  MiniHomeShareError,
  deriveMiniHomeShareIdentity,
  sanitizeMiniHomeShareSnapshot,
  type CreateMiniHomeShareCommand,
  type CreateMiniHomeShareResult,
  type MiniHomeShareStore,
  type PublicMiniHomePlacement,
  type PublicMiniHomeShareSnapshot,
  type RevokeMiniHomeShareCommand,
} from "./mini-home-share.js";

const HASH = /^[a-f0-9]{64}$/;
const BASE64URL_256 = /^[A-Za-z0-9_-]{43}$/;
const SHARE_SCHEMA_VERSION = 1;

export class FirestoreMiniHomeShareStore implements MiniHomeShareStore {
  constructor(private readonly firestore: Firestore) {}

  async create(command: CreateMiniHomeShareCommand): Promise<CreateMiniHomeShareResult> {
    const lookupIdentity = deriveMiniHomeShareIdentity(
      command.tokenKey,
      command.ownerUid,
      command.operationId,
      "operation-lookup",
    );
    const metadataRef = this.firestore.doc(
      `users/${command.ownerUid}/shareLinks/${lookupIdentity.shareId}`,
    );
    const operationHash = sha256(`planterior:mini-home-share-operation:v1\0${command.operationId}`);
    const envelopeHash = sha256(canonicalJson({ expectedRevision: command.expectedRevision }));

    return this.firestore.runTransaction(async (transaction) => {
      const existing = await transaction.get(metadataRef);
      if (existing.exists) {
        const replay = parseMetadata(existing, command.ownerUid, lookupIdentity.shareId);
        if (replay.operationHash !== operationHash || replay.envelopeHash !== envelopeHash) {
          throw new MiniHomeShareError(
            "already-exists",
            "The operation identifier was already used with a different request",
            { field: "operationId" },
          );
        }
        const identity = deriveMiniHomeShareIdentity(
          command.tokenKey,
          command.ownerUid,
          command.operationId,
          replay.projectionToken,
        );
        if (identity.shareId !== replay.shareId || identity.tokenHash !== replay.tokenHash) {
          throw new MiniHomeShareError("data-loss", "Stored share identity is malformed", { field: "shareLink" });
        }
        const publicDocument = await transaction.get(this.firestore.doc(`publicShares/${identity.tokenHash}`));
        assertPublicMirror(publicDocument, replay);
        return result(replay, identity.token, command.publicEndpoint);
      }

      const pointer = await transaction.get(
        this.firestore.doc(`users/${command.ownerUid}/miniHomeProjectionPointers/current`),
      );
      if (!pointer.exists) {
        throw new MiniHomeShareError("failed-precondition", "A saved Mini-home layout is required", { field: "layout" });
      }
      let published: Awaited<ReturnType<typeof readPublishedOwnerProjection>>;
      try {
        published = await readPublishedOwnerProjection(
          transaction,
          this.firestore,
          command.ownerUid,
        );
      } catch (error) {
        if (error instanceof MiniHomeShareError) throw error;
        throw new MiniHomeShareError("data-loss", "The saved Mini-home projection is malformed", { field: "projection" });
      }
      const snapshot = projectionSnapshot(published, Timestamp.fromDate(command.now));
      if (snapshot.layout.kind !== "present") {
        throw new MiniHomeShareError("failed-precondition", "A saved Mini-home layout is required", { field: "layout" });
      }
      if (snapshot.layout.revision !== command.expectedRevision) {
        throw new MiniHomeShareError(
          "failed-precondition",
          "The saved Mini-home revision changed",
          { field: "expectedRevision", actualRevision: snapshot.layout.revision },
        );
      }
      const identity = deriveMiniHomeShareIdentity(
        command.tokenKey,
        command.ownerUid,
        command.operationId,
        snapshot.snapshotToken,
      );
      if (identity.shareId !== lookupIdentity.shareId) {
        throw new MiniHomeShareError("data-loss", "Derived share identity is unstable", { field: "shareId" });
      }
      const publicRef = this.firestore.doc(`publicShares/${identity.tokenHash}`);
      if ((await transaction.get(publicRef)).exists) {
        throw new MiniHomeShareError("already-exists", "The derived share identity already exists", { field: "tokenHash" });
      }
      const publicSnapshot = sanitizeMiniHomeShareSnapshot(snapshot, identity.shareId, command.now);
      const createdAt = Timestamp.fromDate(command.now);
      const expiresAt = Timestamp.fromMillis(command.now.getTime() + MINI_HOME_SHARE_LIFETIME_MILLIS);
      const common = {
        schemaVersion: SHARE_SCHEMA_VERSION,
        shareId: identity.shareId,
        tokenHash: identity.tokenHash,
        sourceRevision: snapshot.layout.revision,
        sourceProjectionGeneration: snapshot.snapshotGeneration,
        projectionToken: snapshot.snapshotToken,
        createdAt,
        expiresAt,
        revokedAt: null,
      };
      transaction.create(metadataRef, {
        ...common,
        ownerUid: command.ownerUid,
        operationHash,
        envelopeHash,
      });
      transaction.create(publicRef, {
        ...common,
        state: "ACTIVE",
        snapshot: publicSnapshot,
      });
      return result(common, identity.token, command.publicEndpoint);
    }, { maxAttempts: 5 });
  }

  async revoke(command: RevokeMiniHomeShareCommand): Promise<string> {
    const metadataRef = this.firestore.doc(`users/${command.ownerUid}/shareLinks/${command.shareId}`);
    return this.firestore.runTransaction(async (transaction) => {
      const metadataDocument = await transaction.get(metadataRef);
      if (!metadataDocument.exists) {
        throw new MiniHomeShareError("not-found", "Share link was not found", { field: "shareId" });
      }
      const metadata = parseMetadata(metadataDocument, command.ownerUid, command.shareId);
      const publicRef = this.firestore.doc(`publicShares/${metadata.tokenHash}`);
      const publicDocument = await transaction.get(publicRef);
      const publicState = assertPublicMirror(publicDocument, metadata);
      if (metadata.revokedAt !== null) {
        if (publicState.state !== "REVOKED" || publicState.revokedAt?.toMillis() !== metadata.revokedAt.toMillis()) {
          throw new MiniHomeShareError("data-loss", "Stored share revocation is torn", { field: "revokedAt" });
        }
        return metadata.revokedAt.toDate().toISOString();
      }
      if (publicState.state !== "ACTIVE" || publicState.revokedAt !== null) {
        throw new MiniHomeShareError("data-loss", "Stored share revocation is torn", { field: "revokedAt" });
      }
      const revokedAt = Timestamp.fromDate(command.now);
      transaction.update(metadataRef, { revokedAt });
      transaction.update(publicRef, { state: "REVOKED", revokedAt });
      return revokedAt.toDate().toISOString();
    }, { maxAttempts: 5 });
  }

  async loadPublic(tokenHash: string, now: Date): Promise<PublicMiniHomeShareSnapshot | null> {
    if (!HASH.test(tokenHash)) return null;
    const document = await this.firestore.doc(`publicShares/${tokenHash}`).get();
    if (!document.exists) return null;
    try {
      if (
        document.id !== tokenHash ||
        document.get("schemaVersion") !== SHARE_SCHEMA_VERSION ||
        document.get("tokenHash") !== tokenHash ||
        document.get("state") !== "ACTIVE" ||
        document.get("revokedAt") !== null
      ) return null;
      const shareId = document.get("shareId");
      const projectionToken = document.get("projectionToken");
      const sourceRevision = positiveInteger(document.get("sourceRevision"));
      const sourceProjectionGeneration = positiveInteger(document.get("sourceProjectionGeneration"));
      const createdAt = timestamp(document.get("createdAt"));
      const expiresAt = timestamp(document.get("expiresAt"));
      if (
        !BASE64URL_256.test(shareId) ||
        !HASH.test(projectionToken) ||
        sourceProjectionGeneration < 1 ||
        expiresAt.toMillis() - createdAt.toMillis() !== MINI_HOME_SHARE_LIFETIME_MILLIS ||
        now.getTime() >= expiresAt.toMillis()
      ) return null;
      const snapshot = parsePublicSnapshot(document.get("snapshot"));
      if (
        snapshot.sourceRevision !== sourceRevision ||
        snapshot.createdAt !== createdAt.toDate().toISOString() ||
        snapshot.expiresAt !== expiresAt.toDate().toISOString()
      ) return null;
      return snapshot;
    } catch {
      return null;
    }
  }
}

type ParsedMetadata = Readonly<{
  shareId: string;
  ownerUid: string;
  tokenHash: string;
  sourceRevision: number;
  sourceProjectionGeneration: number;
  projectionToken: string;
  operationHash: string;
  envelopeHash: string;
  createdAt: Timestamp;
  expiresAt: Timestamp;
  revokedAt: Timestamp | null;
}>;

function parseMetadata(document: DocumentSnapshot, ownerUid: string, shareId: string): ParsedMetadata {
  const createdAt = storedTimestamp(document.get("createdAt"), "shareLink");
  const expiresAt = storedTimestamp(document.get("expiresAt"), "shareLink");
  const revokedValue = document.get("revokedAt");
  const revokedAt = revokedValue === null ? null : storedTimestamp(revokedValue, "shareLink");
  const parsed = {
    shareId: string(document.get("shareId")),
    ownerUid: string(document.get("ownerUid")),
    tokenHash: string(document.get("tokenHash")),
    sourceRevision: positiveInteger(document.get("sourceRevision")),
    sourceProjectionGeneration: positiveInteger(document.get("sourceProjectionGeneration")),
    projectionToken: string(document.get("projectionToken")),
    operationHash: string(document.get("operationHash")),
    envelopeHash: string(document.get("envelopeHash")),
    createdAt,
    expiresAt,
    revokedAt,
  };
  if (
    document.get("schemaVersion") !== SHARE_SCHEMA_VERSION ||
    document.id !== shareId ||
    parsed.shareId !== shareId ||
    parsed.ownerUid !== ownerUid ||
    !BASE64URL_256.test(parsed.shareId) ||
    !HASH.test(parsed.tokenHash) ||
    !HASH.test(parsed.projectionToken) ||
    !HASH.test(parsed.operationHash) ||
    !HASH.test(parsed.envelopeHash) ||
    parsed.expiresAt.toMillis() - parsed.createdAt.toMillis() !== MINI_HOME_SHARE_LIFETIME_MILLIS ||
    (parsed.revokedAt !== null && parsed.revokedAt.toMillis() < parsed.createdAt.toMillis())
  ) throw new MiniHomeShareError("data-loss", "Stored share metadata is malformed", { field: "shareLink" });
  return parsed;
}

function assertPublicMirror(
  document: DocumentSnapshot,
  metadata: ParsedMetadata,
): Readonly<{ state: "ACTIVE" | "REVOKED"; revokedAt: Timestamp | null }> {
  if (!document.exists) throw new MiniHomeShareError("data-loss", "Stored public share is missing", { field: "publicShare" });
  const revokedValue = document.get("revokedAt");
  const revokedAt = revokedValue === null ? null : storedTimestamp(revokedValue, "publicShare");
  const state = document.get("state");
  if (
    document.id !== metadata.tokenHash ||
    document.get("schemaVersion") !== SHARE_SCHEMA_VERSION ||
    document.get("shareId") !== metadata.shareId ||
    document.get("tokenHash") !== metadata.tokenHash ||
    document.get("sourceRevision") !== metadata.sourceRevision ||
    document.get("sourceProjectionGeneration") !== metadata.sourceProjectionGeneration ||
    document.get("projectionToken") !== metadata.projectionToken ||
    storedTimestamp(document.get("createdAt"), "publicShare").toMillis() !== metadata.createdAt.toMillis() ||
    storedTimestamp(document.get("expiresAt"), "publicShare").toMillis() !== metadata.expiresAt.toMillis() ||
    (state !== "ACTIVE" && state !== "REVOKED") ||
    (state === "ACTIVE" && revokedAt !== null) ||
    (state === "REVOKED" && revokedAt === null)
  ) throw new MiniHomeShareError("data-loss", "Stored public share is malformed", { field: "publicShare" });
  return { state, revokedAt };
}

function result(
  metadata: Pick<ParsedMetadata, "shareId" | "sourceRevision" | "createdAt" | "expiresAt">,
  token: string,
  publicEndpoint: string,
): CreateMiniHomeShareResult {
  const url = new URL(publicEndpoint);
  url.searchParams.set("token", token);
  return {
    shareId: metadata.shareId,
    url: url.toString(),
    sourceRevision: metadata.sourceRevision,
    createdAt: metadata.createdAt.toDate().toISOString(),
    expiresAt: metadata.expiresAt.toDate().toISOString(),
  };
}

function parsePublicSnapshot(value: unknown): PublicMiniHomeShareSnapshot {
  if (!record(value) || !record(value.grid) || !Array.isArray(value.placements)) throw new Error("snapshot");
  if (
    value.contractVersion !== MINI_HOME_SHARE_CONTRACT_VERSION ||
    value.grid.columns !== 5 || value.grid.rows !== 4 || value.grid.projection !== "isometric" ||
    value.placements.length > 20 ||
    typeof value.createdAt !== "string" || typeof value.expiresAt !== "string"
  ) throw new Error("snapshot");
  const sourceRevision = positiveInteger(value.sourceRevision);
  const placements = value.placements.map(parsePlacement);
  return {
    contractVersion: MINI_HOME_SHARE_CONTRACT_VERSION,
    sourceRevision,
    createdAt: value.createdAt,
    expiresAt: value.expiresAt,
    grid: { columns: 5, rows: 4, projection: "isometric" },
    placements,
  };
}

function parsePlacement(value: unknown, index: number): PublicMiniHomePlacement {
  if (!record(value) || !record(value.position)) throw new Error("placement");
  const position = {
    x: coordinate(value.position.x),
    y: coordinate(value.position.y),
    z: nonNegativeInteger(value.position.z),
  };
  if (position.z !== index) throw new Error("placement");
  const ordinal = positiveInteger(value.ordinal);
  if (value.kind === "plant" && record(value.style)) {
    const variant = positiveInteger(value.style.variant);
    if (variant > 6 || value.style.scale !== 1) throw new Error("placement");
    return { kind: "plant", ordinal, style: { variant, scale: 1 }, position };
  }
  if (value.kind === "decoration" && record(value.style)) {
    const variant = positiveInteger(value.style.variant);
    if (variant > 4) throw new Error("placement");
    return { kind: "decoration", ordinal, style: { variant }, position };
  }
  if (value.kind === "catalog-item" && record(value.media)) {
    const category = value.category;
    const mimeType = value.media.mimeType;
    if (
      typeof value.catalogItemId !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(value.catalogItemId) ||
      typeof value.displayName !== "string" || [...value.displayName].length < 1 || [...value.displayName].length > 100 ||
      (category !== "BACKGROUND" && category !== "FURNITURE" && category !== "DECORATION") ||
      typeof value.media.path !== "string" || typeof value.media.sha256 !== "string" || !HASH.test(value.media.sha256) ||
      (mimeType !== "image/png" && mimeType !== "image/jpeg" && mimeType !== "image/webp")
    ) throw new Error("placement");
    const expectedPrefix = `catalog-assets/${value.catalogItemId}/${value.media.sha256}`;
    if (
      !(
        (value.media.path === `${expectedPrefix}.png` && mimeType === "image/png") ||
        ((value.media.path === `${expectedPrefix}.jpg` || value.media.path === `${expectedPrefix}.jpeg`) && mimeType === "image/jpeg") ||
        (value.media.path === `${expectedPrefix}.webp` && mimeType === "image/webp")
      )
    ) throw new Error("placement");
    const byteSize = positiveInteger(value.media.byteSize);
    const width = positiveInteger(value.media.width);
    const height = positiveInteger(value.media.height);
    const mediaRevision = positiveInteger(value.media.mediaRevision);
    if (
      byteSize > 8 * 1024 * 1024 || width > 32_768 || height > 32_768 ||
      width * height > 64 * 1024 * 1024 || width * height * 4 > 256 * 1024 * 1024 ||
      width > height * 32 || height > width * 32
    ) throw new Error("placement");
    return {
      kind: "catalog-item",
      ordinal,
      catalogItemId: value.catalogItemId,
      displayName: value.displayName,
      category,
      media: {
        path: value.media.path,
        sha256: value.media.sha256,
        byteSize,
        mimeType,
        width,
        height,
        mediaRevision,
      },
      position,
    };
  }
  throw new Error("placement");
}

function record(value: unknown): value is Readonly<Record<string, unknown>> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function timestamp(value: unknown): Timestamp {
  if (!(value instanceof Timestamp)) throw new Error("timestamp");
  return value;
}

function storedTimestamp(value: unknown, field: "shareLink" | "publicShare"): Timestamp {
  if (!(value instanceof Timestamp)) {
    throw new MiniHomeShareError("data-loss", "Stored share timestamp is malformed", { field });
  }
  return value;
}

function positiveInteger(value: unknown): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 1) throw new Error("integer");
  return value;
}

function nonNegativeInteger(value: unknown): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) throw new Error("integer");
  return value;
}

function coordinate(value: unknown): number {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0 || value > 1) throw new Error("coordinate");
  return value;
}

function string(value: unknown): string {
  if (typeof value !== "string") throw new Error("string");
  return value;
}

function sha256(value: string): string {
  return createHash("sha256").update(value, "utf8").digest("hex");
}

function canonicalJson(value: Readonly<Record<string, unknown>>): string {
  return `{${Object.entries(value).sort(([a], [b]) => a.localeCompare(b)).map(([key, item]) => `${JSON.stringify(key)}:${JSON.stringify(item)}`).join(",")}}`;
}
