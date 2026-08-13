#!/bin/sh
set -eu

if [ "$#" -lt 1 ]; then
  printf 'IOS_QA_USAGE_ERROR expected_task_number\n' >&2
  exit 64
fi

task_number=$1
shift
attempt_dir=
device=

while [ "$#" -gt 0 ]; do
  case "$1" in
    --attempt-dir)
      attempt_dir=${2:-}
      shift
      ;;
    --device)
      device=${2:-}
      shift
      ;;
    *)
      printf 'IOS_QA_USAGE_ERROR unknown_argument=%s\n' "$1" >&2
      exit 64
      ;;
  esac
  shift
done

if [ -z "$attempt_dir" ]; then
  printf 'IOS_QA_USAGE_ERROR missing_attempt_dir\n' >&2
  exit 64
fi

mkdir -p "$attempt_dir"
evidence="$attempt_dir/task-$task_number-ios-app-implementation.log"
exec >"$evidence" 2>&1

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/../.." && pwd)
android_root=/Users/yeoreum/Documents/Planterior_Helper-worktrees/feat-android-app

if [ "$task_number" = "2" ]; then
  development_config="$repo_root/ios/Config/Development.xcconfig"
  release_config="$repo_root/ios/Config/Release.xcconfig"
  if [ "${QA_MISSING_CONFIG:-0}" = "1" ]; then
    development_config="$repo_root/ios/Config/Missing.xcconfig"
  fi
  if [ ! -f "$development_config" ]; then
    printf 'IOS_CONFIGURATION_MISSING config=%s\n' "$development_config" >&2
    exit 70
  fi
  if [ ! -f "$release_config" ]; then
    printf 'IOS_CONFIGURATION_MISSING config=%s\n' "$release_config" >&2
    exit 70
  fi
  export QA_ATTEMPT_DIR="$attempt_dir"
  "$script_dir/verify.sh"
  printf 'IOS_TASK_2_QA_OK\n'
  exit 0
fi

if [ "$task_number" = "3" ]; then
  swift test --package-path "$repo_root/ios/Packages/PlanteriorCore"
  if grep -R -n -E '^import (Firebase|SwiftData|SwiftUI|UIKit)' \
    "$repo_root/ios/Packages/PlanteriorCore/Sources/PlanteriorDomain"; then
    printf 'IOS_DOMAIN_DEPENDENCY_VIOLATION\n' >&2
    exit 72
  fi
  printf 'IOS_TASK_3_QA_OK\n'
  exit 0
fi

