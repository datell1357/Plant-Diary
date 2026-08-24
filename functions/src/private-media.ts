import { createHash } from "node:crypto";
import {
  commitPrivateMediaInputSchema,
  PRIVATE_MEDIA_PREFIX,
  PRIVATE_MEDIA_UPLOAD_TTL_MILLIS,
  PrivateMediaError,
  reservePrivateMediaInputSchema,
  validObjectGeneration,
  type PrivateMediaAuth,
  type PrivateMediaObject,
  type PrivateMediaObjectStore,
  type PrivateMediaReservation,
  type PrivateMediaReservationRepository,
  type PrivateMediaSigner,
} from "./private-media-contract.js";

export {
  PRIVATE_MEDIA_KINDS,
  PRIVATE_MEDIA_MAX_BYTES,
  PRIVATE_MEDIA_PREFIX,
  PRIVATE_MEDIA_SEAL_CONTENT_TYPE,
  PRIVATE_MEDIA_STATES,
  PRIVATE_MEDIA_UPLOAD_TTL_MILLIS,
  PrivateMediaError,
  isPrivateMediaSeal,
  parsePrivateMediaReference,
  validObjectGeneration,
} from "./private-media-contract.js";
export type {
  CommitPrivateMediaCommand,
  CreateSealResult,
  DeleteGenerationResult,
  MarkPrivateMediaSealedCommand,
  PrivateMediaAuth,
  PrivateMediaErrorCode,
  PrivateMediaKind,
  PrivateMediaObject,
  PrivateMediaObjectStore,
  PrivateMediaReference,
  PrivateMediaReservation,
  PrivateMediaReservationRepository,
  PrivateMediaSignCommand,
  PrivateMediaSigner,
  PrivateMediaState,
  ResolvePrivateMediaCommand,
  ResolvedPrivateMedia,
} from "./private-media-contract.js";

type ReserveDependencies = Readonly<{
  repository: PrivateMediaReservationRepository;
  signer: PrivateMediaSigner;
  nowMillis: () => number;
  reservationId: () => string;
}>;
type CommitDependencies = Readonly<{
  repository: PrivateMediaReservationRepository;
  objects: PrivateMediaObjectStore;
  nowMillis: () => number;
}>;

export type ReservedPrivateMediaUpload = Readonly<{
  reservationId: string;
  upload: Readonly<{
    method: "PUT";
    url: string;
    expiresAtMillis: number;
    requiredHeaders: Readonly<Record<string, string>>;
  }>;
}>;
export type CommittedPrivateMedia = Readonly<{
  reference: Readonly<{ reservationId: string; generation: string }>;
  mediaKind: PrivateMediaReservation["mediaKind"];
  contentType: string;
  byteSize: number;
}>;

/**
 * Safety invariant: every admitted upload is a conditional create for one opaque path.
 * Deletion makes that path permanently non-empty with an ownerless seal. Therefore a
 * paused upload either wins before cleanup and is generation-deleted, or loses its
 * ifGenerationMatch=0 precondition after the seal; neither order leaves owner media.
 */
