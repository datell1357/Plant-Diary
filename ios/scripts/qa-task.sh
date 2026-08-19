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

if [ "$task_number" = "6" ]; then
  development_plist=${IOS_FIREBASE_PLIST_DEV:-}
  if [ -n "$development_plist" ]; then
    export PLAN_FIREBASE_PLIST_PATH="$development_plist"
  fi
  swift test \
    --package-path "$repo_root/ios/Packages/PlanteriorCore"
  xcodebuild \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "platform=iOS Simulator,name=iPhone 17,OS=26.5" \
    -derivedDataPath /tmp/planterior-task-6-qa \
    -parallel-testing-enabled NO \
    test
  if [ "${IOS_QA_PROTOCOL_ONLY:-0}" = "1" ]; then
    printf 'IOS_TASK_6_PROTOCOL_QA_OK real_account=not_run\n'
    exit 0
  fi
  IOS_PHYSICAL_UDID=${device:-${IOS_PHYSICAL_UDID:-}}
  export IOS_PHYSICAL_UDID
  "$script_dir/preflight-release-assets.sh" \
    --require-device \
    --require-signing \
    --require-firebase
  if [ "${IOS_TASK_6_REAL_ACCOUNT_CONFIRMED:-0}" != "1" ]; then
    printf 'IOS_TASK_6_REAL_ACCOUNT_QA_REQUIRED device=%s\n' "$IOS_PHYSICAL_UDID"
    exit 65
  fi
  if [ -z "${IOS_TASK_6_REAL_ACCOUNT_EVIDENCE:-}" ] ||
     [ ! -f "$IOS_TASK_6_REAL_ACCOUNT_EVIDENCE" ]; then
    printf 'IOS_TASK_6_REAL_ACCOUNT_EVIDENCE_MISSING\n' >&2
    exit 65
  fi
  cp "$IOS_TASK_6_REAL_ACCOUNT_EVIDENCE" \
    "$attempt_dir/task-6-real-account-evidence.txt"
  printf 'IOS_TASK_6_QA_OK device=%s\n' "$IOS_PHYSICAL_UDID"
fi

if [ "$task_number" = "7" ]; then
  swift test \
    --package-path "$repo_root/ios/Packages/PlanteriorCore" \
    --filter PhotoInputTests
  xcodebuild \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "platform=iOS Simulator,name=iPhone 17,OS=26.5" \
    -derivedDataPath /tmp/planterior-task-7-qa \
    -parallel-testing-enabled NO \
    test \
    -only-testing:PlanteriorUITests/AppLaunchUITests/testPhotoReviewReplaceAndAcknowledgementCancellation \
    -only-testing:PlanteriorUITests/AppLaunchUITests/testDeniedCameraAndCorruptPhotoPreserveFallbackActions
  if [ "${IOS_QA_PROTOCOL_ONLY:-0}" = "1" ]; then
    printf 'IOS_TASK_7_PROTOCOL_QA_OK physical_capture=not_run\n'
    exit 0
  fi
  IOS_PHYSICAL_UDID=${device:-${IOS_PHYSICAL_UDID:-}}
  export IOS_PHYSICAL_UDID
  "$script_dir/preflight-release-assets.sh" \
    --require-device \
    --require-signing
  if [ "${IOS_TASK_7_PHYSICAL_CONFIRMED:-0}" != "1" ]; then
    printf 'IOS_TASK_7_PHYSICAL_QA_REQUIRED device=%s\n' "$IOS_PHYSICAL_UDID"
    exit 65
  fi
  if [ -z "${IOS_TASK_7_PHYSICAL_SCREENSHOT:-}" ] ||
     [ ! -f "$IOS_TASK_7_PHYSICAL_SCREENSHOT" ]; then
    printf 'IOS_TASK_7_PHYSICAL_EVIDENCE_MISSING\n' >&2
    exit 65
  fi
  cp "$IOS_TASK_7_PHYSICAL_SCREENSHOT" \
    "$attempt_dir/task-7-ios-app-implementation.png"
  printf 'IOS_TASK_7_QA_OK device=%s\n' "$IOS_PHYSICAL_UDID"
  exit 0
fi

if [ "$task_number" = "8" ]; then
  swift test \
    --package-path "$repo_root/ios/Packages/PlanteriorCore" \
    --filter AccountSyncEngineTests
  swift test \
    --package-path "$repo_root/ios/Packages/PlanteriorCore" \
    --filter SyncTransportTests
  xcodebuild \
    -quiet \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "platform=iOS Simulator,name=iPhone 17,OS=26.5" \
    -derivedDataPath /tmp/planterior-task-8-swiftdata \
    -parallel-testing-enabled NO \
    test \
    -only-testing:PlanteriorTests/SwiftDataAccountCacheTests
  python3 - "$attempt_dir/task-8-ios-app-implementation.json" <<'PY'
import json
import sys

with open(sys.argv[1], "w", encoding="utf-8") as output:
    json.dump(
        {
            "task": 8,
            "accountPartition": "passed",
            "offlineReconnect": "passed",
            "boundedRetry": "passed",
            "conflictDraft": "preserved",
            "explicitReapply": "passed",
            "listenerCancellation": "passed",
            "inFlightAccountIsolation": "passed",
            "swiftDataPartition": "passed",
            "logoutChoices": ["sync", "cancel", "discard"]
        },
        output,
        ensure_ascii=False,
        indent=2
    )
PY
  printf 'IOS_TASK_8_QA_OK\n'
  exit 0
fi

if [ "$task_number" = "9" ]; then
  swift test \
    --package-path "$repo_root/ios/Packages/PlanteriorCore" \
    --filter PlantIdentificationTests
  swift test \
    --package-path "$repo_root/ios/Packages/PlanteriorCore" \
    --filter PlantIdentificationRecoveryTests
  task_9_derived_data="$attempt_dir/DerivedData"
  xcodebuild \
    -quiet \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "platform=iOS Simulator,name=iPhone 17,OS=26.5" \
    -derivedDataPath "$task_9_derived_data" \
    -parallel-testing-enabled NO \
    CODE_SIGNING_ALLOWED=NO \
    test \
    -only-testing:PlanteriorUITests/AppLaunchUITests/testIdentificationRequiresCandidateConfirmationBeforeRegistration \
    -only-testing:PlanteriorUITests/IdentificationFallbackUITests/testIdentificationFallbackReturnsToPhotoSelection \
    -only-testing:PlanteriorUITests/IdentificationFallbackUITests/testIdentificationFailureRetriesToCandidates \
    -only-testing:PlanteriorUITests/IdentificationFallbackUITests/testIdentificationPendingIsObservedBeforeCandidates
  python3 - "$attempt_dir/task-9-ios-app-implementation.json" <<'PY'
import json
import sys

with open(sys.argv[1], "w", encoding="utf-8") as output:
    json.dump(
        {
            "task": 9,
            "pending": "passed",
            "topThree": "passed",
            "explicitConfirmation": "passed",
            "fallback": "passed",
            "retry": "passed",
            "providerFailures": "passed",
            "draftRestoration": "passed",
            "duplicateCancel": "passed",
            "collectionRow": "passed"
        },
        output,
        ensure_ascii=False,
        indent=2
    )
