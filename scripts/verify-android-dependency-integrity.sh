#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CATALOG="$ROOT_DIR/gradle/libs.versions.toml"
VERIFICATION="$ROOT_DIR/gradle/verification-metadata.xml"
WRAPPER="$ROOT_DIR/gradle/wrapper/gradle-wrapper.properties"

fail() {
    printf 'android dependency integrity gate failed: %s\n' "$1" >&2
    exit 1
}

[[ -s "$CATALOG" ]] || fail "missing version catalog"
[[ -s "$VERIFICATION" ]] || fail "missing Gradle verification metadata"
[[ -s "$WRAPPER" ]] || fail "missing Gradle wrapper properties"
grep -Eq '^distributionSha256Sum=[0-9a-f]{64}$' "$WRAPPER" || fail "wrapper distribution checksum is not pinned"
grep -Fq '<verification-metadata' "$VERIFICATION" || fail "verification metadata is malformed"
grep -Fq 'RepositoriesMode.FAIL_ON_PROJECT_REPOS' "$ROOT_DIR/settings.gradle.kts" || fail "project repositories are not locked"

"$ROOT_DIR/gradlew" --dependency-verification=strict help >/dev/null
printf 'ANDROID_DEPENDENCY_INTEGRITY result=pass verification=strict network=allowed\n'
