const fs = require("node:fs");
const path = require("node:path");
const yaml = require("../firebase-tests/node_modules/js-yaml");

const REMOTE_ACTION = /^[^/@\s]+\/[^@\s]+(?:\/[^@\s]+)*@[0-9a-f]{40}$/;

function workflowFiles(workflowsDirectory) {
  return fs
    .readdirSync(workflowsDirectory, { withFileTypes: true })
    .filter(
      (entry) =>
        entry.isFile() &&
        (entry.name.endsWith(".yml") || entry.name.endsWith(".yaml")),
    )
    .map((entry) => path.join(workflowsDirectory, entry.name))
    .sort();
}

function verifyGithubActionPins(workflowsDirectory) {
  const files = workflowFiles(workflowsDirectory);
  if (files.length === 0) {
    throw new Error(`No workflow files found in ${workflowsDirectory}`);
  }

  let remoteActionCount = 0;
  const failures = [];
  for (const file of files) {
    const documents = [];
    yaml.loadAll(fs.readFileSync(file, "utf8"), (document) => {
      documents.push(document);
    });
    const visited = new WeakSet();

    function inspect(value, location) {
      if (value === null || typeof value !== "object") {
        return;
      }
      if (visited.has(value)) {
        return;
      }
      visited.add(value);
      if (Array.isArray(value)) {
        value.forEach((entry, index) =>
          inspect(entry, `${location}[${index}]`),
        );
        return;
      }

      for (const [key, entry] of Object.entries(value)) {
        const childLocation = `${location}.${key}`;
        if (key === "uses") {
          if (typeof entry !== "string") {
            failures.push(`${file}:${childLocation}: uses must be a string`);
          } else if (!entry.startsWith("./")) {
            remoteActionCount += 1;
            if (!REMOTE_ACTION.test(entry)) {
              failures.push(
                `${file}:${childLocation}: remote action must use a full commit SHA: ${entry}`,
              );
            }
          }
        }
        inspect(entry, childLocation);
      }
    }

    documents.forEach((document, index) =>
      inspect(document, `document[${index}]`),
    );
  }

  if (remoteActionCount === 0) {
    failures.push(`${workflowsDirectory}: no remote action references found`);
  }
  if (failures.length > 0) {
    throw new Error(failures.join("\n"));
  }
  return { files: files.length, remoteActions: remoteActionCount };
}

if (require.main === module) {
  const workflowsDirectory =
    process.argv[2] ?? path.resolve(".github", "workflows");
  const result = verifyGithubActionPins(workflowsDirectory);
  process.stdout.write(
    `github-actions-pins files=${result.files} remoteActions=${result.remoteActions}\n`,
  );
}

module.exports = { verifyGithubActionPins };
