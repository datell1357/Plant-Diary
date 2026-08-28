import type { DocumentSnapshot, Firestore } from "firebase-admin/firestore"
import { z } from "zod"
import { InventoryOwnedItemObjectSchema } from "./inventory-contract.js"
import {
  MINI_HOME_CONTRACT_VERSION,
  MiniHomeDocumentSchema,
  MiniHomeOperationSchema,
  type MiniHomeOwnerUid,
  type MiniHomeSnapshot,
  MiniHomeSnapshotSchema,
  miniHomeRequestHash,
  miniHomeSnapshotHash,
  type SaveMiniHomeResponse,
} from "./mini-home-contract.js"
import type { MiniHomeSaveCommand, MiniHomeStore } from "./mini-home-service.js"

const OwnedTargetSchema = InventoryOwnedItemObjectSchema.omit({ availability: true })
  .extend({ ownerUid: z.string() })
  .strict()
const PlantTargetSchema = z.object({ ownerUid: z.string() }).passthrough()
const ServerEpochMillisSchema = z.number().int().min(0).max(Number.MAX_SAFE_INTEGER)

export class MiniHomeStoreError extends Error {
  override readonly name = "MiniHomeStoreError"

  constructor(
    readonly code: "failed-precondition" | "data-loss",
    message: string,
  ) {
    super(message)
  }
}

function stored<T>(schema: z.ZodType<T>, value: unknown, label: string): T {
  try {
    return schema.parse(value)
  } catch (error: unknown) {
    if (error instanceof z.ZodError) {
      throw new MiniHomeStoreError("data-loss", `Stored ${label} is malformed`)
    }
    throw error
  }
}

function verifiedSnapshot(snapshot: MiniHomeSnapshot, label: string): MiniHomeSnapshot {
  if (snapshot.snapshotHash !== miniHomeSnapshotHash(snapshot)) {
    throw new MiniHomeStoreError("data-loss", `Stored ${label} hash is malformed`)
  }
  return snapshot
}

function snapshotFromDocument(
  document: DocumentSnapshot,
  ownerUid: MiniHomeOwnerUid,
): MiniHomeSnapshot | null {
  if (!document.exists) return null
  const { schemaVersion, ...fields } = stored(
    MiniHomeDocumentSchema,
    document.data(),
    "MiniHome document",
  )
  if (fields.ownerUid !== ownerUid) {
    throw new MiniHomeStoreError("data-loss", "Stored MiniHome owner is malformed")
  }
  return verifiedSnapshot(
    stored(
      MiniHomeSnapshotSchema,
      { contractVersion: schemaVersion, ...fields },
      "MiniHome snapshot",
    ),
    "MiniHome snapshot",
  )
}

function documentFromSnapshot(snapshot: MiniHomeSnapshot): Readonly<Record<string, unknown>> {
  const { contractVersion, ...fields } = snapshot
  return { schemaVersion: contractVersion, ...fields }
}

function replayOperation(
  document: DocumentSnapshot,
  command: MiniHomeSaveCommand,
): SaveMiniHomeResponse {
  const operation = stored(MiniHomeOperationSchema, document.data(), "MiniHome operation")
  if (
    operation.ownerUid !== command.ownerUid ||
    operation.expectedRevision !== command.expectedRevision ||
    operation.requestHash !== command.requestHash
  ) {
    throw new MiniHomeStoreError(
      "failed-precondition",
      "Operation ID was already used for another MiniHome save",
    )
  }
  const snapshot = verifiedSnapshot(operation.snapshot, "MiniHome operation snapshot")
  if (
    snapshot.ownerUid !== command.ownerUid ||
    snapshot.revision !== command.expectedRevision + 1 ||
    miniHomeRequestHash({
      ownerUid: snapshot.ownerUid,
      expectedRevision: operation.expectedRevision,
      roomId: snapshot.roomId,
      name: snapshot.name,
      placements: snapshot.placements,
    }) !== operation.requestHash
  ) {
    throw new MiniHomeStoreError("data-loss", "Stored MiniHome operation result is malformed")
  }
  return { kind: "duplicate", snapshot }
}

