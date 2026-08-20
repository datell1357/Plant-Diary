import assert from "node:assert/strict";
import test from "node:test";
import {
  MiniHomeError,
  executeLoadMiniHomeLayout,
  executeSaveMiniHomeLayout,
  miniHomeBidiControlCodePoints,
  miniHomeUnicodeWhiteSpaceCodePoints,
  recoverLegacyMiniHomeName,
  type MiniHomeAuthoritativeRead,
  type MiniHomeLayoutCommand,
  type MiniHomeLayoutReader,
  type MiniHomeLayoutStore,
  type MiniHomeSaveResult,
} from "./mini-home.js";

class FakeReader implements MiniHomeLayoutReader {
  result: MiniHomeAuthoritativeRead = {
    kind: "missing",
    ownerUid: "user-a",
    generation: 1,
    tombstoneId: "initial-missing",
    updatedAtEpochMillis: 0,
  };
  owners: string[] = [];

  async load(ownerUid: string): Promise<MiniHomeAuthoritativeRead> {
    this.owners.push(ownerUid);
    return this.result;
  }
}

class FakeStore implements MiniHomeLayoutStore {
  revision = 0;
  calls = 0;
  availablePlants = new Set(["plant-a", "plant-b"]);
  availableItems = new Set(["decor-a"]);

  async save(command: MiniHomeLayoutCommand): Promise<MiniHomeSaveResult> {
    this.calls += 1;
    for (const placement of command.placements) {
      if (placement.plantId !== null && !this.availablePlants.has(placement.plantId)) {
        throw new MiniHomeError("failed-precondition", "Plant is unavailable", "UNAVAILABLE_ENTITY");
      }
      if (placement.itemId !== null && !this.availableItems.has(placement.itemId)) {
        throw new MiniHomeError("failed-precondition", "Decoration is unavailable", "UNAVAILABLE_ENTITY");
      }
    }
    if (command.expectedRevision !== this.revision) {
      return { kind: "conflict", actualRevision: this.revision };
    }
    this.revision += 1;
    return { kind: "applied", revision: this.revision };
  }
}

const request = {
  expectedOwnerUid: "user-a",
  miniHomeId: "home-a",
  expectedRevision: 0,
  idempotencyKey: "mini-home-operation-0001",
  name: "나의 미니 식물원",
  placements: [
    {
      placementId: "placement-a",
      plantId: "plant-a",
      itemId: null,
      normalizedX: 0.1,
      normalizedY: 0.125,
      zIndex: 0,
    },
  ],
};

test("every enumerated Unicode White Space code point is rejected at either name boundary", async () => {
  const whiteSpace = [
    0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020, 0x0085, 0x00A0, 0x1680,
    0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008,
    0x2009, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000,
  ];
  assert.deepEqual([...miniHomeUnicodeWhiteSpaceCodePoints], whiteSpace);
  for (const codePoint of whiteSpace) {
    const value = String.fromCodePoint(codePoint);
    for (const name of [`${value}valid`, `valid${value}`]) {
      const store = new FakeStore();
      await assert.rejects(
        () => executeSaveMiniHomeLayout({ uid: "user-a" }, { ...request, name }, store),
        (error: unknown) =>
          error instanceof MiniHomeError &&
          error.reason === "INVALID_REQUEST" &&
          error.details?.field === "name",
        `U+${codePoint.toString(16).toUpperCase()}`,
      );
      assert.equal(store.calls, 0);
    }
  }
});

test("name parity corpus covers normalization length surrogate control and bidi boundaries", async () => {
  const valid = ["A", "가나다", "é", "A B", "A\u00A0B", "😀".repeat(100)];
  const bidiControls = [
    0x061C, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E,
    0x2066, 0x2067, 0x2068, 0x2069,
  ];
  assert.deepEqual([...miniHomeBidiControlCodePoints], bidiControls);
  const invalid = [
    "", "e\u0301", "😀".repeat(101), "\uD800", "\uDC00", "A\u0000B", "A\u001FB",
    "A\u007FB", "A\u0085B", "A\u061CB", "A\u200EB", "A\u200FB", "A\u202AB",
    "A\u202BB", "A\u202CB", "A\u202DB", "A\u202EB", "A\u2066B", "A\u2067B",
    "A\u2068B", "A\u2069B",
  ];
  for (const name of valid) {
    const store = new FakeStore();
    assert.deepEqual(
      await executeSaveMiniHomeLayout({ uid: "user-a" }, { ...request, name }, store),
      { kind: "applied", revision: 1 },
      JSON.stringify(name),
    );
  }
  for (const name of invalid) {
    const store = new FakeStore();
    await assert.rejects(
      () => executeSaveMiniHomeLayout({ uid: "user-a" }, { ...request, name }, store),
      (error: unknown) =>
        error instanceof MiniHomeError &&
        error.reason === "INVALID_REQUEST" &&
        error.details?.field === "name",
      JSON.stringify(name),
    );
    assert.equal(store.calls, 0);
  }
});

