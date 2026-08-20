import { FieldValue, Timestamp, type Firestore } from "firebase-admin/firestore";
import {
  MiniHomeError,
  recoverLegacyMiniHomeName,
  type MiniHomeAuthoritativeLayout,
  type MiniHomeAuthoritativePlacement,
  type MiniHomeAuthoritativeRead,
  type MiniHomeDeleteCommand,
  type MiniHomeLayoutCommand,
  type MiniHomeLayoutDeleter,
  type MiniHomeLayoutReader,
  type MiniHomeLayoutStore,
  type MiniHomeSaveResult,
} from "./mini-home.js";

const INITIAL_OWNER_GENERATION = 1;

type MiniHomeReadHooks = Readonly<{
  afterHomeRead?: (attempt: number) => Promise<void>;
}>;

export class FirestoreMiniHomeLayoutStore implements MiniHomeLayoutStore, MiniHomeLayoutReader, MiniHomeLayoutDeleter {
  private loadAttempts = 0;

  constructor(
    private readonly firestore: Firestore,
    private readonly readHooks: MiniHomeReadHooks = {},
  ) {}

  async load(ownerUid: string): Promise<MiniHomeAuthoritativeRead> {
    return this.firestore.runTransaction(async (transaction) => {
      const attempt = ++this.loadAttempts;
      const ownerRoot = `users/${ownerUid}`;
      const stateRef = this.firestore.doc(`${ownerRoot}/miniHomeStates/current`);
      const [homes, state] = await Promise.all([
        transaction.get(this.firestore.collection(`${ownerRoot}/miniHomes`).limit(2)),
        transaction.get(stateRef),
      ]);
      await this.readHooks.afterHomeRead?.(attempt);
      if (homes.empty) {
        if (!state.exists) {
          const updatedAt = Timestamp.now();
          transaction.create(stateRef, {
            ownerUid,
            state: "DELETED",
            miniHomeId: null,
            layoutRevision: null,
            requestHash: null,
            tombstoneId: "initial-missing",
            revision: INITIAL_OWNER_GENERATION,
            expectedRevision: 0,
            idempotencyKey: "initial-missing",
            updatedAt,
          });
          return {
            kind: "missing",
            ownerUid,
            generation: INITIAL_OWNER_GENERATION,
            tombstoneId: "initial-missing",
            updatedAtEpochMillis: updatedAt.toMillis(),
          };
        }
        const parsed = authoritativeState(state, ownerUid);
        if (parsed.state !== "DELETED" || parsed.tombstoneId === null) {
          malformed("Mini-home state is active without a layout", "state");
        }
        return {
          kind: "missing",
          ownerUid,
          generation: parsed.generation,
          tombstoneId: parsed.tombstoneId,
          updatedAtEpochMillis: parsed.updatedAtEpochMillis,
        };
      }
      if (homes.size !== 1) malformed("The account has multiple mini-homes", "miniHomeId");
      const home = homes.docs[0];
      if (home === undefined || home.get("ownerUid") !== ownerUid) malformed("Stored mini-home owner is malformed", "ownerUid");
      const miniHomeId = home.id;
      const revision = safeInteger(home.get("revision"), "revision", 1);
      const expectedRevision = safeInteger(home.get("expectedRevision"), "expectedRevision", 0);
      if (expectedRevision !== revision - 1) malformed("Stored mini-home revision lineage is malformed", "expectedRevision");
      const idempotencyKey = safeOperationId(home.get("idempotencyKey"), "idempotencyKey");
      const requestHash = home.get("requestHash");
      if (typeof requestHash !== "string" || !/^[a-f0-9]{64}$/.test(requestHash)) malformed("Stored request hash is malformed", "requestHash");
      const name = recoverLegacyMiniHomeName(home.get("name"));
      if (name === null) malformed("Stored mini-home name is malformed", "name");
      const placedPlantCount = safeInteger(home.get("placedPlantCount"), "placedPlantCount", 0);
      const placementCount = safeInteger(home.get("placementCount"), "placementCount", 0);
      if (placementCount > 20) malformed("Stored placement count exceeds the bound", "placementCount");
      const placementIds = boundedPlacementIds(home.get("placementIds"), placementCount);
      const updatedAtEpochMillis = timestampMillis(home.get("updatedAt"), "updatedAt");
      const generation = state.exists
        ? activeGeneration(state, ownerUid, miniHomeId, revision, idempotencyKey, requestHash)
        : Math.max(INITIAL_OWNER_GENERATION, revision);
      const placementSnapshot = await transaction.get(
        this.firestore.collection(`${ownerRoot}/placements`).where("miniHomeId", "==", miniHomeId),
      );
      if (placementSnapshot.size !== placementCount) malformed("Stored placement rows are partial", "placementCount");
      const placements = placementSnapshot.docs
        .map((document): MiniHomeAuthoritativePlacement => {
          if (document.get("ownerUid") !== ownerUid) malformed("Stored placement owner is malformed", "ownerUid");
          const layoutRevision = safeInteger(document.get("layoutRevision"), "layoutRevision", 1);
          const placementRevision = safeInteger(document.get("revision"), "revision", 1);
          const placementExpectedRevision = safeInteger(document.get("expectedRevision"), "expectedRevision", 0);
          if (layoutRevision !== revision || placementRevision !== revision || placementExpectedRevision !== expectedRevision) {
            malformed("Stored placement revision differs from its mini-home", "layoutRevision");
          }
          const placementOperation = safeOperationId(document.get("idempotencyKey"), "idempotencyKey");
          if (placementOperation !== idempotencyKey) malformed("Stored placement operation differs from its mini-home", "idempotencyKey");
          const plantId = optionalOpaqueId(document.get("plantId"), "plantId");
          const itemId = optionalOpaqueId(document.get("itemId"), "itemId");
          if ((plantId === null) === (itemId === null)) malformed("Stored placement target is malformed", "target");
          const normalizedX = finiteCoordinate(document.get("normalizedX"), "normalizedX");
          const normalizedY = finiteCoordinate(document.get("normalizedY"), "normalizedY");
          return {
            placementId: safeOpaqueId(document.id, "placementId"),
            ownerUid,
            miniHomeId,
            layoutRevision,
            plantId,
            itemId,
            normalizedX,
            normalizedY,
            zIndex: safeInteger(document.get("zIndex"), "zIndex", 0),
            revision: placementRevision,
            expectedRevision: placementExpectedRevision,
            idempotencyKey: placementOperation,
            updatedAtEpochMillis: timestampMillis(document.get("updatedAt"), "updatedAt"),
          };
        })
        .sort((left, right) => left.zIndex - right.zIndex || left.placementId.localeCompare(right.placementId));
      if (placements.some((placement, index) => placement.zIndex !== index)) {
        malformed("Stored placement depth is partial or unordered", "zIndex");
      }
      if (placements.some((placement, index) => placement.placementId !== placementIds[index])) {
        malformed("Stored placement IDs differ from the committed layout", "placementIds");
      }
      if (placements.filter((placement) => placement.plantId !== null).length !== placedPlantCount) {
        malformed("Stored placed plant count differs from placement rows", "placedPlantCount");
      }
      if (!state.exists) {
        transaction.create(stateRef, {
          ownerUid,
          state: "ACTIVE",
          miniHomeId,
          layoutRevision: revision,
          requestHash,
          tombstoneId: null,
          revision: generation,
          expectedRevision: generation - 1,
          idempotencyKey,
          updatedAt: home.get("updatedAt"),
        });
      }
      const layout: MiniHomeAuthoritativeLayout = {
        ownerUid,
        generation,
        miniHomeId,
        name,
        placedPlantCount,
        revision,
        expectedRevision,
        idempotencyKey,
        requestHash,
        updatedAtEpochMillis,
        placements,
      };
      return { kind: "present", layout };
    }, { maxAttempts: 5 });
  }

