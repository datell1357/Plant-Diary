const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const { verifyGithubActionPins } = require("./verify-github-actions-pins.cjs");

const SHA = "0123456789abcdef0123456789abcdef01234567";

function fixture(workflow) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "action-pins-"));
  fs.writeFileSync(path.join(directory, "verify.yml"), workflow);
  return directory;
}

function cleanup(directory) {
  fs.rmSync(directory, { recursive: true, force: true });
}

test("accepts pinned remote and local actions in step syntax", () => {
  const directory = fixture(`
steps:
  - uses: actions/checkout@${SHA}
  - "uses": actions/setup-node@${SHA}
  - { uses: actions/setup-java@${SHA} }
  - "us\\u0065s": gradle/actions/setup-gradle@${SHA}
  - uses: "./.github/actions/local"
`);
  try {
    assert.deepEqual(verifyGithubActionPins(directory), {
      files: 1,
      remoteActions: 4,
    });
  } finally {
    cleanup(directory);
  }
});

for (const declaration of [
  "uses: actions/checkout@v4",
  "- uses: actions/checkout@v4",
  '- "uses": actions/checkout@v4',
]) {
  test(`rejects mutable action reference: ${declaration}`, () => {
    const directory = fixture(`steps:\n  ${declaration}\n`);
    try {
      assert.throws(
        () => verifyGithubActionPins(directory),
        /full commit SHA/u,
      );
    } finally {
      cleanup(directory);
    }
  });
}

test("rejects non-string uses declarations", () => {
  const directory = fixture("steps:\n  - uses: [actions/checkout, v4]\n");
  try {
    assert.throws(
      () => verifyGithubActionPins(directory),
      /uses must be a string/u,
    );
  } finally {
    cleanup(directory);
  }
});

test("rejects mutable actions in flow mappings", () => {
  const directory = fixture(`
steps:
  - uses: actions/setup-node@${SHA}
  - { uses: actions/checkout@v4 }
`);
  try {
    assert.throws(() => verifyGithubActionPins(directory), /full commit SHA/u);
  } finally {
    cleanup(directory);
  }
});

test("rejects mutable actions resolved through YAML aliases", () => {
  const directory = fixture(`
mutable: &mutable actions/checkout@v4
steps:
  - uses: *mutable
`);
  try {
    assert.throws(() => verifyGithubActionPins(directory), /full commit SHA/u);
  } finally {
    cleanup(directory);
  }
});

test("rejects escaped mutable uses keys", () => {
  const directory = fixture(`
steps:
  - uses: actions/setup-node@${SHA}
  - "u\\u0073es": actions/checkout@v4
`);
  try {
    assert.throws(() => verifyGithubActionPins(directory), /full commit SHA/u);
  } finally {
    cleanup(directory);
  }
});

test("accepts pinned actions in multiline flow mappings", () => {
  const directory = fixture(`
steps:
  - {
      "uses": actions/checkout@${SHA}
    }
`);
  try {
    assert.deepEqual(verifyGithubActionPins(directory), {
      files: 1,
      remoteActions: 1,
    });
  } finally {
    cleanup(directory);
  }
});

test("fails closed when the workflow directory is missing", () => {
  const directory = path.join(
    os.tmpdir(),
    `missing-action-pins-${process.pid}-${Date.now()}`,
  );
  assert.throws(() => verifyGithubActionPins(directory), /ENOENT/u);
});
