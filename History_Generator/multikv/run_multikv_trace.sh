#!/usr/bin/env bash
# Load/trace/export orchestration for BenchBase multikv on PostgreSQL.
set -euo pipefail

ROOT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
REPO_ROOT=$(cd -- "$ROOT_DIR/.." && pwd)
MULTIKV_DIR="$ROOT_DIR/multikv"
LOAD=false

usage() {
  echo "usage: $0 [--load]" >&2
  echo "  --load  ask BenchBase to create/load users, items, and orders before tracing" >&2
}

while (($#)); do
  case "$1" in
    --load) LOAD=true ;;
    -h|--help) usage; exit 0 ;;
    *) usage; exit 2 ;;
  esac
  shift
done

BENCHBASE_JAR=${BENCHBASE_JAR:?Set BENCHBASE_JAR to the built BenchBase PostgreSQL distribution jar}
MULTIKV_DSN=${MULTIKV_DSN:?Set MULTIKV_DSN, preferably using a local .pgpass entry}
MULTIKV_CONFIG=${MULTIKV_CONFIG:?Set MULTIKV_CONFIG to a local, credential-bearing multikv XML config}
CASE_NAME=${CASE_NAME:-multikv_real_postgresql_repeatable_read}
MULTIKV_ANOMALY=${MULTIKV_ANOMALY:-write-skew}
MULTIKV_TRANSACTION_COUNT=${MULTIKV_TRANSACTION_COUNT:-0}
EXPECTED_VERDICT=${EXPECTED_VERDICT:-}
SERIAL_ORDER=${SERIAL_ORDER:-}

if [[ ! "$MULTIKV_TRANSACTION_COUNT" =~ ^[0-9]+$ ]]; then
  echo "MULTIKV_TRANSACTION_COUNT must be a non-negative integer" >&2
  exit 2
fi
TRANSACTION_COUNT_NUM=$((10#$MULTIKV_TRANSACTION_COUNT))

case "$MULTIKV_ANOMALY" in
  none|write-skew|lost-update) ;;
  *)
    echo "MULTIKV_ANOMALY must be none, write-skew, or lost-update" >&2
    exit 2
    ;;
esac

if [[ ! -f "$BENCHBASE_JAR" ]]; then
  echo "missing BENCHBASE_JAR: $BENCHBASE_JAR" >&2
  exit 2
fi
if [[ ! -f "$MULTIKV_CONFIG" ]]; then
  echo "missing MULTIKV_CONFIG: $MULTIKV_CONFIG" >&2
  exit 2
fi
BENCHBASE_HOME=$(cd -- "$(dirname -- "$BENCHBASE_JAR")" && pwd)

run_benchbase() {
  (cd "$BENCHBASE_HOME" && java "$@")
}

if [[ "$LOAD" == true ]]; then
  run_benchbase -jar "$BENCHBASE_JAR" -b multikv -c "$MULTIKV_CONFIG" --create=true --load=true --execute=false
fi

psql "$MULTIKV_DSN" -v ON_ERROR_STOP=1 -f "$MULTIKV_DIR/sql/01_install_multikv_trace.sql"
psql "$MULTIKV_DSN" -v ON_ERROR_STOP=1 -c 'SELECT ser_multikv_trace.snapshot_initial_state();'

run_benchbase -Dser.multikv.trace=true -jar "$BENCHBASE_JAR" -b multikv -c "$MULTIKV_CONFIG" --create=false --load=false --execute=true

if ((TRANSACTION_COUNT_NUM > 0)); then
  trace_counts=$(
    psql "$MULTIKV_DSN" -X -qAt -v ON_ERROR_STOP=1 -c \
      "SELECT count(*) || '|' ||
              count(*) FILTER (WHERE commit_observed_ts IS NOT NULL)
       FROM ser_multikv_trace.trace_txn"
  )
  expected_counts="${TRANSACTION_COUNT_NUM}|${TRANSACTION_COUNT_NUM}"
  if [[ "$trace_counts" != "$expected_counts" ]]; then
    echo "expected committed multikv trace counts $expected_counts, got $trace_counts" >&2
    exit 1
  fi
fi

CASE_DIR="$REPO_ROOT/predicateHistories/multikv/$CASE_NAME/hist-00000"
mkdir -p "$CASE_DIR"
psql "$MULTIKV_DSN" -X -qAt -v ON_ERROR_STOP=1 -f "$MULTIKV_DIR/sql/02_export_multikv_trace.sql" > "$CASE_DIR/raw_multikv_trace.jsonl"

CONVERT_ARGS=(
  --raw "$CASE_DIR/raw_multikv_trace.jsonl"
  --case-dir "$CASE_DIR"
  --anomaly "$MULTIKV_ANOMALY"
)
if [[ -n "$EXPECTED_VERDICT" ]]; then
  CONVERT_ARGS+=(--expected-verdict "$EXPECTED_VERDICT")
fi
if [[ "$EXPECTED_VERDICT" == "ACCEPT" ]]; then
  if [[ -z "$SERIAL_ORDER" ]]; then
    echo "EXPECTED_VERDICT=ACCEPT requires externally verified SERIAL_ORDER='txn-id txn-id ...'" >&2
    exit 2
  fi
  read -r -a SERIAL_ORDER_IDS <<< "$SERIAL_ORDER"
  CONVERT_ARGS+=(--serial-order "${SERIAL_ORDER_IDS[@]}")
fi

python3 "$MULTIKV_DIR/multikv_trace_to_prhist.py" "${CONVERT_ARGS[@]}"
python3 "$MULTIKV_DIR/audit_multikv_join_history.py" "$CASE_DIR"

if ((TRANSACTION_COUNT_NUM > 0)); then
  python3 - "$CASE_DIR/manifest.json" "$TRANSACTION_COUNT_NUM" <<'PY'
import json
import sys

manifest_path, expected_text = sys.argv[1], sys.argv[2]
with open(manifest_path, encoding="utf-8") as handle:
    actual = json.load(handle).get("transactions")
expected = int(expected_text)
if actual != expected:
    raise SystemExit(
        f"expected manifest transactions={expected}, got {actual!r}"
    )
PY
fi

echo "PRHIST case: $CASE_DIR"
