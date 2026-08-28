import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import { fileURLToPath } from "node:url"
import { z } from "zod"
import {
  canonicalMiniHomeRequest,
  canonicalMiniHomeSnapshot,
  LoadMiniHomeInputSchema,
  LoadMiniHomeResponseSchema,
  MiniHomeDocumentSchema,
  MiniHomeOperationSchema,
  MiniHomeSnapshotSchema,
  miniHomeRequestHash,
  miniHomeSnapshotHash,
  SaveMiniHomeInputSchema,
  SaveMiniHomeResponseSchema,
} from "./mini-home-contract.js"

const fixturePath = fileURLToPath(
  new URL("../../../docs/ios/minihome-contract-v1.fixture.json", import.meta.url),
)

const plantPlacement = {
  placementId: "placement-plant",
  plantId: "plant-monstera",
  normalizedX: 0.25,
  normalizedY: 0.75,
  zIndex: 1,
} as const
const itemPlacement = {
  placementId: "placement-lamp",
  itemId: "item-lamp",
  normalizedX: 0,
  normalizedY: 1,
  zIndex: 0,
} as const
const snapshotSource = {
  contractVersion: 1 as const,
  ownerUid: "owner-fixture",
  roomId: "room-main",
  name: "나의 미니홈",
  placements: [plantPlacement, itemPlacement],
  revision: 7,
  updatedAtEpochMillis: 1_787_616_123_456,
}

function snapshot() {
  return MiniHomeSnapshotSchema.parse({
    ...snapshotSource,
    snapshotHash: miniHomeSnapshotHash(snapshotSource),
  })
}

test("Given MiniHome boundaries, when unknown or malformed values arrive, then every schema fails closed", () => {
  // Given
  const validSave = {
    expectedOwnerUid: "owner-fixture",
    expectedRevision: 7,
    operationId: "operation-fixture-0001",
    roomId: "room-main",
    name: "나의 미니홈",
    placements: [plantPlacement, itemPlacement],
  }
  const invalid: readonly unknown[] = [
    { ...validSave, extra: true },
    { ...validSave, expectedOwnerUid: "bad/path" },
    { ...validSave, expectedRevision: -1 },
    { ...validSave, operationId: "short" },
    { ...validSave, name: " surrounded " },
    { ...validSave, name: "x".repeat(101) },
    { ...validSave, placements: [{ ...plantPlacement, extra: true }] },
    { ...validSave, placements: [{ ...plantPlacement, itemId: "item-lamp" }] },
    { ...validSave, placements: [{ ...plantPlacement, plantId: undefined }] },
    { ...validSave, placements: [{ ...plantPlacement, normalizedX: Number.NaN }] },
    { ...validSave, placements: [{ ...plantPlacement, normalizedY: 1.01 }] },
    { ...validSave, placements: [{ ...plantPlacement, zIndex: -1 }] },
    { ...validSave, placements: [plantPlacement, plantPlacement] },
    {
      ...validSave,
      placements: Array.from({ length: 21 }, (_, index) => ({
        ...plantPlacement,
        placementId: `placement-${index}`,
      })),
    },
  ]

  // When / Then
  for (const input of invalid) assert.equal(SaveMiniHomeInputSchema.safeParse(input).success, false)
  assert.equal(
    LoadMiniHomeInputSchema.safeParse({ expectedOwnerUid: "owner-fixture", extra: true }).success,
    false,
  )
  assert.equal(MiniHomeSnapshotSchema.safeParse({ ...snapshot(), extra: true }).success, false)
  assert.equal(
    SaveMiniHomeResponseSchema.safeParse({ kind: "committed", snapshot: snapshot(), extra: true })
      .success,
    false,
  )
  assert.equal(
    MiniHomeOperationSchema.safeParse({
      schemaVersion: 1,
      ownerUid: "owner-fixture",
      expectedRevision: 0,
      requestHash: "a".repeat(64),
      snapshot: snapshot(),
      extra: true,
    }).success,
    false,
  )
  assert.equal(
    MiniHomeDocumentSchema.safeParse({
      ...snapshot(),
      schemaVersion: 1,
      contractVersion: undefined,
      snapshotHash: "A".repeat(64),
    }).success,
    false,
  )
})

