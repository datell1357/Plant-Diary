import { createHash } from "node:crypto";
import { FieldValue, Timestamp, type Firestore } from "firebase-admin/firestore";

export type NotificationAuthContext = Readonly<{ uid: string }>;

export type NotificationEndpointCommand = Readonly<{
  ownerUid: string;
  installationId: string;
  installationSecret: string;
  nextInstallationSecret: string;
  generation: number;
  token: string;
  platform: "ANDROID";
  notificationsEnabled: boolean;
}>;

export type NotificationEndpointUnregistration = Readonly<{
  ownerUid: string;
  installationId: string;
  installationSecret: string;
  generation: number;
}>;

export type PlantWateringSettingsCommand = Readonly<{
  plantId: string;
  enabled: boolean;
  timeOverride: string | null;
}>;

export type WateringNotificationSettingsCommand = Readonly<{
  ownerUid: string;
  expectedRevision: number;
  defaultTime: string;
  zoneId: string;
  globalEnabled: boolean;
  plants: readonly PlantWateringSettingsCommand[];
}>;

export type AccountProfileCommand = Readonly<{
  ownerUid: string;
  displayName: string | null;
  providers: readonly ("GOOGLE" | "APPLE")[];
  zoneId: string;
}>;

export type EndpointRevocationStatus = "REVOKED" | "ALREADY_ABSENT";

export interface NotificationSettingsStore {
  registerEndpoint(command: NotificationEndpointCommand): Promise<void>;
  unregisterEndpoint(
    command: NotificationEndpointUnregistration,
  ): Promise<EndpointRevocationStatus>;
  ensureWateringSettings(ownerUid: string): Promise<void>;
  updateWateringSettings(command: WateringNotificationSettingsCommand): Promise<number>;
  updateAccountProfile(command: AccountProfileCommand): Promise<void>;
  reconcileWateringTimezone(ownerUid: string): Promise<void>;
}

export type NotificationSettingsErrorCode =
  | "unauthenticated"
  | "permission-denied"
  | "invalid-argument"
  | "failed-precondition"
  | "aborted";

export class NotificationSettingsError extends Error {
  constructor(readonly code: NotificationSettingsErrorCode, message: string) {
    super(message);
    this.name = "NotificationSettingsError";
  }
}

const opaqueId = /^[A-Za-z0-9_-]{8,128}$/;
const tokenPattern = /^[^\s]{1,4096}$/;
const secretPattern = /^[^\s]{16,128}$/;
const localTimePattern = /^(0\d|1\d|2[0-3]):[0-5]\d$/;
const maximumPlants = 200;

export async function executeRegisterNotificationEndpoint(
  auth: NotificationAuthContext | null,
  input: unknown,
  store: NotificationSettingsStore,
): Promise<Readonly<{ registered: true }>> {
  const uid = authenticatedUid(auth);
  const value = exactRecord(input, [
    "expectedOwnerUid",
    "installationId",
    "installationSecret",
    "nextInstallationSecret",
    "generation",
    "token",
    "platform",
    "notificationsEnabled",
  ]);
  assertExpectedOwner(value, uid);
  const installationId = stringField(value, "installationId");
  const token = stringField(value, "token");
  const installationSecret = stringField(value, "installationSecret");
  const nextInstallationSecret = stringField(value, "nextInstallationSecret");
  const generation = positiveGeneration(value.generation);
  if (
    !opaqueId.test(installationId) ||
    !tokenPattern.test(token) ||
    !secretPattern.test(installationSecret) ||
    !secretPattern.test(nextInstallationSecret) ||
    value.platform !== "ANDROID" ||
    typeof value.notificationsEnabled !== "boolean"
  ) {
    throw new NotificationSettingsError("invalid-argument", "Notification endpoint is invalid");
  }
  await store.registerEndpoint({
    ownerUid: uid,
    installationId,
    installationSecret,
    nextInstallationSecret,
    generation,
    token,
    platform: "ANDROID",
    notificationsEnabled: value.notificationsEnabled,
  });
  return { registered: true };
}