  async save(command: MiniHomeLayoutCommand): Promise<MiniHomeSaveResult> {
    return this.firestore.runTransaction(async (transaction) => {
      const ownerRoot = `users/${command.ownerUid}`;
      const operationRef = this.firestore.doc(`${ownerRoot}/operations/${command.idempotencyKey}`);
      const homeRef = this.firestore.doc(`${ownerRoot}/miniHomes/${command.miniHomeId}`);
      const stateRef = this.firestore.doc(`${ownerRoot}/miniHomeStates/current`);
      const homesQuery = this.firestore.collection(`${ownerRoot}/miniHomes`).limit(2);
      const placementsQuery = this.firestore.collection(`${ownerRoot}/placements`).where("miniHomeId", "==", command.miniHomeId);
      const operation = await transaction.get(operationRef);
      if (operation.exists) {
        const revision = operation.get("revision");
        const expectedRevision = operation.get("expectedRevision");
        const idempotencyKey = operation.get("idempotencyKey");
        const requestHash = operation.get("requestHash");
        const details =
          typeof revision === "number" &&
              typeof expectedRevision === "number" &&
              typeof idempotencyKey === "string" &&
              typeof requestHash === "string"
            ? {
                committedOperationId: idempotencyKey,
                committedExpectedRevision: expectedRevision,
                committedRevision: revision,
                committedPayloadHash: requestHash,
              }
            : undefined;
        if (details === undefined || operation.get("documentPath") !== homeRef.path) {
          throw new MiniHomeError(
            "invalid-argument",
            "Idempotency receipt does not match this layout",
            "OUTBOX_MISMATCH",
            { ...(details ?? {}), field: "idempotencyKey" },
          );
        }
        if (requestHash !== command.requestHash) {
          throw new MiniHomeError(
            "invalid-argument",
            "Idempotency receipt payload does not match this layout",
            "PAYLOAD_MISMATCH",
            { ...(details ?? {}), field: "idempotencyKey" },
          );
        }
        return { kind: "duplicate", revision };
      }

      const [home, homes, previousPlacements, state] = await Promise.all([
        transaction.get(homeRef),
        transaction.get(homesQuery),
        transaction.get(placementsQuery),
        transaction.get(stateRef),
      ]);
      if (homes.docs.some((document) => document.id !== command.miniHomeId)) {
        throw new MiniHomeError(
          "failed-precondition",
          "The account already has another mini-home",
          "REVISION_CONFLICT",
          { field: "miniHomeId" },
        );
      }
      const actualRevision = home.exists ? home.get("revision") : 0;
      if (typeof actualRevision !== "number" || !Number.isSafeInteger(actualRevision) || actualRevision < 0) {
        throw new MiniHomeError(
          "data-loss",
          "Stored mini-home revision is malformed",
          "MALFORMED_RESPONSE",
          { field: "storedRevision" },
        );
      }
      const storedName = home.exists ? home.get("name") : null;
      const recoveredStoredName = home.exists ? recoverLegacyMiniHomeName(storedName) : null;
      if (home.exists && recoveredStoredName === null) {
        throw new MiniHomeError(
          "data-loss",
          "Stored mini-home name is malformed",
          "MALFORMED_RESPONSE",
          { field: "storedName" },
        );
      }
      if (actualRevision !== command.expectedRevision) {
        if (home.exists && recoveredStoredName !== storedName) {
          transaction.update(homeRef, { name: recoveredStoredName });
        }
        return { kind: "conflict", actualRevision };
      }

      type TargetRead =
        | Readonly<{ kind: "plant"; document: FirebaseFirestore.DocumentSnapshot }>
        | Readonly<{ kind: "item"; owned: FirebaseFirestore.DocumentSnapshot; catalog: FirebaseFirestore.DocumentSnapshot }>;
      const targetReads: Promise<TargetRead>[] = command.placements.map(async (placement) => {
        if (placement.plantId !== null) {
          const document = await transaction.get(
            this.firestore.doc(`${ownerRoot}/personalPlants/${placement.plantId}`),
          );
          return { kind: "plant" as const, document };
        }
        const itemId = placement.itemId as string;
        const [owned, catalog] = await Promise.all([
          transaction.get(this.firestore.doc(`${ownerRoot}/ownedItems/${itemId}`)),
          transaction.get(this.firestore.doc(`shopItems/${itemId}`)),
        ]);
        return { kind: "item" as const, owned, catalog };
      });
      const targets: TargetRead[] = await Promise.all(targetReads);
      for (const target of targets) {
        if (target.kind === "plant") {
          if (!target.document.exists || target.document.get("ownerUid") !== command.ownerUid) {
            throw new MiniHomeError(
              "failed-precondition",
              "A placed plant is no longer owned",
              "UNAVAILABLE_ENTITY",
              { field: "plantId" },
            );
          }
        } else if (
          !target.owned.exists ||
          target.owned.get("ownerUid") !== command.ownerUid ||
          !target.catalog.exists ||
          target.catalog.get("publicationState") !== "PUBLIC" ||
          !["FURNITURE", "DECORATION"].includes(target.catalog.get("category") as string)
        ) {
          throw new MiniHomeError(
            "failed-precondition",
            "A placed decoration is unavailable",
            "UNAVAILABLE_ENTITY",
            { field: "itemId" },
          );
        }
      }

      const revision = actualRevision + 1;
      const currentGeneration = state.exists
        ? home.exists
          ? activeGeneration(
            state,
            command.ownerUid,
            command.miniHomeId,
            actualRevision,
            safeOperationId(home.get("idempotencyKey"), "idempotencyKey"),
            safeHash(home.get("requestHash"), "requestHash"),
          )
          : deletedGeneration(state, command.ownerUid)
        : actualRevision;
      const generation = currentGeneration + 1;
      transaction.set(homeRef, {
        ownerUid: command.ownerUid,
        name: command.name,
        placedPlantCount: command.placements.filter((placement) => placement.plantId !== null).length,
        placementCount: command.placements.length,
        placementIds: command.placements.map((placement) => placement.placementId),
        revision,
        expectedRevision: actualRevision,
        idempotencyKey: command.idempotencyKey,
        requestHash: command.requestHash,
        updatedAt: FieldValue.serverTimestamp(),
      }, { merge: false });
      transaction.set(stateRef, {
        ownerUid: command.ownerUid,
        state: "ACTIVE",
        miniHomeId: command.miniHomeId,
        layoutRevision: revision,
        requestHash: command.requestHash,
        tombstoneId: null,
        revision: generation,
        expectedRevision: currentGeneration,
        idempotencyKey: command.idempotencyKey,
        updatedAt: FieldValue.serverTimestamp(),
      }, { merge: false });
      previousPlacements.docs.forEach((document) => transaction.delete(document.ref));
      command.placements.forEach((placement) => {
        transaction.set(this.firestore.doc(`${ownerRoot}/placements/${placement.placementId}`), {
          ownerUid: command.ownerUid,
          miniHomeId: command.miniHomeId,
          layoutRevision: revision,
          plantId: placement.plantId,
          itemId: placement.itemId,
          normalizedX: placement.normalizedX,
          normalizedY: placement.normalizedY,
          zIndex: placement.zIndex,
          revision,
          expectedRevision: actualRevision,
          idempotencyKey: command.idempotencyKey,
          updatedAt: FieldValue.serverTimestamp(),
        }, { merge: false });
      });
      transaction.create(operationRef, {
        ownerUid: command.ownerUid,
        documentPath: homeRef.path,
        requestHash: command.requestHash,
        revision,
        expectedRevision: actualRevision,
        idempotencyKey: command.idempotencyKey,
        updatedAt: FieldValue.serverTimestamp(),
      });
      return { kind: "applied", revision };
    });
  }

