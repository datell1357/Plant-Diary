// allow: SIZE_OK — Firebase deployment composition root owns exported trigger wiring.
import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { createHmac, randomUUID } from "node:crypto";
import { getFirestore } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { getStorage } from "firebase-admin/storage";
import { defineSecret, defineString } from "firebase-functions/params";
import { onDocumentWritten } from "firebase-functions/v2/firestore";
import {
  type CallableOptions,
  type CallableRequest,
  HttpsError,
  onCall,
  onRequest,
} from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { onObjectFinalized } from "firebase-functions/v2/storage";
import {
  type AccountDeletionAuth,
  AccountDeletionError,
} from "./account-deletion-contract.js";
import { runAccountDeletionScan } from "./account-deletion-processor.js";
import {
  cancelAccountDeletion as cancelDeletion,
  getAccountDeletionStatus as getDeletionStatus,
  previewAccountDeletion as previewDeletion,
  requestAccountDeletion as requestDeletion,
  retryAccountDeletion as retryDeletion,
} from "./account-deletion-service.js";
import {
  AccountMutationLockedError,
  FirestoreAccountMutationLock,
  withAccountMutationLock,
} from "./account-mutation-lock.js";
import { AppleAuthError, executeAppleCallback, executeBeginAppleSignIn, executeCompleteAppleSignIn } from "./apple-auth.js";
import { FirestoreAppleSessionStore, VerifiedAppleTokenExchange } from "./apple-auth-runtime.js";
import {
  AnalyticsError,
  executeGetAnalyticsConsent,
  executeRecordClientAnalyticsEvents,
  executeSetAnalyticsConsent,
} from "./analytics.js";
import { ContractError, executeOwnerMutation } from "./contracts.js";
import { FirebaseAccountDeletionCleaner } from "./firebase-account-deletion-cleaner.js";
import { FirebaseLegacyIdentificationOriginalStore } from "./firebase-identification-cleanup.js";
import { FirebasePrivateMediaObjectStore, FirebasePrivateMediaSigner } from "./firebase-private-media.js";
import {
  cleanupExpiredAccountDeletionTombstones,
  FirestoreAccountDeletionStore,
} from "./firestore-account-deletion-store.js";
import { FirestoreIdentificationCleanupPersistence } from "./firestore-identification-cleanup.js";
import {
  cleanupExpiredAnalyticsRetention,
  FirestoreAnalyticsStore,
} from "./firestore-analytics-store.js";
import { createFirestoreServerAnalyticsRecorder } from "./server-analytics.js";
import { FirestorePrivateMediaReservationRepository } from "./firestore-private-media.js";
import { FirestoreIdentificationAuthorizationRepository } from "./firestore-identification-authorization.js";
import {
  createIdentificationRequest as createAuthorizedIdentificationRequest,
  IdentificationAuthorizationError,
} from "./identification-authorization.js";
import {
  runIdentificationCleanup,
  throwIfIdentificationCleanupFailed,
} from "./identification-cleanup.js";
import { FirestoreMutationStore } from "./firestore-store.js";
import { FirestoreCatalogProjectionStore } from "./firestore-mini-home-projection.js";
import { FirestoreMiniHomeLayoutStore } from "./firestore-mini-home-store.js";
import { FirestoreMiniHomeSnapshotStore } from "./firestore-mini-home-snapshot-store.js";
import { FirestoreMiniHomeShareStore } from "./firestore-mini-home-share-store.js";
import { FirestoreInventoryStore } from "./firestore-inventory-store.js";
import {
  executeAcquireInventoryItem,
  executeLoadInventory,
  InventoryError,
  type InventoryStore,
} from "./inventory.js";
import { executeDeleteMiniHomeLayout, executeLoadMiniHomeLayout, executeSaveMiniHomeLayout, MiniHomeError } from "./mini-home.js";
import { executeLoadMiniHomeSnapshot, MiniHomeSnapshotError, type MiniHomeSnapshotStore } from "./mini-home-snapshot.js";
import {
  MiniHomeShareError,
  createPublicMiniHomeShareHandler,
  executeCreateMiniHomeShareLink,
  executeRevokeMiniHomeShareLink,
} from "./mini-home-share.js";
import { FirestoreWateringCompletionStore } from "./firestore-watering-store.js";
import {
  FirestoreNotificationSettingsStore,
  NotificationSettingsError,
  executeEnsureWateringNotificationSettings,
  executeReconcileWateringNotificationTimezone,
  executeRegisterNotificationEndpoint,
  executeUnregisterNotificationEndpoint,
  executeUpdateAccountProfile,
  executeUpdateWateringNotificationSettings,
} from "./notification-settings.js";
import {
  FirestoreIdentificationRequestStore,
  IdentificationRuntimeError,
  PlantIdStorageProvider,
  productionPlantIdHttpClient,
} from "./plant-identification-runtime.js";
import { executePlantIdentification, PlantIdentificationError } from "./plant-identification.js";
import {
  commitPrivateMediaReservation as commitMediaReservation,
  PrivateMediaError,
  reservePrivateMediaUpload as reserveMediaUpload,
} from "./private-media.js";
import { handlePrivateMediaFinalized } from "./private-media-seal.js";
import { WateringError, executeWateringCompletion } from "./watering.js";
import {
  executeRefreshWeather,
  executeSearchWeatherRegions,
  executeSetLocationConsent,
  executeSetManualWeatherRegion,
  executeUpdateWeatherAlerts,
} from "./weather-service.js";
import {
  FirestoreWeatherStore,
  OpenWeatherProvider,
  deliverPendingWeatherAlerts,
  executeScheduledWeatherRefresh,
  runConfiguredWeatherRefreshScan,
} from "./weather-runtime.js";
import { WeatherConsentConflictError, WeatherError } from "./weather.js";
import {
  WEATHER_RETENTION_CLEANUP_LIMIT,
  cleanupExpiredWeatherData,
  type WeatherRetentionCleanupFailure,
  type WeatherRetentionCleanupResult,
} from "./weather-retention.js";
import {
  FirebaseWateringPushSender,
  FirestoreWateringDeliveryStore,
  NotificationOpenError,
  cleanupExpiredNotificationRecords,
  executeConfirmNotificationOpened,
  runWateringDeliveryScan,
} from "./watering-notifications.js";

