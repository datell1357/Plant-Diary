#!/usr/bin/env bash
set -euo pipefail

ALGORITHM_VERSION="planterior-binary-patch-v1"
MANIFEST_ALGORITHM_VERSION="planterior-sorted-source-manifest-v1"
SCRIPT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPOSITORY="$SCRIPT_ROOT"
WITH_MANIFEST=false

usage() {
    cat <<'EOF'
Usage: scripts/verify-source-fingerprint.sh [--repository PATH] [--with-manifest]

The payload is exactly the concatenation of:
  1. git diff --binary HEAD
  2. git diff --binary --no-index /dev/null PATH for every non-ignored
     untracked path, sorted bytewise

The payload SHA-256 is the canonical repository fingerprint. The optional
source manifest is reported under a different algorithm and label.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --repository)
            [[ $# -ge 2 ]] || { usage >&2; exit 2; }
            REPOSITORY="$2"
            shift 2
            ;;
        --with-manifest)
            WITH_MANIFEST=true
            shift
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            usage >&2
            exit 2
            ;;
    esac
done

REPOSITORY="$(git -C "$REPOSITORY" rev-parse --show-toplevel)"
HEAD_REVISION="$(git -C "$REPOSITORY" rev-parse HEAD)"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/planterior-fingerprint.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT
PAYLOAD="$TMP_DIR/payload.bin"
UNTRACKED="$TMP_DIR/untracked.zlist"
SORTED_UNTRACKED="$TMP_DIR/untracked-sorted.zlist"

# Keep this construction aligned with the documented canonical brace pipeline.
git -C "$REPOSITORY" diff --binary HEAD > "$PAYLOAD"
git -C "$REPOSITORY" ls-files -z --others --exclude-standard > "$UNTRACKED"
python3 - "$UNTRACKED" "$SORTED_UNTRACKED" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1]).read_bytes().split(b"\0")
paths = sorted(path for path in source if path)
Path(sys.argv[2]).write_bytes(b"".join(path + b"\0" for path in paths))
PY

while IFS= read -r -d '' relative_path; do
    status=0
    (
        cd "$REPOSITORY"
        git diff --binary --no-index -- /dev/null "$relative_path"
    ) >> "$PAYLOAD" || status=$?
    [[ "$status" -eq 1 ]] || {
        printf 'fingerprint failed while diffing untracked path: %s\n' "$relative_path" >&2
        exit 1
    }
done < "$SORTED_UNTRACKED"

PAYLOAD_SHA256="$(shasum -a 256 "$PAYLOAD" | awk '{print $1}')"
printf 'fingerprint-algorithm %s\n' "$ALGORITHM_VERSION"
printf 'head %s\n' "$HEAD_REVISION"
printf 'payload-sha256 %s\n' "$PAYLOAD_SHA256"

if [[ "$WITH_MANIFEST" == true ]]; then
    MANIFEST="$TMP_DIR/source-manifest.txt"
    MANIFEST_COUNT="$TMP_DIR/source-manifest-count.txt"
    python3 - "$REPOSITORY" "$MANIFEST" "$MANIFEST_COUNT" <<'PY'
from pathlib import Path
import hashlib
import os
import subprocess
import sys

root = Path(sys.argv[1])
output = Path(sys.argv[2])
count_output = Path(sys.argv[3])
raw_paths = subprocess.check_output(
    ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
    cwd=root,
)
paths = sorted(path for path in raw_paths.split(b"\0") if path)
existing_paths = []
with output.open("wb") as manifest:
    for raw_path in paths:
        source = root / os.fsdecode(raw_path)
        if not source.exists() and not source.is_symlink():
            continue
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        manifest.write(digest.encode("ascii") + b"  " + raw_path + b"\n")
        existing_paths.append(raw_path)
count_output.write_text(str(len(existing_paths)), encoding="ascii")
PY
    MANIFEST_FILES="$(cat "$MANIFEST_COUNT")"
    MANIFEST_SHA256="$(shasum -a 256 "$MANIFEST" | awk '{print $1}')"
    printf 'manifest-algorithm %s\n' "$MANIFEST_ALGORITHM_VERSION"
    printf 'manifest-files %s\n' "$MANIFEST_FILES"
    printf 'manifest-sha256 %s\n' "$MANIFEST_SHA256"
fi