export async function executeUnregisterNotificationEndpoint(
  auth: NotificationAuthContext | null,
  input: unknown,
  store: NotificationSettingsStore,
): Promise<Readonly<{ unregistered: true; status: EndpointRevocationStatus }>> {
  const uid = authenticatedUid(auth);
  const value = exactRecord(input, ["expectedOwnerUid", "installationId", "installationSecret", "generation"]);
  assertExpectedOwner(value, uid);
  const installationId = stringField(value, "installationId");
  const installationSecret = stringField(value, "installationSecret");
  if (!opaqueId.test(installationId) || !secretPattern.test(installationSecret)) {
    throw new NotificationSettingsError("invalid-argument", "Notification endpoint is invalid");
  }
  const status = await store.unregisterEndpoint({
    ownerUid: uid,
    installationId,
    installationSecret,
    generation: positiveGeneration(value.generation),
  });
  return { unregistered: true, status };
}

export async function executeEnsureWateringNotificationSettings(
  auth: NotificationAuthContext | null,
  input: unknown,
  store: NotificationSettingsStore,
): Promise<Readonly<{ initialized: true }>> {
  const uid = authenticatedUid(auth);
  const value = exactRecord(input, ["expectedOwnerUid"]);
  assertExpectedOwner(value, uid);
  await store.ensureWateringSettings(uid);
  return { initialized: true };
}

export async function executeReconcileWateringNotificationTimezone(
  auth: NotificationAuthContext | null,
  input: unknown,
  store: NotificationSettingsStore,
): Promise<Readonly<{ reconciled: true }>> {
  const uid = authenticatedUid(auth);
  const value = exactRecord(input, ["expectedOwnerUid"]);
  assertExpectedOwner(value, uid);
  await store.reconcileWateringTimezone(uid);
  return { reconciled: true };
}

export async function executeUpdateAccountProfile(
  auth: NotificationAuthContext | null,
  input: unknown,
  store: NotificationSettingsStore,
): Promise<Readonly<{ updated: true }>> {
  const uid = authenticatedUid(auth);
  const value = exactRecord(input, ["expectedOwnerUid", "displayName", "providers", "zoneId"]);
  assertExpectedOwner(value, uid);
  const displayName = value.displayName;
  if (displayName !== null && (typeof displayName !== "string" || displayName.length > 100)) {
    throw new NotificationSettingsError("invalid-argument", "displayName is invalid");
  }
  if (
    !Array.isArray(value.providers) ||
    value.providers.length < 1 ||
    value.providers.some((provider) => provider !== "GOOGLE" && provider !== "APPLE") ||
    new Set(value.providers).size !== value.providers.length
  ) {
    throw new NotificationSettingsError("invalid-argument", "providers are invalid");
  }
  await store.updateAccountProfile({
    ownerUid: uid,
    displayName,
    providers: value.providers as ("GOOGLE" | "APPLE")[],
    zoneId: validZone(stringField(value, "zoneId")),
  });
  return { updated: true };
}

export async function executeUpdateWateringNotificationSettings(
  auth: NotificationAuthContext | null,
  input: unknown,
  store: NotificationSettingsStore,
): Promise<Readonly<{ updated: true; revision: number }>> {
  const uid = authenticatedUid(auth);
  const value = exactRecord(input, ["expectedOwnerUid", "expectedRevision", "defaultTime", "zoneId", "globalEnabled", "plants"]);
  assertExpectedOwner(value, uid);
  const defaultTime = validLocalTime(stringField(value, "defaultTime"));
  const zoneId = validZone(stringField(value, "zoneId"));
  if (typeof value.globalEnabled !== "boolean") {
    throw new NotificationSettingsError("invalid-argument", "globalEnabled must be boolean");
  }
  if (!Array.isArray(value.plants) || value.plants.length > maximumPlants) {
    throw new NotificationSettingsError("invalid-argument", "Plant reminders must be a bounded list");
  }
  const plants = value.plants.map((item) => {
    const plantValue = exactRecord(item, ["plantId", "enabled", "timeOverride"]);
    const plantId = stringField(plantValue, "plantId");
    if (!/^[A-Za-z0-9_-]{1,128}$/.test(plantId) || typeof plantValue.enabled !== "boolean") {
      throw new NotificationSettingsError("invalid-argument", "Plant reminder is invalid");
    }
    const override = plantValue.timeOverride;
    if (override !== null && typeof override !== "string") {
      throw new NotificationSettingsError("invalid-argument", "timeOverride must be a time or null");
    }
    return {
      plantId,
      enabled: plantValue.enabled,
      timeOverride: override === null ? null : validLocalTime(override),
    };
  });
  if (new Set(plants.map((plant) => plant.plantId)).size !== plants.length) {
    throw new NotificationSettingsError("invalid-argument", "Plant reminders must be unique");
  }
  const revision = await store.updateWateringSettings({
    ownerUid: uid,
    expectedRevision: nonNegativeRevision(value.expectedRevision),
    defaultTime,
    zoneId,
    globalEnabled: value.globalEnabled,
    plants,
  });
  return { updated: true, revision };
}