  async delete(command: MiniHomeDeleteCommand): Promise<Readonly<{ kind: "deleted"; generation: number; tombstoneId: string }>> {
    return this.firestore.runTransaction(async (transaction) => {
      const ownerRoot = `users/${command.ownerUid}`;
      const stateRef = this.firestore.doc(`${ownerRoot}/miniHomeStates/current`);
      const homesQuery = this.firestore.collection(`${ownerRoot}/miniHomes`).limit(2);
      const placementsQuery = this.firestore.collection(`${ownerRoot}/placements`).limit(21);
      const [state, homes, placements] = await Promise.all([
        transaction.get(stateRef),
        transaction.get(homesQuery),
        transaction.get(placementsQuery),
      ]);
      if (homes.size > 1 || placements.size > 20) malformed("Stored mini-home deletion set is malformed", "miniHomeId");
      if (state.exists) {
        const parsed = authoritativeState(state, command.ownerUid);
        if (parsed.state === "DELETED" && parsed.tombstoneId === command.tombstoneId) {
          return { kind: "deleted", generation: parsed.generation, tombstoneId: command.tombstoneId };
        }
      }
      const home = homes.docs[0];
      const currentGeneration = state.exists
        ? home === undefined
          ? deletedGeneration(state, command.ownerUid)
          : activeGeneration(
            state,
            command.ownerUid,
            home.id,
            safeInteger(home.get("revision"), "revision", 1),
            safeOperationId(home.get("idempotencyKey"), "idempotencyKey"),
            safeHash(home.get("requestHash"), "requestHash"),
          )
        : home === undefined
          ? 0
          : safeInteger(home.get("revision"), "revision", 1);
      if (command.expectedGeneration !== currentGeneration) {
        throw new MiniHomeError(
          "failed-precondition",
          "Mini-home deletion generation is stale",
          "REVISION_CONFLICT",
          { field: "expectedGeneration" },
        );
      }
      const generation = currentGeneration + 1;
      homes.docs.forEach((document) => transaction.delete(document.ref));
      placements.docs.forEach((document) => transaction.delete(document.ref));
      transaction.set(stateRef, {
        ownerUid: command.ownerUid,
        state: "DELETED",
        miniHomeId: null,
        layoutRevision: null,
        requestHash: null,
        tombstoneId: command.tombstoneId,
        revision: generation,
        expectedRevision: currentGeneration,
        idempotencyKey: command.tombstoneId,
        updatedAt: FieldValue.serverTimestamp(),
      }, { merge: false });
      return { kind: "deleted", generation, tombstoneId: command.tombstoneId };
    });
  }
}

