#!/usr/bin/env bash
set -euo pipefail

ALGORITHM_VERSION="planterior-gitleaks-history-and-source-v1"
SCRIPT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPOSITORY="$SCRIPT_ROOT"
GITLEAKS_BIN="${GITLEAKS_BIN:-gitleaks}"

usage() {
    cat <<'EOF'
Usage: scripts/verify-secrets.sh [--repository PATH] [--gitleaks-bin PATH]

Scans both complete Git history and an exact current-source snapshot containing
tracked plus untracked, non-ignored files. Ignored build outputs are not copied
into the current-source snapshot.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --repository)
            [[ $# -ge 2 ]] || { usage >&2; exit 2; }
            REPOSITORY="$2"
            shift 2
            ;;
        --gitleaks-bin)
            [[ $# -ge 2 ]] || { usage >&2; exit 2; }
            GITLEAKS_BIN="$2"
            shift 2
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
CONFIG="$REPOSITORY/.gitleaks.toml"
[[ -f "$CONFIG" ]] || { printf 'missing Gitleaks config: %s\n' "$CONFIG" >&2; exit 1; }
command -v "$GITLEAKS_BIN" > /dev/null 2>&1 || {
    printf 'gitleaks executable is unavailable: %s\n' "$GITLEAKS_BIN" >&2
    exit 1
}

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/planterior-secret-scan.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT
SNAPSHOT="$TMP_DIR/source"
COUNT_FILE="$TMP_DIR/source-count.txt"
mkdir -p "$SNAPSHOT"

printf 'secret-scan-algorithm %s\n' "$ALGORITHM_VERSION"
printf 'secret-scan-history start\n'
"$GITLEAKS_BIN" git "$REPOSITORY" \
    --no-banner \
    --redact=100 \
    --verbose \
    --config "$CONFIG"
printf 'secret-scan-history exit=0\n'

python3 - "$REPOSITORY" "$SNAPSHOT" "$COUNT_FILE" <<'PY'
from pathlib import Path
import os
import shutil
import subprocess
import sys

root = Path(sys.argv[1])
snapshot = Path(sys.argv[2])
count_output = Path(sys.argv[3])
raw_paths = subprocess.check_output(
    ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
    cwd=root,
)
paths = [path for path in raw_paths.split(b"\0") if path]
copied = 0
for raw_path in paths:
    relative_path = os.fsdecode(raw_path)
    source = root / relative_path
    if not source.exists() and not source.is_symlink():
        continue
    destination = snapshot / relative_path
    destination.parent.mkdir(parents=True, exist_ok=True)
    if source.is_symlink():
        os.symlink(os.readlink(source), destination)
    else:
        shutil.copyfile(source, destination)
    copied += 1
count_output.write_text(str(copied), encoding="ascii")
PY

SOURCE_COUNT="$(cat "$COUNT_FILE")"
printf 'secret-scan-current start files=%s\n' "$SOURCE_COUNT"
"$GITLEAKS_BIN" dir "$SNAPSHOT" \
    --no-banner \
    --redact=100 \
    --verbose \
    --config "$CONFIG"
printf 'secret-scan-current exit=0 files=%s\n' "$SOURCE_COUNT"