if (getApps().length === 0) initializeApp();
const firestore = getFirestore();
const storage = getStorage();
const accountDeletionStore = new FirestoreAccountDeletionStore(firestore);
const accountDeletionCleaner = new FirebaseAccountDeletionCleaner(
  firestore,
  storage,
  getAuth(),
);
const privateMediaRepository = new FirestorePrivateMediaReservationRepository(firestore);
const privateMediaObjects = new FirebasePrivateMediaObjectStore(storage);
const privateMediaSigner = new FirebasePrivateMediaSigner(storage);
const identificationCleanupPersistence = new FirestoreIdentificationCleanupPersistence(firestore);
const legacyIdentificationOriginals = new FirebaseLegacyIdentificationOriginalStore(storage);
const accountMutationLock = new FirestoreAccountMutationLock(firestore);
const store = new FirestoreMutationStore(firestore);
const wateringStore = new FirestoreWateringCompletionStore(firestore);
const catalogProjectionStore = new FirestoreCatalogProjectionStore(firestore);
const miniHomeStore = new FirestoreMiniHomeLayoutStore(firestore);
const miniHomeSnapshotStore = new FirestoreMiniHomeSnapshotStore(firestore);
const miniHomeShareStore = new FirestoreMiniHomeShareStore(firestore);
const inventoryStore = new FirestoreInventoryStore(firestore);
const notificationSettingsStore = new FirestoreNotificationSettingsStore(firestore);
const wateringDeliveryStore = new FirestoreWateringDeliveryStore(firestore);
const appleStore = new FirestoreAppleSessionStore(firestore);
const analyticsStore = new FirestoreAnalyticsStore(firestore);
const serverAnalyticsRecorder = createFirestoreServerAnalyticsRecorder(firestore);
const applePrivateKey = defineSecret("APPLE_PRIVATE_KEY");
const appleAbuseHashKey = defineSecret("APPLE_ABUSE_HASH_KEY");
const plantIdApiKey = defineSecret("PLANT_ID_API_KEY");
const openWeatherApiKey = defineSecret("OPENWEATHER_API_KEY");
const miniHomeShareTokenKey = defineSecret("MINI_HOME_SHARE_TOKEN_KEY");
const storageBucket = defineString("STORAGE_BUCKET");
const weatherStore = new FirestoreWeatherStore(firestore, serverAnalyticsRecorder);

type MutationHandler = (request: CallableRequest<unknown>) => Promise<unknown>;

const emulatorAppCheckDebugTokenEnvironment = "FUNCTIONS_EMULATOR_APP_CHECK_DEBUG_TOKEN";

function requireEmulatorAppCheckToken(request: CallableRequest<unknown>): void {
  if (process.env.FUNCTIONS_EMULATOR !== "true") return;
  const expected = process.env[emulatorAppCheckDebugTokenEnvironment];
  const actual = request.rawRequest.header("x-firebase-appcheck");
  if (expected === undefined || actual !== expected) {
    throw new HttpsError("unauthenticated", "Unauthenticated");
  }
}

