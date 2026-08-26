import { z } from "zod";

export const PRIVATE_MEDIA_PREFIX = "private-media-v2";
export const PRIVATE_MEDIA_SEAL_CONTENT_TYPE = "application/x.planterior-private-media-seal";
export const PRIVATE_MEDIA_UPLOAD_TTL_MILLIS = 10 * 60 * 1_000;
export const PRIVATE_MEDIA_MAX_BYTES = 20 * 1024 * 1024;

export const PRIVATE_MEDIA_KINDS = [
  "IDENTIFICATION_ORIGINAL",
  "PLANT_PHOTO",
] as const;
export type PrivateMediaKind = (typeof PRIVATE_MEDIA_KINDS)[number];

export const PRIVATE_MEDIA_STATES = ["RESERVED", "COMMITTED", "SEALED"] as const;
export type PrivateMediaState = (typeof PRIVATE_MEDIA_STATES)[number];

export type PrivateMediaAuth = Readonly<{ uid: string }>;
export type PrivateMediaReference = Readonly<{
  reservationId: string;
  generation: string;
}>;
export type ResolvedPrivateMedia = Readonly<{
  reference: PrivateMediaReference;
  ownerUid: string;
  mediaKind: PrivateMediaKind;
  objectPath: string;
  contentType: string;
  byteSize: number;
}>;

export type PrivateMediaReservation = Readonly<{
  schemaVersion: 1;
  reservationId: string;
  ownerUid: string;
  mediaKind: PrivateMediaKind;
  contentType: string;
  byteSize: number;
  objectPath: string;
  identificationRequestId: string | null;
  idempotencyKeyHash: string;
  requestHash: string;
  state: PrivateMediaState;
  objectGeneration: string | null;
  sealedGeneration: string | null;
  /** Durable cleanup fence for a committed identification original before its bytes are deleted. */
  cleanupClaimGeneration?: string | null;
  cleanupClaimReason?:
    | "EXPIRED_RESERVED_UPLOAD"
    | "COMMITTED_ORPHANED_IDENTIFICATION_ORIGINAL"
    | "IDENTIFICATION_REQUEST_RETENTION"
    | null;
  createdAtMillis: number;
  expiresAtMillis: number;
  committedAtMillis: number | null;
  sealedAtMillis: number | null;
}>;

export type PrivateMediaObject = Readonly<{
  path: string;
  generation: string;
  byteSize: number;
  contentType: string;
  customMetadata: Readonly<Record<string, string>>;
}>;

export type PrivateMediaSignCommand = Readonly<{
  reservationId: string;
  objectPath: string;
  contentType: string;
  expiresAtMillis: number;
  requiredHeaders: Readonly<Record<string, string>>;
}>;
export interface PrivateMediaSigner {
  signPut(command: PrivateMediaSignCommand): Promise<Readonly<{ url: string }>>;
}

export type DeleteGenerationResult = "deleted" | "absent" | "generation_changed";
export type CreateSealResult =
  | Readonly<{ kind: "created"; generation: string }>
  | Readonly<{ kind: "occupied" }>;
export interface PrivateMediaObjectStore {
  inspect(path: string): Promise<PrivateMediaObject | null>;
  deleteGeneration(path: string, generation: string): Promise<DeleteGenerationResult>;
  createSeal(path: string): Promise<CreateSealResult>;
}

export type CommitPrivateMediaCommand = Readonly<{
  ownerUid: string;
  reservationId: string;
  generation: string;
  committedAtMillis: number;
}>;
export type ResolvePrivateMediaCommand = Readonly<{
  ownerUid: string;
  reference: PrivateMediaReference;
  mediaKind: PrivateMediaKind;
}>;
export type ClaimExpiredReservedUploadCommand = Readonly<{
  reservation: PrivateMediaReservation;
  nowMillis: number;
}>;
export type MarkPrivateMediaSealedCommand = Readonly<{
  expectedReservation: PrivateMediaReservation;
  sealedGeneration: string;
  sealedAtMillis: number;
}>;
export interface PrivateMediaReservationRepository {
  load(reservationId: string): Promise<PrivateMediaReservation | null>;
  reserve(reservation: PrivateMediaReservation): Promise<PrivateMediaReservation>;
  commit(command: CommitPrivateMediaCommand): Promise<PrivateMediaReservation>;
  claimExpiredReservedUpload(
    command: ClaimExpiredReservedUploadCommand,
  ): Promise<PrivateMediaReservation | null>;
  resolve(command: ResolvePrivateMediaCommand): Promise<ResolvedPrivateMedia | null>;
  listOwner(ownerUid: string): Promise<readonly PrivateMediaReservation[]>;
  markSealed(command: MarkPrivateMediaSealedCommand): Promise<void>;
  shouldDeleteFinalized(reservationId: string, ownerUid: string): Promise<boolean>;
}

