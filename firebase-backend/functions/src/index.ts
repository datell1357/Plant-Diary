import { randomUUID } from "node:crypto"
import { getApp, getApps, initializeApp } from "firebase-admin/app"
import { getAuth } from "firebase-admin/auth"
import { getFirestore } from "firebase-admin/firestore"
import { getStorage } from "firebase-admin/storage"
import { type CallableRequest, HttpsError, onCall } from "firebase-functions/v2/https"
import { onSchedule } from "firebase-functions/v2/scheduler"
import {
  type AuthContext,
  type Clock,
  DeletionError,
  type DeletionRequestIds,
  OwnerIdSchema,
} from "./deletion-contract.js"
import { runDueAccountDeletions } from "./deletion-execution.js"
import {
  cancelAccountDeletion as cancelDeletion,
  previewAccountDeletion as previewDeletion,
  recoverAccountDeletion as recoverDeletion,
  requestAccountDeletion as requestDeletion,
} from "./deletion-service.js"
import { FirebaseAccountCleaner } from "./firebase-account-cleaner.js"
import { FirestoreDeletionStore } from "./firestore-deletion-store.js"
import { FirestoreInventoryStore, InventoryStoreError } from "./firestore-inventory-store.js"
import { FirestoreMiniHomeStore, MiniHomeStoreError } from "./firestore-mini-home-store.js"
import { InventoryError } from "./inventory-contract.js"
import {
  acquireInventoryItem as acquireInventoryItemHandler,
  inventoryAuth,
  loadInventory as loadInventoryHandler,
} from "./inventory-service.js"
import { MiniHomeError } from "./mini-home-contract.js"
import {
  loadMiniHome as loadMiniHomeHandler,
  miniHomeAuth,
  saveMiniHome as saveMiniHomeHandler,
} from "./mini-home-service.js"

export {
  InventoryReceiptSchema,
  InventorySnapshotSchema,
  inventorySnapshotHash,
} from "./inventory-contract.js"
export {
  canonicalMiniHomeRequest,
  canonicalMiniHomeSnapshot,
  LoadMiniHomeResponseSchema,
  MiniHomeDocumentSchema,
  MiniHomeOperationSchema,
  MiniHomeSnapshotSchema,
  miniHomeRequestHash,
  miniHomeSnapshotHash,
  SaveMiniHomeResponseSchema,
} from "./mini-home-contract.js"

if (getApps().length === 0) initializeApp()
const app = getApp()
const firestore = getFirestore(app)
const store = new FirestoreDeletionStore(firestore)
const inventoryStore = new FirestoreInventoryStore(firestore)
const miniHomeStore = new FirestoreMiniHomeStore(firestore)
const cleaner = new FirebaseAccountCleaner(firestore, getAuth(app), getStorage(app))

class SystemClock implements Clock {
  nowSeconds(): number {
    return Math.floor(Date.now() / 1_000)
  }
}

class RandomRequestIds implements DeletionRequestIds {
  next(): string {
    return randomUUID()
  }
}

const clock = new SystemClock()
const requestIds = new RandomRequestIds()
const callableOptions = { enforceAppCheck: true, region: "us-central1" } as const

function authContext(auth: CallableRequest<unknown>["auth"]): AuthContext | null {
  if (auth === undefined) return null
  const authTime = auth.token.auth_time
  return {
    uid: OwnerIdSchema.parse(auth.uid),
    authTimeSeconds: typeof authTime === "number" ? authTime : null,
  }
}

export const loadInventory = onCall(callableOptions, async (request) => {
  try {
    return await loadInventoryHandler(inventoryAuth(request.auth), request.data, inventoryStore)
  } catch (error: unknown) {
    if (error instanceof InventoryError || error instanceof InventoryStoreError) {
      throw new HttpsError(error.code, error.message)
    }
    throw error
  }
})

export const acquireInventoryItem = onCall(callableOptions, async (request) => {
  try {
    return await acquireInventoryItemHandler(
      inventoryAuth(request.auth),
      request.data,
      inventoryStore,
    )
  } catch (error: unknown) {
    if (error instanceof InventoryError || error instanceof InventoryStoreError) {
      throw new HttpsError(error.code, error.message)
    }
    throw error
  }
})

export const loadMiniHome = onCall(callableOptions, async (request) => {
  try {
    return await loadMiniHomeHandler(miniHomeAuth(request.auth), request.data, miniHomeStore)
  } catch (error: unknown) {
    if (error instanceof MiniHomeError || error instanceof MiniHomeStoreError) {
      throw new HttpsError(error.code, error.message)
    }
    throw error
  }
})

export const saveMiniHome = onCall(callableOptions, async (request) => {
  try {
    return await saveMiniHomeHandler(miniHomeAuth(request.auth), request.data, miniHomeStore)
  } catch (error: unknown) {
    if (error instanceof MiniHomeError || error instanceof MiniHomeStoreError) {
      throw new HttpsError(error.code, error.message)
    }
    throw error
  }
})

export const previewAccountDeletion = onCall(callableOptions, async (request) => {
  try {
    return await previewDeletion(authContext(request.auth), request.data, store)
  } catch (error: unknown) {
    if (error instanceof DeletionError) throw new HttpsError(error.code, error.message)
    throw error
  }
})

export const recoverAccountDeletion = onCall(callableOptions, async (request) => {
  try {
    return await recoverDeletion(request.data, store)
  } catch (error: unknown) {
    if (error instanceof DeletionError) throw new HttpsError(error.code, error.message)
    throw error
  }
})

export const requestAccountDeletion = onCall(callableOptions, async (request) => {
  try {
    return await requestDeletion(authContext(request.auth), request.data, {
      store,
      clock,
      requestIds,
    })
  } catch (error: unknown) {
    if (error instanceof DeletionError) throw new HttpsError(error.code, error.message)
    throw error
  }
})

export const cancelAccountDeletion = onCall(callableOptions, async (request) => {
  try {
    return await cancelDeletion(authContext(request.auth), request.data, { store, clock })
  } catch (error: unknown) {
    if (error instanceof DeletionError) throw new HttpsError(error.code, error.message)
    throw error
  }
})

export const executeDueAccountDeletions = onSchedule(
  {
    schedule: "every 15 minutes",
    timeZone: "UTC",
    region: "us-central1",
    timeoutSeconds: 540,
    maxInstances: 1,
  },
  async () => {
    await runDueAccountDeletions({ store, cleaner, clock })
  },
)
