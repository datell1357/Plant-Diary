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

if (getApps().length === 0) initializeApp()
const app = getApp()
const firestore = getFirestore(app)
const store = new FirestoreDeletionStore(firestore)
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