type AuthoritativeState = Readonly<{
  generation: number;
  state: "ACTIVE" | "DELETED";
  miniHomeId: string | null;
  layoutRevision: number | null;
  idempotencyKey: string;
  requestHash: string | null;
  tombstoneId: string | null;
  updatedAtEpochMillis: number;
}>;

function authoritativeState(
  document: FirebaseFirestore.DocumentSnapshot,
  ownerUid: string,
): AuthoritativeState {
  if (!document.exists || document.get("ownerUid") !== ownerUid) malformed("Stored mini-home state owner is malformed", "ownerUid");
  const generation = safeInteger(document.get("revision"), "generation", 1);
  const expectedGeneration = safeInteger(document.get("expectedRevision"), "expectedGeneration", 0);
  if (expectedGeneration !== generation - 1) malformed("Stored mini-home state lineage is malformed", "expectedGeneration");
  const state = document.get("state");
  if (state !== "ACTIVE" && state !== "DELETED") malformed("Stored mini-home state is malformed", "state");
  const miniHomeId = document.get("miniHomeId") === null ? null : safeOpaqueId(document.get("miniHomeId"), "miniHomeId");
  const rawLayoutRevision = document.get("layoutRevision");
  const layoutRevision = rawLayoutRevision === null ? null : safeInteger(rawLayoutRevision, "layoutRevision", 1);
  const idempotencyKey = safeOperationId(document.get("idempotencyKey"), "idempotencyKey");
  const requestHash = document.get("requestHash") === null ? null : safeHash(document.get("requestHash"), "requestHash");
  const tombstoneId = document.get("tombstoneId") === null ? null : safeOperationId(document.get("tombstoneId"), "tombstoneId");
  if (state === "ACTIVE" && (miniHomeId === null || layoutRevision === null || requestHash === null || tombstoneId !== null)) {
    malformed("Active mini-home state identity is incomplete", "state");
  }
  if (state === "DELETED" && (miniHomeId !== null || layoutRevision !== null || requestHash !== null || tombstoneId === null || idempotencyKey !== tombstoneId)) {
    malformed("Deleted mini-home state identity is incomplete", "state");
  }
  return {
    generation,
    state,
    miniHomeId,
    layoutRevision,
    idempotencyKey,
    requestHash,
    tombstoneId,
    updatedAtEpochMillis: timestampMillis(document.get("updatedAt"), "updatedAt"),
  };
}

