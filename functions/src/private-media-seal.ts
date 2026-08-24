import {
  isPrivateMediaSeal,
  PRIVATE_MEDIA_PREFIX,
  PrivateMediaError,
  validObjectGeneration,
  type PrivateMediaObject,
  type PrivateMediaObjectStore,
  type PrivateMediaReservation,
  type PrivateMediaReservationRepository,
} from "./private-media-contract.js";

const DEFAULT_MAXIMUM_ATTEMPTS = 8;

type SealOwnerCommand = Readonly<{
  ownerUid: string;
  repository: PrivateMediaReservationRepository;
  objects: PrivateMediaObjectStore;
  nowMillis: () => number;
  maximumAttempts?: number;
}>;

type FinalizedCommand = Readonly<{
  object: PrivateMediaObject;
  repository: PrivateMediaReservationRepository;
  objects: PrivateMediaObjectStore;
}>;

export async function sealOwnerPrivateMedia(command: SealOwnerCommand): Promise<void> {
  const reservations = await command.repository.listOwner(command.ownerUid);
  for (const reservation of reservations) await convergeSeal(reservation, command);

  const verified = await command.repository.listOwner(command.ownerUid);
  for (const reservation of verified) {
    const object = await command.objects.inspect(reservation.objectPath);
    if (
      reservation.state !== "SEALED" ||
      object === null ||
      !isPrivateMediaSeal(object) ||
      reservation.sealedGeneration !== object.generation
    ) {
      throw new PrivateMediaError(
        "failed-precondition",
        "Private media reservation did not converge to a verified seal",
      );
    }
  }
}

async function convergeSeal(
  reservation: PrivateMediaReservation,
  command: SealOwnerCommand,
): Promise<void> {
  const maximumAttempts = command.maximumAttempts ?? DEFAULT_MAXIMUM_ATTEMPTS;
  if (!Number.isSafeInteger(maximumAttempts) || maximumAttempts < 1) {
    throw new PrivateMediaError("invalid-argument", "Seal attempt bound is invalid");
  }
  for (let attempt = 0; attempt < maximumAttempts; attempt += 1) {
    const current = await command.objects.inspect(reservation.objectPath);
    if (current !== null && isPrivateMediaSeal(current)) {
      await command.repository.markSealed({
        ownerUid: command.ownerUid,
        reservationId: reservation.reservationId,
        sealedGeneration: current.generation,
        sealedAtMillis: command.nowMillis(),
      });
      return;
    }
    if (current !== null) {
      await command.objects.deleteGeneration(current.path, current.generation);
      continue;
    }
    const created = await command.objects.createSeal(reservation.objectPath);
    switch (created.kind) {
      case "created":
        await command.repository.markSealed({
          ownerUid: command.ownerUid,
          reservationId: reservation.reservationId,
          sealedGeneration: created.generation,
          sealedAtMillis: command.nowMillis(),
        });
        return;
      case "occupied":
        continue;
      default: {
        const unsupported: never = created;
        throw new TypeError(`Unsupported seal result: ${String(unsupported)}`);
      }
    }
  }
  throw new PrivateMediaError(
    "failed-precondition",
    "Private media sealing did not converge within the deterministic attempt bound",
  );
}

export async function handlePrivateMediaFinalized(command: FinalizedCommand): Promise<void> {
  const segments = command.object.path.split("/");
  if (
    segments.length !== 2 ||
    segments[0] !== PRIVATE_MEDIA_PREFIX ||
    segments[1] === undefined ||
    segments[1].length < 8 ||
    validObjectGeneration(command.object.generation) === null
  ) return;
  const ownerUid = command.object.customMetadata.ownerUid;
  const reservationId = command.object.customMetadata.reservationId;
  if (
    ownerUid === undefined ||
    reservationId !== segments[1] ||
    Object.keys(command.object.customMetadata).length !== 2
  ) return;
  if (await command.repository.shouldDeleteFinalized(reservationId, ownerUid)) {
    await command.objects.deleteGeneration(command.object.path, command.object.generation);
  }
}