test("legacy read recovery normalizes only names that become canonical and safe", () => {
  assert.equal(recoverLegacyMiniHomeName("e\u0301"), "é");
  assert.equal(recoverLegacyMiniHomeName("정상 이름"), "정상 이름");
  assert.equal(recoverLegacyMiniHomeName("😀".repeat(100)), "😀".repeat(100));
  const controls = [
    ...Array.from({ length: 0x20 }, (_, codePoint) => codePoint),
    ...Array.from({ length: 0x21 }, (_, index) => 0x7F + index),
  ];
  for (const codePoint of [...controls, ...miniHomeBidiControlCodePoints]) {
    assert.equal(
      recoverLegacyMiniHomeName(`A${String.fromCodePoint(codePoint)}B`),
      null,
      `U+${codePoint.toString(16).toUpperCase()}`,
    );
  }
  for (const value of ["", "x".repeat(101), " invalid ", "\uD800", "\uDC00"]) {
    assert.equal(recoverLegacyMiniHomeName(value), null, JSON.stringify(value));
  }
});

test("every mini-home validation rejection returns typed permanent reason and field details", async () => {
  const invalid: ReadonlyArray<Readonly<{ field: string; input: unknown }>> = [
    { field: "request", input: null },
    { field: "request", input: { ...request, extra: true } },
    { field: "expectedOwnerUid", input: { ...request, expectedOwnerUid: "" } },
    { field: "miniHomeId", input: { ...request, miniHomeId: "bad/id" } },
    { field: "idempotencyKey", input: { ...request, idempotencyKey: "short" } },
    { field: "expectedRevision", input: { ...request, expectedRevision: -1 } },
    { field: "expectedRevision", input: { ...request, expectedRevision: Number.MAX_SAFE_INTEGER } },
    { field: "name", input: { ...request, name: " surrounded " } },
    { field: "name", input: { ...request, name: "x".repeat(101) } },
    { field: "placements", input: { ...request, placements: "not-an-array" } },
    { field: "placement", input: { ...request, placements: [null] } },
    { field: "placement", input: { ...request, placements: [{ ...request.placements[0], extra: true }] } },
    { field: "placementId", input: { ...request, placements: [{ ...request.placements[0], placementId: "bad/id" }] } },
    { field: "target", input: { ...request, placements: [{ ...request.placements[0], plantId: null }] } },
    { field: "normalizedX", input: { ...request, placements: [{ ...request.placements[0], normalizedX: Number.NaN }] } },
    { field: "coordinates", input: { ...request, placements: [{ ...request.placements[0], normalizedX: 0.2 }] } },
    { field: "zIndex", input: { ...request, placements: [{ ...request.placements[0], zIndex: 1 }] } },
    {
      field: "placements",
      input: {
        ...request,
        placements: [
          request.placements[0],
          { ...request.placements[0], placementId: "placement-b", plantId: "plant-b", zIndex: 1 },
        ],
      },
    },
  ];

  for (const row of invalid) {
    const store = new FakeStore();
    await assert.rejects(
      () => executeSaveMiniHomeLayout({ uid: "user-a" }, row.input, store),
      (error: unknown) => {
        const typed = error as MiniHomeError & { reason?: string; details?: { field?: string } };
        return error instanceof MiniHomeError &&
          error.code === "invalid-argument" &&
          typed.reason === "INVALID_REQUEST" &&
          typed.details?.field === row.field;
      },
      row.field,
    );
    assert.equal(store.calls, 0, row.field);
  }
});

test("mini-home save derives owner and accepts a valid snapped layout", async () => {
  const store = new FakeStore();
  assert.deepEqual(await executeSaveMiniHomeLayout({ uid: "user-a" }, request, store), {
    kind: "applied",
    revision: 1,
  });
  assert.equal(store.calls, 1);
});