/**
 * Keeps emulator App Check behavior fail-closed without weakening Firebase's production verification.
 * Every onCall export must be composed through this function or onMutationCall below.
 */
function onAppCheckedCall(
  options: CallableOptions<unknown>,
  handler: MutationHandler,
) {
  return onCall(options, async (request) => {
    requireEmulatorAppCheckToken(request);
    return handler(request);
  });
}

function onMutationCall(
  options: CallableOptions<unknown>,
  handler: MutationHandler,
) {
  const guarded = withAccountMutationLock(accountMutationLock, handler);
  return onAppCheckedCall(options, async (request) => {
    try {
      return await guarded(request);
    } catch (error: unknown) {
      if (error instanceof AccountMutationLockedError) {
        throw new HttpsError("failed-precondition", error.message);
      }
      throw error;
    }
  });
}

function deletionAuth(request: CallableRequest<unknown>): AccountDeletionAuth | null {
  if (request.auth === undefined) return null;
  const authTime = request.auth.token.auth_time;
  return {
    uid: request.auth.uid,
    authTimeSeconds: typeof authTime === "number" ? authTime : null,
  };
}

function deletionHttpsError(error: unknown): never {
  if (!(error instanceof AccountDeletionError)) throw error;
  throw new HttpsError(error.code, error.message);
}

function analyticsHttpsError(error: unknown): never {
  if (!(error instanceof AnalyticsError)) throw error;
  throw new HttpsError(error.code, error.message);
}

function privateMediaHttpsError(error: unknown): never {
  if (!(error instanceof PrivateMediaError)) throw error;
  throw new HttpsError(error.code, error.message);
}

function requiredEnvironment(name: "APPLE_CLIENT_ID" | "APPLE_REDIRECT_URI" | "APPLE_TEAM_ID" | "APPLE_KEY_ID"): string {
  const value = process.env[name];
  if (value === undefined || value.length === 0) throw new AppleAuthError("failed-precondition", `${name} is not configured`);
  return value;
}

function appleConfig() {
  return {
    clientId: requiredEnvironment("APPLE_CLIENT_ID"),
    redirectUri: requiredEnvironment("APPLE_REDIRECT_URI"),
  };
}

function appleHttpsError(error: unknown): never {
  if (!(error instanceof AppleAuthError)) throw error;
  switch (error.code) {
    case "invalid-argument": throw new HttpsError("invalid-argument", error.message);
    case "not-found": throw new HttpsError("not-found", error.message);
    case "already-exists": throw new HttpsError("already-exists", error.message);
    case "failed-precondition": throw new HttpsError("failed-precondition", error.message);
    case "unauthenticated": throw new HttpsError("unauthenticated", error.message);
    case "resource-exhausted": throw new HttpsError("resource-exhausted", error.message);
    case "unavailable": throw new HttpsError("unavailable", error.message);
  }
}

const deletionCallableOptions = { enforceAppCheck: true, region: "us-central1" } as const;

export const previewAccountDeletion = onAppCheckedCall(deletionCallableOptions, async (request) => {
  try {
    return await previewDeletion(deletionAuth(request), request.data, accountDeletionStore);
  } catch (error: unknown) {
    return deletionHttpsError(error);
  }
});

export const requestAccountDeletion = onAppCheckedCall(deletionCallableOptions, async (request) => {
  try {
    return await requestDeletion(deletionAuth(request), request.data, {
      store: accountDeletionStore,
      nowMillis: Date.now,
      requestId: randomUUID,
      analytics: serverAnalyticsRecorder,
      analyticsDeletion: analyticsStore,
    });
  } catch (error: unknown) {
    return deletionHttpsError(error);
  }
});

export const getAccountDeletionStatus = onAppCheckedCall(deletionCallableOptions, async (request) => {
  try {
    return await getDeletionStatus(deletionAuth(request), request.data, accountDeletionStore);
  } catch (error: unknown) {
    return deletionHttpsError(error);
  }
});

export const cancelAccountDeletion = onAppCheckedCall(deletionCallableOptions, async (request) => {
  try {
    return await cancelDeletion(deletionAuth(request), request.data, {
      store: accountDeletionStore,
      nowMillis: Date.now,
    });
  } catch (error: unknown) {
    return deletionHttpsError(error);
  }
});

export const retryAccountDeletion = onAppCheckedCall(deletionCallableOptions, async (request) => {
  try {
    return await retryDeletion(deletionAuth(request), request.data, {
      store: accountDeletionStore,
      nowMillis: Date.now,
      requestId: randomUUID,
    });
  } catch (error: unknown) {
    return deletionHttpsError(error);
  }
});

