# Todo 15 source-bound QA

Todo 15 evidence is valid only for the committed HEAD and tree supplied when the run starts. Commit the QA harness first, then read both identifiers from that commit. Never copy identifiers into an old manifest or promote an old run under a new source revision.

```bash
TODO15_HEAD="$(git rev-parse HEAD)"
TODO15_TREE="$(git rev-parse 'HEAD^{tree}')"
test "${#TODO15_HEAD}" -eq 40
test "${#TODO15_TREE}" -eq 40
```

## API 37 instrumentation

Every `MiniHomeShareVisualApi37Test` run requires both instrumentation arguments. The device validates that each value is exactly 40 lowercase hexadecimal characters and writes those supplied values into the new run manifest. It does not invoke or infer Git on the device.

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.planterior.helper.feature.share.MiniHomeShareVisualApi37Test \
  -Pandroid.testInstrumentationRunnerArguments.todo15SourceHead="$TODO15_HEAD" \
  -Pandroid.testInstrumentationRunnerArguments.todo15SourceTree="$TODO15_TREE"
```

For direct runner invocation, pass the same values with `am instrument -e todo15SourceHead ... -e todo15SourceTree ...`.

## Live Firebase QA

The live script requires expected source values through CLI options or the corresponding environment variables. Before contacting emulators it independently reads the repository's current `HEAD` and `HEAD^{tree}` and rejects any mismatch.

```bash
node firebase-tests/scripts/run-todo15-live-qa.cjs \
  --expected-head "$TODO15_HEAD" \
  --expected-tree "$TODO15_TREE"

# Equivalent environment form:
TODO15_EXPECTED_HEAD="$TODO15_HEAD" \
TODO15_EXPECTED_TREE="$TODO15_TREE" \
node firebase-tests/scripts/run-todo15-live-qa.cjs
```

Use `--help` for the argument contract and `--self-test` for deterministic missing, malformed, and mismatch checks.

## Visual promotion and comparison

Promotion and comparison both require explicit expected source values. Promotion refuses a run whose manifest does not match. Verification also checks the canonical manifest, preventing old canonical evidence from being relabeled in a new result.

```bash
python3 firebase-tests/scripts/verify-todo15-visual.py promote \
  --expected-head "$TODO15_HEAD" \
  --expected-tree "$TODO15_TREE" \
  --reference .omo/evidence/todo15/api37/run1 \
  --canonical test-fixtures/todo15/visual

python3 firebase-tests/scripts/verify-todo15-visual.py verify \
  --expected-head "$TODO15_HEAD" \
  --expected-tree "$TODO15_TREE" \
  --canonical test-fixtures/todo15/visual \
  --runs .omo/evidence/todo15/api37/run2 .omo/evidence/todo15/api37/run3 \
  --output .omo/evidence/todo15/api37/todo15-api37-three-run-determinism.json
```

Run `python3 firebase-tests/scripts/verify-todo15-visual.py self-test` to exercise missing, malformed, mismatch, and matching source contracts without changing evidence.