export type FirestoreNotificationSettingsStoreHooks = Readonly<{
  beforeWateringSettingsTransaction?: () => Promise<void>;
  beforeWateringSettingsCommit?: () => Promise<void>;
}>;

export class FirestoreNotificationSettingsStore implements NotificationSettingsStore {
  constructor(
    private readonly firestore: Firestore,
    private readonly hooks: FirestoreNotificationSettingsStoreHooks = {},
  ) {}

  async registerEndpoint(command: NotificationEndpointCommand): Promise<void> {
    await this.firestore.runTransaction(async (transaction) => {
      const ownerRef = this.firestore.doc(`notificationEndpointOwners/${command.installationId}`);
      const owner = await transaction.get(ownerRef);
      const currentSecretHash = secretHash(command.installationSecret);
      const nextSecretHash = secretHash(command.nextInstallationSecret);
      const requestHash = endpointRegistrationRequestHash(command);
      const currentGeneration = endpointGeneration(owner);
      if (!owner.exists) {
        if (command.generation !== 1 || currentSecretHash !== nextSecretHash) {
          throw new NotificationSettingsError("failed-precondition", "Initial endpoint generation is invalid");
        }
      } else {
        if (
          owner.get("state") === "REGISTERED" &&
          owner.get("ownerUid") === command.ownerUid &&
          owner.get("generation") === command.generation &&
          owner.get("registrationRequestHash") === requestHash
        ) return;
        if (owner.get("secretHash") !== currentSecretHash) {
          throw new NotificationSettingsError("permission-denied", "Installation proof is invalid");
        }
        if (
          command.generation > currentGeneration &&
          hasActiveSendLease(owner.get("activeSendLeases"), new Date())
        ) {
          throw new NotificationSettingsError("aborted", "Endpoint send is still in flight");
        }
        if (command.generation < currentGeneration) return;
        if (command.generation === currentGeneration) {
          if (
            owner.get("state") === "REGISTERED" &&
            owner.get("ownerUid") === command.ownerUid &&
            owner.get("token") === command.token &&
            owner.get("notificationsEnabled") === command.notificationsEnabled &&
            owner.get("secretHash") === nextSecretHash
          ) return;
          throw new NotificationSettingsError("failed-precondition", "Endpoint generation is already used");
        }
        if (command.generation !== currentGeneration + 1) {
          throw new NotificationSettingsError("failed-precondition", "Endpoint generation must increment by one");
        }
        const previousOwner = owner.get("ownerUid");
        const state = owner.get("state");
        if (previousOwner !== command.ownerUid) {
          if (state !== "UNREGISTERED") {
            throw new NotificationSettingsError(
              "permission-denied",
              "Current owner must revoke before transfer",
            );
          }
          if (nextSecretHash === currentSecretHash) {
            throw new NotificationSettingsError(
              "failed-precondition",
              "Owner transfer must rotate its proof",
            );
          }
        } else if (nextSecretHash !== currentSecretHash) {
          throw new NotificationSettingsError(
            "failed-precondition",
            "Secret rotates only during owner transfer",
          );
        }
        if (typeof previousOwner === "string" && previousOwner !== command.ownerUid) {
          transaction.delete(
            this.firestore.doc(`users/${previousOwner}/notificationEndpoints/${command.installationId}`),
          );
        }
      }
      const endpoint = {
        ownerUid: command.ownerUid,
        installationId: command.installationId,
        generation: command.generation,
        token: command.token,
        platform: command.platform,
        notificationsEnabled: command.notificationsEnabled,
        updatedAt: FieldValue.serverTimestamp(),
      };
      transaction.set(
        this.firestore.doc(`users/${command.ownerUid}/notificationEndpoints/${command.installationId}`),
        endpoint,
        { merge: false },
      );
      transaction.set(
        ownerRef,
        {
          ...endpoint,
          secretHash: nextSecretHash,
          registrationRequestHash: requestHash,
          state: "REGISTERED",
        },
        { merge: false },
      );
    });
  }