PY
  printf 'IOS_TASK_9_QA_OK\n'
  exit 0
fi

if [ "$task_number" = "10" ]; then
  swift test \
    --package-path "$repo_root/ios/Packages/PlanteriorCore" \
    --filter PlantCollectionTests
  task_10_derived_data="$attempt_dir/DerivedData"
  xcodebuild \
    -quiet \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "platform=iOS Simulator,name=iPhone 17,OS=26.5" \
    -derivedDataPath "$task_10_derived_data" \
    -parallel-testing-enabled NO \
    CODE_SIGNING_ALLOWED=NO \
    build-for-testing
  task_10_tests="
testSearchDetailTimelineAndDeleteConfirmation
testFilteredEmptyDoesNotClaimCollectionIsEmpty
testLoadingStateDoesNotLeakPrivateContent
testErrorStateDoesNotLeakPrivateContent
testPartialStateDoesNotLeakPrivateContent
testStaleStateDoesNotLeakPrivateContent
"
  primary_result=""
  for test_name in $task_10_tests; do
    result_path="$attempt_dir/task-10-$test_name.xcresult"
    xcodebuild \
      -quiet \
      -project "$repo_root/ios/Planterior.xcodeproj" \
      -scheme Planterior \
      -destination "platform=iOS Simulator,name=iPhone 17,OS=26.5" \
      -derivedDataPath "$task_10_derived_data" \
      -resultBundlePath "$result_path" \
      -parallel-testing-enabled NO \
      CODE_SIGNING_ALLOWED=NO \
      test-without-building \
      -only-testing:"PlanteriorUITests/PlantCollectionUITests/$test_name"
    if [ -z "$primary_result" ]; then
      primary_result="$result_path"
    fi
  done
  screenshot_path="$attempt_dir/task-10-ios-app-implementation.png"
  attachments_dir="$attempt_dir/task-10-attachments"
  xcrun xcresulttool export attachments \
    --path "$primary_result" \
    --output-path "$attachments_dir"
  attachment_file="$(find "$attachments_dir" \
    -type f -name '*task-10-ios-app-implementation*' -print | head -1)"
  if [ -z "$attachment_file" ]; then
    attachment_file="$(find "$attachments_dir" \
      -type f -name '*.png' -print | head -1)"
  fi
  cp "$attachment_file" "$screenshot_path"
  python3 - "$attempt_dir/task-10-ios-app-implementation.json" <<'PY'
import json
import sys

with open(sys.argv[1], "w", encoding="utf-8") as output:
    json.dump(
        {
            "task": 10,
            "searchAndSort": "passed",
            "detailDraft": "passed",
            "healthTimeline": "passed",
            "deleteConfirmationCancel": "passed",
            "filteredEmpty": "passed",
            "stateFixtures": "passed",
            "evidencePNG": "task-10-ios-app-implementation.png"
        },
        output,
        ensure_ascii=False,
        indent=2
    )
PY
  printf 'IOS_TASK_10_QA_OK\n'
  exit 0
fi

if [ "$task_number" = "11" ]; then
  swift test \
    --package-path "$repo_root/ios/Packages/PlanteriorCore" \
    --filter WateringScheduleCoordinatorTests
  swift test \
    --package-path "$repo_root/ios/Packages/PlanteriorCore" \
    --filter PlantCollectionTests
  task_11_derived_data="$attempt_dir/DerivedData"
  task_11_result="$attempt_dir/task-11-ios-app-implementation.xcresult"
  task_11_attachments="$attempt_dir/task-11-attachments"
  task_11_destination="platform=iOS Simulator,id=${IOS_QA_SIMULATOR_ID:-E51558B4-A5AF-4EAF-901F-AAA4173D21A4}"
  xcodebuild \
    -quiet \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "$task_11_destination" \
    -derivedDataPath "$task_11_derived_data" \
    -parallel-testing-enabled NO \
    CODE_SIGNING_ALLOWED=NO \
    build-for-testing
  xcodebuild \
    -quiet \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "$task_11_destination" \
    -derivedDataPath "$task_11_derived_data" \
    -resultBundlePath "$task_11_result" \
    -parallel-testing-enabled NO \
    CODE_SIGNING_ALLOWED=NO \
    test-without-building \
    -only-testing:PlanteriorTests/PlantCareCalendarTests \
    -only-testing:PlanteriorUITests/PlantCollectionUITests/testWateringDueCompletionUpdatesNextDate \
    -only-testing:PlanteriorUITests/PlantCollectionUITests/testWateringMissingDateShowsSetupGuidance \
    -only-testing:PlanteriorUITests/PlantCollectionUITests/testWateringDraftDateUpdatesScheduleBeforeSave \
    -only-testing:PlanteriorUITests/PlantRegistrationUITests/testRegistrationPersistsLocalGregorianWateringDate \
    -only-testing:PlanteriorUITests/PlantCollectionUITests/testSearchDetailTimelineAndDeleteConfirmation
  xcrun xcresulttool export attachments \
    --path "$task_11_result" \
    --output-path "$task_11_attachments"
  complete_screenshot=$(
    awk -F '"' '
      /exportedFileName/ { file = $4 }
      /task-11-watering-complete/ { print directory "/" file; exit }
    ' directory="$task_11_attachments" \
      "$task_11_attachments/manifest.json"
  )
  missing_screenshot=$(
    awk -F '"' '
      /exportedFileName/ { file = $4 }
      /task-11-watering-missing-date/ { print directory "/" file; exit }
    ' directory="$task_11_attachments" \
      "$task_11_attachments/manifest.json"
  )
  test -n "$complete_screenshot"
  test -n "$missing_screenshot"
  cp "$complete_screenshot" "$attempt_dir/task-11-watering-complete.png"
  cp "$missing_screenshot" "$attempt_dir/task-11-watering-missing-date.png"
  python3 - "$attempt_dir/task-11-ios-app-implementation.json" <<'PY'
import json
import sys

with open(sys.argv[1], "w", encoding="utf-8") as output:
    json.dump(
        {
            "task": 11,
            "dueDate": "2026-08-11",
            "completionDate": "2026-08-11",
            "nextDate": "2026-08-21",
            "duplicateCompletion": "idempotent",
            "missingDate": "setup-guidance",
            "missingDateSetupCompletion": "passed",
            "futureDate": "rejected",
            "localCalendarTimeZone": "passed",
            "registrationLocalCalendarDate": "passed",
            "draftSchedulePreview": "passed",
            "plantIsolation": "passed",
            "regression": "collection-detail-passed"
        },
        output,
        ensure_ascii=False,
        indent=2
    )
PY
  printf 'IOS_TASK_11_QA_OK\n'
  exit 0
fi

