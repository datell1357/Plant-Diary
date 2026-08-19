import { FieldValue, Timestamp, type Firestore } from "firebase-admin/firestore";
import type { MutationResult } from "./contracts.js";
import { localDateTimeToInstant } from "./notification-settings.js";
import {
  WateringError,
  addLocalDays,
  resolveAccountLocalDate,
  type WateringCompletionCommand,
  type WateringCompletionStore,
} from "./watering.js";

export class FirestoreWateringCompletionStore implements WateringCompletionStore {
  constructor(private readonly firestore: Firestore) {}

  async completeWatering(command: WateringCompletionCommand, now: Date): Promise<MutationResult> {
    return this.firestore.runTransaction(async (transaction) => {
      const accountRef = this.firestore.doc(`users/${command.ownerUid}`);
      const plantRef = this.firestore.doc(
        `users/${command.ownerUid}/personalPlants/${command.plantId}`,
      );
      const scheduleRef = this.firestore.doc(
        `users/${command.ownerUid}/wateringSchedules/${command.plantId}`,
      );
      const settingsRef = this.firestore.doc(
        `users/${command.ownerUid}/notificationSettings/watering`,
      );
      const preferenceRef = this.firestore.doc(
        `users/${command.ownerUid}/notificationPlantSettings/${command.plantId}`,
      );
      const recordRef = this.firestore.doc(
        `users/${command.ownerUid}/wateringRecords/${command.idempotencyKey}`,
      );
      const operationRef = this.firestore.doc(
        `users/${command.ownerUid}/operations/${command.idempotencyKey}`,
      );

      const operation = await transaction.get(operationRef);
      if (operation.exists) {
        const revision = operation.get("plantRevision");
        if (
          typeof revision !== "number" ||
          !Number.isSafeInteger(revision) ||
          revision < 1 ||
          operation.get("revision") !== revision ||
          operation.get("documentPath") !== plantRef.path ||
          operation.get("idempotencyKey") !== command.idempotencyKey ||
          operation.get("recordId") !== command.idempotencyKey ||
          operation.get("requestHash") !== command.requestHash
        ) {
          throw new WateringError("invalid-argument", "Watering receipt does not match the request");
        }
        return { kind: "duplicate", revision };
      }

      const account = await transaction.get(accountRef);
      const plant = await transaction.get(plantRef);
      if (!account.exists || !plant.exists) {
        throw new WateringError("failed-precondition", "Account or personal plant is unavailable");
      }
      if (plant.get("ownerUid") !== command.ownerUid) {
        throw new WateringError("permission-denied", "Personal plant owner does not match");
      }
      const plantRevision = plant.get("revision");
      if (typeof plantRevision !== "number" || !Number.isSafeInteger(plantRevision)) {
        throw new WateringError("failed-precondition", "Personal plant revision is malformed");
      }
      if (plantRevision !== command.expectedPlantRevision) {
        return { kind: "conflict", actualRevision: plantRevision };
      }
      const zoneId = account.get("zoneId");
      if (typeof zoneId !== "string") {
        throw new WateringError("failed-precondition", "Account timezone is unavailable");
      }
      const contentId = plant.get("contentId");
      if (typeof contentId !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(contentId)) {
        throw new WateringError("failed-precondition", "Published watering interval is unavailable");
      }
      const contentRef = this.firestore.doc(`plantContents/${contentId}`);
      const content = await transaction.get(contentRef);
      const interval = content.get("wateringIntervalDays");
      if (
        !content.exists ||
        content.get("publicationState") !== "PUBLIC" ||
        typeof interval !== "number" ||
        !Number.isSafeInteger(interval) ||
        interval < 1 ||
        interval > 365
      ) {
        throw new WateringError("failed-precondition", "Published watering interval is unavailable");
      }
      const [schedule, notificationSettings, notificationPreference] = await Promise.all([
        transaction.get(scheduleRef),
        transaction.get(settingsRef),
        transaction.get(preferenceRef),
      ]);
      const scheduleRevision = schedule.exists ? schedule.get("revision") : 0;
      if (typeof scheduleRevision !== "number" || !Number.isSafeInteger(scheduleRevision) || scheduleRevision < 0) {
        throw new WateringError("failed-precondition", "Watering schedule revision is malformed");
      }
      const wateredDate = resolveAccountLocalDate(command.requestedWateredDate, zoneId, now);
      const dueDate = addLocalDays(wateredDate, interval);
      const nextPlantRevision = plantRevision + 1;
      const nextScheduleRevision = scheduleRevision + 1;
      const serverTimestamp = FieldValue.serverTimestamp();
      const defaultTime = notificationSettings.get("defaultTime");
      const timeOverride = notificationPreference.get("timeOverride");
      const notificationCandidateActive =
        notificationSettings.exists &&
        notificationSettings.get("wateringEnabled") === true &&
        (!notificationPreference.exists || notificationPreference.get("enabled") !== false) &&
        typeof defaultTime === "string";

      transaction.set(
        plantRef,
        {
          lastWateredDate: wateredDate,
          revision: nextPlantRevision,
          expectedRevision: plantRevision,
          idempotencyKey: command.idempotencyKey,
          updatedAt: serverTimestamp,
        },
        { merge: true },
      );
      transaction.create(recordRef, {
        ownerUid: command.ownerUid,
        plantId: command.plantId,
        wateredDate,
        recordedAt: serverTimestamp,
        revision: 1,
        expectedRevision: 0,
        idempotencyKey: command.idempotencyKey,
        updatedAt: serverTimestamp,
      });
      transaction.set(scheduleRef, {
        ownerUid: command.ownerUid,
        plantId: command.plantId,
        dueDate,
        zoneId,
        notificationCandidateActive,
        ...(notificationCandidateActive
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
        revision: nextScheduleRevision,
        expectedRevision: scheduleRevision,
        idempotencyKey: command.idempotencyKey,
        updatedAt: serverTimestamp,
      });
      transaction.create(operationRef, {
        ownerUid: command.ownerUid,
        documentPath: plantRef.path,
        requestHash: command.requestHash,
        wateredDate,
        dueDate,
        recordId: command.idempotencyKey,
        plantRevision: nextPlantRevision,
        scheduleRevision: nextScheduleRevision,
        recordedAt: serverTimestamp,
        zoneId,
        revision: nextPlantRevision,
        expectedRevision: plantRevision,
        idempotencyKey: command.idempotencyKey,
        updatedAt: serverTimestamp,
      });
      return { kind: "applied", revision: nextPlantRevision };
    });
  }
}
