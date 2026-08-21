#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

"$ROOT_DIR/scripts/verify-source-fingerprint.sh" --repository "$ROOT_DIR" --with-manifest
"$ROOT_DIR/scripts/verify-secrets.sh" --repository "$ROOT_DIR"
