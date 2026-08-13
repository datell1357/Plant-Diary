#!/bin/sh
set -eu

require_device=false
require_signing=false
require_firebase=false
require_asc=false

while [ "$#" -gt 0 ]; do
  case "$1" in
    --require-device) require_device=true ;;
    --require-signing) require_signing=true ;;
    --require-firebase) require_firebase=true ;;
    --require-asc) require_asc=true ;;
    *)
      printf 'IOS_PREFLIGHT_USAGE_ERROR unknown_argument=%s\n' "$1" >&2
      exit 64
      ;;
  esac
  shift
done

missing=0

report_missing() {
  printf 'IOS_RELEASE_PREREQUISITE_MISSING asset=%s\n' "$1" >&2
  missing=1
}

require_file() {
  variable_name=$1
  eval "file_path=\${$variable_name:-}"
  if [ -z "$file_path" ] || [ ! -f "$file_path" ]; then
    report_missing "$variable_name"
    return
  fi
  printf 'IOS_RELEASE_PREREQUISITE_PRESENT asset=%s file=%s\n' \
    "$variable_name" "$(basename "$file_path")"
}

verify_firebase_plist() {
  variable_name=$1
  eval "plist_path=\${$variable_name:-}"
  if [ -z "$plist_path" ] || [ ! -f "$plist_path" ]; then
    report_missing "$variable_name"
    return
  fi

  bundle_id=$(plutil -extract BUNDLE_ID raw -o - "$plist_path" 2>/dev/null || true)
  project_id=$(plutil -extract PROJECT_ID raw -o - "$plist_path" 2>/dev/null || true)
  app_id=$(plutil -extract GOOGLE_APP_ID raw -o - "$plist_path" 2>/dev/null || true)
  reversed_client_id=$(
    plutil -extract REVERSED_CLIENT_ID raw -o - "$plist_path" 2>/dev/null || true
  )

  if [ "$bundle_id" != "com.planterior.helper" ]; then
    report_missing "$variable_name:BUNDLE_ID"
    return
  fi
  if [ -z "$project_id" ] || [ -z "$app_id" ] || [ -z "$reversed_client_id" ]; then
    report_missing "$variable_name:FIREBASE_METADATA"
    return
  fi
  if ! command -v firebase >/dev/null 2>&1; then
    report_missing FIREBASE_CLI
    return
  fi

  apps_json=$(mktemp)
  trap 'rm -f "$apps_json"' EXIT HUP INT TERM
  if ! firebase apps:list IOS --project "$project_id" --json >"$apps_json" 2>/dev/null; then
    report_missing "$variable_name:FIREBASE_PROJECT_ACCESS"
    rm -f "$apps_json"
    trap - EXIT HUP INT TERM
    return
  fi
  if ! jq -e \
    --arg app_id "$app_id" \
    --arg bundle_id "$bundle_id" \
    '.result[]? | select(.appId == $app_id and .platform == "IOS" and .metadata.bundleId == $bundle_id)' \
    "$apps_json" >/dev/null; then
    report_missing "$variable_name:FIREBASE_IOS_APP_REGISTRATION"
    rm -f "$apps_json"
    trap - EXIT HUP INT TERM
    return
  fi
  rm -f "$apps_json"
  trap - EXIT HUP INT TERM

  printf 'IOS_RELEASE_PREREQUISITE_PRESENT asset=%s project=%s\n' \
    "$variable_name" "$project_id"
}

if [ "$require_device" = true ]; then
  if [ -z "${IOS_PHYSICAL_UDID:-}" ]; then
    report_missing IOS_PHYSICAL_UDID
  elif ! xcrun xctrace list devices 2>/dev/null |
    awk -v udid="$IOS_PHYSICAL_UDID" '
      /^== Devices ==/ { in_devices = 1; next }
      /^== Simulators ==/ { in_devices = 0 }
      in_devices && index($0, udid) { found = 1 }
      END { exit(found ? 0 : 1) }
    '; then
    report_missing IOS_PHYSICAL_UDID
  else
    printf 'IOS_RELEASE_PREREQUISITE_PRESENT asset=IOS_PHYSICAL_UDID\n'
  fi
fi

if [ "$require_signing" = true ]; then
  if [ "${QA_HIDE_SIGNING:-0}" = "1" ]; then
    report_missing IOS_CODESIGN_IDENTITY
    report_missing IOS_PROVISIONING_PROFILE
  else
    identity_count=$(
      security find-identity -v -p codesigning 2>/dev/null |
        awk '$1 ~ /^[0-9]+\)/ { count += 1 } END { print count + 0 }'
    )
    if [ "$identity_count" -eq 0 ]; then
      report_missing IOS_CODESIGN_IDENTITY
    else
      printf 'IOS_RELEASE_PREREQUISITE_PRESENT asset=IOS_CODESIGN_IDENTITY count=%s\n' \
        "$identity_count"
    fi

    if [ -n "${IOS_PROVISIONING_PROFILE_DIR:-}" ]; then
      profile_roots=$IOS_PROVISIONING_PROFILE_DIR
    else
      profile_roots="$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles
$HOME/Library/MobileDevice/Provisioning Profiles"
    fi
    profile_count=0
    old_ifs=$IFS
    IFS='
'
    for profile_dir in $profile_roots; do
      count=$(
        find "$profile_dir" -maxdepth 1 -type f 2>/dev/null |
          awk 'END { print NR + 0 }'
      )
      profile_count=$((profile_count + count))
    done
    IFS=$old_ifs
    if [ "$profile_count" -eq 0 ]; then
      report_missing IOS_PROVISIONING_PROFILE
    else
      printf 'IOS_RELEASE_PREREQUISITE_PRESENT asset=IOS_PROVISIONING_PROFILE count=%s\n' \
        "$profile_count"
    fi
  fi
fi

if [ "$require_firebase" = true ]; then
  verify_firebase_plist IOS_FIREBASE_PLIST_DEV
  verify_firebase_plist IOS_FIREBASE_PLIST_PROD
fi

if [ "$require_asc" = true ]; then
  require_file APP_STORE_CONNECT_API_KEY_PATH
  require_file APNS_KEY_PATH
  for variable_name in APP_STORE_CONNECT_ISSUER_ID APP_STORE_CONNECT_KEY_ID \
    APP_STORE_CONNECT_APP_ID APPLE_TEAM_ID APNS_KEY_ID; do
    eval "variable_value=\${$variable_name:-}"
    if [ -z "$variable_value" ]; then
      report_missing "$variable_name"
    else
      printf 'IOS_RELEASE_PREREQUISITE_PRESENT asset=%s\n' "$variable_name"
    fi
  done
fi

if [ "$missing" -ne 0 ]; then
  exit 65
fi

printf 'IOS_RELEASE_PREREQUISITES_OK\n'