test("Given canonical MiniHome state, when hashing every field and order, then bytes are stable and field-complete", () => {
  // Given
  const baseline = snapshot()
  const changes = [
    { ...snapshotSource, contractVersion: 2 },
    { ...snapshotSource, ownerUid: "owner-other" },
    { ...snapshotSource, roomId: "room-other" },
    { ...snapshotSource, name: "다른 이름" },
    { ...snapshotSource, revision: 8 },
    { ...snapshotSource, updatedAtEpochMillis: 1_787_616_123_457 },
    { ...snapshotSource, placements: [{ ...plantPlacement, placementId: "placement-other" }] },
    { ...snapshotSource, placements: [{ ...plantPlacement, plantId: "plant-other" }] },
    { ...snapshotSource, placements: [{ ...itemPlacement, itemId: "item-other" }] },
    { ...snapshotSource, placements: [{ ...plantPlacement, normalizedX: 0.5 }] },
    { ...snapshotSource, placements: [{ ...plantPlacement, normalizedY: 0.5 }] },
    { ...snapshotSource, placements: [{ ...plantPlacement, zIndex: 2 }] },
  ]

  // When
  const reordered = { ...snapshotSource, placements: [...snapshotSource.placements].reverse() }

  // Then
  assert.equal(miniHomeSnapshotHash(reordered), baseline.snapshotHash)
  assert.equal(canonicalMiniHomeSnapshot(reordered), canonicalMiniHomeSnapshot(snapshotSource))
  for (const changed of changes)
    assert.notEqual(miniHomeSnapshotHash(changed), baseline.snapshotHash)
})

test("Given a semantically equal save, when placement input order differs, then request bytes and hash match", () => {
  // Given
  const request = {
    ownerUid: "owner-fixture",
    expectedRevision: 7,
    roomId: "room-main",
    name: "나의 미니홈",
    placements: [plantPlacement, itemPlacement],
  }
  const reordered = { ...request, placements: [...request.placements].reverse() }

  // When / Then
  assert.equal(canonicalMiniHomeRequest(request), canonicalMiniHomeRequest(reordered))
  assert.equal(miniHomeRequestHash(request), miniHomeRequestHash(reordered))
})

test("Given the shared v1 fixture, when parsed, then all wire and stored keys and hashes are exact", async () => {
  // Given
  const FixtureSchema = z
    .object({
      contractVersion: z.literal(1),
      callables: z.tuple([z.literal("loadMiniHome"), z.literal("saveMiniHome")]),
      canonicalSnapshotEncoding: z.string().min(1),
      canonicalRequestEncoding: z.string().min(1),
      requestHash: z.string().regex(/^[a-f0-9]{64}$/),
      snapshot: MiniHomeSnapshotSchema,
      loadResponse: LoadMiniHomeResponseSchema,
      saveResponse: SaveMiniHomeResponseSchema,
      operation: MiniHomeOperationSchema,
      document: MiniHomeDocumentSchema,
    })
    .strict()

  // When
  const fixture: unknown = JSON.parse(await readFile(fixturePath, "utf8"))
  const parsed = FixtureSchema.parse(fixture)

  // Then
  assert.equal(canonicalMiniHomeSnapshot(parsed.snapshot), parsed.canonicalSnapshotEncoding)
  assert.equal(miniHomeSnapshotHash(parsed.snapshot), parsed.snapshot.snapshotHash)
  assert.equal(
    parsed.snapshot.snapshotHash,
    "faaab600e855c12fbb97933232ddba4674069a8fe03874d860d728c49de17bb1",
  )
  const fixtureRequest = {
    ownerUid: parsed.snapshot.ownerUid,
    expectedRevision: parsed.operation.expectedRevision,
    roomId: parsed.snapshot.roomId,
    name: parsed.snapshot.name,
    placements: parsed.snapshot.placements,
  }
  assert.equal(canonicalMiniHomeRequest(fixtureRequest), parsed.canonicalRequestEncoding)
  assert.equal(miniHomeRequestHash(fixtureRequest), parsed.requestHash)
  assert.equal(
    parsed.requestHash,
    "fe1d175e863aa02e5d9b741da24cb563899db935b10de85fa47ecf1687689a66",
  )
  assert.equal(parsed.operation.requestHash, parsed.requestHash)
  assert.equal(parsed.operation.snapshot.snapshotHash, parsed.snapshot.snapshotHash)
  assert.deepEqual(parsed.saveResponse, { kind: "committed", snapshot: parsed.snapshot })
})
