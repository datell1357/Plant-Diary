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

if [ "$task_number" != "1" ]; then
  printf 'IOS_QA_TASK_NOT_IMPLEMENTED task=%s\n' "$task_number" >&2
  exit 69
fi

if [ -z "$attempt_dir" ]; then
  printf 'IOS_QA_USAGE_ERROR missing_attempt_dir\n' >&2
  exit 64
fi

mkdir -p "$attempt_dir"
evidence="$attempt_dir/task-1-ios-app-implementation.log"
exec >"$evidence" 2>&1

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/../.." && pwd)
android_root=/Users/yeoreum/Documents/Planterior_Helper-worktrees/feat-android-app

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
