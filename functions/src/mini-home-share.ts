import { createHash, createHmac } from "node:crypto";
import type { Request, Response } from "express";
import type { AuthContext } from "./contracts.js";
import type { InventoryCatalogItem } from "./inventory.js";
import type { MiniHomeSnapshot } from "./mini-home-snapshot.js";

export const MINI_HOME_SHARE_LIFETIME_MILLIS = 30 * 24 * 60 * 60 * 1000;
export const MINI_HOME_SHARE_CONTRACT_VERSION = 1 as const;

const OPERATION_ID = /^[A-Za-z0-9_-]{8,128}$/;
const SHARE_ID = /^[A-Za-z0-9_-]{43}$/;
const TOKEN = /^[A-Za-z0-9_-]{43}$/;
const MAX_PLACEMENTS = 20;

export type MiniHomeShareErrorCode =
  | "unauthenticated"
  | "invalid-argument"
  | "failed-precondition"
  | "already-exists"
  | "not-found"
  | "data-loss";

export class MiniHomeShareError extends Error {
  constructor(
    readonly code: MiniHomeShareErrorCode,
    message: string,
    readonly details?: Readonly<Record<string, string | number>>,
  ) {
    super(message);
    this.name = "MiniHomeShareError";
  }
}

export type MiniHomeShareIdentity = Readonly<{
  shareId: string;
  token: string;
  tokenHash: string;
}>;

type PublicPosition = Readonly<{ x: number; y: number; z: number }>;
type PublicMediaIdentity = Readonly<{
  path: string;
  sha256: string;
  byteSize: number;
  mimeType: "image/png" | "image/jpeg" | "image/webp";
  width: number;
  height: number;
  mediaRevision: number;
}>;

export type PublicMiniHomePlacement =
  | Readonly<{
      kind: "plant";
      ordinal: number;
      style: Readonly<{ variant: number; scale: number }>;
      position: PublicPosition;
    }>
  | Readonly<{
      kind: "catalog-item";
      ordinal: number;
      catalogItemId: string;
      displayName: string;
      category: "BACKGROUND" | "FURNITURE" | "DECORATION";
      media: PublicMediaIdentity;
      position: PublicPosition;
    }>
  | Readonly<{
      kind: "decoration";
      ordinal: number;
      style: Readonly<{ variant: number }>;
      position: PublicPosition;
    }>;

export type PublicMiniHomeShareSnapshot = Readonly<{
  contractVersion: typeof MINI_HOME_SHARE_CONTRACT_VERSION;
  sourceRevision: number;
  createdAt: string;
  expiresAt: string;
  grid: Readonly<{ columns: 5; rows: 4; projection: "isometric" }>;
  placements: readonly PublicMiniHomePlacement[];
}>;

export type CreateMiniHomeShareResult = Readonly<{
  shareId: string;
  url: string;
  sourceRevision: number;
  createdAt: string;
  expiresAt: string;
}>;

export type CreateMiniHomeShareCommand = Readonly<{
  ownerUid: string;
  operationId: string;
  expectedRevision: number;
  tokenKey: string;
  now: Date;
  publicEndpoint: string;
}>;

export type RevokeMiniHomeShareCommand = Readonly<{
  ownerUid: string;
  shareId: string;
  now: Date;
}>;

export type RevokeMiniHomeShareResult = Readonly<{
  shareId: string;
  revokedAt: string;
}>;

export interface MiniHomeShareStore {
  create(command: CreateMiniHomeShareCommand): Promise<CreateMiniHomeShareResult>;
  revoke(command: RevokeMiniHomeShareCommand): Promise<string>;
  loadPublic(tokenHash: string, now: Date): Promise<PublicMiniHomeShareSnapshot | null>;
}