if [ "$task_number" = "4" ]; then
  simulator_name=${IOS_QA_SIMULATOR_NAME:-iPhone 17}
  derived_data=/tmp/planterior-task-4-qa
  result_bundle="$attempt_dir/task-4-ios-app-implementation.xcresult"
  attachment_dir="$attempt_dir/task-4-attachments"
  ax5_result_bundle="$attempt_dir/task-4-ios-app-implementation-ax5.xcresult"
  ax5_attachment_dir="$attempt_dir/task-4-attachments-ax5"
  rm -rf \
    "$result_bundle" \
    "$attachment_dir" \
    "$ax5_result_bundle" \
    "$ax5_attachment_dir"
  xcrun simctl boot "$simulator_name" 2>/dev/null || true
  xcrun simctl bootstatus "$simulator_name" -b
  xcrun simctl ui "$simulator_name" appearance light
  xcrun simctl ui "$simulator_name" content_size medium
  xcodebuild \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "platform=iOS Simulator,name=$simulator_name,OS=26.5" \
    -derivedDataPath "$derived_data" \
    -resultBundlePath "$result_bundle" \
    test
  xcrun xcresulttool export attachments \
    --path "$result_bundle" \
    --output-path "$attachment_dir"
  screenshot=$(find "$attachment_dir" -type f -name '*.png' | head -n 1)
  test -n "$screenshot"
  cp "$screenshot" "$attempt_dir/task-4-ios-app-implementation.png"
  compact_result_bundle="$attempt_dir/task-4-ios-app-implementation-390x844.xcresult"
  compact_attachment_dir="$attempt_dir/task-4-attachments-390x844"
  rm -rf "$compact_result_bundle" "$compact_attachment_dir"
  xcodebuild \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "platform=iOS Simulator,name=iPhone 17e,OS=26.5" \
    -derivedDataPath "$derived_data" \
    -resultBundlePath "$compact_result_bundle" \
    test \
    -only-testing:PlanteriorUITests/AppLaunchUITests/testCaptureRenderedShell
  xcrun xcresulttool export attachments \
    --path "$compact_result_bundle" \
    --output-path "$compact_attachment_dir"
  compact_screenshot=$(find "$compact_attachment_dir" -type f -name '*.png' | head -n 1)
  test -n "$compact_screenshot"
  cp "$compact_screenshot" \
    "$attempt_dir/task-4-ios-app-implementation-390x844.png"
  xcrun simctl ui "$simulator_name" \
    content_size accessibility-extra-extra-extra-large
  xcodebuild \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "platform=iOS Simulator,name=$simulator_name,OS=26.5" \
    -derivedDataPath "$derived_data" \
    -resultBundlePath "$ax5_result_bundle" \
    test-without-building \
    -only-testing:PlanteriorUITests/AppLaunchUITests/testCaptureRenderedShell
  xcrun xcresulttool export attachments \
    --path "$ax5_result_bundle" \
    --output-path "$ax5_attachment_dir"
  ax5_screenshot=$(find "$ax5_attachment_dir" -type f -name '*.png' | head -n 1)
  test -n "$ax5_screenshot"
  cp "$ax5_screenshot" "$attempt_dir/task-4-ios-app-implementation-ax5.png"
  xcrun simctl ui "$simulator_name" content_size medium
  test -s "$attempt_dir/task-4-ios-app-implementation.png"
  test -s "$attempt_dir/task-4-ios-app-implementation-390x844.png"
  test -s "$attempt_dir/task-4-ios-app-implementation-ax5.png"
  xcrun simctl launch "$simulator_name" com.planterior.helper
  xcrun simctl openurl "$simulator_name" \
    "planterior://plant/deleted-plant"
  printf 'IOS_TASK_4_QA_OK simulator=%s variants=light,390x844,ax5\n' \
    "$simulator_name"
  exit 0
fi

if [ "$task_number" = "5" ]; then
  contract="$repo_root/ios/Packages/PlanteriorCore/Tests/PlanteriorDataTests/Fixtures/backend-contract-v1.json"
  if [ "${IOS_QA_MISSING_CONTRACT:-0}" = "1" ]; then
    contract="$contract.missing"
  fi
  if [ ! -f "$contract" ]; then
    printf 'IOS_BACKEND_CONTRACT_UNAVAILABLE path=%s\n' "$contract" >&2
    exit 70
  fi
  android_root=/Users/yeoreum/Documents/Planterior_Helper-worktrees/feat-android-app
  pinned_commit=8f362c4de2bc76d16875ac80d0c8ad794e950340
  python3 - "$contract" "$android_root" "$pinned_commit" <<'PY'
import hashlib
import json
import subprocess
import sys

contract_path, android_root, commit = sys.argv[1:]
with open(contract_path, encoding="utf-8") as source:
    manifest = json.load(source)
for pinned in manifest["pinnedFiles"]:
    content = subprocess.check_output(
        ["git", "-C", android_root, "show", f"{commit}:{pinned['path']}"]
    )
    if hashlib.sha256(content).hexdigest() != pinned["sha256"]:
        raise SystemExit("IOS_BACKEND_CONTRACT_UNAVAILABLE digest-mismatch")
PY
  producer_fixture="$repo_root/ios/qa/fixtures/task-5-owner-mutation.json"
  manifest_fixture=/tmp/planterior-task-5-manifest-fixture.json
  python3 - "$contract" "$manifest_fixture" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    fixture = json.load(source)["validOwnerMutations"][0]