export const executeDueAccountDeletions = onSchedule(
  { schedule: "every 5 minutes", timeZone: "UTC", timeoutSeconds: 540 },
  async () => {
    await runAccountDeletionScan({
      store: accountDeletionStore,
      cleaner: accountDeletionCleaner,
      nowMillis: Date.now,
    });
  },
);

export const cleanupExpiredAccountDeletionRecords = onSchedule(
  { schedule: "every 60 minutes", timeZone: "UTC", timeoutSeconds: 120 },
  async () => {
    const result = await cleanupExpiredAccountDeletionTombstones(firestore);
    if (result.failures.length > 0) {
      throw new AggregateError(
        result.failures.map((failure) => failure.error),
        "Account deletion tombstone cleanup had per-item failures",
      );
    }
  },
);

export const cleanupExpiredNotificationRetention = onSchedule(
  { schedule: "every 60 minutes", timeZone: "UTC", timeoutSeconds: 120 },
  async () => {
    const result = await cleanupExpiredNotificationRecords(firestore);
    if (result.failures.length > 0) {
      throw new AggregateError(
        result.failures.map((failure) => failure.error),
        "Notification retention cleanup had per-item failures",
      );
    }
  },
);

export const cleanupExpiredWeatherRetention = onSchedule(
  { schedule: "every 60 minutes", timeZone: "UTC", timeoutSeconds: 120 },
  async () => {
    const result: WeatherRetentionCleanupResult = await cleanupExpiredWeatherData(
      firestore,
      undefined,
      WEATHER_RETENTION_CLEANUP_LIMIT,
    );
    const failures: readonly WeatherRetentionCleanupFailure[] = result.failures;
    if (failures.length > 0) {
      throw new AggregateError(
        failures.map((failure) => failure.error),
        "Weather retention cleanup had per-item failures",
      );
    }
  },
);

export const getAnalyticsConsent = onAppCheckedCall(
  { enforceAppCheck: true, region: "us-central1" },
  async (request) => {
    try {
      return await executeGetAnalyticsConsent(
        request.auth === undefined ? null : { uid: request.auth.uid },
        request.data,
        analyticsStore,
      );
    } catch (error: unknown) {
      return analyticsHttpsError(error);
    }
  },
);

export const setAnalyticsConsent = onMutationCall(
  { enforceAppCheck: true, region: "us-central1" },
  async (request) => {
    try {
      return await executeSetAnalyticsConsent(
        request.auth === undefined ? null : { uid: request.auth.uid },
        request.data,
        analyticsStore,
      );
    } catch (error: unknown) {
      return analyticsHttpsError(error);
    }
  },
);

export const recordAnalyticsEvent = onMutationCall(
  { enforceAppCheck: true, region: "us-central1" },
  async (request) => {
    try {
      return await executeRecordClientAnalyticsEvents(
        request.auth === undefined ? null : { uid: request.auth.uid },
        request.data,
        analyticsStore,
      );
    } catch (error: unknown) {
      return analyticsHttpsError(error);
    }
  },
);

export const cleanupExpiredAnalytics = onSchedule(
  { schedule: "every 60 minutes", timeZone: "UTC", timeoutSeconds: 120 },
  async () => {
    await cleanupExpiredAnalyticsRetention(firestore);
  },
);

export { recordServerAnalyticsEvent } from "./firestore-analytics-store.js";

export const reservePrivateMediaUpload = onMutationCall(
  { enforceAppCheck: true, region: "us-central1" },
  async (request) => {
    try {
      return await reserveMediaUpload(
        request.auth === undefined ? null : { uid: request.auth.uid },
        request.data,
        {
          repository: privateMediaRepository,
          signer: privateMediaSigner,
          nowMillis: Date.now,
          reservationId: () => randomUUID().replaceAll("-", ""),
        },
      );
    } catch (error: unknown) {
      return privateMediaHttpsError(error);
    }
  },
);

export const commitPrivateMediaReservation = onMutationCall(
  { enforceAppCheck: true, region: "us-central1" },
  async (request) => {
    try {
      return await commitMediaReservation(
        request.auth === undefined ? null : { uid: request.auth.uid },
        request.data,
        { repository: privateMediaRepository, objects: privateMediaObjects, nowMillis: Date.now },
      );
    } catch (error: unknown) {
      return privateMediaHttpsError(error);
    }
  },
);