export function deriveMiniHomeShareIdentity(
  tokenKey: string,
  ownerUid: string,
  operationId: string,
  projectionIdentity: string,
): MiniHomeShareIdentity {
  requireTokenKey(tokenKey);
  const shareId = createHash("sha256")
    .update("planterior:mini-home-share-id:v1\0", "utf8")
    .update(ownerUid, "utf8")
    .update("\0", "utf8")
    .update(operationId, "utf8")
    .digest("base64url");
  const token = createHmac("sha256", tokenKey)
    .update("planterior:mini-home-share-token:v1\0", "utf8")
    .update(ownerUid, "utf8")
    .update("\0", "utf8")
    .update(operationId, "utf8")
    .update("\0", "utf8")
    .update(projectionIdentity, "utf8")
    .digest("base64url");
  return {
    shareId,
    token,
    tokenHash: createHash("sha256").update(token, "utf8").digest("hex"),
  };
}

export async function executeCreateMiniHomeShareLink(
  auth: AuthContext | null,
  input: unknown,
  store: MiniHomeShareStore,
  tokenKey: string,
  now: Date,
  publicEndpoint: string,
): Promise<CreateMiniHomeShareResult> {
  const ownerUid = authenticatedOwner(auth);
  const request = exactRecord(input, ["operationId", "expectedRevision"]);
  const operationId = request.operationId;
  if (typeof operationId !== "string" || !OPERATION_ID.test(operationId)) {
    invalid("operationId must be an 8 to 128 character path-safe identifier", "operationId");
  }
  const expectedRevision = request.expectedRevision;
  if (
    typeof expectedRevision !== "number" ||
    !Number.isSafeInteger(expectedRevision) ||
    expectedRevision < 1
  ) invalid("expectedRevision must be a JS-safe positive integer", "expectedRevision");
  requireTokenKey(tokenKey);
  requireNow(now);
  requirePublicEndpoint(publicEndpoint);
  return store.create({ ownerUid, operationId, expectedRevision, tokenKey, now, publicEndpoint });
}

export async function executeRevokeMiniHomeShareLink(
  auth: AuthContext | null,
  input: unknown,
  store: MiniHomeShareStore,
  now: Date,
): Promise<RevokeMiniHomeShareResult> {
  const ownerUid = authenticatedOwner(auth);
  const request = exactRecord(input, ["shareId"]);
  const shareId = request.shareId;
  if (typeof shareId !== "string" || !SHARE_ID.test(shareId)) {
    invalid("shareId must be a 43 character base64url identifier", "shareId");
  }
  requireNow(now);
  const revokedAt = await store.revoke({ ownerUid, shareId, now });
  return { shareId, revokedAt };
}

export function sanitizeMiniHomeShareSnapshot(
  snapshot: MiniHomeSnapshot,
  _shareId: string,
  createdAt: Date,
): PublicMiniHomeShareSnapshot {
  if (snapshot.layout.kind !== "present") {
    throw new MiniHomeShareError("failed-precondition", "A saved Mini-home layout is required", { field: "layout" });
  }
  if (
    snapshot.ownerUid !== snapshot.layout.ownerUid ||
    snapshot.layout.placementCount !== snapshot.layout.placements.length ||
    snapshot.layout.placements.length > MAX_PLACEMENTS
  ) throw new MiniHomeShareError("data-loss", "The saved Mini-home projection is malformed", { field: "layout" });
  requireNow(createdAt);
  const layout = snapshot.layout;
  const catalog = new Map(snapshot.inventory.catalog.map((item) => [item.itemId, item] as const));
  let plantOrdinal = 0;
  const placements = layout.placements.map((placement, index): PublicMiniHomePlacement => {
    if (
      placement.ownerUid !== snapshot.ownerUid ||
      placement.layoutRevision !== layout.revision ||
      placement.zIndex !== index ||
      !boundedCoordinate(placement.normalizedX) ||
      !boundedCoordinate(placement.normalizedY)
    ) throw new MiniHomeShareError("data-loss", "The saved Mini-home placement is malformed", { field: `placements[${index}]` });
    const position = { x: placement.normalizedX, y: placement.normalizedY, z: placement.zIndex };
    if (placement.plantId !== null && placement.itemId === null) {
      plantOrdinal += 1;
      return {
        kind: "plant",
        ordinal: plantOrdinal,
        style: { variant: ((plantOrdinal - 1) % 6) + 1, scale: 1 },
        position,
      };
    }
    if (placement.itemId !== null && placement.plantId === null) {
      const item = catalog.get(placement.itemId);
      if (item === undefined) {
        return { kind: "decoration", ordinal: index + 1, style: { variant: (index % 4) + 1 }, position };
      }
      return publicCatalogPlacement(item, index + 1, position);
    }
    throw new MiniHomeShareError("data-loss", "The saved Mini-home placement target is malformed", { field: `placements[${index}]` });
  });
  return {
    contractVersion: MINI_HOME_SHARE_CONTRACT_VERSION,
    sourceRevision: layout.revision,
    createdAt: createdAt.toISOString(),
    expiresAt: new Date(createdAt.getTime() + MINI_HOME_SHARE_LIFETIME_MILLIS).toISOString(),
    grid: { columns: 5, rows: 4, projection: "isometric" },
    placements,
  };
}

