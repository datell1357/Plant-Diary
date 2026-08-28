import { createHash } from "node:crypto"
import { z } from "zod"

export const MINI_HOME_CONTRACT_VERSION = 1 as const
export const MINI_HOME_MAX_PLACEMENTS = 20 as const

const OpaqueIdSchema = z.string().regex(/^[A-Za-z0-9_-]{1,128}$/)
export const OwnerUidSchema = OpaqueIdSchema.brand("MiniHomeOwnerUid")
export type MiniHomeOwnerUid = z.infer<typeof OwnerUidSchema>
const RoomIdSchema = OpaqueIdSchema.brand("MiniHomeRoomId")
const PlacementIdSchema = OpaqueIdSchema.brand("MiniHomePlacementId")
const PlantIdSchema = OpaqueIdSchema.brand("MiniHomePlantId")
const ItemIdSchema = OpaqueIdSchema.brand("MiniHomeItemId")
const OperationIdSchema = z
  .string()
  .regex(/^[A-Za-z0-9_-]{8,128}$/)
  .brand("MiniHomeOperationId")
const RevisionSchema = z.number().int().min(1).max(Number.MAX_SAFE_INTEGER)
const ExpectedRevisionSchema = z
  .number()
  .int()
  .min(0)
  .max(Number.MAX_SAFE_INTEGER - 1)
const EpochMillisSchema = z.number().int().min(0).max(Number.MAX_SAFE_INTEGER)
const HashSchema = z.string().regex(/^[a-f0-9]{64}$/)
const CoordinateSchema = z.number().finite().min(0).max(1)
const ZIndexSchema = z
  .number()
  .int()
  .min(0)
  .max(MINI_HOME_MAX_PLACEMENTS - 1)
const NameSchema = z
  .string()
  .min(1)
  .max(100)
  .refine((value) => value === value.trim(), "Name must already be trimmed")

const PlacementFields = {
  placementId: PlacementIdSchema,
  normalizedX: CoordinateSchema,
  normalizedY: CoordinateSchema,
  zIndex: ZIndexSchema,
} as const
const PlantPlacementSchema = z
  .object({ ...PlacementFields, plantId: PlantIdSchema })
  .strict()
  .readonly()
const ItemPlacementSchema = z
  .object({ ...PlacementFields, itemId: ItemIdSchema })
  .strict()
  .readonly()
export const MiniHomePlacementSchema = z.union([PlantPlacementSchema, ItemPlacementSchema])
export type MiniHomePlacement = z.infer<typeof MiniHomePlacementSchema>

const PlacementsSchema = z
  .array(MiniHomePlacementSchema)
  .max(MINI_HOME_MAX_PLACEMENTS)
  .superRefine((placements, context) => {
    const ids = new Set(placements.map((placement) => placement.placementId))
    if (ids.size !== placements.length) {
      context.addIssue({ code: "custom", message: "Placement IDs must be unique" })
    }
  })
  .readonly()

const SnapshotFields = {
  ownerUid: OwnerUidSchema,
  roomId: RoomIdSchema,
  name: NameSchema,
  placements: PlacementsSchema,
  revision: RevisionSchema,
  updatedAtEpochMillis: EpochMillisSchema,
  snapshotHash: HashSchema,
} as const

const MiniHomeSnapshotObjectSchema = z
  .object({ contractVersion: z.literal(MINI_HOME_CONTRACT_VERSION), ...SnapshotFields })
  .strict()
export const MiniHomeSnapshotSchema = MiniHomeSnapshotObjectSchema.readonly()
export type MiniHomeSnapshot = z.infer<typeof MiniHomeSnapshotSchema>

export const MiniHomeDocumentSchema = z
  .object({ schemaVersion: z.literal(MINI_HOME_CONTRACT_VERSION), ...SnapshotFields })
  .strict()
  .readonly()
export type MiniHomeDocument = z.infer<typeof MiniHomeDocumentSchema>

export const LoadMiniHomeInputSchema = z
  .object({ expectedOwnerUid: OwnerUidSchema })
  .strict()
  .readonly()
export const SaveMiniHomeInputSchema = z
  .object({
    expectedOwnerUid: OwnerUidSchema,
    expectedRevision: ExpectedRevisionSchema,
    operationId: OperationIdSchema,
    roomId: RoomIdSchema,
    name: NameSchema,
    placements: PlacementsSchema,
  })
  .strict()
  .readonly()
export type SaveMiniHomeInput = z.infer<typeof SaveMiniHomeInputSchema>