export class FirestoreMiniHomeStore implements MiniHomeStore {
  constructor(
    private readonly firestore: Firestore,
    private readonly nowEpochMillis: () => number = Date.now,
  ) {}

  async load(ownerUid: MiniHomeOwnerUid): Promise<MiniHomeSnapshot | null> {
    const document = await this.firestore.doc(`users/${ownerUid}/miniHomes/current`).get()
    return snapshotFromDocument(document, ownerUid)
  }

  async save(command: MiniHomeSaveCommand): Promise<SaveMiniHomeResponse> {
    return this.firestore.runTransaction(
      async (transaction): Promise<SaveMiniHomeResponse> => {
        const root = `users/${command.ownerUid}`
        const operationReference = this.firestore.doc(
          `${root}/miniHomeOperations/${command.operationId}`,
        )
        const operation = await transaction.get(operationReference)
        if (operation.exists) return replayOperation(operation, command)

        const homeReference = this.firestore.doc(`${root}/miniHomes/current`)
        const current = snapshotFromDocument(await transaction.get(homeReference), command.ownerUid)
        if ((current?.revision ?? 0) !== command.expectedRevision) {
          return { kind: "conflict", snapshot: current }
        }
        if (current !== null && current.roomId !== command.roomId) {
          throw new MiniHomeStoreError("failed-precondition", "MiniHome room ID cannot change")
        }

        const targets = await Promise.all(
          command.placements.map(async (placement) => {
            if ("plantId" in placement) {
              return {
                kind: "plant" as const,
                id: placement.plantId,
                document: await transaction.get(
                  this.firestore.doc(`${root}/personalPlants/${placement.plantId}`),
                ),
              }
            }
            return {
              kind: "item" as const,
              id: placement.itemId,
              document: await transaction.get(
                this.firestore.doc(`${root}/ownedItems/${placement.itemId}`),
              ),
            }
          }),
        )
        for (const target of targets) {
          if (!target.document.exists) {
            throw new MiniHomeStoreError("failed-precondition", "Placement target is not owned")
          }
          if (target.kind === "plant") {
            const parsed = PlantTargetSchema.safeParse(target.document.data())
            if (!parsed.success || parsed.data.ownerUid !== command.ownerUid) {
              throw new MiniHomeStoreError("failed-precondition", "Placed plant is not owned")
            }
          } else {
            const parsed = OwnedTargetSchema.safeParse(target.document.data())
            if (
              !parsed.success ||
              parsed.data.ownerUid !== command.ownerUid ||
              String(parsed.data.itemId) !== String(target.id)
            ) {
              throw new MiniHomeStoreError("failed-precondition", "Placed item is not owned")
            }
          }
        }

        const updatedAtEpochMillis = ServerEpochMillisSchema.parse(this.nowEpochMillis())
        const source = {
          contractVersion: MINI_HOME_CONTRACT_VERSION,
          ownerUid: command.ownerUid,
          roomId: command.roomId,
          name: command.name,
          placements: command.placements,
          revision: command.expectedRevision + 1,
          updatedAtEpochMillis,
        }
        const snapshot = MiniHomeSnapshotSchema.parse({
          ...source,
          snapshotHash: miniHomeSnapshotHash(source),
        })
        transaction.set(homeReference, documentFromSnapshot(snapshot), { merge: false })
        transaction.create(operationReference, {
          schemaVersion: MINI_HOME_CONTRACT_VERSION,
          ownerUid: command.ownerUid,
          expectedRevision: command.expectedRevision,
          requestHash: command.requestHash,
          snapshot,
        })
        return { kind: "committed", snapshot }
      },
      { maxAttempts: 5 },
    )
  }
}