export function renderPublicMiniHomeShareHtml(snapshot: PublicMiniHomeShareSnapshot): string {
  const tiles = snapshot.placements.map((placement) => {
    const left = `${Math.round(placement.position.x * 1000) / 10}%`;
    const top = `${Math.round(placement.position.y * 1000) / 10}%`;
    if (placement.kind === "catalog-item") {
      return `<li class="placement item ${placement.category.toLowerCase()}" style="left:${left};top:${top};z-index:${placement.position.z}" aria-label="${escapeHtml(placement.displayName)}"><span>${escapeHtml(placement.displayName)}</span></li>`;
    }
    const label = placement.kind === "plant" ? `Plant ${placement.ordinal}` : `Decoration ${placement.ordinal}`;
    return `<li class="placement ${placement.kind}" style="left:${left};top:${top};z-index:${placement.position.z}" aria-label="${label}"><span>${label}</span></li>`;
  }).join("");
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Shared Mini-home</title><style>:root{color-scheme:light;background:#f4efe5;color:#244234;font-family:system-ui,sans-serif}body{margin:0;min-height:100vh;display:grid;place-items:center}.card{width:min(92vw,720px);padding:24px}.room{position:relative;aspect-ratio:5/4;list-style:none;margin:0;padding:0;border-radius:24px;background:linear-gradient(145deg,#dcead8,#f8e8c9);display:grid;grid-template-columns:repeat(5,1fr);grid-template-rows:repeat(4,1fr);overflow:hidden}.room:before{content:"";position:absolute;inset:0;background:repeating-linear-gradient(30deg,transparent 0 9.5%,#ffffff55 10%),repeating-linear-gradient(150deg,transparent 0 9.5%,#ffffff44 10%)}.placement{position:absolute;transform:translate(-50%,-50%) rotate(-2deg);min-width:54px;min-height:54px;border-radius:50% 50% 44% 44%;display:grid;place-items:center;background:#4f8b63;box-shadow:0 10px 18px #173d2433}.placement span{position:absolute;top:100%;white-space:nowrap;margin-top:4px;font-size:11px;background:#fffbdc;padding:2px 6px;border-radius:8px}.item{background:#bf835b}.background{width:86px;border-radius:12px}.furniture{width:72px;border-radius:16px}.decoration{background:#d5a44c}h1{font-size:clamp(24px,5vw,42px);margin:0 0 8px}p{margin:0 0 20px;color:#52675b}</style></head><body><main class="card"><h1>Mini-home</h1><p>A private, static snapshot shared for 30 days.</p><ol class="room" data-columns="5" data-rows="4" data-projection="isometric">${tiles}</ol></main></body></html>`;
}

export function createPublicMiniHomeShareHandler(
  store: MiniHomeShareStore,
  now: () => Date = () => new Date(),
): (request: Request, response: Response) => Promise<void> {
  return async (request, response) => {
    setPublicHeaders(response);
    if (request.method !== "GET") {
      notFound(response);
      return;
    }
    const token = typeof request.query.token === "string" ? request.query.token : null;
    if (token === null || !TOKEN.test(token)) {
      notFound(response);
      return;
    }
    const tokenHash = createHash("sha256").update(token, "utf8").digest("hex");
    let snapshot: PublicMiniHomeShareSnapshot | null;
    try {
      snapshot = await store.loadPublic(tokenHash, now());
    } catch {
      snapshot = null;
    }
    if (snapshot === null) {
      notFound(response);
      return;
    }
    if (request.accepts(["html", "json"]) === "json") {
      response.status(200).type("application/json; charset=utf-8").send(JSON.stringify(snapshot));
      return;
    }
    response.status(200).type("html").send(renderPublicMiniHomeShareHtml(snapshot));
  };
}

function publicCatalogPlacement(
  item: InventoryCatalogItem,
  ordinal: number,
  position: PublicPosition,
): PublicMiniHomePlacement {
  if ([...item.name].length < 1 || [...item.name].length > 100) {
    throw new MiniHomeShareError("data-loss", "Public catalog display data is malformed", { field: "catalog" });
  }
  return {
    kind: "catalog-item",
    ordinal,
    catalogItemId: item.itemId,
    displayName: item.name,
    category: item.category,
    media: { ...item.mediaIdentity },
    position,
  };
}

function exactRecord(input: unknown, fields: readonly string[]): Readonly<Record<string, unknown>> {
  if (input === null || typeof input !== "object" || Array.isArray(input)) invalid("request must be an object", "request");
  const value = input as Readonly<Record<string, unknown>>;
  const keys = Object.keys(value).sort();
  if (keys.length !== fields.length || fields.some((field) => !keys.includes(field))) {
    invalid("Fields do not match the Mini-home share contract", "request");
  }
  return value;
}

function authenticatedOwner(auth: AuthContext | null): string {
  if (auth === null || typeof auth.uid !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(auth.uid)) {
    throw new MiniHomeShareError("unauthenticated", "Authentication is required", { field: "auth" });
  }
  return auth.uid;
}

function requireTokenKey(value: string): void {
  if (typeof value !== "string" || Buffer.byteLength(value, "utf8") < 32) {
    throw new MiniHomeShareError("failed-precondition", "Mini-home sharing is unavailable", { field: "tokenKey" });
  }
}

function requireNow(value: Date): void {
  if (!(value instanceof Date) || !Number.isSafeInteger(value.getTime()) || value.getTime() < 0) {
    throw new MiniHomeShareError("data-loss", "Server time is unavailable", { field: "now" });
  }
}

function requirePublicEndpoint(value: string): void {
  try {
    const parsed = new URL(value);
    const localHttp = parsed.protocol === "http:" && (parsed.hostname === "127.0.0.1" || parsed.hostname === "localhost");
    if (!(parsed.protocol === "https:" || localHttp) || parsed.search.length !== 0 || parsed.hash.length !== 0) throw new Error();
  } catch {
    throw new MiniHomeShareError("failed-precondition", "Public share endpoint is unavailable", { field: "publicEndpoint" });
  }
}

function boundedCoordinate(value: number): boolean {
  return typeof value === "number" && Number.isFinite(value) && value >= 0 && value <= 1;
}

function invalid(message: string, field: string): never {
  throw new MiniHomeShareError("invalid-argument", message, { field });
}

function escapeHtml(value: string): string {
  return value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#39;");
}

function setPublicHeaders(response: Response): void {
  response.set({
    "Cache-Control": "no-store",
    "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; img-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
    "X-Robots-Tag": "noindex, nofollow, noarchive",
  });
}

function notFound(response: Response): void {
  response.status(404).type("text/plain; charset=utf-8").send("Not found");
}
