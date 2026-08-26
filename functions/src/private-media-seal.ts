import {
  isPrivateMediaSeal,
  matchesPrivateMediaReservationObject,
  PRIVATE_MEDIA_PREFIX,
  PrivateMediaError,
  validObjectGeneration,
  type PrivateMediaObject,
  type PrivateMediaObjectStore,
  type PrivateMediaReservation,
  type PrivateMediaReservationRepository,
} from "./private-media-contract.js";

const DEFAULT_MAXIMUM_ATTEMPTS = 8;

type SealCommand = Readonly<{
  repository: PrivateMediaReservationRepository;
  objects: PrivateMediaObjectStore;
  nowMillis: () => number;
  maximumAttempts?: number;
}>;

type SealOwnerCommand = SealCommand & Readonly<{ ownerUid: string }>;

type FinalizedCommand = Readonly<{
  object: PrivateMediaObject;
  repository: PrivateMediaReservationRepository;
  objects: PrivateMediaObjectStore;
}>;

export async function sealOwnerPrivateMedia(command: SealOwnerCommand): Promise<void> {
  const reservations = await command.repository.listOwner(command.ownerUid);
  for (const reservation of reservations) {
    await sealPrivateMediaReservation(reservation, command);
  }

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

export async function sealPrivateMediaReservation(
  reservation: PrivateMediaReservation,
  command: SealCommand,
): Promise<Readonly<{ sealedGeneration: string }>> {
  await convergeSeal(reservation, command);
  const sealed = await command.objects.inspect(reservation.objectPath);
  if (sealed === null || !isPrivateMediaSeal(sealed)) {
    throw new PrivateMediaError(
      "failed-precondition",
      "Private media reservation did not converge to a verified seal",
    );
  }
  const persisted = await command.repository.load(reservation.reservationId);
  if (
    persisted === null ||
    persisted.ownerUid !== reservation.ownerUid ||
    persisted.state !== "SEALED" ||
    persisted.sealedGeneration !== sealed.generation
  ) {
    throw new PrivateMediaError(
      "failed-precondition",
      "Private media reservation seal metadata was not verified",
    );
  }
  return { sealedGeneration: sealed.generation };
}

async function convergeSeal(
  reservation: PrivateMediaReservation,
  command: SealCommand,
): Promise<void> {
  const maximumAttempts = command.maximumAttempts ?? DEFAULT_MAXIMUM_ATTEMPTS;
  if (!Number.isSafeInteger(maximumAttempts) || maximumAttempts < 1) {
    throw new PrivateMediaError("invalid-argument", "Seal attempt bound is invalid");
  }
  let cleanupGeneration: string | null = null;
  for (let attempt = 0; attempt < maximumAttempts; attempt += 1) {
    const current = await command.objects.inspect(reservation.objectPath);
    if (current !== null && isPrivateMediaSeal(current)) {
      await command.repository.markSealed({
        expectedReservation: reservation,
        sealedGeneration: current.generation,
        sealedAtMillis: command.nowMillis(),
      });
      return;
    }
    if (current !== null) {
      if (reservation.cleanupClaimReason === "EXPIRED_RESERVED_UPLOAD") {
        if (cleanupGeneration !== null && cleanupGeneration !== current.generation) {
          throw new PrivateMediaError(
            "failed-precondition",
            "Private media generation changed while sealing",
          );
        }
        cleanupGeneration = current.generation;
      }
      const deleted = await command.objects.deleteGeneration(
        current.path,
        current.generation,
      );
      if (deleted === "generation_changed") {
        throw new PrivateMediaError(
          "failed-precondition",
          "Private media generation changed while sealing",
        );
      }
      continue;
    }
    const created = await command.objects.createSeal(reservation.objectPath);
    switch (created.kind) {
      case "created":
        await command.repository.markSealed({
          expectedReservation: reservation,
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
  if (segments[0] !== PRIVATE_MEDIA_PREFIX) return;
  const generation = validObjectGeneration(command.object.generation);
  if (generation === null) return;
  const reservationId = segments[1];
  if (
    segments.length !== 2 ||
    reservationId === undefined ||
    !/^[A-Za-z0-9_-]{8,128}$/.test(reservationId)
  ) {
    await command.objects.deleteGeneration(command.object.path, generation);
    return;
  }

  const reservation = await command.repository.load(reservationId);
  if (reservation === null) {
    if (!isPrivateMediaSeal(command.object)) {
      await command.objects.deleteGeneration(command.object.path, generation);
    }
    return;
  }
  if (isPrivateMediaSeal(command.object) && reservation.state !== "SEALED") {
    return;
  }
  if (!matchesPrivateMediaReservationObject(command.object, reservation)) {
    await command.objects.deleteGeneration(command.object.path, generation);
    return;
  }
  if (
    reservation.state !== "SEALED" &&
    await command.repository.shouldDeleteFinalized(reservation.reservationId, reservation.ownerUid)
  ) {
    await command.objects.deleteGeneration(command.object.path, generation);
  }
}