function activeGeneration(
  document: FirebaseFirestore.DocumentSnapshot,
  ownerUid: string,
  miniHomeId: string,
  layoutRevision: number,
  idempotencyKey: string,
  requestHash: string,
): number {
  const state = authoritativeState(document, ownerUid);
  if (
    state.state !== "ACTIVE" ||
    state.miniHomeId !== miniHomeId ||
    state.layoutRevision !== layoutRevision ||
    state.idempotencyKey !== idempotencyKey ||
    state.requestHash !== requestHash
  ) {
    malformed("Active mini-home state differs from its layout", "state");
  }
  return state.generation;
}

function deletedGeneration(document: FirebaseFirestore.DocumentSnapshot, ownerUid: string): number {
  const state = authoritativeState(document, ownerUid);
  if (state.state !== "DELETED") malformed("Mini-home layout is missing for active state", "state");
  return state.generation;
}

function malformed(message: string, field: string): never {
  throw new MiniHomeError("data-loss", message, "MALFORMED_RESPONSE", { field });
}

function safeInteger(value: unknown, field: string, minimum: number): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < minimum) {
    malformed(`Stored ${field} is malformed`, field);
  }
  return value;
}

function safeHash(value: unknown, field: string): string {
  if (typeof value !== "string" || !/^[a-f0-9]{64}$/.test(value)) {
    malformed(`Stored ${field} is malformed`, field);
  }
  return value;
}

