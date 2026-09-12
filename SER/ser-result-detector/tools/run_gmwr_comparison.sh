#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec python3 "$ROOT/tools/run_gmwr_comparison.py" --project-root "$ROOT" "$@"