export const deleteFinalizedPrivateMediaDuringDeletion = onObjectFinalized(
  { bucket: storageBucket, region: "us-central1", retry: true },
  async (event) => {
    await handlePrivateMediaFinalized({
      object: {
        path: event.data.name,
        generation: String(event.data.generation),
        byteSize: event.data.size,
        contentType: event.data.contentType ?? "application/octet-stream",
        customMetadata: event.data.metadata ?? {},
      },
      repository: privateMediaRepository,
      objects: privateMediaObjects,
    });
  },
);

export const cleanupExpiredIdentificationOriginals = onSchedule(
  { schedule: "every 60 minutes", timeZone: "UTC", timeoutSeconds: 540 },
  async () => {
    const result = await runIdentificationCleanup({
      persistence: identificationCleanupPersistence,
      reservations: privateMediaRepository,
      objects: privateMediaObjects,
      legacyObjects: legacyIdentificationOriginals,
      nowMillis: Date.now,
    });
    throwIfIdentificationCleanupFailed(result);
  },
);

export function createCatalogProjectionWriteHandler(
  rebuilder: Pick<FirestoreCatalogProjectionStore, "rebuild">,
): () => Promise<void> {
  return async () => {
    await rebuilder.rebuild();
  };
}

export const publishCatalogProjectionOnWrite = onDocumentWritten(
  {
    document: "shopItems/{itemId}",
    retry: true,
  },
  createCatalogProjectionWriteHandler(catalogProjectionStore),
);

export const applyRevisionedOwnerWrite = onMutationCall({ enforceAppCheck: true }, async (request) => {
  try {
    return await executeOwnerMutation(request.auth === undefined ? null : { uid: request.auth.uid }, request.data, store);
  } catch (error: unknown) {
    if (error instanceof ContractError) throw new HttpsError(error.code, error.message);
    throw error;
  }
});

export { executeOwnerMutation, executeServerStateWrite } from "./contracts.js";

export const loadMiniHomeLayout = onAppCheckedCall({ enforceAppCheck: true }, async (request) => {
  try {
    return await executeLoadMiniHomeLayout(
      request.auth === undefined ? null : { uid: request.auth.uid },
      request.data,
      miniHomeStore,
    );
  } catch (error: unknown) {
    if (error instanceof MiniHomeError) {
      throw new HttpsError(
        error.code,
        error.message,
        error.reason === undefined
          ? undefined
          : { reason: error.reason, ...error.details },
      );
    }
    throw error;
  }
});

export function createLoadMiniHomeSnapshotCallable(store: MiniHomeSnapshotStore) {
  return onAppCheckedCall({ enforceAppCheck: true }, async (request) => {
    try {
      return await executeLoadMiniHomeSnapshot(
        request.auth === undefined ? null : { uid: request.auth.uid },
        request.data,
        store,
      );
    } catch (error: unknown) {
      if (error instanceof MiniHomeSnapshotError) {
        throw new HttpsError(error.code, error.message, {
          reason: error.reason,
          ...error.details,
        });
      }
      throw error;
    }
  });
}

export const loadMiniHomeSnapshot = createLoadMiniHomeSnapshotCallable(miniHomeSnapshotStore);

function miniHomeShareHttpsError(error: unknown): never {
  if (!(error instanceof MiniHomeShareError)) throw error;
  throw new HttpsError(error.code, error.message, error.details);
}

function miniHomeSharePublicEndpoint(host: string | undefined): string {
  const configured = process.env.MINI_HOME_SHARE_PUBLIC_URL;
  if (configured !== undefined && configured.length > 0) return configured;
  const projectId = process.env.GCLOUD_PROJECT ?? process.env.GCP_PROJECT;
  if (projectId === undefined || !/^[a-z][a-z0-9-]{4,29}$/.test(projectId)) {
    throw new MiniHomeShareError("failed-precondition", "Public share endpoint is unavailable");
  }
  if (process.env.FUNCTIONS_EMULATOR === "true" && host !== undefined) {
    return `http://${host}/${projectId}/us-central1/publicMiniHomeShare`;
  }
  return `https://us-central1-${projectId}.cloudfunctions.net/publicMiniHomeShare`;
}

export const createMiniHomeShareLink = onMutationCall(
  { enforceAppCheck: true, secrets: [miniHomeShareTokenKey] },
  async (request) => {
    try {
      return await executeCreateMiniHomeShareLink(
        request.auth === undefined ? null : { uid: request.auth.uid },
        request.data,
        miniHomeShareStore,
        miniHomeShareTokenKey.value(),
        new Date(),
        miniHomeSharePublicEndpoint(request.rawRequest.headers.host),
        serverAnalyticsRecorder,
      );
    } catch (error: unknown) {
      return miniHomeShareHttpsError(error);
    }
  },
);

