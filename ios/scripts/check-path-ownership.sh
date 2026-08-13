#!/bin/sh
set -eu

if [ "$#" -eq 0 ]; then
  printf 'IOS_OWNERSHIP_USAGE_ERROR expected_path\n' >&2
  exit 64
fi

for path in "$@"; do
  case "$path" in
    ios/* | docs/ios/* | .github/workflows/ios.yml | .omo/boulder.json | .gitignore)
      ;;
    *)
      printf 'IOS_OWNERSHIP_VIOLATION path=%s\n' "$path" >&2
      exit 66
      ;;
  esac
done

printf 'IOS_OWNERSHIP_OK count=%s\n' "$#"