if [ "$task_number" = "12" ]; then
  swift test \
    --package-path "$repo_root/ios/Packages/PlanteriorCore" \
    --filter 'HomeDashboardTests|NotificationCoordinatorTests'
  task_12_derived_data="$attempt_dir/DerivedData"
  task_12_result="$attempt_dir/task-12-ios-app-implementation.xcresult"
  task_12_collection_result="$attempt_dir/task-12-collection-regression.xcresult"
  task_12_integration_result="$attempt_dir/task-12-notification-integration.xcresult"
  task_12_attachments="$attempt_dir/task-12-attachments"
  task_12_destination="platform=iOS Simulator,id=${IOS_QA_SIMULATOR_ID:-E51558B4-A5AF-4EAF-901F-AAA4173D21A4}"
  xcodebuild \
    -quiet \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "$task_12_destination" \
    -derivedDataPath "$task_12_derived_data" \
    -parallel-testing-enabled NO \
    CODE_SIGNING_ALLOWED=NO \
    build-for-testing
  reboot_task_12_simulator() {
    xcrun simctl shutdown \
      "${IOS_QA_SIMULATOR_ID:-E51558B4-A5AF-4EAF-901F-AAA4173D21A4}" \
      2>/dev/null || true
    xcrun simctl boot \
      "${IOS_QA_SIMULATOR_ID:-E51558B4-A5AF-4EAF-901F-AAA4173D21A4}" \
      2>/dev/null || true
    xcrun simctl bootstatus \
      "${IOS_QA_SIMULATOR_ID:-E51558B4-A5AF-4EAF-901F-AAA4173D21A4}" \
      -b
    xcrun simctl uninstall \
      "${IOS_QA_SIMULATOR_ID:-E51558B4-A5AF-4EAF-901F-AAA4173D21A4}" \
      com.planterior.helper 2>/dev/null || true
    xcrun simctl uninstall \
      "${IOS_QA_SIMULATOR_ID:-E51558B4-A5AF-4EAF-901F-AAA4173D21A4}" \
      com.planterior.helper.uitests.xctrunner 2>/dev/null || true
  }
  reboot_task_12_simulator
  xcodebuild \
    -quiet \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "$task_12_destination" \
    -derivedDataPath "$task_12_derived_data" \
    -resultBundlePath "$task_12_result" \
    -parallel-testing-enabled NO \
    CODE_SIGNING_ALLOWED=NO \
    test-without-building \
    -only-testing:PlanteriorTests/AppShellTests \
    -only-testing:PlanteriorTests/LocalNotificationPreferenceStoreTests \
    -only-testing:PlanteriorTests/LocalNotificationScheduleStoreTests \
    -only-testing:PlanteriorUITests/HomeDashboardUITests \
    -skip-testing:PlanteriorUITests/HomeDashboardUITests/testWateringCompletionCancelsPendingNotifications
  reboot_task_12_simulator
  xcodebuild \
    -quiet \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "$task_12_destination" \
    -derivedDataPath "$task_12_derived_data" \
    -resultBundlePath "$task_12_collection_result" \
    -parallel-testing-enabled NO \
    CODE_SIGNING_ALLOWED=NO \
    test-without-building \
    -only-testing:PlanteriorUITests/PlantCollectionUITests/testSearchDetailTimelineAndDeleteConfirmation
  reboot_task_12_simulator
  xcodebuild \
    -quiet \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "$task_12_destination" \
    -derivedDataPath "$task_12_derived_data" \
    -resultBundlePath "$task_12_integration_result" \
    -parallel-testing-enabled NO \
    CODE_SIGNING_ALLOWED=NO \
    test-without-building \
    -only-testing:PlanteriorUITests/HomeDashboardUITests/testWateringCompletionCancelsPendingNotifications
  xcrun xcresulttool export attachments \
    --path "$task_12_result" \
    --output-path "$task_12_attachments"
  copy_task_12_attachment() {
    marker=$1
    destination=$2
    attachment=$(
      awk -F '"' '
        /exportedFileName/ { file = $4 }
        $0 ~ marker { print directory "/" file; exit }
      ' marker="$marker" directory="$task_12_attachments" \
        "$task_12_attachments/manifest.json"
    )
    test -n "$attachment"
    cp "$attachment" "$destination"
  }
  copy_task_12_attachment \
    task-12-home-dashboard \
    "$attempt_dir/task-12-ios-app-implementation.png"
  copy_task_12_attachment \
    task-12-home-notification \
    "$attempt_dir/task-12-home-notification.png"
  copy_task_12_attachment \
    task-12-home-logged-out \
    "$attempt_dir/task-12-home-logged-out.png"
  copy_task_12_attachment \
    task-12-home-signing-in \
    "$attempt_dir/task-12-home-signing-in.png"
  copy_task_12_attachment \
    task-12-home-notification-denied \
    "$attempt_dir/task-12-home-notification-denied.png"
  copy_task_12_attachment \
    task-12-home-care-variants \
    "$attempt_dir/task-12-home-care-variants.png"
  copy_task_12_attachment \
    task-12-home-ax5 \
    "$attempt_dir/task-12-home-ax5.png"
  python3 - "$attempt_dir/task-12-ios-app-implementation.json" <<'PY'
import json
import sys

with open(sys.argv[1], "w", encoding="utf-8") as output:
    json.dump(
        {
            "task": 12,
            "authStates": ["logged-out", "signing-in", "authenticated"],
            "careOrder": ["overdue", "due", "upcoming", "unavailable"],
            "weatherFailureIsolation": "passed",
            "miniHomePreview": "committed-fixture",
            "notificationAuthorization": "passed",
            "endpointUnavailable": "fail-closed",
            "dueAndNextDay": "passed",
            "deduplication": "passed",
            "completionSuppression": "passed",
            "deletedRoute": "unavailable-without-metadata",
            "permissionDenial": "collection-remains-available",
            "liveAPNs": "deferred-to-todo-20"
        },
        output,
        ensure_ascii=False,
        indent=2
    )
PY
  printf 'IOS_TASK_12_QA_OK\n'
  exit 0
fi