function safeOpaqueId(value: unknown, field: string): string {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(value)) {
    malformed(`Stored ${field} is malformed`, field);
  }
  return value;
}

function safeOperationId(value: unknown, field: string): string {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]{8,128}$/.test(value)) {
    malformed(`Stored ${field} is malformed`, field);
  }
  return value;
}

function optionalOpaqueId(value: unknown, field: string): string | null {
  if (value === null) return null;
  return safeOpaqueId(value, field);
}

function finiteCoordinate(value: unknown, field: string): number {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0 || value > 1) {
    malformed(`Stored ${field} is malformed`, field);
  }
  return value;
}

function boundedPlacementIds(value: unknown, expectedCount: number): readonly string[] {
  if (!Array.isArray(value) || value.length !== expectedCount) {
    malformed("Stored placement IDs do not match placementCount", "placementIds");
  }
  const ids = value.map((item) => safeOpaqueId(item, "placementIds"));
  if (new Set(ids).size !== ids.length) malformed("Stored placement IDs are not unique", "placementIds");
  return ids;
}

function timestampMillis(value: unknown, field: string): number {
  if (value === null || typeof value !== "object" || !("toMillis" in value)) {
    malformed(`Stored ${field} is malformed`, field);
  }
  const toMillis = (value as { toMillis?: unknown }).toMillis;
  if (typeof toMillis !== "function") malformed(`Stored ${field} is malformed`, field);
  const millis = toMillis.call(value) as unknown;
  return safeInteger(millis, field, 0);
}