export const revokeMiniHomeShareLink = onMutationCall(
  { enforceAppCheck: true },
  async (request) => {
    try {
      return await executeRevokeMiniHomeShareLink(
        request.auth === undefined ? null : { uid: request.auth.uid },
        request.data,
        miniHomeShareStore,
        new Date(),
      );
    } catch (error: unknown) {
      return miniHomeShareHttpsError(error);
    }
  },
);

export const publicMiniHomeShare = onRequest(
  { cors: false },
  createPublicMiniHomeShareHandler(miniHomeShareStore),
);

export const deleteMiniHomeLayout = onMutationCall({ enforceAppCheck: true }, async (request) => {
  try {
    return await executeDeleteMiniHomeLayout(
      request.auth === undefined ? null : { uid: request.auth.uid },
      request.data,
      miniHomeStore,
    );
  } catch (error: unknown) {
    if (error instanceof MiniHomeError) {
      throw new HttpsError(
        error.code,
        error.message,
        error.reason === undefined
          ? undefined
          : { reason: error.reason, ...error.details },
      );
    }
    throw error;
  }
});

export const saveMiniHomeLayout = onMutationCall({ enforceAppCheck: true }, async (request) => {
  try {
    return await executeSaveMiniHomeLayout(
      request.auth === undefined ? null : { uid: request.auth.uid },
      request.data,
      miniHomeStore,
      serverAnalyticsRecorder,
    );
  } catch (error: unknown) {
    if (error instanceof MiniHomeError) {
      throw new HttpsError(
        error.code,
        error.message,
        error.reason === undefined
          ? undefined
          : { reason: error.reason, ...error.details },
      );
    }
    throw error;
  }
});

function inventoryHttpsError(error: unknown): never {
  if (!(error instanceof InventoryError)) throw error;
  throw new HttpsError(error.code, error.message, {
    reason: error.reason,
    ...error.details,
  });
}

export function createLoadInventoryCallable(store: InventoryStore) {
  return onAppCheckedCall({ enforceAppCheck: true }, async (request) => {
    try {
      return await executeLoadInventory(
        request.auth === undefined ? null : { uid: request.auth.uid },
        request.data,
        store,
      );
    } catch (error: unknown) {
      return inventoryHttpsError(error);
    }
  });
}

export const loadInventory = createLoadInventoryCallable(inventoryStore);

export const acquireInventoryItem = onMutationCall({ enforceAppCheck: true }, async (request) => {
  try {
    return await executeAcquireInventoryItem(
      request.auth === undefined ? null : { uid: request.auth.uid },
      request.data,
      inventoryStore,
    );
  } catch (error: unknown) {
    return inventoryHttpsError(error);
  }
});

export const completeWatering = onMutationCall({ enforceAppCheck: true }, async (request) => {
  try {
    return await executeWateringCompletion(
      request.auth === undefined ? null : { uid: request.auth.uid },
      request.data,
      wateringStore,
    );
  } catch (error: unknown) {
    if (error instanceof WateringError) throw new HttpsError(error.code, error.message);
    throw error;
  }
});

function notificationHttpsError(error: unknown): never {
  if (!(error instanceof NotificationSettingsError)) throw error;
  throw new HttpsError(error.code, error.message);
}

export const registerNotificationEndpoint = onMutationCall(
  { enforceAppCheck: true },
  async (request) => {
    try {
      return await executeRegisterNotificationEndpoint(
        request.auth === undefined ? null : { uid: request.auth.uid },
        request.data,
        notificationSettingsStore,
      );
    } catch (error: unknown) {
      return notificationHttpsError(error);
    }
  },
);

export const unregisterNotificationEndpoint = onMutationCall(
  { enforceAppCheck: true },
  async (request) => {
    try {
      return await executeUnregisterNotificationEndpoint(
        request.auth === undefined ? null : { uid: request.auth.uid },
        request.data,
        notificationSettingsStore,
      );
    } catch (error: unknown) {
      return notificationHttpsError(error);
    }
  },
);

export const ensureWateringNotificationSettings = onMutationCall(
  { enforceAppCheck: true },
  async (request) => {
    try {
      return await executeEnsureWateringNotificationSettings(
        request.auth === undefined ? null : { uid: request.auth.uid },
        request.data,
        notificationSettingsStore,
      );
    } catch (error: unknown) {
      return notificationHttpsError(error);
    }
  },
);

export const updateAccountProfile = onMutationCall(
  { enforceAppCheck: true },
  async (request) => {
    try {
      return await executeUpdateAccountProfile(
        request.auth === undefined ? null : { uid: request.auth.uid },
        request.data,
        notificationSettingsStore,
      );
    } catch (error: unknown) {
      return notificationHttpsError(error);
    }
  },
);

