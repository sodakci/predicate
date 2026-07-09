#!/usr/bin/env bash
# Build/load/trace/export orchestration for BenchBase kvpredicate on PostgreSQL.
set -euo pipefail

ROOT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
REPO_ROOT=$(cd -- "$ROOT_DIR/.." && pwd)
KVPREDICATE_DIR="$ROOT_DIR/kv"
LOAD=false

usage() {
  echo "usage: $0 [--load]" >&2
  echo "  --load  ask BenchBase to create/load its kv table before tracing" >&2
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
KVPREDICATE_DSN=${KVPREDICATE_DSN:?Set KVPREDICATE_DSN, preferably using a local .pgpass entry}
KVPREDICATE_CONFIG=${KVPREDICATE_CONFIG:?Set KVPREDICATE_CONFIG to a local, credential-bearing kvpredicate XML config}
CASE_NAME=${CASE_NAME:-kvpredicate_real_postgresql_sf1_s4_serializable}
EXPECTED_VERDICT=${EXPECTED_VERDICT:-}
SERIAL_ORDER=${SERIAL_ORDER:-}

if [[ ! -f "$BENCHBASE_JAR" ]]; then
  echo "missing BENCHBASE_JAR: $BENCHBASE_JAR" >&2
  exit 2
fi
if [[ ! -f "$KVPREDICATE_CONFIG" ]]; then
  echo "missing KVPREDICATE_CONFIG: $KVPREDICATE_CONFIG" >&2
  exit 2
fi
BENCHBASE_HOME=$(cd -- "$(dirname -- "$BENCHBASE_JAR")" && pwd)

run_benchbase() {
  (cd "$BENCHBASE_HOME" && java "$@")
}

if [[ "$LOAD" == true ]]; then
  run_benchbase -jar "$BENCHBASE_JAR" -b kvpredicate -c "$KVPREDICATE_CONFIG" --create=true --load=true --execute=false
fi

psql "$KVPREDICATE_DSN" -v ON_ERROR_STOP=1 -f "$KVPREDICATE_DIR/sql/01_install_kvpredicate_trace.sql"
psql "$KVPREDICATE_DSN" -v ON_ERROR_STOP=1 -c 'SELECT ser_kvpredicate_trace.snapshot_initial_state();'

run_benchbase -Dser.kvpredicate.trace=true -jar "$BENCHBASE_JAR" -b kvpredicate -c "$KVPREDICATE_CONFIG" --create=false --load=false --execute=true

CASE_DIR="$REPO_ROOT/PolySIHistories/kvpredicate/$CASE_NAME/hist-00000"
mkdir -p "$CASE_DIR"
psql "$KVPREDICATE_DSN" -X -qAt -v ON_ERROR_STOP=1 -f "$KVPREDICATE_DIR/sql/02_export_kvpredicate_trace.sql" > "$CASE_DIR/raw_kvpredicate_trace.jsonl"
CONVERT_ARGS=(--raw "$CASE_DIR/raw_kvpredicate_trace.jsonl" --case-dir "$CASE_DIR")
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
python3 "$KVPREDICATE_DIR/kvpredicate_trace_to_prhist.py" "${CONVERT_ARGS[@]}"
python3 "$KVPREDICATE_DIR/audit_kvpredicate_prhist.py" "$CASE_DIR"

echo "PRHIST case: $CASE_DIR"