export async function reservePrivateMediaUpload(
  auth: PrivateMediaAuth | null,
  input: unknown,
  dependencies: ReserveDependencies,
): Promise<ReservedPrivateMediaUpload> {
  if (auth === null) throw new PrivateMediaError("unauthenticated", "Sign-in is required");
  const parsed = reservePrivateMediaInputSchema.safeParse(input);
  if (!parsed.success) invalidInput(parsed.error);
  if (parsed.data.expectedOwnerUid !== auth.uid) {
    throw new PrivateMediaError("permission-denied", "Owner does not match authentication");
  }
  const reservationId = dependencies.reservationId();
  if (!/^[A-Za-z0-9_-]{8,128}$/.test(reservationId)) {
    throw new PrivateMediaError("unavailable", "Reservation ID generation failed");
  }
  const nowMillis = dependencies.nowMillis();
  const requestHash = createHash("sha256")
    .update(parsed.data.mediaKind)
    .update("\0")
    .update(parsed.data.contentType)
    .update("\0")
    .update(String(parsed.data.byteSize))
    .digest("hex");
  const reservation = await dependencies.repository.reserve({
    schemaVersion: 1,
    reservationId,
    ownerUid: auth.uid,
    mediaKind: parsed.data.mediaKind,
    contentType: parsed.data.contentType,
    byteSize: parsed.data.byteSize,
    objectPath: `${PRIVATE_MEDIA_PREFIX}/${reservationId}`,
    idempotencyKeyHash: createHash("sha256").update(parsed.data.idempotencyKey).digest("hex"),
    requestHash,
    state: "RESERVED",
    objectGeneration: null,
    sealedGeneration: null,
    createdAtMillis: nowMillis,
    expiresAtMillis: nowMillis + PRIVATE_MEDIA_UPLOAD_TTL_MILLIS,
    committedAtMillis: null,
    sealedAtMillis: null,
  });
  if (nowMillis >= reservation.expiresAtMillis) {
    throw new PrivateMediaError("failed-precondition", "Private media reservation has expired");
  }
  const requiredHeaders = {
    "content-type": reservation.contentType,
    "x-goog-if-generation-match": "0",
    "x-goog-meta-owner-uid": reservation.ownerUid,
    "x-goog-meta-reservation-id": reservation.reservationId,
  } as const;
  const signed = await dependencies.signer.signPut({
    reservationId: reservation.reservationId,
    objectPath: reservation.objectPath,
    contentType: reservation.contentType,
    expiresAtMillis: reservation.expiresAtMillis,
    requiredHeaders,
  });
  return {
    reservationId: reservation.reservationId,
    upload: {
      method: "PUT",
      url: signed.url,
      expiresAtMillis: reservation.expiresAtMillis,
      requiredHeaders,
    },
  };
}

export async function commitPrivateMediaReservation(
  auth: PrivateMediaAuth | null,
  input: unknown,
  dependencies: CommitDependencies,
): Promise<CommittedPrivateMedia> {
  if (auth === null) throw new PrivateMediaError("unauthenticated", "Sign-in is required");
  const parsed = commitPrivateMediaInputSchema.safeParse(input);
  if (!parsed.success) invalidInput(parsed.error);
  if (parsed.data.expectedOwnerUid !== auth.uid) {
    throw new PrivateMediaError("permission-denied", "Owner does not match authentication");
  }
  const reservation = await dependencies.repository.load(parsed.data.reservationId);
  if (reservation === null) throw new PrivateMediaError("not-found", "Reservation was not found");
  if (reservation.ownerUid !== auth.uid) {
    throw new PrivateMediaError("permission-denied", "Reservation is not owned by this account");
  }
  if (reservation.state === "SEALED") {
    throw new PrivateMediaError("failed-precondition", "Reservation is sealed");
  }
  const object = await dependencies.objects.inspect(reservation.objectPath);
  if (object === null || !matchesReservation(object, reservation)) {
    throw new PrivateMediaError("failed-precondition", "Uploaded object does not match reservation");
  }
  const generation = validObjectGeneration(object.generation);
  if (generation === null) {
    throw new PrivateMediaError("failed-precondition", "Uploaded object generation is invalid");
  }
  const committed = await dependencies.repository.commit({
    ownerUid: auth.uid,
    reservationId: reservation.reservationId,
    generation,
    committedAtMillis: dependencies.nowMillis(),
  });
  return {
    reference: { reservationId: committed.reservationId, generation },
    mediaKind: committed.mediaKind,
    contentType: committed.contentType,
    byteSize: committed.byteSize,
  };
}

function matchesReservation(
  object: PrivateMediaObject,
  reservation: PrivateMediaReservation,
): boolean {
  const metadataKeys = Object.keys(object.customMetadata).sort();
  return object.path === reservation.objectPath &&
    object.byteSize === reservation.byteSize &&
    object.contentType === reservation.contentType &&
    metadataKeys.length === 2 &&
    metadataKeys[0] === "ownerUid" &&
    metadataKeys[1] === "reservationId" &&
    object.customMetadata.ownerUid === reservation.ownerUid &&
    object.customMetadata.reservationId === reservation.reservationId;
}

function invalidInput(cause: unknown): never {
  throw new PrivateMediaError("invalid-argument", "Private media request is invalid", { cause });
}
