const requiredMajor = 22;
const actual = process.versions.node;
const actualMajor = Number.parseInt(actual.split(".", 1)[0], 10);

if (actualMajor !== requiredMajor) {
  console.error(
    `NODE_RUNTIME required=${requiredMajor} actual=${actual} status=wrong-major`,
  );
  process.exit(1);
}

console.log(`NODE_RUNTIME required=${requiredMajor} actual=${actual} status=ok`);