export const reconcileWateringNotificationTimezone = onMutationCall(
  { enforceAppCheck: true },
  async (request) => {
    try {
      return await executeReconcileWateringNotificationTimezone(
        request.auth === undefined ? null : { uid: request.auth.uid },
        request.data,
        notificationSettingsStore,
      );
    } catch (error: unknown) {
      return notificationHttpsError(error);
    }
  },
);

export const updateWateringNotificationSettings = onMutationCall(
  { enforceAppCheck: true },
  async (request) => {
    try {
      return await executeUpdateWateringNotificationSettings(
        request.auth === undefined ? null : { uid: request.auth.uid },
        request.data,
        notificationSettingsStore,
      );
    } catch (error: unknown) {
      return notificationHttpsError(error);
    }
  },
);

export const confirmNotificationOpened = onMutationCall(
  { enforceAppCheck: true },
  async (request) => {
    try {
      return await executeConfirmNotificationOpened(
        firestore,
        request.auth === undefined ? null : { uid: request.auth.uid },
        request.data,
        new Date(),
        serverAnalyticsRecorder,
      );
    } catch (error: unknown) {
      if (error instanceof NotificationOpenError) throw new HttpsError(error.code, error.message);
      throw error;
    }
  },
);

export const deliverDueWateringNotifications = onSchedule(
  { schedule: "every 15 minutes", timeZone: "UTC", timeoutSeconds: 540 },
  async () => {
    await runWateringDeliveryScan(
      wateringDeliveryStore,
      new FirebaseWateringPushSender(getMessaging()),
      new Date(),
      100,
      serverAnalyticsRecorder,
    );
  },
);

function weatherHttpsError(error: unknown): never {
  if (!(error instanceof WeatherError)) throw error;
  throw new HttpsError(
    error.code,
    error.message,
    error instanceof WeatherConsentConflictError
      ? {
          commandGeneration: error.commandGeneration,
          granted: error.granted,
          conflict: true,
        }
      : undefined,
  );
}

export const searchWeatherRegions = onAppCheckedCall(
  { enforceAppCheck: true, secrets: [openWeatherApiKey] },
  async (request) => {
    try {
      return await executeSearchWeatherRegions(
        request.auth === undefined ? null : { uid: request.auth.uid },
        request.data,
        new OpenWeatherProvider(openWeatherApiKey.value()),
      );
    } catch (error: unknown) {
      return weatherHttpsError(error);
    }
  },
);

export const setWeatherLocationConsent = onMutationCall(
  { enforceAppCheck: true },
  async (request) => {
    try {
      return await executeSetLocationConsent(
        request.auth === undefined ? null : { uid: request.auth.uid },
        request.data,
        weatherStore,
      );
    } catch (error: unknown) {
      return weatherHttpsError(error);
    }
  },
);

export const setManualWeatherRegion = onMutationCall(
  { enforceAppCheck: true },
  async (request) => {
    try {
      return await executeSetManualWeatherRegion(
        request.auth === undefined ? null : { uid: request.auth.uid },
        request.data,
        weatherStore,
      );
    } catch (error: unknown) {
      return weatherHttpsError(error);
    }
  },
);

export const updateWeatherAlerts = onMutationCall(
  { enforceAppCheck: true },
  async (request) => {
    try {
      return await executeUpdateWeatherAlerts(
        request.auth === undefined ? null : { uid: request.auth.uid },
        request.data,
        weatherStore,
      );
    } catch (error: unknown) {
      return weatherHttpsError(error);
    }
  },
);

export const refreshWeather = onMutationCall(
  { enforceAppCheck: true, secrets: [openWeatherApiKey], timeoutSeconds: 30 },
  async (request) => {
    try {
      const result = await executeRefreshWeather(
        request.auth === undefined ? null : { uid: request.auth.uid },
        request.data,
        weatherStore,
        new OpenWeatherProvider(openWeatherApiKey.value()),
      );
      return result;
    } catch (error: unknown) {
      return weatherHttpsError(error);
    }
  },
);

export const deliverWeatherAlertOutbox = onSchedule(
  { schedule: "every 5 minutes", timeZone: "UTC", timeoutSeconds: 240 },
  async () => {
    await deliverPendingWeatherAlerts(
      firestore,
      getMessaging(),
      undefined,
      100,
      undefined,
      { analytics: serverAnalyticsRecorder },
    );
  },
);