test("mini-home save rejects spoofing overlap invalid layering and unavailable entities", async () => {
  const store = new FakeStore();
  await assert.rejects(
    () => executeSaveMiniHomeLayout({ uid: "user-b" }, request, store),
    (error: unknown) => error instanceof MiniHomeError && error.code === "permission-denied",
  );
  await assert.rejects(
    () => executeSaveMiniHomeLayout({ uid: "user-a" }, {
      ...request,
      placements: [
        request.placements[0],
        { ...request.placements[0], placementId: "placement-b", plantId: "plant-b", zIndex: 1 },
      ],
    }, store),
    MiniHomeError,
  );
  await assert.rejects(
    () => executeSaveMiniHomeLayout({ uid: "user-a" }, {
      ...request,
      placements: [{ ...request.placements[0], zIndex: 2 }],
    }, store),
    MiniHomeError,
  );
  await assert.rejects(
    () => executeSaveMiniHomeLayout({ uid: "user-a" }, {
      ...request,
      placements: [{ ...request.placements[0], plantId: "foreign-plant" }],
    }, store),
    (error: unknown) =>
      error instanceof MiniHomeError &&
      error.code === "failed-precondition" &&
      error.reason === "UNAVAILABLE_ENTITY",
  );
});

 test("mini-home layering follows projected cell depth rather than cartesian row order", async () => {
  const store = new FakeStore();
  assert.deepEqual(
    await executeSaveMiniHomeLayout(
      { uid: "user-a" },
      {
        ...request,
        placements: [
          {
            placementId: "projected-back",
            plantId: "plant-a",
            itemId: null,
            normalizedX: 0.1,
            normalizedY: 0.375,
            zIndex: 0,
          },
          {
            placementId: "projected-front",
            plantId: "plant-b",
            itemId: null,
            normalizedX: 0.9,
            normalizedY: 0.125,
            zIndex: 1,
          },
        ],
      },
      store,
    ),
    { kind: "applied", revision: 1 },
  );
});

test("mini-home save returns a typed revision conflict without writing", async () => {
  const store = new FakeStore();
  store.revision = 4;
  assert.deepEqual(await executeSaveMiniHomeLayout({ uid: "user-a" }, request, store), {
    kind: "conflict",
    actualRevision: 4,
  });
});

test("authoritative mini-home load derives owner and preserves missing versus zero placements", async () => {
  const reader = new FakeReader();
  assert.deepEqual(
    await executeLoadMiniHomeLayout({ uid: "user-a" }, { expectedOwnerUid: "user-a" }, reader),
    { kind: "missing", ownerUid: "user-a", generation: 1, tombstoneId: "initial-missing", updatedAtEpochMillis: 0 },
  );
  reader.result = {
    kind: "present",
    layout: {
    ownerUid: "user-a",
    generation: 2,
    miniHomeId: "home-a",
    name: "empty room",
    placedPlantCount: 0,
    revision: 2,
    expectedRevision: 1,
    idempotencyKey: "operation-empty-room",
    requestHash: "a".repeat(64),
    updatedAtEpochMillis: 2000,
    placements: [],
    },
  };
  assert.deepEqual(
    await executeLoadMiniHomeLayout({ uid: "user-a" }, { expectedOwnerUid: "user-a" }, reader),
    { kind: "present", ...reader.result.layout, placementCount: 0 },
  );
  assert.deepEqual(reader.owners, ["user-a", "user-a"]);
});

test("authoritative mini-home load rejects missing auth spoofing and extra request fields", async () => {
  const reader = new FakeReader();
  await assert.rejects(
    () => executeLoadMiniHomeLayout(null, { expectedOwnerUid: "user-a" }, reader),
    (error: unknown) => error instanceof MiniHomeError && error.code === "unauthenticated",
  );
  await assert.rejects(
    () => executeLoadMiniHomeLayout({ uid: "user-b" }, { expectedOwnerUid: "user-a" }, reader),
    (error: unknown) => error instanceof MiniHomeError && error.code === "permission-denied",
  );
  await assert.rejects(
    () => executeLoadMiniHomeLayout({ uid: "user-a" }, { expectedOwnerUid: "user-a", extra: true }, reader),
    (error: unknown) => error instanceof MiniHomeError && error.code === "invalid-argument",
  );
  assert.equal(reader.owners.length, 0);
});