export const LoadMiniHomeResponseSchema = z.discriminatedUnion("kind", [
  z
    .object({
      kind: z.literal("empty"),
      contractVersion: z.literal(MINI_HOME_CONTRACT_VERSION),
      ownerUid: OwnerUidSchema,
    })
    .strict()
    .readonly(),
  MiniHomeSnapshotObjectSchema.extend({ kind: z.literal("snapshot") })
    .strict()
    .readonly(),
])
export type LoadMiniHomeResponse = z.infer<typeof LoadMiniHomeResponseSchema>

export const SaveMiniHomeResponseSchema = z.discriminatedUnion("kind", [
  z
    .object({ kind: z.literal("committed"), snapshot: MiniHomeSnapshotSchema })
    .strict()
    .readonly(),
  z
    .object({ kind: z.literal("duplicate"), snapshot: MiniHomeSnapshotSchema })
    .strict()
    .readonly(),
  z
    .object({ kind: z.literal("conflict"), snapshot: MiniHomeSnapshotSchema.nullable() })
    .strict()
    .readonly(),
])
export type SaveMiniHomeResponse = z.infer<typeof SaveMiniHomeResponseSchema>

export const MiniHomeOperationSchema = z
  .object({
    schemaVersion: z.literal(MINI_HOME_CONTRACT_VERSION),
    ownerUid: OwnerUidSchema,
    expectedRevision: ExpectedRevisionSchema,
    requestHash: HashSchema,
    snapshot: MiniHomeSnapshotSchema,
  })
  .strict()
  .readonly()
export type MiniHomeOperation = z.infer<typeof MiniHomeOperationSchema>

type CanonicalPlacement = Readonly<{
  placementId: string
  normalizedX: number
  normalizedY: number
  zIndex: number
}> &
  (Readonly<{ plantId: string }> | Readonly<{ itemId: string }>)

type CanonicalSnapshot = Readonly<{
  contractVersion: number
  ownerUid: string
  roomId: string
  name: string
  placements: readonly CanonicalPlacement[]
  revision: number
  updatedAtEpochMillis: number
}>
export type CanonicalRequest = Readonly<{
  ownerUid: string
  expectedRevision: number
  roomId: string
  name: string
  placements: readonly CanonicalPlacement[]
}>

const encoded = (value: string): string => Buffer.from(value, "utf8").toString("base64url")

function coordinateHex(value: number): string {
  const bytes = Buffer.allocUnsafe(8)
  bytes.writeDoubleBE(value === 0 ? 0 : value)
  return bytes.toString("hex")
}

export function sortedMiniHomePlacements<T extends CanonicalPlacement>(
  placements: readonly T[],
): readonly T[] {
  return [...placements].sort((left, right) =>
    left.placementId < right.placementId ? -1 : left.placementId > right.placementId ? 1 : 0,
  )
}

function placementLines(placements: readonly CanonicalPlacement[]): readonly string[] {
  return sortedMiniHomePlacements(placements).map((placement) => {
    const target =
      "plantId" in placement ? ["P", encoded(placement.plantId)] : ["I", encoded(placement.itemId)]
    return [
      "P",
      encoded(placement.placementId),
      ...target,
      coordinateHex(placement.normalizedX),
      coordinateHex(placement.normalizedY),
      String(placement.zIndex),
    ].join("\t")
  })
}

export function canonicalMiniHomeSnapshot(snapshot: CanonicalSnapshot): string {
  return [
    "MINIHOME-SNAPSHOT-V1",
    String(snapshot.contractVersion),
    encoded(snapshot.ownerUid),
    encoded(snapshot.roomId),
    encoded(snapshot.name),
    String(snapshot.revision),
    String(snapshot.updatedAtEpochMillis),
    ...placementLines(snapshot.placements),
  ].join("\n")
}

export function miniHomeSnapshotHash(snapshot: CanonicalSnapshot): string {
  return createHash("sha256").update(canonicalMiniHomeSnapshot(snapshot), "utf8").digest("hex")
}

export function canonicalMiniHomeRequest(request: CanonicalRequest): string {
  return [
    "MINIHOME-REQUEST-V1",
    String(MINI_HOME_CONTRACT_VERSION),
    encoded(request.ownerUid),
    String(request.expectedRevision),
    encoded(request.roomId),
    encoded(request.name),
    ...placementLines(request.placements),
  ].join("\n")
}

export function miniHomeRequestHash(request: CanonicalRequest): string {
  return createHash("sha256").update(canonicalMiniHomeRequest(request), "utf8").digest("hex")
}

export type MiniHomeErrorCode =
  | "unauthenticated"
  | "permission-denied"
  | "invalid-argument"
  | "failed-precondition"
  | "data-loss"

export class MiniHomeError extends Error {
  override readonly name = "MiniHomeError"

  constructor(
    readonly code: MiniHomeErrorCode,
    message: string,
  ) {
    super(message)
  }
}
