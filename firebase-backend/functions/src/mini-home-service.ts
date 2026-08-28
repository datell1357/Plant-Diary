import { z } from "zod"
import {
  LoadMiniHomeInputSchema,
  type LoadMiniHomeResponse,
  MINI_HOME_CONTRACT_VERSION,
  MiniHomeError,
  type MiniHomeOwnerUid,
  type MiniHomePlacement,
  type MiniHomeSnapshot,
  miniHomeRequestHash,
  OwnerUidSchema,
  SaveMiniHomeInputSchema,
  type SaveMiniHomeResponse,
  sortedMiniHomePlacements,
} from "./mini-home-contract.js"

export type MiniHomeAuth = Readonly<{ uid: MiniHomeOwnerUid }>

export type MiniHomeSaveCommand = Readonly<{
  ownerUid: MiniHomeOwnerUid
  expectedRevision: number
  operationId: string
  roomId: string
  name: string
  placements: readonly MiniHomePlacement[]
  requestHash: string
}>

export interface MiniHomeStore {
  load(ownerUid: MiniHomeOwnerUid): Promise<MiniHomeSnapshot | null>
  save(command: MiniHomeSaveCommand): Promise<SaveMiniHomeResponse>
}

function parseBoundary<T>(schema: z.ZodType<T>, input: unknown): T {
  try {
    return schema.parse(input)
  } catch (error: unknown) {
    if (error instanceof z.ZodError) {
      throw new MiniHomeError("invalid-argument", "Payload does not match MiniHome v1")
    }
    throw error
  }
}

function requireOwner(
  auth: MiniHomeAuth | null,
  expectedOwnerUid: MiniHomeOwnerUid,
): MiniHomeOwnerUid {
  if (auth === null) throw new MiniHomeError("unauthenticated", "Sign-in is required")
  if (auth.uid !== expectedOwnerUid) {
    throw new MiniHomeError("permission-denied", "Owner does not match authentication")
  }
  return auth.uid
}

export function miniHomeAuth(auth: Readonly<{ uid: string }> | undefined): MiniHomeAuth | null {
  if (auth === undefined) return null
  try {
    return { uid: OwnerUidSchema.parse(auth.uid) }
  } catch (error: unknown) {
    if (error instanceof z.ZodError) return null
    throw error
  }
}

export async function loadMiniHome(
  auth: MiniHomeAuth | null,
  input: unknown,
  store: MiniHomeStore,
): Promise<LoadMiniHomeResponse> {
  if (auth === null) throw new MiniHomeError("unauthenticated", "Sign-in is required")
  const request = parseBoundary(LoadMiniHomeInputSchema, input)
  const ownerUid = requireOwner(auth, request.expectedOwnerUid)
  const snapshot = await store.load(ownerUid)
  if (snapshot === null) {
    return { kind: "empty", contractVersion: MINI_HOME_CONTRACT_VERSION, ownerUid }
  }
  return { kind: "snapshot", ...snapshot }
}

export async function saveMiniHome(
  auth: MiniHomeAuth | null,
  input: unknown,
  store: MiniHomeStore,
): Promise<SaveMiniHomeResponse> {
  if (auth === null) throw new MiniHomeError("unauthenticated", "Sign-in is required")
  const request = parseBoundary(SaveMiniHomeInputSchema, input)
  const ownerUid = requireOwner(auth, request.expectedOwnerUid)
  const placements = sortedMiniHomePlacements(request.placements)
  return store.save({
    ownerUid,
    expectedRevision: request.expectedRevision,
    operationId: request.operationId,
    roomId: request.roomId,
    name: request.name,
    placements,
    requestHash: miniHomeRequestHash({
      ownerUid,
      expectedRevision: request.expectedRevision,
      roomId: request.roomId,
      name: request.name,
      placements,
    }),
  })
}
