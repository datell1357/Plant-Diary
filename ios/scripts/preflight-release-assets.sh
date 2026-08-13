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

    profile_dir=${IOS_PROVISIONING_PROFILE_DIR:-"$HOME/Library/MobileDevice/Provisioning Profiles"}
    profile_count=$(
      find "$profile_dir" -maxdepth 1 -type f 2>/dev/null |
        awk 'END { print NR + 0 }'
    )
    if [ "$profile_count" -eq 0 ]; then
      report_missing IOS_PROVISIONING_PROFILE
    else
      printf 'IOS_RELEASE_PREREQUISITE_PRESENT asset=IOS_PROVISIONING_PROFILE count=%s\n' \
        "$profile_count"
    fi
  fi
fi

if [ "$require_firebase" = true ]; then
  require_file IOS_FIREBASE_PLIST_DEV
  require_file IOS_FIREBASE_PLIST_PROD
  if [ -z "${GOOGLE_REVERSED_CLIENT_ID:-}" ]; then
    report_missing GOOGLE_REVERSED_CLIENT_ID
  else
    printf 'IOS_RELEASE_PREREQUISITE_PRESENT asset=GOOGLE_REVERSED_CLIENT_ID\n'
  fi
fi

if [ "$require_asc" = true ]; then
  require_file APP_STORE_CONNECT_API_KEY_PATH
  require_file APNS_KEY_PATH
  for variable_name in APP_STORE_CONNECT_ISSUER_ID APP_STORE_CONNECT_KEY_ID \
    APPLE_TEAM_ID APNS_KEY_ID; do
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
