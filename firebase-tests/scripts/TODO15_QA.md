# Todo 15 source-bound QA

Todo 15 evidence is bound to the committed HEAD and tree supplied when the run starts. Commit the QA harness first, then read both identifiers from that checkpoint. The visual reference source is immutable: a later current run may have different source identifiers, and its manifest must retain those current identifiers rather than relabeling the tracked reference fixtures.

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

## Visual checkpoint, then current verification

Use two source checkpoints. First, run and promote the reviewed green reference. Promotion requires the reference run's identifiers and writes immutable `referenceHead`, `referenceTree`, and reviewed PNG hashes. Commit the resulting tracked fixtures without rewriting those reference fields.

```bash
REFERENCE_HEAD="$TODO15_HEAD"
REFERENCE_TREE="$TODO15_TREE"
python3 firebase-tests/scripts/verify-todo15-visual.py promote \
  --expected-head "$REFERENCE_HEAD" \
  --expected-tree "$REFERENCE_TREE" \
  --reference .omo/evidence/todo15/api37/run1 \
  --canonical test-fixtures/todo15/visual
```

After committing the reference fixtures, start fresh independently wiped current runs and read the new commit identifiers. Verify requires those identifiers in each current run manifest. It independently validates that the canonical manifest and `reference-review.json` agree with the immutable reference IDs and hashes; it does not require reference and current sources to be equal.

```bash
CURRENT_HEAD="$(git rev-parse HEAD)"
CURRENT_TREE="$(git rev-parse 'HEAD^{tree}')"
python3 firebase-tests/scripts/verify-todo15-visual.py verify \
  --expected-head "$CURRENT_HEAD" \
  --expected-tree "$CURRENT_TREE" \
  --canonical test-fixtures/todo15/visual \
  --runs .omo/evidence/todo15/api37/run2 .omo/evidence/todo15/api37/run3 \
  --output .omo/evidence/todo15/api37/todo15-api37-three-run-determinism.json
```

The verification output records both `referenceHead`/`referenceTree` and `currentHead`/`currentTree`. Run `python3 firebase-tests/scripts/verify-todo15-visual.py self-test` to exercise same-source, distinct-source, relabeling, current-source, and changed-PNG checks without changing evidence.
