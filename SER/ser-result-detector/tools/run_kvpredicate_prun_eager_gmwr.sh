#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
WORKSPACE_DIR=$(cd "$PROJECT_DIR/../.." && pwd)
HISTORY_ROOT="$WORKSPACE_DIR/predicateHistories/kvpredicate/test"

if [[ $# -gt 1 ]]; then
  echo "Usage: $0 [output-dir]" >&2
  exit 2
fi

OUTPUT_DIR=${1:-"$PROJECT_DIR/results/kvpredicate-reachability-eager-gmwr"}

exec "$SCRIPT_DIR/run_gmwr_comparison.sh" \
  "$HISTORY_ROOT" \
  --out-dir "$OUTPUT_DIR" \
  --ww-pruning REACHABILITY \
  --xmx 5g \
  --timeout-seconds 600 \
  --min-available-memory-mb 2048 \
  --mode EAGER \
  --mode GMWR