export type PrivateMediaErrorCode =
  | "unauthenticated"
  | "permission-denied"
  | "invalid-argument"
  | "failed-precondition"
  | "not-found"
  | "unavailable";

export class PrivateMediaError extends Error {
  override readonly name = "PrivateMediaError";

  constructor(
    readonly code: PrivateMediaErrorCode,
    message: string,
    options?: ErrorOptions,
  ) {
    super(message, options);
  }
}

const ownerUidSchema = z.string().regex(/^[A-Za-z0-9_-]{1,128}$/);
const reservationIdSchema = z.string().regex(/^[A-Za-z0-9_-]{8,128}$/);
const generationSchema = z.string().regex(/^[1-9][0-9]*$/);
const mediaReferenceSchema = z.object({
  reservationId: reservationIdSchema,
  generation: generationSchema,
}).strict();

export const reservePrivateMediaInputSchema = z.object({
  expectedOwnerUid: ownerUidSchema,
  mediaKind: z.enum(PRIVATE_MEDIA_KINDS),
  contentType: z.enum(["image/jpeg", "image/png", "image/webp", "image/heif", "image/heic"]),
  byteSize: z.number().int().min(1).max(PRIVATE_MEDIA_MAX_BYTES),
  idempotencyKey: z.string().regex(/^[A-Za-z0-9_-]{8,128}$/),
}).strict();

export const commitPrivateMediaInputSchema = z.object({
  expectedOwnerUid: ownerUidSchema,
  reservationId: reservationIdSchema,
}).strict();

export function parsePrivateMediaReference(value: unknown): PrivateMediaReference {
  const parsed = mediaReferenceSchema.safeParse(value);
  if (!parsed.success) {
    throw new PrivateMediaError("invalid-argument", "Media reference is invalid", {
      cause: parsed.error,
    });
  }
  return parsed.data;
}

export function validObjectGeneration(value: unknown): string | null {
  const parsed = generationSchema.safeParse(value);
  return parsed.success ? parsed.data : null;
}

export function isPrivateMediaSeal(object: PrivateMediaObject): boolean {
  return object.byteSize === 0 &&
    object.contentType === PRIVATE_MEDIA_SEAL_CONTENT_TYPE &&
    Object.keys(object.customMetadata).length === 1 &&
    object.customMetadata.privateMediaSeal === "true";
}

export function matchesPrivateMediaReservationObject(
  object: PrivateMediaObject,
  reservation: PrivateMediaReservation,
): boolean {
  if (object.path !== reservation.objectPath || validObjectGeneration(object.generation) === null) {
    return false;
  }
  switch (reservation.state) {
    case "RESERVED":
      return matchesReservedUpload(object, reservation);
    case "COMMITTED":
      return object.generation === reservation.objectGeneration &&
        matchesReservedUpload(object, reservation);
    case "SEALED":
      return object.generation === reservation.sealedGeneration && isPrivateMediaSeal(object);
    default: {
      const unsupported: never = reservation.state;
      throw new TypeError(`Unsupported private media state: ${String(unsupported)}`);
    }
  }
}

function matchesReservedUpload(
  object: PrivateMediaObject,
  reservation: PrivateMediaReservation,
): boolean {
  const metadataKeys = Object.keys(object.customMetadata).sort();
  return object.byteSize === reservation.byteSize &&
    object.contentType === reservation.contentType &&
    metadataKeys.length === 2 &&
    metadataKeys[0] === "ownerUid" &&
    metadataKeys[1] === "reservationId" &&
    object.customMetadata.ownerUid === reservation.ownerUid &&
    object.customMetadata.reservationId === reservation.reservationId;
}
