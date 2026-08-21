import { FieldValue, Timestamp, type Firestore } from "firebase-admin/firestore";
import { ContractError, type MutationResult, type MutationStore, type OwnerMutationCommand, type ServerStateCommand } from "./contracts.js";
import {
  ownerProjectionDraft,
  projectionPlant,
  publishOwnerProjection,
  readCatalogForWriter,
  readOwnerForWriter,
} from "./firestore-mini-home-projection.js";
import { localDateTimeToInstant } from "./notification-settings.js";
import { addLocalDays } from "./watering.js";

function timestampPayload(payload: Readonly<Record<string, unknown>>): Readonly<Record<string, unknown>> {
  return Object.fromEntries(Object.entries(payload).map(([key, value]) => {
    if ((key.endsWith("At") || key.endsWith("For")) && typeof value === "string") return [key, Timestamp.fromDate(new Date(value))];
    return [key, value];
  }));
}

export class FirestoreMutationStore implements MutationStore {
  constructor(
    private readonly firestore: Firestore,
    private readonly now: () => Timestamp = Timestamp.now,
  ) {}

  async publicPlantContentExists(contentId: string): Promise<boolean> {
    const content = await this.firestore.doc(`plantContents/${contentId}`).get();
    return content.exists && content.get("publicationState") === "PUBLIC";
  }

  async ownerZoneId(ownerUid: string): Promise<string> {
    const account = await this.firestore.doc(`users/${ownerUid}`).get();
    const value = account.get("zoneId");
    if (typeof value !== "string") throw new ContractError("invalid-argument", "Account timezone is unavailable");
    return value;
  }