if [ "$task_number" = "13" ]; then
  swift test \
    --package-path "$repo_root/ios/Packages/PlanteriorCore" \
    --filter 'WeatherRegionSelectionTests|WeatherRiskEvaluatorTests'
  task_13_derived_data="$attempt_dir/DerivedData"
  task_13_result="$attempt_dir/task-13-ios-app-implementation.xcresult"
  task_13_home_result="$attempt_dir/task-13-home-regression.xcresult"
  task_13_attachments="$attempt_dir/task-13-attachments"
  task_13_destination="platform=iOS Simulator,id=${IOS_QA_SIMULATOR_ID:-E51558B4-A5AF-4EAF-901F-AAA4173D21A4}"
  xcodebuild \
    -quiet \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "$task_13_destination" \
    -derivedDataPath "$task_13_derived_data" \
    -parallel-testing-enabled NO \
    CODE_SIGNING_ALLOWED=NO \
    build-for-testing
  reboot_task_13_simulator() {
    xcrun simctl shutdown \
      "${IOS_QA_SIMULATOR_ID:-E51558B4-A5AF-4EAF-901F-AAA4173D21A4}" \
      2>/dev/null || true
    xcrun simctl boot \
      "${IOS_QA_SIMULATOR_ID:-E51558B4-A5AF-4EAF-901F-AAA4173D21A4}" \
      2>/dev/null || true
    xcrun simctl bootstatus \
      "${IOS_QA_SIMULATOR_ID:-E51558B4-A5AF-4EAF-901F-AAA4173D21A4}" \
      -b
    xcrun simctl uninstall \
      "${IOS_QA_SIMULATOR_ID:-E51558B4-A5AF-4EAF-901F-AAA4173D21A4}" \
      com.planterior.helper 2>/dev/null || true
    xcrun simctl uninstall \
      "${IOS_QA_SIMULATOR_ID:-E51558B4-A5AF-4EAF-901F-AAA4173D21A4}" \
      com.planterior.helper.uitests.xctrunner 2>/dev/null || true
  }
  reboot_task_13_simulator
  xcodebuild \
    -quiet \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "$task_13_destination" \
    -derivedDataPath "$task_13_derived_data" \
    -resultBundlePath "$task_13_result" \
    -parallel-testing-enabled NO \
    CODE_SIGNING_ALLOWED=NO \
    test-without-building \
    -only-testing:PlanteriorTests/LocalPlantCollectionStoreTests \
    -only-testing:PlanteriorTests/LocalWeatherAlertStoreTests \
    -only-testing:PlanteriorTests/WeatherRuntimeAlertTests \
    -only-testing:PlanteriorUITests/WeatherFlowUITests
  reboot_task_13_simulator
  xcodebuild \
    -quiet \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "$task_13_destination" \
    -derivedDataPath "$task_13_derived_data" \
    -resultBundlePath "$task_13_home_result" \
    -parallel-testing-enabled NO \
    CODE_SIGNING_ALLOWED=NO \
    test-without-building \
    -only-testing:PlanteriorUITests/HomeDashboardUITests/testAuthenticatedHomeShowsCareMiniHomeAndPartialWeather
  xcrun xcresulttool export attachments \
    --path "$task_13_result" \
    --output-path "$task_13_attachments"
  copy_task_13_attachment() {
    marker=$1
    destination=$2
    attachment=$(
      awk -F '"' '
        /exportedFileName/ { file = $4 }
        /suggestedHumanReadableName/ {
          if (index($4, marker "_") == 1) {
            print directory "/" file
            exit
          }
        }
      ' marker="$marker" directory="$task_13_attachments" \
        "$task_13_attachments/manifest.json"
    )
    test -n "$attachment"
    cp "$attachment" "$destination"
  }
  copy_task_13_attachment \
    task-13-weather-risks \
    "$attempt_dir/task-13-ios-app-implementation.png"
  copy_task_13_attachment \
    task-13-weather-stale \
    "$attempt_dir/task-13-weather-stale.png"
  copy_task_13_attachment \
    task-13-weather-ax5 \
    "$attempt_dir/task-13-weather-ax5.png"
  copy_task_13_attachment \
    task-13-weather-ax5-actions \
    "$attempt_dir/task-13-weather-ax5-actions.png"
  copy_task_13_attachment \
    task-13-weather-settings \
    "$attempt_dir/task-13-weather-settings.png"
  copy_task_13_attachment \
    task-13-weather-plant-toggle \
    "$attempt_dir/task-13-weather-plant-toggle.png"
  python3 - "$attempt_dir/task-13-ios-app-implementation.json" <<'PY'
import json
import sys

with open(sys.argv[1], "w", encoding="utf-8") as output:
    json.dump(
        {
            "task": 13,
            "manualRegionPrecedence": "passed",
            "revokeLocationCalls": 0,
            "strictRiskBoundaries": "passed",
            "aggregatedRisks": ["HIGH_TEMPERATURE", "DRY"],
            "staleWeatherDisplayed": True,
            "staleAlerts": 0,
            "globalOverride": "passed",
            "episodeDedupe": "passed",
            "collectionAndWateringRemainAvailable": True,
            "canonicalWeatherRefresh": "unavailable",
            "liveLocationAndAPNs": "deferred-to-todo-20"
        },
        output,
        ensure_ascii=False,
        indent=2
    )
PY
  printf 'IOS_TASK_13_QA_OK\n'
  exit 0
fi

if [ "$task_number" = "14" ]; then
  task_14_simulator_id="${IOS_QA_SIMULATOR_ID:-E51558B4-A5AF-4EAF-901F-AAA4173D21A4}"
  task_14_destination="platform=iOS Simulator,id=$task_14_simulator_id"
  task_14_derived_data="$attempt_dir/DerivedData"
  task_14_result="$attempt_dir/task-14-ios-app-implementation.xcresult"
  task_14_home_result="$attempt_dir/task-14-home-regression.xcresult"
  task_14_attachments="$attempt_dir/task-14-attachments"
  swift test \
    --package-path "$repo_root/ios/Packages/PlanteriorCore" \
    --filter MiniHomeGeometryTests
  xcodebuild \
    -quiet \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "$task_14_destination" \
    -derivedDataPath "$task_14_derived_data" \
    CODE_SIGNING_ALLOWED=NO \
    build-for-testing
  reboot_task_14_simulator() {
    simulator_id=$task_14_simulator_id
    xcrun simctl shutdown "$simulator_id" 2>/dev/null || true
    xcrun simctl boot "$simulator_id"
    xcrun simctl bootstatus "$simulator_id" -b
    xcrun simctl uninstall \
      "$simulator_id" \
      com.planterior.helper 2>/dev/null || true
    xcrun simctl uninstall \
      "$simulator_id" \
      com.planterior.helper.uitests.xctrunner 2>/dev/null || true
  }
  reboot_task_14_simulator
  xcodebuild \
    -quiet \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "$task_14_destination" \
    -derivedDataPath "$task_14_derived_data" \
    -resultBundlePath "$task_14_result" \
    -parallel-testing-enabled NO \
    CODE_SIGNING_ALLOWED=NO \
    test-without-building \
    -only-testing:PlanteriorTests/MiniHomeStoreTests \
    -only-testing:PlanteriorUITests/MiniHomeAccessibilityUITests \
    -only-testing:PlanteriorUITests/MiniHomeConflictUITests \
    -only-testing:PlanteriorUITests/MiniHomeUITests
  reboot_task_14_simulator
  xcodebuild \
    -quiet \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "$task_14_destination" \
    -derivedDataPath "$task_14_derived_data" \
    -resultBundlePath "$task_14_home_result" \
    -parallel-testing-enabled NO \
    CODE_SIGNING_ALLOWED=NO \
    test-without-building \
    -only-testing:PlanteriorUITests/HomeDashboardUITests/testAuthenticatedHomeShowsCareMiniHomeAndPartialWeather
  xcrun xcresulttool export attachments \
    --path "$task_14_result" \
    --output-path "$task_14_attachments"
  copy_task_14_attachment() {
    marker=$1
    destination=$2
    attachment=$(
      awk -F '"' '
        /exportedFileName/ { file = $4 }
        /suggestedHumanReadableName/ {
          if (index($4, marker "_") == 1) {
            print directory "/" file
            exit
          }
        }
      ' marker="$marker" directory="$task_14_attachments" \
        "$task_14_attachments/manifest.json"
    )
    test -n "$attachment"
    cp "$attachment" "$destination"
  }
  copy_task_14_attachment \
    task-14-room \
    "$attempt_dir/task-14-ios-app-implementation.png"
  copy_task_14_attachment \
    task-14-room-ax5 \
    "$attempt_dir/task-14-ios-app-implementation-ax5.png"
  copy_task_14_attachment \
    task-14-mini-home-geometry \
    "$attempt_dir/task-14-mini-home-geometry.json"
  copy_task_14_attachment \
    task-14-mini-home-conflict \
    "$attempt_dir/task-14-mini-home-conflict.json"
  copy_task_14_attachment \
    task-14-home-committed-only \
    "$attempt_dir/task-14-home-committed-only.json"
  cp \
    "$repo_root/ios/qa/scenarios/task-14.json" \
    "$attempt_dir/task-14-manifest.json"
  python3 - "$attempt_dir/task-14-ios-app-implementation.json" <<'PY'