  async unregisterEndpoint(
    command: NotificationEndpointUnregistration,
  ): Promise<EndpointRevocationStatus> {
    return this.firestore.runTransaction(async (transaction) => {
      const ownerRef = this.firestore.doc(`notificationEndpointOwners/${command.installationId}`);
      const owner = await transaction.get(ownerRef);
      if (!owner.exists) {
        transaction.delete(
          this.firestore.doc(
            `users/${command.ownerUid}/notificationEndpoints/${command.installationId}`,
          ),
        );
        transaction.create(ownerRef, {
          ownerUid: command.ownerUid,
          installationId: command.installationId,
          generation: command.generation,
          secretHash: secretHash(command.installationSecret),
          state: "UNREGISTERED",
          revocationStatus: "ALREADY_ABSENT",
          updatedAt: FieldValue.serverTimestamp(),
        });
        return "ALREADY_ABSENT";
      }
      if (owner.get("secretHash") !== secretHash(command.installationSecret)) {
        throw new NotificationSettingsError("permission-denied", "Installation proof is invalid");
      }
      const currentGeneration = endpointGeneration(owner);
      if (command.generation < currentGeneration) {
        if (owner.get("state") === "UNREGISTERED" && owner.get("ownerUid") === command.ownerUid) {
          return "REVOKED";
        }
        throw new NotificationSettingsError("failed-precondition", "Endpoint revocation is stale");
      }
      if (command.generation === currentGeneration) {
        if (owner.get("state") === "UNREGISTERED" && owner.get("ownerUid") === command.ownerUid) {
          return owner.get("revocationStatus") === "ALREADY_ABSENT"
            ? "ALREADY_ABSENT"
            : "REVOKED";
        }
        throw new NotificationSettingsError("failed-precondition", "Endpoint generation is already used");
      }
      if (command.generation !== currentGeneration + 1) {
        throw new NotificationSettingsError("failed-precondition", "Endpoint generation must increment by one");
      }
      if (owner.get("ownerUid") !== command.ownerUid) {
        throw new NotificationSettingsError("permission-denied", "Only the current owner can revoke an endpoint");
      }
      if (hasActiveSendLease(owner.get("activeSendLeases"), new Date())) {
        throw new NotificationSettingsError("aborted", "Endpoint send is still in flight");
      }
      transaction.delete(
        this.firestore.doc(`users/${command.ownerUid}/notificationEndpoints/${command.installationId}`),
      );
      transaction.set(
        ownerRef,
        {
          ownerUid: command.ownerUid,
          installationId: command.installationId,
          generation: command.generation,
          secretHash: owner.get("secretHash"),
          state: "UNREGISTERED",
          revocationStatus: "REVOKED",
          updatedAt: FieldValue.serverTimestamp(),
        },
        { merge: false },
      );
      return "REVOKED";
    });
  }

  async ensureWateringSettings(ownerUid: string): Promise<void> {
    await this.firestore.runTransaction(async (transaction) => {
      const accountRef = this.firestore.doc(`users/${ownerUid}`);
      const settingsRef = this.firestore.doc(`users/${ownerUid}/notificationSettings/watering`);
      const scheduleQuery = this.firestore.collection(`users/${ownerUid}/wateringSchedules`).limit(maximumPlants + 1);
      const [account, settings, schedules] = await Promise.all([
        transaction.get(accountRef),
        transaction.get(settingsRef),
        transaction.get(scheduleQuery),
      ]);
      if (!account.exists) {
        throw new NotificationSettingsError("failed-precondition", "Account is unavailable");
      }
      if (settings.exists) return;
      if (schedules.size > maximumPlants) {
        throw new NotificationSettingsError("failed-precondition", "Too many watering schedules");
      }
      const zoneId = validZoneDocument(account.get("zoneId"));
      const updatedAt = FieldValue.serverTimestamp();
      transaction.create(settingsRef, {
        ownerUid,
        wateringEnabled: true,
        weatherEnabled: false,
        defaultTime: "09:00",
        zoneId,
        revision: 1,
        expectedRevision: 0,
        idempotencyKey: "notification-defaults-v1",
        updatedAt,
      });
      for (const schedule of schedules.docs) {
        const dueDate = schedule.get("dueDate");
        if (typeof dueDate !== "string") continue;
        const scheduleRevision = revisionOf(schedule);
        transaction.set(
          schedule.ref,
          {
            notificationCandidateActive: true,
            nextNotificationAt: Timestamp.fromDate(localDateTimeToInstant(dueDate, "09:00", zoneId)),
            revision: scheduleRevision + 1,
            expectedRevision: scheduleRevision,
            idempotencyKey: "notification-defaults-v1",
            updatedAt,
          },
          { merge: true },
        );
      }
    });
  }

