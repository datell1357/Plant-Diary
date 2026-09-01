#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ios_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
repo_root=$(CDPATH= cd -- "$ios_root/.." && pwd)
derived_data=${DERIVED_DATA_PATH:-"$repo_root/.derivedData/ios"}
destination=${IOS_SIMULATOR_DESTINATION:-"platform=iOS Simulator,name=iPhone 17,OS=26.5"}
firebase_plist_path=${PLAN_FIREBASE_PLIST_PATH-}
attempt_dir=${QA_ATTEMPT_DIR:-"$repo_root/.omo/evidence/ios/task-2"}
result_bundle=${IOS_RESULT_BUNDLE_PATH:-"$attempt_dir/task-2-ios-app-implementation.xcresult"}
swiftlint_baseline="$script_dir/swiftlint-fec810e-baseline.json"

cd "$ios_root"
if [ -e "$result_bundle" ]; then
  printf 'IOS_RESULT_BUNDLE_ALREADY_EXISTS: %s\n' "$result_bundle" >&2
  exit 72
fi
mkdir -p "$attempt_dir"
mkdir -p "$(dirname -- "$result_bundle")"
xcodegen generate --spec project.yml
swiftformat --lint \
  "$ios_root/App" \
  "$ios_root/Tests" \
  "$ios_root/Packages/PlanteriorCore/Package.swift" \
  "$ios_root/Packages/PlanteriorCore/Sources" \
  "$ios_root/Packages/PlanteriorCore/Tests"
swiftlint lint \
  --strict \
  --config "$repo_root/.swiftlint.yml" \
  --baseline "$swiftlint_baseline"
swift test --no-parallel --package-path "$ios_root/Packages/PlanteriorCore"
xcodebuild \
  -resolvePackageDependencies \
  -workspace Planterior.xcworkspace \
  -scheme Planterior
xcodebuild \
  -workspace Planterior.xcworkspace \
  -scheme Planterior \
  -destination "$destination" \
  -derivedDataPath "$derived_data" \
  "PLAN_FIREBASE_PLIST_PATH=$firebase_plist_path" \
  build-for-testing
xcodebuild \
  -workspace Planterior.xcworkspace \
  -scheme Planterior \
  -destination "$destination" \
  -derivedDataPath "$derived_data" \
  -resultBundlePath "$result_bundle" \
  "PLAN_FIREBASE_PLIST_PATH=$firebase_plist_path" \
  test-without-building

if grep -R -E \
  '(BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|AIza[0-9A-Za-z_-]{20,}|ya29\.[0-9A-Za-z_-]+)' \
  "$repo_root/ios" \
  --exclude-dir=Planterior.xcodeproj \
  --exclude-dir=.derivedData \
  --exclude-dir=DerivedData \
  --exclude-dir=SourcePackages \
  --exclude-dir=build \
  --exclude-dir=.build \
  --exclude-dir=Intermediates.noindex; then
  printf 'IOS_SECRET_SCAN_FAILED\n' >&2
  exit 71
fi
printf 'IOS_SECRET_SCAN_OK\n'