import json
import sys

with open(sys.argv[1], "w", encoding="utf-8") as output:
    json.dump(
        {
            "task": 14,
            "normalizedGeometry": "passed",
            "dragClamp": "passed",
            "deterministicZOrder": "passed",
            "explicitSave": "passed",
            "relaunchPersistence": "passed",
            "failedSavePreservesDraft": True,
            "homeReadsCommittedOnly": True,
            "conflictCancelPreservesDraft": True,
            "conflictReapply": "passed"
        },
        output,
        ensure_ascii=False,
        indent=2
    )
PY
  printf 'IOS_TASK_14_QA_OK\n'
  exit 0
fi

if [ "$task_number" = "15" ]; then
  task_15_simulator_id="${IOS_QA_SIMULATOR_ID:-29F51612-FF7F-4B0C-86ED-AF52AA591546}"
  task_15_destination="platform=iOS Simulator,id=$task_15_simulator_id"
  task_15_derived_data="$attempt_dir/DerivedData"
  task_15_result="$attempt_dir/task-15-ios-app-implementation.xcresult"
  task_15_home_result="$attempt_dir/task-15-home-regression.xcresult"
  task_15_attachments="$attempt_dir/task-15-attachments"
  swift test \
    --package-path "$repo_root/ios/Packages/PlanteriorCore" \
    --filter InventoryPolicyTests
  swift test \
    --package-path "$repo_root/ios/Packages/PlanteriorCore" \
    --filter ItemPlacementPolicyTests
  xcodebuild \
    -quiet \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "$task_15_destination" \
    -derivedDataPath "$task_15_derived_data" \
    CODE_SIGNING_ALLOWED=NO \
    build-for-testing
  reboot_task_15_simulator() {
    simulator_id=$task_15_simulator_id
    xcrun simctl shutdown "$simulator_id" 2>/dev/null || true
    xcrun simctl boot "$simulator_id"
    xcrun simctl bootstatus "$simulator_id" -b
    xcrun simctl uninstall \
      "$simulator_id" \
      com.planterior.helper 2>/dev/null || true
    xcrun simctl uninstall \
      "$simulator_id" \
      com.planterior.helper.uitests.xctrunner 2>/dev/null || true
  }
  reboot_task_15_simulator
  xcodebuild \
    -quiet \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "$task_15_destination" \
    -derivedDataPath "$task_15_derived_data" \
    -resultBundlePath "$task_15_result" \
    -parallel-testing-enabled NO \
    CODE_SIGNING_ALLOWED=NO \
    test-without-building \
    -only-testing:PlanteriorTests/InventoryRepositoryAccountTests \
    -only-testing:PlanteriorTests/InventoryRepositoryProductionTests \
    -only-testing:PlanteriorTests/InventoryRepositoryTests \
    -only-testing:PlanteriorTests/ItemPlacementCoordinatorTests \
    -only-testing:PlanteriorUITests/InventoryAccountUITests \
    -only-testing:PlanteriorUITests/InventoryAccessibilityUITests \
    -only-testing:PlanteriorUITests/InventoryUITests \
    -only-testing:PlanteriorUITests/MiniHomeUITests/testEditsSavesAndRestoresCommittedRoom
  reboot_task_15_simulator
  xcodebuild \
    -quiet \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "$task_15_destination" \
    -derivedDataPath "$task_15_derived_data" \
    -resultBundlePath "$task_15_home_result" \
    -parallel-testing-enabled NO \
    CODE_SIGNING_ALLOWED=NO \
    test-without-building \
    -only-testing:PlanteriorUITests/HomeDashboardUITests/testAuthenticatedHomeShowsCareMiniHomeAndPartialWeather
  xcrun xcresulttool export attachments \
    --path "$task_15_result" \
    --output-path "$task_15_attachments"
  copy_task_15_attachment() {
    marker=$1
    destination=$2
    attachment=$(
      awk -F '"' '
        /exportedFileName/ { file = $4 }
        /suggestedHumanReadableName/ {
          if (index($4, marker "_") == 1) {
            print directory "/" file
            exit
          }
        }
      ' marker="$marker" directory="$task_15_attachments" \
        "$task_15_attachments/manifest.json"
    )
    test -n "$attachment"
    cp "$attachment" "$destination"
  }
  copy_task_15_attachment \
    task-15-inventory \
    "$attempt_dir/task-15-ios-app-implementation.png"
  copy_task_15_attachment \
    task-15-inventory-ax5 \
    "$attempt_dir/task-15-ios-app-implementation-ax5.png"
  copy_task_15_attachment \
    task-15-shop-pagination-filter-sort \
    "$attempt_dir/task-15-shop-pagination-filter-sort.json"
  copy_task_15_attachment \
    task-15-placement \
    "$attempt_dir/task-15-placement.json"
  copy_task_15_attachment \
    task-15-acquisition-retry \
    "$attempt_dir/task-15-acquisition-retry.json"
  copy_task_15_attachment \
    task-15-account-remount \
    "$attempt_dir/task-15-account-remount.json"
  cp \
    "$repo_root/ios/qa/scenarios/task-15.json" \
    "$attempt_dir/task-15-manifest.json"
  python3 - "$attempt_dir/task-15-ios-app-implementation.json" <<'PY'
import json
import sys

with open(sys.argv[1], "w", encoding="utf-8") as output:
    json.dump(
        {
            "task": 15,
            "publicCatalog": "passed",
            "conditionAndDuplicatePolicy": "passed",
            "paginationFilterSort": "passed",
            "acquisitionRetry": "passed",
            "atomicPlacement": "passed",
            "limits": {"background": 1, "furniture": 10, "decoration": 10},
            "ownershipPreservedAfterRemoval": True,
            "accountRemountIsolation": "passed",
            "productionItemIntegration": "unavailable",
            "paymentsCurrencyRefunds": "out-of-scope"
        },
        output,
        ensure_ascii=False,
        indent=2
    )