  async updateWateringSettings(command: WateringNotificationSettingsCommand): Promise<number> {
    await this.hooks.beforeWateringSettingsTransaction?.();
    return this.firestore.runTransaction(async (transaction) => {
      const accountRef = this.firestore.doc(`users/${command.ownerUid}`);
      const settingsRef = this.firestore.doc(`users/${command.ownerUid}/notificationSettings/watering`);
      const plantsQuery = this.firestore
        .collection(`users/${command.ownerUid}/personalPlants`)
        .limit(maximumPlants + 1);
      const preferenceRefs = command.plants.map((plant) =>
        this.firestore.doc(`users/${command.ownerUid}/notificationPlantSettings/${plant.plantId}`),
      );
      const scheduleRefs = command.plants.map((plant) =>
        this.firestore.doc(`users/${command.ownerUid}/wateringSchedules/${plant.plantId}`),
      );
      const [account, settings, authoritativePlants, preferences, schedules] = await Promise.all([
        transaction.get(accountRef),
        transaction.get(settingsRef),
        transaction.get(plantsQuery),
        Promise.all(preferenceRefs.map((ref) => transaction.get(ref))),
        Promise.all(scheduleRefs.map((ref) => transaction.get(ref))),
      ]);
      if (!account.exists) {
        throw new NotificationSettingsError("failed-precondition", "Account is unavailable");
      }
      const accountZone = validZoneDocument(account.get("zoneId"));
      if (accountZone !== command.zoneId) {
        throw new NotificationSettingsError("failed-precondition", "Account timezone changed");
      }
      if (authoritativePlants.size > maximumPlants) {
        throw new NotificationSettingsError("failed-precondition", "Too many personal plants");
      }
      if (authoritativePlants.docs.some((plant) => plant.get("ownerUid") !== command.ownerUid)) {
        throw new NotificationSettingsError("failed-precondition", "Personal plant is unavailable");
      }
      const authoritativePlantIds = authoritativePlants.docs.map((plant) => plant.id).sort();
      const submittedPlantIds = command.plants.map((plant) => plant.plantId).sort();
      if (
        authoritativePlantIds.length !== submittedPlantIds.length ||
        authoritativePlantIds.some((plantId, index) => plantId !== submittedPlantIds[index])
      ) {
        throw new NotificationSettingsError(
          "aborted",
          "Personal plant set changed; reload required",
        );
      }
      const settingsRevision = revisionOf(settings);
      if (settingsRevision !== command.expectedRevision) {
        throw new NotificationSettingsError("aborted", "Notification settings changed; reload required");
      }
      const operationSuffix = createHash("sha256")
        .update(JSON.stringify(command), "utf8")
        .digest("hex")
        .slice(0, 32);
      const idempotencyKey = `notification-${operationSuffix}`;
      const updatedAt = FieldValue.serverTimestamp();
      await this.hooks.beforeWateringSettingsCommit?.();
      transaction.set(
        settingsRef,
        {
          ownerUid: command.ownerUid,
          wateringEnabled: command.globalEnabled,
          weatherEnabled: settings.exists && settings.get("weatherEnabled") === true,
          defaultTime: command.defaultTime,
          zoneId: accountZone,
          revision: settingsRevision + 1,
          expectedRevision: settingsRevision,
          idempotencyKey,
          updatedAt,
        },
        { merge: false },
      );
      command.plants.forEach((plantCommand, index) => {
        const preference = preferences[index]!;
        const preferenceRevision = revisionOf(preference);
        transaction.set(
          preferenceRefs[index]!,
          {
            ownerUid: command.ownerUid,
            plantId: plantCommand.plantId,
            enabled: plantCommand.enabled,
            timeOverride: plantCommand.timeOverride,
            revision: preferenceRevision + 1,
            expectedRevision: preferenceRevision,
            idempotencyKey,
            updatedAt,
          },
          { merge: false },
        );
        const schedule = schedules[index]!;
        if (!schedule.exists) return;
        const scheduleRevision = revisionOf(schedule);
        const dueDate = schedule.get("dueDate");
        const active =
          command.globalEnabled && plantCommand.enabled && typeof dueDate === "string";
        transaction.set(
          scheduleRefs[index]!,
          {
            notificationCandidateActive: active,
            reminderTime: FieldValue.delete(),
            enabled: FieldValue.delete(),
            revision: scheduleRevision + 1,
            expectedRevision: scheduleRevision,
            idempotencyKey,
            nextNotificationAt: active
              ? Timestamp.fromDate(
                  localDateTimeToInstant(
                    dueDate as string,
                    plantCommand.timeOverride ?? command.defaultTime,
                    accountZone,
                  ),
                )
              : FieldValue.delete(),
            updatedAt,
          },
          { merge: true },
        );
      });
      return settingsRevision + 1;
    });
  }