export const refreshConfiguredWeather = onSchedule(
  {
    schedule: "every 60 minutes",
    timeZone: "UTC",
    timeoutSeconds: 540,
    secrets: [openWeatherApiKey],
  },
  async () => {
    const provider = new OpenWeatherProvider(openWeatherApiKey.value());
    await runConfiguredWeatherRefreshScan(firestore, async (ownerUid, signal) => {
      await executeScheduledWeatherRefresh(ownerUid, weatherStore, provider, signal);
    });
  },
);

export const beginAppleSignIn = onAppCheckedCall(
  { enforceAppCheck: true, secrets: [appleAbuseHashKey] },
  async (request) => {
    try {
      const appId = request.app?.appId;
      const ip = request.rawRequest.ip;
      const hashKey = appleAbuseHashKey.value();
      if (
        typeof appId !== "string" || appId.length < 3 || appId.length > 256 ||
        typeof ip !== "string" || ip.length < 2 || ip.length > 128 ||
        hashKey.length < 32
      ) {
        throw new AppleAuthError("failed-precondition", "Apple session admission is unavailable");
      }
      const abuseKeyHash = createHmac("sha256", hashKey).update(appId).update("\0").update(ip).digest("hex");
      return await executeBeginAppleSignIn(
        request.data,
        appleStore,
        appleConfig(),
        abuseKeyHash,
        new Date(),
        () => randomUUID().replaceAll("-", ""),
      );
    } catch (error: unknown) {
      return appleHttpsError(error);
    }
  },
);

// Apple posts this OAuth redirect from its server, so it cannot carry the app's App Check token.
// The callback is not callable: strict state lookup, TTL, one-time code attachment, and deny-all
// Firestore rules are its authentication boundary.
export const appleOAuthCallback = onRequest(async (request, response) => {
  try {
    const result = await executeAppleCallback(request.body, appleStore, new Date());
    response.redirect(302, result.redirectUri);
  } catch (error: unknown) {
    if (error instanceof AppleAuthError) {
      response.status(400).send("Apple sign-in could not be completed.");
      return;
    }
    throw error;
  }
});

export const completeAppleSignIn = onAppCheckedCall({ enforceAppCheck: true, secrets: [applePrivateKey] }, async (request) => {
  try {
    const exchange = new VerifiedAppleTokenExchange({
      ...appleConfig(),
      teamId: requiredEnvironment("APPLE_TEAM_ID"),
      keyId: requiredEnvironment("APPLE_KEY_ID"),
      privateKey: applePrivateKey.value(),
    });
    return await executeCompleteAppleSignIn(request.data, appleStore, exchange, new Date());
  } catch (error: unknown) {
    return appleHttpsError(error);
  }
});

export const cleanupExpiredAppleAuthSessions = onSchedule(
  { schedule: "every 15 minutes", timeZone: "UTC", timeoutSeconds: 120 },
  async () => {
    await appleStore.cleanupExpired(new Date());
  },
);

export const createIdentificationRequest = onMutationCall({ enforceAppCheck: true }, async (request) => {
  try {
    return await createAuthorizedIdentificationRequest(
      request.auth === undefined ? null : { uid: request.auth.uid },
      request.data,
      {
        admissions: new FirestoreIdentificationAuthorizationRepository(firestore),
        nowMillis: () => Date.now(),
      },
    );
  } catch (error: unknown) {
    if (error instanceof IdentificationAuthorizationError) {
      throw new HttpsError(error.code, error.message);
    }
    throw error;
  }
});

export const identifyPlant = onMutationCall({ enforceAppCheck: true, secrets: [plantIdApiKey] }, async (request) => {
  try {
    return await executePlantIdentification(
      request.auth === undefined ? null : { uid: request.auth.uid },
      request.data,
      new FirestoreIdentificationRequestStore(firestore),
      new PlantIdStorageProvider(productionPlantIdHttpClient(plantIdApiKey.value())),
    );
  } catch (error: unknown) {
    if (error instanceof PlantIdentificationError) {
      if (error.reason === "unauthenticated") throw new HttpsError("unauthenticated", "Sign-in is required");
      if (error.reason === "invalid_argument") throw new HttpsError("invalid-argument", "Identification request is invalid");
      if (error.reason === "permission_denied") throw new HttpsError("permission-denied", "Request is not owned by this user");
    }
    if (error instanceof IdentificationRuntimeError) {
      switch (error.reason) {
        case "not_found": throw new HttpsError("not-found", "Identification request was not found");
        case "permission_denied": throw new HttpsError("permission-denied", "Request is not owned by this user");
        case "malformed_state": throw new HttpsError("failed-precondition", "Identification request is unavailable");
        case "failed_precondition": throw new HttpsError("failed-precondition", "Identification request cannot be processed");
      }
    }
    throw error;
  }
});