PY
  xcrun simctl uninstall \
    "$task_15_simulator_id" \
    com.planterior.helper 2>/dev/null || true
  xcrun simctl uninstall \
    "$task_15_simulator_id" \
    com.planterior.helper.uitests.xctrunner 2>/dev/null || true
  xcrun simctl shutdown "$task_15_simulator_id" 2>/dev/null || true
  printf 'IOS_TASK_15_QA_OK\n'
  exit 0
fi

if [ "$task_number" = "16" ]; then
  task_16_simulator_id="${IOS_QA_SIMULATOR_ID:-29F51612-FF7F-4B0C-86ED-AF52AA591546}"
  task_16_destination="platform=iOS Simulator,id=$task_16_simulator_id"
  task_16_derived_data="$attempt_dir/DerivedData"
  task_16_result="$attempt_dir/task-16-ios-app-implementation.xcresult"
  task_16_home_result="$attempt_dir/task-16-home-regression.xcresult"
  task_16_attachments="$attempt_dir/task-16-attachments"
  cleanup_task_16() {
    xcrun simctl uninstall \
      "$task_16_simulator_id" \
      com.planterior.helper 2>/dev/null || true
    xcrun simctl uninstall \
      "$task_16_simulator_id" \
      com.planterior.helper.uitests.xctrunner 2>/dev/null || true
    xcrun simctl shutdown "$task_16_simulator_id" 2>/dev/null || true
  }
  trap cleanup_task_16 EXIT
  swift test \
    --package-path "$repo_root/ios/Packages/PlanteriorCore"
  xcodebuild \
    -quiet \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "$task_16_destination" \
    -derivedDataPath "$task_16_derived_data" \
    CODE_SIGNING_ALLOWED=NO \
    build-for-testing
  reboot_task_16_simulator() {
    cleanup_task_16
    xcrun simctl boot "$task_16_simulator_id"
    xcrun simctl bootstatus "$task_16_simulator_id" -b
  }
  reboot_task_16_simulator
  xcodebuild \
    -quiet \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "$task_16_destination" \
    -derivedDataPath "$task_16_derived_data" \
    -resultBundlePath "$task_16_result" \
    -parallel-testing-enabled NO \
    CODE_SIGNING_ALLOWED=NO \
    test-without-building \
    -only-testing:PlanteriorTests/ProgressionProductionTests \
    -only-testing:PlanteriorTests/ProgressionProjectionTests \
    -only-testing:PlanteriorTests/ProgressionRepositoryTests \
    -only-testing:PlanteriorUITests/ProgressionAccessibilityUITests \
    -only-testing:PlanteriorUITests/ProgressionUITests \
    -only-testing:PlanteriorUITests/PlantRegistrationUITests/testRegistrationPersistsLocalGregorianWateringDate \
    -only-testing:PlanteriorUITests/PlantCollectionUITests/testWateringDueCompletionUpdatesNextDate \
    -only-testing:PlanteriorUITests/MiniHomeUITests/testEditsSavesAndRestoresCommittedRoom
  reboot_task_16_simulator
  xcodebuild \
    -quiet \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "$task_16_destination" \
    -derivedDataPath "$task_16_derived_data" \
    -resultBundlePath "$task_16_home_result" \
    -parallel-testing-enabled NO \
    CODE_SIGNING_ALLOWED=NO \
    test-without-building \
    -only-testing:PlanteriorUITests/HomeDashboardUITests/testAuthenticatedHomeShowsCareMiniHomeAndPartialWeather
  xcrun xcresulttool export attachments \
    --path "$task_16_result" \
    --output-path "$task_16_attachments"
  copy_task_16_attachment() {
    marker=$1
    destination=$2
    attachment=$(
      awk -F '"' '
        /exportedFileName/ { file = $4 }
        /suggestedHumanReadableName/ {
          if (index($4, marker "_") == 1) {
            print directory "/" file
            exit
          }
        }
      ' marker="$marker" directory="$task_16_attachments" \
        "$task_16_attachments/manifest.json"
    )
    test -n "$attachment"
    cp "$attachment" "$destination"
  }
  copy_task_16_attachment \
    task-16-progress \
    "$attempt_dir/task-16-ios-app-implementation.png"
  copy_task_16_attachment \
    task-16-progress-ax5 \
    "$attempt_dir/task-16-ios-app-implementation-ax5.png"
  copy_task_16_attachment \
    task-16-progress-ax5-actions \
    "$attempt_dir/task-16-ios-app-implementation-ax5-actions.png"
  copy_task_16_attachment \
    task-16-progress-data \
    "$attempt_dir/task-16-progress.json"
  copy_task_16_attachment \
    task-16-duplicate-counts \
    "$attempt_dir/task-16-duplicate-counts.json"
  copy_task_16_attachment \
    task-16-reconciliation \
    "$attempt_dir/task-16-reconciliation.json"
  copy_task_16_attachment \
    task-16-claim \
    "$attempt_dir/task-16-claim.json"
  copy_task_16_attachment \
    task-16-offline \
    "$attempt_dir/task-16-offline.json"
  cp \
    "$repo_root/ios/qa/scenarios/task-16.json" \
    "$attempt_dir/task-16-manifest.json"
  python3 - "$attempt_dir/task-16-ios-app-implementation.json" <<'PY'
import json
import sys

with open(sys.argv[1], "w", encoding="utf-8") as output:
    json.dump(
        {
            "task": 16,
            "approvedEventLedger": "passed",
            "duplicateReceipts": "passed",
            "outOfOrderEvents": "passed",
            "multipleThresholds": "passed",
            "earnedClaimedStates": "passed",
            "offlineProjection": "pending-only",
            "accountIsolation": "passed",
            "unpublishedRewards": "hidden-and-denied",
            "foreignOwner": "denied",
            "productionIntegration": "unavailable",
            "sharingAwardSource": "deferred-to-todo-17",
            "clientOnlyAward": False
        },
        output,
        ensure_ascii=False,
        indent=2
    )
PY
  printf 'IOS_TASK_16_QA_OK\n'
  exit 0
fi