  async updateAccountProfile(command: AccountProfileCommand): Promise<void> {
    await this.firestore.runTransaction(async (transaction) => {
      const accountRef = this.firestore.doc(`users/${command.ownerUid}`);
      const settingsRef = this.firestore.doc(
        `users/${command.ownerUid}/notificationSettings/watering`,
      );
      const schedulesQuery = this.firestore
        .collection(`users/${command.ownerUid}/wateringSchedules`)
        .limit(maximumPlants + 1);
      const preferencesQuery = this.firestore.collection(
        `users/${command.ownerUid}/notificationPlantSettings`,
      );
      const [account, settings, schedules, preferences] = await Promise.all([
        transaction.get(accountRef),
        transaction.get(settingsRef),
        transaction.get(schedulesQuery),
        transaction.get(preferencesQuery),
      ]);
      if (schedules.size > maximumPlants) {
        throw new NotificationSettingsError("failed-precondition", "Too many watering schedules");
      }
      const scheduleRevisions = schedules.docs.map(revisionOf);
      const accountRevision = revisionOf(account);
      const zoneChanged = account.get("zoneId") !== command.zoneId;
      const operationHash = createHash("sha256")
        .update(JSON.stringify(command), "utf8")
        .digest("hex")
        .slice(0, 32);
      const idempotencyKey = `account-profile-${operationHash}`;
      const updatedAt = FieldValue.serverTimestamp();
      transaction.set(
        accountRef,
        {
          ownerUid: command.ownerUid,
          displayName: command.displayName,
          providers: [...command.providers].sort(),
          zoneId: command.zoneId,
          revision: accountRevision + 1,
          expectedRevision: accountRevision,
          idempotencyKey,
          updatedAt,
        },
        { merge: false },
      );
      if (!zoneChanged) return;
      const preferencesByPlant = new Map(preferences.docs.map((item) => [item.id, item]));
      const globalEnabled = settings.exists && settings.get("wateringEnabled") === true;
      const defaultTime = settings.exists
        ? validLocalTime(settings.get("defaultTime"))
        : null;
      if (settings.exists) {
        const settingsRevision = revisionOf(settings);
        transaction.set(
          settingsRef,
          {
            zoneId: command.zoneId,
            revision: settingsRevision + 1,
            expectedRevision: settingsRevision,
            idempotencyKey,
            updatedAt,
          },
          { merge: true },
        );
      }
      schedules.docs.forEach((schedule, index) => {
        const dueDate = schedule.get("dueDate");
        const preference = preferencesByPlant.get(schedule.id);
        const active =
          globalEnabled &&
          preference?.get("enabled") !== false &&
          typeof dueDate === "string" &&
          defaultTime !== null;
        const override = preference?.get("timeOverride");
        transaction.set(
          schedule.ref,
          {
            zoneId: command.zoneId,
            notificationCandidateActive: active,
            nextNotificationAt: active
              ? Timestamp.fromDate(
                  localDateTimeToInstant(
                    dueDate as string,
                    typeof override === "string" ? override : defaultTime,
                    command.zoneId,
                  ),
                )
              : FieldValue.delete(),
            revision: scheduleRevisions[index]! + 1,
            expectedRevision: scheduleRevisions[index]!,
            idempotencyKey,
            updatedAt,
          },
          { merge: true },
        );
      });
    });
  }