with open(sys.argv[2], "w", encoding="utf-8") as target:
    json.dump(fixture, target, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
PY
  python3 - "$producer_fixture" /tmp/planterior-task-5-producer-fixture.json <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    fixture = json.load(source)
with open(sys.argv[2], "w", encoding="utf-8") as target:
    json.dump(fixture, target, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
PY
  cmp /tmp/planterior-task-5-producer-fixture.json "$manifest_fixture"
  pinned_tree=/tmp/planterior-firebase-contract-8f362c4
  rm -rf "$pinned_tree"
  mkdir -p "$pinned_tree"
  git -C "$android_root" archive "$pinned_commit" |
    tar -x -C "$pinned_tree"
  ln -s "$android_root/firebase-tests/node_modules" \
    "$pinned_tree/firebase-tests/node_modules"
  (
    cd "$pinned_tree"
    firebase emulators:exec \
      --only firestore,storage \
      --project demo-planterior \
      "npm --prefix firebase-tests test"
  )
  swift test --package-path "$repo_root/ios/Packages/PlanteriorCore"
  python3 - "$contract" "$attempt_dir/task-5-ios-app-implementation.json" <<'PY'
import json
import sys

contract_path, evidence_path = sys.argv[1:]
with open(contract_path, encoding="utf-8") as source:
    manifest = json.load(source)
evidence = {
    "contractVersion": manifest["contractVersion"],
    "sourceCommit": manifest["sourceCommit"],
    "pinnedFileCount": len(manifest["pinnedFiles"]),
    "validFixtureCount": len(manifest["validOwnerMutations"]),
    "forbiddenFixtureCount": len(manifest["forbiddenFixtures"]),
    "unavailableIntegrationCount": len(manifest["unavailableIntegrations"]),
    "unavailablePolicyCount": len(manifest["unavailablePolicies"]),
    "duplicateResult": "original-revision",
    "staleRevisionResult": "conflict-no-write",
    "rulesEvidence": "pinned-android-emulator-suite-passed"
}
with open(evidence_path, "w", encoding="utf-8") as target:
    json.dump(evidence, target, ensure_ascii=False, indent=2)
PY
  printf 'IOS_TASK_5_QA_OK\n'
  exit 0
fi

if [ "$task_number" != "1" ]; then
  printf 'IOS_QA_TASK_NOT_IMPLEMENTED task=%s\n' "$task_number" >&2
  exit 69
fi

if [ "${PATH_OWNERSHIP_PROBE:-}" = "android" ]; then
  "$script_dir/check-path-ownership.sh" app/build.gradle.kts
fi

"$script_dir/check-path-ownership.sh" \
  ios/scripts/preflight-release-assets.sh \
  ios/scripts/check-path-ownership.sh \
  ios/scripts/qa-task.sh \
  ios/qa/scenarios/task-1.json \
  .omo/boulder.json \
  .gitignore

IOS_PHYSICAL_UDID=${device:-${IOS_PHYSICAL_UDID:-}}
export IOS_PHYSICAL_UDID
"$script_dir/preflight-release-assets.sh" \
  --require-device \
  --require-signing \
  --require-firebase \
  --require-asc

branch=$(git -C "$repo_root" branch --show-current)
if [ "$branch" != "feat/ios-app" ]; then
  printf 'IOS_WORKTREE_BRANCH_MISMATCH actual=%s\n' "$branch" >&2
  exit 67
fi

expected_root=/Users/yeoreum/Documents/Planterior_Helper-worktrees/feat-ios-app
if [ "$repo_root" != "$expected_root" ]; then
  printf 'IOS_WORKTREE_PATH_MISMATCH actual=%s\n' "$repo_root" >&2
  exit 67
fi

base=$(git -C "$repo_root" merge-base HEAD main)
main=$(git -C "$repo_root" rev-parse main)
if [ "$base" != "$main" ]; then
  printf 'IOS_WORKTREE_BASE_MISMATCH base=%s main=%s\n' "$base" "$main" >&2
  exit 67
fi

if [ -n "${ANDROID_STATUS_BEFORE_DIGEST:-}" ]; then
  current_digest=$(
    git -C "$android_root" status --porcelain=v1 -uall |
      shasum -a 256 |
      awk '{ print $1 }'
  )
  if [ "$current_digest" != "$ANDROID_STATUS_BEFORE_DIGEST" ]; then
    printf 'IOS_ANDROID_STATUS_CHANGED before=%s after=%s\n' \
      "$ANDROID_STATUS_BEFORE_DIGEST" "$current_digest" >&2
    exit 68
  fi
fi

printf 'IOS_TASK_1_QA_OK\n'
