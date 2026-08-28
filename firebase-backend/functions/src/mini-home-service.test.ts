import assert from "node:assert/strict"
import test from "node:test"
import {
  MiniHomeError,
  type MiniHomeSnapshot,
  MiniHomeSnapshotSchema,
  miniHomeRequestHash,
  miniHomeSnapshotHash,
  OwnerUidSchema,
} from "./mini-home-contract.js"
import {
  loadMiniHome,
  type MiniHomeSaveCommand,
  type MiniHomeStore,
  miniHomeAuth,
  saveMiniHome,
} from "./mini-home-service.js"

const owner = OwnerUidSchema.parse("mini-home-owner")
const placement = {
  placementId: "placement-plant",
  plantId: "plant-a",
  normalizedX: 0.25,
  normalizedY: 0.75,
  zIndex: 0,
} as const

function fixtureSnapshot(revision = 1) {
  const source = {
    contractVersion: 1 as const,
    ownerUid: owner,
    roomId: "room-main",
    name: "Mini Home",
    placements: [placement],
    revision,
    updatedAtEpochMillis: 1_787_616_000_000 + revision,
  }
  return MiniHomeSnapshotSchema.parse({ ...source, snapshotHash: miniHomeSnapshotHash(source) })
}

class MiniHomeStoreFake implements MiniHomeStore {
  readonly saves: MiniHomeSaveCommand[] = []
  current: MiniHomeSnapshot | null = fixtureSnapshot()

  async load() {
    return this.current
  }

  async save(command: MiniHomeSaveCommand) {
    this.saves.push(command)
    const current = this.current
    if (current === null) throw new RangeError("Fixture snapshot is missing")
    return { kind: "committed" as const, snapshot: current }
  }
}

const validSave = {
  expectedOwnerUid: owner,
  expectedRevision: 1,
  operationId: "operation-service-0001",
  roomId: "room-main",
  name: "Mini Home",
  placements: [placement],
}

test("Given missing or foreign authentication, when loading or saving, then storage is never reached", async () => {
  // Given
  const store = new MiniHomeStoreFake()

  // When / Then
  await assert.rejects(
    () => loadMiniHome(null, { expectedOwnerUid: owner }, store),
    (error: unknown) => error instanceof MiniHomeError && error.code === "unauthenticated",
  )
  await assert.rejects(
    () => loadMiniHome(miniHomeAuth({ uid: "owner-other" }), { expectedOwnerUid: owner }, store),
    (error: unknown) => error instanceof MiniHomeError && error.code === "permission-denied",
  )
  await assert.rejects(
    () => saveMiniHome(miniHomeAuth({ uid: "owner-other" }), validSave, store),
    (error: unknown) => error instanceof MiniHomeError && error.code === "permission-denied",
  )
  assert.equal(store.saves.length, 0)
})

test("Given malformed save fields, when saving, then exact parsing rejects before persistence", async () => {
  // Given
  const invalid: readonly unknown[] = [
    { ...validSave, extra: true },
    { ...validSave, expectedRevision: -1 },
    { ...validSave, placements: [{ ...placement, itemId: "item-a" }] },
  ]

  // When
  const store = new MiniHomeStoreFake()
  for (const input of invalid)
    await assert.rejects(() => saveMiniHome(miniHomeAuth({ uid: owner }), input, store))

  // Then
  assert.equal(store.saves.length, 0)
})

test("Given an authorized save, when dispatched, then the store receives normalized order and canonical request hash", async () => {
  // Given
  const item = {
    placementId: "placement-item",
    itemId: "item-lamp",
    normalizedX: 0.75,
    normalizedY: 0.25,
    zIndex: 1,
  } as const
  const store = new MiniHomeStoreFake()
  const request = { ...validSave, placements: [item, placement] }

  // When
  await saveMiniHome(miniHomeAuth({ uid: owner }), request, store)

  // Then
  assert.equal(store.saves.length, 1)
  const command = store.saves.at(0)
  assert.ok(command)
  assert.deepEqual(
    command.placements.map((value) => value.placementId),
    ["placement-item", "placement-plant"],
  )
  assert.equal(
    command.requestHash,
    miniHomeRequestHash({
      ownerUid: owner,
      expectedRevision: 1,
      roomId: "room-main",
      name: "Mini Home",
      placements: [placement, item],
    }),
  )
})

test("Given empty and existing authoritative state, when loaded, then exact response variants are returned", async () => {
  // Given
  const store = new MiniHomeStoreFake()

  // When
  const present = await loadMiniHome(
    miniHomeAuth({ uid: owner }),
    { expectedOwnerUid: owner },
    store,
  )
  store.current = null
  const empty = await loadMiniHome(miniHomeAuth({ uid: owner }), { expectedOwnerUid: owner }, store)

  // Then
  assert.deepEqual(present, { kind: "snapshot", ...fixtureSnapshot() })
  assert.deepEqual(empty, { kind: "empty", contractVersion: 1, ownerUid: owner })
})