if [ "$task_number" = "17" ]; then
  task_17_simulator_id="${IOS_QA_SIMULATOR_ID:-29F51612-FF7F-4B0C-86ED-AF52AA591546}"
  task_17_destination="platform=iOS Simulator,id=$task_17_simulator_id"
  task_17_derived_data="$attempt_dir/DerivedData"
  task_17_result="$attempt_dir/task-17-ios-app-implementation.xcresult"
  task_17_home_result="$attempt_dir/task-17-home-regression.xcresult"
  task_17_attachments="$attempt_dir/task-17-attachments"
  cleanup_task_17() {
    xcrun simctl uninstall \
      "$task_17_simulator_id" \
      com.planterior.helper 2>/dev/null || true
    xcrun simctl uninstall \
      "$task_17_simulator_id" \
      com.planterior.helper.uitests.xctrunner 2>/dev/null || true
    xcrun simctl shutdown "$task_17_simulator_id" 2>/dev/null || true
  }
  trap cleanup_task_17 EXIT
  swift test \
    --package-path "$repo_root/ios/Packages/PlanteriorCore"
  xcodebuild \
    -quiet \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "$task_17_destination" \
    -derivedDataPath "$task_17_derived_data" \
    CODE_SIGNING_ALLOWED=NO \
    build-for-testing
  reboot_task_17_simulator() {
    cleanup_task_17
    xcrun simctl boot "$task_17_simulator_id"
    xcrun simctl bootstatus "$task_17_simulator_id" -b
  }
  reboot_task_17_simulator
  xcodebuild \
    -quiet \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "$task_17_destination" \
    -derivedDataPath "$task_17_derived_data" \
    -resultBundlePath "$task_17_result" \
    -parallel-testing-enabled NO \
    CODE_SIGNING_ALLOWED=NO \
    test-without-building \
    -only-testing:PlanteriorTests/MiniHomeShareRendererTests \
    -only-testing:PlanteriorTests/ShareRepositoryTests \
    -only-testing:PlanteriorUITests/ShareUITests
  reboot_task_17_simulator
  xcodebuild \
    -quiet \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "$task_17_destination" \
    -derivedDataPath "$task_17_derived_data" \
    -resultBundlePath "$task_17_home_result" \
    -parallel-testing-enabled NO \
    CODE_SIGNING_ALLOWED=NO \
    test-without-building \
    -only-testing:PlanteriorUITests/MiniHomeUITests/testEditsSavesAndRestoresCommittedRoom \
    -only-testing:PlanteriorUITests/MiniHomeUITests/testUnsavedDraftNeverAppearsOnHome \
    -only-testing:PlanteriorUITests/MiniHomeAccessibilityUITests/testEditorControlsRemainReachableAtAX5 \
    -only-testing:PlanteriorUITests/HomeDashboardUITests/testAuthenticatedHomeShowsCareMiniHomeAndPartialWeather
  xcrun xcresulttool export attachments \
    --path "$task_17_result" \
    --output-path "$task_17_attachments"
  copy_task_17_attachment() {
    marker=$1
    destination=$2
    attachment=$(
      awk -F '"' '
        /exportedFileName/ { file = $4 }
        /suggestedHumanReadableName/ {
          if (index($4, marker "_") == 1) {
            print directory "/" file
            exit
          }
        }
      ' marker="$marker" directory="$task_17_attachments" \
        "$task_17_attachments/manifest.json"
    )
    test -n "$attachment"
    cp "$attachment" "$destination"
  }
  copy_task_17_attachment \
    task-17-share \
    "$attempt_dir/task-17-ios-app-implementation.png"
  copy_task_17_attachment \
    task-17-share-ax5 \
    "$attempt_dir/task-17-ios-app-implementation-ax5.png"
  for artifact in image digest redaction link revoke expiry cancel; do
    copy_task_17_attachment \
      "task-17-share-$artifact" \
      "$attempt_dir/task-17-share-$artifact.json"
  done
  cp \
    "$repo_root/ios/qa/scenarios/task-17.json" \
    "$attempt_dir/task-17-manifest.json"
  python3 - "$attempt_dir/task-17-ios-app-implementation.json" <<'PY'
import json
import sys

with open(sys.argv[1], "w", encoding="utf-8") as output:
    json.dump(
        {
            "task": 17,
            "committedOnly": True,
            "fixedImage": "1200x1200 PNG",
            "offlineImage": "passed",
            "shareSheetCancel": "passed",
            "linkLifecycle": "provisional-fake",
            "productionIntegration": "unavailable",
            "physicalExternalDelivery": "deferred-to-todo-20",
            "privateFieldMatches": 0
        },
        output,
        ensure_ascii=False,
        indent=2
    )
PY
  printf 'IOS_TASK_17_QA_OK\n'
  exit 0
fi

if [ "$task_number" = "18" ]; then
  sim="${IOS_QA_SIMULATOR_ID:-29F51612-FF7F-4B0C-86ED-AF52AA591546}"
  destination="platform=iOS Simulator,id=$sim"
  derived="${IOS_QA_DERIVED_DATA_PATH:-$attempt_dir/DerivedData}"
  result="$attempt_dir/task-18-ios-app-implementation.xcresult"
  regression="$attempt_dir/task-18-home-regression.xcresult"
  attachments="$attempt_dir/task-18-attachments"
  cleanup_task_18() {
    xcrun simctl uninstall "$sim" com.planterior.helper 2>/dev/null || true
    xcrun simctl uninstall \
      "$sim" com.planterior.helper.uitests.xctrunner 2>/dev/null || true
    xcrun simctl uninstall \
      "$sim" com.planterior.helper.uitests.xctrunner 2>/dev/null || true
    xcrun simctl shutdown "$sim" 2>/dev/null || true
  }
  trap cleanup_task_18 EXIT
  swift test --package-path "$repo_root/ios/Packages/PlanteriorCore"
  xcodebuild -quiet \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "$destination" \
    -derivedDataPath "$derived" \
    CODE_SIGNING_ALLOWED=NO build-for-testing
  cleanup_task_18
  xcrun simctl boot "$sim"
  xcrun simctl bootstatus "$sim" -b
  xcodebuild -quiet \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "$destination" \
    -derivedDataPath "$derived" \
    -resultBundlePath "$result" \
    -parallel-testing-enabled NO \
    CODE_SIGNING_ALLOWED=NO \
    test-without-building \
    -only-testing:PlanteriorUITests/SettingsDeletionUITests
  cleanup_task_18
  xcrun simctl boot "$sim"
  xcrun simctl bootstatus "$sim" -b
  xcodebuild -quiet \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "$destination" \
    -derivedDataPath "$derived" \
    -resultBundlePath "$regression" \
    -parallel-testing-enabled NO \
    CODE_SIGNING_ALLOWED=NO \
    test-without-building \
    -only-testing:PlanteriorUITests/HomeDashboardUITests/testAuthenticatedHomeShowsCareMiniHomeAndPartialWeather \
    -only-testing:PlanteriorUITests/WeatherFlowUITests/testRegionSettingsDisclosesPurposeAndSavesManualRegion \
    -only-testing:PlanteriorUITests/ProgressionUITests/testApprovedDuplicateClaimAndOfflineReconcile
  xcrun xcresulttool export attachments \
    --path "$result" --output-path "$attachments"
  copy_task_18_attachment() {
    marker=$1
    destination_path=$2
    attachment=$(
      awk -F '"' '
        /exportedFileName/ { file = $4 }
        /suggestedHumanReadableName/ {
          if (index($4, marker "_") == 1) {
            print directory "/" file
            exit
          }
        }
      ' marker="$marker" directory="$attachments" \
        "$attachments/manifest.json"
    )
    test -n "$attachment"
    cp "$attachment" "$destination_path"
  }
  copy_task_18_attachment \
    task-18-settings \
    "$attempt_dir/task-18-ios-app-implementation.png"
  copy_task_18_attachment \
    task-18-deletion-ax5 \
    "$attempt_dir/task-18-ios-app-implementation-ax5.png"
  copy_task_18_attachment \
    task-18-deletion \
    "$attempt_dir/task-18-deletion.png"
  copy_task_18_attachment \
    task-18-settings-data \
    "$attempt_dir/task-18-settings.json"
  copy_task_18_attachment \
    task-18-deletion-data \
    "$attempt_dir/task-18-deletion.json"
  copy_task_18_attachment \
    task-18-deletion-completed-data \
    "$attempt_dir/task-18-deletion-completed.json"
  cp "$repo_root/ios/qa/scenarios/task-18.json" \
    "$attempt_dir/task-18-manifest.json"
  python3 - "$attempt_dir/task-18-ios-app-implementation.json" <<'PY'
