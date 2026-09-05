import { randomUUID } from "node:crypto"
import type { DocumentReference, Firestore } from "firebase-admin/firestore"
import { z } from "zod"
import {
  PLANT_IDENTIFICATION_CONTRACT_VERSION,
  type PlantIdentificationOperation,
  PlantIdentificationProxyError,
  type PlantIdentificationResponse,
  PlantIdentificationResponseSchema,
  type PlantIdentificationResultStore,
} from "./plant-identification-proxy.js"

export const PLANT_IDENTIFICATION_LEASE_MILLIS = 45_000 as const

const OpaqueIDSchema = z.string().regex(/^[A-Za-z0-9_-]{8,128}$/)
const HashSchema = z.string().regex(/^[a-f0-9]{64}$/)
const EpochMillisSchema = z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER)
const LeaseIDSchema = z.string().uuid()

const StoredOperationBaseSchema = z.object({
  contractVersion: z.literal(PLANT_IDENTIFICATION_CONTRACT_VERSION),
  ownerUid: z.string().min(1).max(128),
  requestID: OpaqueIDSchema,
  idempotencyKey: OpaqueIDSchema,
  requestHash: HashSchema,
  leaseID: LeaseIDSchema,
  leaseExpiresAtEpochMillis: EpochMillisSchema,
})

const StoredProcessingOperationSchema = StoredOperationBaseSchema.extend({
  status: z.literal("processing"),
})
  .strict()
  .readonly()
const StoredCompletedOperationSchema = StoredOperationBaseSchema.extend({
  status: z.literal("completed"),
  response: PlantIdentificationResponseSchema,
})
  .strict()
  .readonly()
const StoredOperationSchema = z.discriminatedUnion("status", [
  StoredProcessingOperationSchema,
  StoredCompletedOperationSchema,
])
type StoredOperation = z.infer<typeof StoredOperationSchema>
type StoredProcessingOperation = z.infer<typeof StoredProcessingOperationSchema>

type Claim =
  | Readonly<{ kind: "replay"; response: PlantIdentificationResponse }>
  | Readonly<{ kind: "claimed"; leaseID: string }>

function operationReference(
  firestore: Firestore,
  operation: PlantIdentificationOperation,
): DocumentReference {
  return firestore.doc(
    `users/${operation.ownerID}/plantIdentificationOperations/${operation.idempotencyKey}`,
  )
}

function storedOperation(value: unknown): StoredOperation {
  try {
    return StoredOperationSchema.parse(value)
  } catch (error: unknown) {
    if (error instanceof z.ZodError) {
      throw new PlantIdentificationProxyError(
        "provider-unavailable",
        "Stored plant identification operation is malformed",
      )
    }
    throw error
  }
}

function assertMatchingOperation(
  stored: StoredOperation,
  operation: PlantIdentificationOperation,
): void {
  if (
    stored.contractVersion !== PLANT_IDENTIFICATION_CONTRACT_VERSION ||
    stored.ownerUid !== operation.ownerID ||
    stored.requestID !== operation.requestID ||
    stored.idempotencyKey !== operation.idempotencyKey ||
    stored.requestHash !== operation.requestHash
  ) {
    throw new PlantIdentificationProxyError(
      "conflict",
      "Idempotency key was already used for another plant identification request",
    )
  }
}

function newClaim(
  operation: PlantIdentificationOperation,
  nowEpochMillis: number,
): StoredProcessingOperation {
  return {
    contractVersion: PLANT_IDENTIFICATION_CONTRACT_VERSION,
    ownerUid: operation.ownerID,
    requestID: operation.requestID,
    idempotencyKey: operation.idempotencyKey,
    requestHash: operation.requestHash,
    status: "processing",
    leaseID: randomUUID(),
    leaseExpiresAtEpochMillis: nowEpochMillis + PLANT_IDENTIFICATION_LEASE_MILLIS,
  }
}

export class FirestorePlantIdentificationStore implements PlantIdentificationResultStore {
  constructor(
    private readonly firestore: Firestore,
    private readonly nowEpochMillis: () => number = Date.now,
  ) {}

  async runOnce(
    operation: PlantIdentificationOperation,
    execute: () => Promise<PlantIdentificationResponse>,
  ): Promise<PlantIdentificationResponse> {
    const claim = await this.claim(operation)
    if (claim.kind === "replay") return claim.response
    const response = PlantIdentificationResponseSchema.parse(await execute())
    return this.finalize(operation, claim.leaseID, response)
  }

  private async claim(operation: PlantIdentificationOperation): Promise<Claim> {
    const nowEpochMillis = EpochMillisSchema.parse(this.nowEpochMillis())
    const reference = operationReference(this.firestore, operation)
    return this.firestore.runTransaction(
      async (transaction): Promise<Claim> => {
        const snapshot = await transaction.get(reference)
        if (!snapshot.exists) {
          const claim = newClaim(operation, nowEpochMillis)
          transaction.create(reference, claim)
          return { kind: "claimed", leaseID: claim.leaseID }
        }

        const stored = storedOperation(snapshot.data())
        assertMatchingOperation(stored, operation)
        if (stored.status === "completed") return { kind: "replay", response: stored.response }
        if (stored.leaseExpiresAtEpochMillis > nowEpochMillis) {
          throw new PlantIdentificationProxyError(
            "conflict",
            "Plant identification request is already in progress",
          )
        }

        const takeover = newClaim(operation, nowEpochMillis)
        transaction.set(reference, takeover, { merge: false })
        return { kind: "claimed", leaseID: takeover.leaseID }
      },
      { maxAttempts: 5 },
    )
  }

  private async finalize(
    operation: PlantIdentificationOperation,
    leaseID: string,
    response: PlantIdentificationResponse,
  ): Promise<PlantIdentificationResponse> {
    const reference = operationReference(this.firestore, operation)
    return this.firestore.runTransaction(
      async (transaction): Promise<PlantIdentificationResponse> => {
        const snapshot = await transaction.get(reference)
        if (!snapshot.exists) {
          throw new PlantIdentificationProxyError(
            "conflict",
            "Plant identification operation disappeared before finalization",
          )
        }
        const stored = storedOperation(snapshot.data())
        assertMatchingOperation(stored, operation)
        if (stored.status === "completed") return stored.response
        if (stored.leaseID !== leaseID) {
          throw new PlantIdentificationProxyError(
            "conflict",
            "Plant identification lease no longer matches",
          )
        }
        const completed = StoredCompletedOperationSchema.parse({
          ...stored,
          status: "completed",
          response,
        })
        transaction.set(reference, completed, { merge: false })
        return completed.response
      },
      { maxAttempts: 5 },
    )
  }
}