  async reconcileWateringTimezone(ownerUid: string): Promise<void> {
    await this.firestore.runTransaction(async (transaction) => {
      const accountRef = this.firestore.doc(`users/${ownerUid}`);
      const settingsRef = this.firestore.doc(`users/${ownerUid}/notificationSettings/watering`);
      const schedulesQuery = this.firestore
        .collection(`users/${ownerUid}/wateringSchedules`)
        .limit(maximumPlants + 1);
      const preferencesQuery = this.firestore.collection(
        `users/${ownerUid}/notificationPlantSettings`,
      );
      const [account, settings, schedules, preferences] = await Promise.all([
        transaction.get(accountRef),
        transaction.get(settingsRef),
        transaction.get(schedulesQuery),
        transaction.get(preferencesQuery),
      ]);
      if (!account.exists || !settings.exists) return;
      if (schedules.size > maximumPlants) {
        throw new NotificationSettingsError("failed-precondition", "Too many watering schedules");
      }
      const zoneId = validZoneDocument(account.get("zoneId"));
      if (settings.get("zoneId") === zoneId && schedules.docs.every((item) => item.get("zoneId") === zoneId)) {
        return;
      }
      const defaultTime = validLocalTime(settings.get("defaultTime"));
      const globalEnabled = settings.get("wateringEnabled") === true;
      const preferencesByPlant = new Map(preferences.docs.map((item) => [item.id, item]));
      const updatedAt = FieldValue.serverTimestamp();
      const settingsRevision = revisionOf(settings);
      transaction.set(
        settingsRef,
        {
          zoneId,
          revision: settingsRevision + 1,
          expectedRevision: settingsRevision,
          idempotencyKey: `notification-timezone-${zoneId}`,
          updatedAt,
        },
        { merge: true },
      );
      for (const schedule of schedules.docs) {
        const dueDate = schedule.get("dueDate");
        if (typeof dueDate !== "string") continue;
        const preference = preferencesByPlant.get(schedule.id);
        const active = globalEnabled && preference?.get("enabled") !== false;
        const override = preference?.get("timeOverride");
        const scheduleRevision = revisionOf(schedule);
        transaction.set(
          schedule.ref,
          {
            zoneId,
            notificationCandidateActive: active,
            nextNotificationAt: active
              ? Timestamp.fromDate(
                  localDateTimeToInstant(
                    dueDate,
                    typeof override === "string" ? override : defaultTime,
                    zoneId,
                  ),
                )
              : FieldValue.delete(),
            revision: scheduleRevision + 1,
            expectedRevision: scheduleRevision,
            idempotencyKey: `notification-timezone-${zoneId}`,
            updatedAt,
          },
          { merge: true },
        );
      }
    });
  }
}

function hasActiveSendLease(value: unknown, now: Date): boolean {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  return Object.values(value).some(
    (expiry) => expiry instanceof Timestamp && expiry.toDate() > now,
  );
}

function endpointGeneration(snapshot: FirebaseFirestore.DocumentSnapshot): number {
  if (!snapshot.exists) return 0;
  const value = snapshot.get("generation");
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value : 0;
}

function revisionOf(snapshot: FirebaseFirestore.DocumentSnapshot): number {
  if (!snapshot.exists) return 0;
  const value = snapshot.get("revision");
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 1) {
    throw new NotificationSettingsError("failed-precondition", "Revision is malformed");
  }
  return value;
}

function authenticatedUid(auth: NotificationAuthContext | null): string {
  const uid = auth?.uid;
  if (uid === undefined || !/^[A-Za-z0-9_-]{1,128}$/.test(uid)) {
    throw new NotificationSettingsError("unauthenticated", "Authentication is required");
  }
  return uid;
}

