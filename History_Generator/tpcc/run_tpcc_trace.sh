#!/usr/bin/env bash
# Build/load/trace/export orchestration.  It intentionally requires explicit
# DB credentials and a supplied BenchBase jar; it never creates users or saves
# passwords.  See README.md for the first-run PostgreSQL prerequisites.
set -euo pipefail

ROOT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
REPO_ROOT=$(cd -- "$ROOT_DIR/.." && pwd)
TPCC_DIR="$ROOT_DIR/tpcc"
BENCHBASE_DIR="$ROOT_DIR/benchbase"
LOAD=false

usage() {
  echo "usage: $0 [--load]" >&2
  echo "  --load  ask BenchBase to create/load its normal TPC-C tables before tracing" >&2
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
TPCC_DSN=${TPCC_DSN:?Set TPCC_DSN, preferably using a local .pgpass entry}
BENCHBASE_CONFIG=${BENCHBASE_CONFIG:?Set BENCHBASE_CONFIG to a local, credential-bearing TPC-C XML config}
CASE_NAME=${CASE_NAME:-tpcc_neworder_payment_stocklevel_sf1_s2_serializable}
EXPECTED_VERDICT=${EXPECTED_VERDICT:-}
SERIAL_ORDER=${SERIAL_ORDER:-}

if [[ ! -f "$BENCHBASE_JAR" ]]; then
  echo "missing BENCHBASE_JAR: $BENCHBASE_JAR" >&2
  exit 2
fi
if [[ ! -f "$BENCHBASE_CONFIG" ]]; then
  echo "missing BENCHBASE_CONFIG: $BENCHBASE_CONFIG" >&2
  exit 2
fi

if [[ "$LOAD" == true ]]; then
  java -jar "$BENCHBASE_JAR" -b tpcc -c "$BENCHBASE_CONFIG" --create=true --load=true --execute=false
fi

psql "$TPCC_DSN" -v ON_ERROR_STOP=1 -f "$TPCC_DIR/sql/01_install_tpcc_trace.sql"
psql "$TPCC_DSN" -v ON_ERROR_STOP=1 -c 'SELECT ser_tpcc_trace.snapshot_initial_state();'

java -Dser.tpcc.trace=true -jar "$BENCHBASE_JAR" -b tpcc -c "$BENCHBASE_CONFIG" --create=false --load=false --execute=true

CASE_DIR="$REPO_ROOT/PolySIHistories/tpcc/$CASE_NAME/hist-00000"
mkdir -p "$CASE_DIR"
psql "$TPCC_DSN" -X -qAt -v ON_ERROR_STOP=1 -f "$TPCC_DIR/sql/02_export_tpcc_trace.sql" > "$CASE_DIR/raw_tpcc_trace.jsonl"
CONVERT_ARGS=(--raw "$CASE_DIR/raw_tpcc_trace.jsonl" --case-dir "$CASE_DIR")
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
python3 "$TPCC_DIR/tpcc_trace_to_prhist.py" "${CONVERT_ARGS[@]}"
python3 "$TPCC_DIR/audit_tpcc_prhist.py" "$CASE_DIR"

echo "PRHIST case: $CASE_DIR"