  async applyOwnerMutation(command: OwnerMutationCommand): Promise<MutationResult> {
    return this.firestore.runTransaction(async (transaction) => {
      const operationRef = this.firestore.doc(`users/${command.ownerUid}/operations/${command.idempotencyKey}`);
      const documentRef = this.firestore.doc(command.documentPath);
      const operation = await transaction.get(operationRef);
      if (operation.exists) {
        const revision = operation.get("revision");
        const documentPath = operation.get("documentPath");
        const requestHash = operation.get("requestHash");
        if (typeof revision !== "number" || documentPath !== command.documentPath || requestHash !== command.requestHash) {
          throw new ContractError("invalid-argument", "Operation receipt does not match the mutation");
        }
        return { kind: "duplicate", revision };
      }
      const document = await transaction.get(documentRef);
      const projectionTime = this.now();
      const projectionContext = command.collection === "personalPlants"
        ? {
            catalog: await readCatalogForWriter(transaction, this.firestore),
          }
        : null;
      const ownerProjection = projectionContext === null
        ? null
        : await readOwnerForWriter(
            transaction,
            this.firestore,
            command.ownerUid,
            projectionContext.catalog,
            projectionTime,
          );
      const actualRevision = document.exists ? document.get("revision") : 0;
      if (typeof actualRevision !== "number") throw new ContractError("invalid-argument", "Stored revision is malformed");
      if (
        actualRevision !== command.expectedRevision ||
        (command.mutationType === "CREATE" && document.exists) ||
        (command.mutationType === "UPDATE" && !document.exists)
      ) return { kind: "conflict", actualRevision };
      const revision = actualRevision + 1;
      if (command.collection === "personalPlants" && command.mutationType === "CREATE") {
        const plants = await transaction.get(
          this.firestore.collection(`users/${command.ownerUid}/personalPlants`).limit(200),
        );
        if (plants.size >= 200) {
          throw new ContractError("resource-exhausted", "An account can contain at most 200 plants");
        }
      }
      const write = { ...command.payload, ownerUid: command.ownerUid, revision, expectedRevision: command.expectedRevision, idempotencyKey: command.idempotencyKey, updatedAt: projectionTime };
      if (command.collection === "personalPlants" && "lastWateredDate" in command.payload) {
        const scheduleRef = this.firestore.doc(
          `users/${command.ownerUid}/wateringSchedules/${command.documentId}`,
        );
        const accountRef = this.firestore.doc(`users/${command.ownerUid}`);
        const settingsRef = this.firestore.doc(
          `users/${command.ownerUid}/notificationSettings/watering`,
        );
        const preferenceRef = this.firestore.doc(
          `users/${command.ownerUid}/notificationPlantSettings/${command.documentId}`,
        );
        const contentId =
          typeof command.payload.contentId === "string"
            ? command.payload.contentId
            : document.get("contentId");
        const [schedule, account, settings, preference, content] = await Promise.all([
          transaction.get(scheduleRef),
          transaction.get(accountRef),
          transaction.get(settingsRef),
          transaction.get(preferenceRef),
          typeof contentId === "string"
            ? transaction.get(this.firestore.doc(`plantContents/${contentId}`))
            : Promise.resolve(null),
        ]);
        const lastWateredDate = command.payload.lastWateredDate;
        const interval = content?.get("wateringIntervalDays");
        if (
          typeof lastWateredDate === "string" &&
          content?.exists === true &&
          content.get("publicationState") === "PUBLIC" &&
          typeof interval === "number" &&
          Number.isSafeInteger(interval) &&
          interval >= 1 &&
          interval <= 365
        ) {
          const zoneId = account.get("zoneId");
          if (typeof zoneId !== "string") {
            throw new ContractError("invalid-argument", "Account timezone is unavailable");
          }
          const scheduleRevision = schedule.exists ? schedule.get("revision") : 0;
          if (
            typeof scheduleRevision !== "number" ||
            !Number.isSafeInteger(scheduleRevision) ||
            scheduleRevision < 0
          ) {
            throw new ContractError("invalid-argument", "Watering schedule revision is malformed");
          }
          const dueDate = addLocalDays(lastWateredDate, interval);
          const defaultTime = settings.get("defaultTime");
          const timeOverride = preference.get("timeOverride");
          const active =
            settings.exists &&
            settings.get("wateringEnabled") === true &&
            (!preference.exists || preference.get("enabled") !== false) &&
            typeof defaultTime === "string";
          transaction.set(
            scheduleRef,
            {
              ownerUid: command.ownerUid,
              plantId: command.documentId,
              dueDate,
              zoneId,
              notificationCandidateActive: active,
              ...(active
                ? {
                    nextNotificationAt: Timestamp.fromDate(
                      localDateTimeToInstant(
                        dueDate,
                        typeof timeOverride === "string" ? timeOverride : (defaultTime as string),
                        zoneId,
                      ),
                    ),
                  }
                : {}),
              revision: scheduleRevision + 1,
              expectedRevision: scheduleRevision,
              idempotencyKey: command.idempotencyKey,
              updatedAt: FieldValue.serverTimestamp(),
            },
            { merge: false },
          );
        } else if (schedule.exists) {
          transaction.delete(scheduleRef);
        }
      }
      if (command.mutationType === "UPDATE") transaction.set(documentRef, write, { merge: true });
      else transaction.create(documentRef, write);
      if (projectionContext !== null && ownerProjection !== null) {
        const projectedPlant = projectionPlant(
          command.documentId,
          { ...(document.data() ?? {}), ...write },
          command.ownerUid,
        );
        const published = await publishOwnerProjection(
          transaction,
          this.firestore,
          command.ownerUid,
          ownerProjection.prior,
          ownerProjectionDraft(
            ownerProjection.draft.layout,
            ownerProjection.draft.owned,
            [
              ...ownerProjection.draft.plants.filter((plant) => plant.plantId !== command.documentId),
              projectedPlant,
            ],
          ),
          projectionContext.catalog,
          projectionTime,
        );
        transaction.set(
          this.firestore.doc(`users/${command.ownerUid}/inventoryStates/current`),
          {
            ownerUid: command.ownerUid,
            generation: published.inventoryGeneration,
            snapshotHash: published.inventorySnapshotHash,
            updatedAt: projectionTime,
          },
          { merge: false },
        );
      }
      transaction.create(operationRef, { ownerUid: command.ownerUid, documentPath: command.documentPath, requestHash: command.requestHash, revision, expectedRevision: command.expectedRevision, idempotencyKey: command.idempotencyKey, updatedAt: projectionTime });
      return { kind: "applied", revision };
    });
  }

  async writeServerState(command: ServerStateCommand): Promise<void> {
    await this.firestore.doc(command.documentPath).set({ ...timestampPayload(command.payload), ownerUid: command.ownerUid, updatedAt: FieldValue.serverTimestamp() }, { merge: false });
  }
}