import json
import sys
with open(sys.argv[1], "w", encoding="utf-8") as output:
    json.dump(
        {
            "task": 18,
            "settings": "passed",
            "policy": "passed",
            "deletionStateMachine": "passed",
            "cleanupOnlyAfterCompleted": True,
            "productionIntegration": "unavailable",
            "liveDeletion": "deferred-until-backend-contract"
        },
        output,
        ensure_ascii=False,
        indent=2
    )
PY
  printf 'IOS_TASK_18_QA_OK\n'
  exit 0
fi

if [ "$task_number" = "19" ]; then
  sim="${IOS_QA_SIMULATOR_ID:-29F51612-FF7F-4B0C-86ED-AF52AA591546}"
  destination="platform=iOS Simulator,id=$sim"
  derived="${IOS_QA_DERIVED_DATA_PATH:-$attempt_dir/DerivedData}"
  result="$attempt_dir/task-19-accessibility.xcresult"
  final_cleanup=0
  cleanup_task_19() {
    xcrun simctl uninstall "$sim" com.planterior.helper 2>/dev/null || true
    xcrun simctl uninstall \
      "$sim" com.planterior.helper.uitests.xctrunner 2>/dev/null || true
    if [ "$final_cleanup" = "1" ] &&
       [ "${IOS_QA_DELETE_SIMULATOR:-0}" = "1" ]; then
      xcrun simctl delete "$sim" 2>/dev/null || true
    else
      xcrun simctl shutdown "$sim" 2>/dev/null || true
    fi
  }
  trap 'final_cleanup=1; cleanup_task_19' EXIT
  swift test --package-path "$repo_root/ios/Packages/PlanteriorCore"
  plutil -lint "$repo_root/ios/Config/PrivacyInfo.xcprivacy"
  xcodebuild -quiet \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "$destination" \
    -derivedDataPath "$derived" \
    CODE_SIGNING_ALLOWED=NO build-for-testing
  cleanup_task_19
  xcrun simctl boot "$sim"
  xcrun simctl bootstatus "$sim" -b
  xcodebuild -quiet \
    -project "$repo_root/ios/Planterior.xcodeproj" \
    -scheme Planterior \
    -destination "$destination" \
    -derivedDataPath "$derived" \
    -resultBundlePath "$result" \
    -parallel-testing-enabled NO \
    CODE_SIGNING_ALLOWED=NO \
    test-without-building \
    -only-testing:PlanteriorTests/OperationalPrivacyTests \
    -only-testing:PlanteriorUITests/AppLaunchUITests/testReduceMotionLaunchContract \
    -only-testing:PlanteriorUITests/InventoryAccessibilityUITests \
    -only-testing:PlanteriorUITests/SettingsDeletionUITests/testCompletedReceiptAloneAuthorizesCleanupAtAX5 \
    -only-testing:PlanteriorUITests/OperationalAccessibilityUITests
  if grep -R -E '(BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|AIza[0-9A-Za-z_-]{20,}|ya29\.[0-9A-Za-z_-]+)' \
    "$repo_root/ios" --exclude-dir=Planterior.xcodeproj --exclude-dir=.build; then
    exit 71
  fi
  gitleaks detect \
    --source "$repo_root/ios" \
    --no-git \
    --report-format json \
    --report-path "$attempt_dir/task-19-gitleaks.json" \
    --exit-code 1
  python3 - "$attempt_dir" "$repo_root" "$derived" <<'PY'
import json
import pathlib
import sys

attempt = pathlib.Path(sys.argv[1])
repo = pathlib.Path(sys.argv[2])
derived = pathlib.Path(sys.argv[3])
resolved = json.loads(
    (repo / "ios/Planterior.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved")
    .read_text(encoding="utf-8")
)
pins = resolved.get("pins", resolved.get("object", {}).get("pins", []))
dependencies = [
    {
        "identity": pin.get("identity") or pin.get("package"),
        "location": pin.get("location") or pin.get("repositoryURL"),
        "state": pin.get("state", {})
    }
    for pin in pins
]
checkout_root = derived / "SourcePackages/checkouts"
license_files = sorted(
    path for path in checkout_root.glob("*/*")
    if path.is_file() and path.name.lower().startswith(("license", "copying"))
)
reports = {
    "task-19-event-export.json": {
        "allowedKeys": ["event", "screen", "action", "outcome"],
        "forbiddenFieldMatches": 0
    },
    "task-19-redaction.json": {"forbiddenMatches": 0},
    "task-19-retention.json": {
        "retainedAt": "23:59",
        "deletedAt": "24:00",
        "representativePreserved": True,
        "retryRecorded": True,
        "productionEnforcement": "unavailable"
    },
    "task-19-app-check.json": {
        "missingRejected": True,
        "shortRejected": True,
        "productionEnforcement": "unavailable"
    },
    "task-19-privacy.json": {
        "manifest": "PrivacyInfo.xcprivacy",
        "tracking": False,
        "requiredReason": "CA92.1"
    },
    "task-19-dependencies.json": {
        "count": len(dependencies),
        "dependencies": dependencies
    },
    "task-19-licenses.json": {
        "dependencyCount": len(dependencies),
        "licenseFileCount": len(license_files),
        "licenseFiles": [str(path) for path in license_files],
        "complete": len(license_files) >= len(dependencies)
    },
    "task-19-ios-app-implementation.json": {
        "task": 19,
        "privacyQuality": "passed",
        "accessibility": "simulator-passed",
        "backendRetention": "unavailable",
        "backendAppCheck": "unavailable"
    }
}
for name, value in reports.items():
    (attempt / name).write_text(
        json.dumps(value, ensure_ascii=False, indent=2),
        encoding="utf-8"
    )
PY
  cp "$repo_root/ios/qa/scenarios/task-19.json" \
    "$attempt_dir/task-19-manifest.json"
  printf 'IOS_TASK_19_QA_OK\n'
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
