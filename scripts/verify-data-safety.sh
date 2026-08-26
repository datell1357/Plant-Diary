#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/planterior-data-safety.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT

run_gate() {
    local name="$1"
    shift
    local log="$TMP_DIR/$name.log"
    if "$@" >"$log" 2>&1; then
        printf 'DATA_SAFETY_GATE name=%s result=pass\n' "$name"
    else
        local status=$?
        printf 'DATA_SAFETY_GATE name=%s result=fail exit=%s\n' "$name" "$status" >&2
        exit "$status"
    fi
}

verify_github_action_pins() {
    if ! npx --yes node@22 --test "$ROOT_DIR/scripts/test-github-actions-pins.cjs"; then
        return 1
    fi
    npx --yes node@22 "$ROOT_DIR/scripts/verify-github-actions-pins.cjs" "$ROOT_DIR/.github/workflows"
}

run_gate actual-checkout-security "$ROOT_DIR/scripts/verify-security.sh"
run_gate github-actions-pins verify_github_action_pins
run_gate matrix-source-forbidden npx --yes node@22 --test "$ROOT_DIR/firebase-tests/test/data-safety-contract.test.cjs"
run_gate android-dependency-integrity "$ROOT_DIR/scripts/verify-android-dependency-integrity.sh"
run_gate functions-npm-audit npm --prefix "$ROOT_DIR/functions" audit --audit-level=moderate
run_gate firebase-tests-npm-audit npm --prefix "$ROOT_DIR/firebase-tests" audit --audit-level=moderate
printf 'DATA_SAFETY_GATE aggregate=pass\n'