function assertExpectedOwner(value: Readonly<Record<string, unknown>>, uid: string): void {
  if (stringField(value, "expectedOwnerUid") !== uid) {
    throw new NotificationSettingsError("permission-denied", "Authenticated owner does not match the request");
  }
}

function nonNegativeRevision(value: unknown): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) {
    throw new NotificationSettingsError("invalid-argument", "expectedRevision must be a non-negative integer");
  }
  return value;
}

function secretHash(value: string): string {
  return createHash("sha256").update(value, "utf8").digest("hex");
}

function endpointRegistrationRequestHash(command: NotificationEndpointCommand): string {
  return createHash("sha256")
    .update(
      JSON.stringify({
        ownerUid: command.ownerUid,
        installationId: command.installationId,
        installationSecretHash: secretHash(command.installationSecret),
        nextInstallationSecretHash: secretHash(command.nextInstallationSecret),
        generation: command.generation,
        token: command.token,
        platform: command.platform,
        notificationsEnabled: command.notificationsEnabled,
      }),
      "utf8",
    )
    .digest("hex");
}

function positiveGeneration(value: unknown): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 1) {
    throw new NotificationSettingsError("invalid-argument", "generation must be a positive integer");
  }
  return value;
}

function exactRecord(value: unknown, fields: readonly string[]): Readonly<Record<string, unknown>> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new NotificationSettingsError("invalid-argument", "Payload must be an object");
  }
  const result = Object.fromEntries(Object.entries(value));
  if (!fields.every((field) => field in result) || !Object.keys(result).every((field) => fields.includes(field))) {
    throw new NotificationSettingsError("invalid-argument", "Payload fields do not match the contract");
  }
  return result;
}

function stringField(value: Readonly<Record<string, unknown>>, field: string): string {
  const candidate = value[field];
  if (typeof candidate !== "string" || candidate.length === 0) {
    throw new NotificationSettingsError("invalid-argument", `${field} must be a non-empty string`);
  }
  return candidate;
}

function validLocalTime(value: string): string {
  if (!localTimePattern.test(value)) {
    throw new NotificationSettingsError("invalid-argument", "Reminder time is invalid");
  }
  return value;
}

function validZone(value: string): string {
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: value }).format();
  } catch {
    throw new NotificationSettingsError("invalid-argument", "Timezone is invalid");
  }
  return value;
}

function validZoneDocument(value: unknown): string {
  if (typeof value !== "string") {
    throw new NotificationSettingsError("failed-precondition", "Account timezone is unavailable");
  }
  return validZone(value);
}

export function localDateTimeToInstant(date: string, time: string, zoneId: string): Date {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !localTimePattern.test(time)) {
    throw new NotificationSettingsError("failed-precondition", "Notification candidate time is invalid");
  }
  const [year, month, day] = date.split("-").map(Number) as [number, number, number];
  const [hour, minute] = time.split(":").map(Number) as [number, number];
  const desiredAsUtc = Date.UTC(year, month - 1, day, hour, minute);
  let instant = new Date(desiredAsUtc);
  for (let iteration = 0; iteration < 4; iteration += 1) {
    const parts = zonedParts(instant, zoneId);
    const representedAsUtc = Date.UTC(parts.year, parts.month - 1, parts.day, parts.hour, parts.minute);
    const adjustment = desiredAsUtc - representedAsUtc;
    if (adjustment === 0) return instant;
    instant = new Date(instant.valueOf() + adjustment);
  }
  const final = zonedParts(instant, zoneId);
  if (Date.UTC(final.year, final.month - 1, final.day, final.hour, final.minute) !== desiredAsUtc) {
    throw new NotificationSettingsError("failed-precondition", "Local reminder time does not exist");
  }
  return instant;
}

function zonedParts(value: Date, zoneId: string): { year: number; month: number; day: number; hour: number; minute: number } {
  const formatter = new Intl.DateTimeFormat("en-US", {
    timeZone: zoneId,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  });
  const values = Object.fromEntries(
    formatter
      .formatToParts(value)
      .filter((part) => part.type !== "literal")
      .map((part) => [part.type, Number(part.value)]),
  );
  return {
    year: values.year!,
    month: values.month!,
    day: values.day!,
    hour: values.hour!,
    minute: values.minute!,
  };
}
