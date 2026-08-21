#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FINGERPRINT="$ROOT_DIR/scripts/verify-source-fingerprint.sh"
SECRET_GATE="$ROOT_DIR/scripts/verify-secrets.sh"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/planterior-security-contract.XXXXXX")"
DETECTION_VALUE="aB9xQ2mN7vK4pR8tY5uW3zC6"
ALLOWED_ONE="legacy-receipt-0001"
ALLOWED_TWO="inventory-fixture-0001"
NEAR_MATCH="legacy-receipt-0002"
trap 'rm -rf "$TMP_DIR"' EXIT

fail() {
    printf 'security provenance contract failed: %s\n' "$1" >&2
    exit 1
}

new_repository() {
    local name="$1"
    local repository="$TMP_DIR/$name"
    mkdir -p "$repository"
    git -C "$repository" init -q
    git -C "$repository" config user.name "Planterior Security Contract"
    git -C "$repository" config user.email "security-contract@example.invalid"
    cp "$ROOT_DIR/.gitleaks.toml" "$repository/.gitleaks.toml"
    printf 'build/\n' > "$repository/.gitignore"
    printf 'clean baseline\n' > "$repository/README.md"
    git -C "$repository" add .
    git -C "$repository" commit -qm "baseline"
    printf '%s\n' "$repository"
}

payload_hash() {
    "$FINGERPRINT" --repository "$1" | awk '$1 == "payload-sha256" { print $2 }'
}

manual_payload_hash() {
    local repository="$1"
    (
        cd "$repository"
        {
            git diff --binary HEAD
            while IFS= read -r relative_path; do
                status=0
                git diff --binary --no-index -- /dev/null "$relative_path" || status=$?
                [[ "$status" -eq 1 ]] || exit 1
            done < <(git -c core.quotepath=false ls-files --others --exclude-standard | LC_ALL=C sort)
        } | shasum -a 256 | awk '{print $1}'
    )
}

expect_secret_failure() {
    local repository="$1"
    local label="$2"
    local log_file="$TMP_DIR/$label.log"
    if "$SECRET_GATE" --repository "$repository" > "$log_file" 2>&1; then
        fail "$label secret was accepted"
    fi
    grep -Fq "leaks found" "$log_file" || fail "$label did not produce a Gitleaks finding"
    printf 'secret contract detected: %s\n' "$label"
}

fingerprint_repository="$(new_repository fingerprint)"
clean_one="$(payload_hash "$fingerprint_repository")"
clean_two="$(payload_hash "$fingerprint_repository")"
[[ "$clean_one" == "$clean_two" ]] || fail "clean fingerprint recomputation drifted"
empty_hash="$(printf '' | shasum -a 256 | awk '{print $1}')"
[[ "$clean_one" == "$empty_hash" ]] || fail "clean fingerprint is not the empty patch hash"
manifest_output="$TMP_DIR/fingerprint-manifest.txt"
"$FINGERPRINT" --repository "$fingerprint_repository" --with-manifest > "$manifest_output"
grep -Eq '^payload-sha256 [0-9a-f]{64}$' "$manifest_output" || fail "payload hash label is missing"
grep -Eq '^manifest-sha256 [0-9a-f]{64}$' "$manifest_output" || fail "manifest hash label is missing"
[[ "$(awk '$1 == "payload-sha256" { print $2 }' "$manifest_output")" == "$clean_one" ]] ||
    fail "optional manifest changed the patch fingerprint"

printf 'dirty tracked\n' >> "$fingerprint_repository/README.md"
tracked_one="$(payload_hash "$fingerprint_repository")"
tracked_two="$(payload_hash "$fingerprint_repository")"
[[ "$tracked_one" != "$clean_one" ]] || fail "tracked change did not alter fingerprint"
[[ "$tracked_one" == "$tracked_two" ]] || fail "tracked fingerprint recomputation drifted"
git -C "$fingerprint_repository" reset --hard -q HEAD

printf '\000\001\002binary-a\377' > "$fingerprint_repository/z-asset.bin"
printf '\377\002\001binary-b\000' > "$fingerprint_repository/a-asset.bin"
untracked_one="$(payload_hash "$fingerprint_repository")"
untracked_two="$(payload_hash "$fingerprint_repository")"
[[ "$untracked_one" != "$clean_one" ]] || fail "untracked change did not alter fingerprint"
[[ "$untracked_one" == "$untracked_two" ]] || fail "untracked fingerprint recomputation drifted"
printf '\000\001\002binary-c\377' > "$fingerprint_repository/z-asset.bin"
untracked_changed="$(payload_hash "$fingerprint_repository")"
[[ "$untracked_changed" != "$untracked_one" ]] || fail "untracked binary content did not alter fingerprint"
[[ "$untracked_changed" == "$(manual_payload_hash "$fingerprint_repository")" ]] ||
    fail "script fingerprint differs from canonical brace pipeline"
printf 'fingerprint contracts passed: clean, tracked, untracked, binary, stable recomputation\n'

history_repository="$(new_repository history-secret)"
mkdir -p "$history_repository/functions/src"
printf 'export const config = { idempotencyKey: "%s" };\n' "$DETECTION_VALUE" > "$history_repository/functions/src/inventory.ts"
git -C "$history_repository" add functions/src/inventory.ts
git -C "$history_repository" commit -qm "seed history canary"
expect_secret_failure "$history_repository" history

tracked_repository="$(new_repository tracked-secret)"
mkdir -p "$tracked_repository/functions/src"
printf 'export const config = { enabled: true };\n' > "$tracked_repository/functions/src/inventory.ts"
git -C "$tracked_repository" add functions/src/inventory.ts
git -C "$tracked_repository" commit -qm "add production source"
printf 'export const config = { idempotencyKey: "%s" };\n' "$DETECTION_VALUE" > "$tracked_repository/functions/src/inventory.ts"
expect_secret_failure "$tracked_repository" tracked-dirty

untracked_repository="$(new_repository untracked-secret)"
mkdir -p "$untracked_repository/functions/src"
printf 'export const config = { idempotencyKey: "%s" };\n' "$DETECTION_VALUE" > "$untracked_repository/functions/src/inventory.ts"
expect_secret_failure "$untracked_repository" untracked

allowed_repository="$(new_repository allowed-fixture)"
mkdir -p "$allowed_repository/functions/src" "$allowed_repository/build"
cat > "$allowed_repository/functions/src/mini-home.emulator-spec.ts" <<EOF
const first = { idempotencyKey: "$ALLOWED_ONE" };
const second = { idempotencyKey: "$ALLOWED_TWO" };
EOF
printf 'const ignored = { idempotencyKey: "%s" };\n' "$DETECTION_VALUE" > "$allowed_repository/build/generated-secret.ts"
"$SECRET_GATE" --repository "$allowed_repository" > "$TMP_DIR/allowed.log" 2>&1 ||
    fail "exact allowlisted fixtures or ignored build output failed"
printf 'secret contract allowed: exact fixture path and values; ignored build output excluded\n'

near_match_repository="$(new_repository near-match)"
mkdir -p "$near_match_repository/functions/src"
printf 'const value = { idempotencyKey: "%s" };\n' "$NEAR_MATCH" > "$near_match_repository/functions/src/mini-home.emulator-spec.ts"
expect_secret_failure "$near_match_repository" near-match

printf 'security provenance contracts passed\n'
