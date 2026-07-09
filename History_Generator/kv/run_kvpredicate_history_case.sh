#!/usr/bin/env bash
# Generate one kvpredicate history case with parameter overrides.
set -euo pipefail

ROOT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
REPO_ROOT=$(cd -- "$ROOT_DIR/.." && pwd)
KVPREDICATE_DIR="$ROOT_DIR/kv"
BENCHBASE_SRC="$ROOT_DIR/benchbase"

today=$(date +%Y%m%d)
CASE_NAME=${CASE_NAME:-kvpredicate_serializable_${today}}
BASE_CONFIG=${BASE_CONFIG:-}
KVPREDICATE_CONFIG=${KVPREDICATE_CONFIG:-"$KVPREDICATE_DIR/.runtime/${CASE_NAME}.xml"}
BENCHBASE_JAR=${BENCHBASE_JAR:-"$BENCHBASE_SRC/target/benchbase-postgres/benchbase-postgres/benchbase.jar"}
JAVA_ENV=${JAVA_ENV:-"$ROOT_DIR/.tools/java23.env"}
REQUESTED_PGPASSFILE=${PGPASSFILE:-}

DB_HOST=${DB_HOST:-127.0.0.1}
DB_PORT=${DB_PORT:-5432}
DB_NAME=${DB_NAME:-kvpredicate_trace_benchbase}
DB_USER=${DB_USER:-kvpredicate_user}
DB_PASSWORD=${DB_PASSWORD:-}
KVPREDICATE_DSN=${KVPREDICATE_DSN:-postgresql://$DB_USER@$DB_HOST:$DB_PORT/$DB_NAME}
if [[ -z "$REQUESTED_PGPASSFILE" && -n "$DB_PASSWORD" ]]; then
  PGPASSFILE="$KVPREDICATE_DIR/.runtime/${CASE_NAME}.pgpass"
else
  PGPASSFILE=${REQUESTED_PGPASSFILE:-"$KVPREDICATE_DIR/.runtime/pgpass"}
fi

BUILD=${BUILD:-true}
LOAD=${LOAD:-true}
EXPECTED_VERDICT=${EXPECTED_VERDICT:-}
SERIAL_ORDER=${SERIAL_ORDER:-}

if [[ -z "$BASE_CONFIG" ]]; then
  if [[ -f "$KVPREDICATE_DIR/.runtime/kvpredicate_trace.xml" ]]; then
    BASE_CONFIG="$KVPREDICATE_DIR/.runtime/kvpredicate_trace.xml"
  else
    BASE_CONFIG="$KVPREDICATE_DIR/config/kvpredicate_trace.xml"
  fi
fi

if [[ -f "$JAVA_ENV" ]]; then
  # shellcheck disable=SC1090
  source "$JAVA_ENV"
fi

if [[ -z "$REQUESTED_PGPASSFILE" && -n "$DB_PASSWORD" ]]; then
  PGPASSFILE="$KVPREDICATE_DIR/.runtime/${CASE_NAME}.pgpass"
else
  PGPASSFILE=${REQUESTED_PGPASSFILE:-"$KVPREDICATE_DIR/.runtime/pgpass"}
fi

mkdir -p "$(dirname -- "$KVPREDICATE_CONFIG")"

if [[ -n "$DB_PASSWORD" ]]; then
  printf '%s:%s:%s:%s:%s\n' "$DB_HOST" "$DB_PORT" "$DB_NAME" "$DB_USER" "$DB_PASSWORD" > "$PGPASSFILE"
  chmod 600 "$PGPASSFILE"
fi

export DB_HOST
export DB_PORT
export DB_NAME
export DB_USER
export DB_PASSWORD

python3 - "$BASE_CONFIG" "$KVPREDICATE_CONFIG" <<'PY'
import os
import sys
import xml.etree.ElementTree as ET

base_config, out_config = sys.argv[1], sys.argv[2]
tree = ET.parse(base_config)
root = tree.getroot()

def set_text(path, env_name):
    value = os.environ.get(env_name)
    if value is None or value == "":
        return
    elem = ensure(path)
    elem.text = value

def ensure(path):
    parts = path.split("/")
    elem = root
    for part in parts:
        child = elem.find(part)
        if child is None:
            child = ET.SubElement(elem, part)
        elem = child
    return elem

overrides = {
    "isolation": "ISOLATION",
    "scalefactor": "SCALEFACTOR",
    "keyCount": "KEY_COUNT",
    "keyDist": "KEY_DIST",
    "keyDistBase": "KEY_DIST_BASE",
    "minTxnLength": "MIN_TXN_LENGTH",
    "maxTxnLength": "MAX_TXN_LENGTH",
    "maxWritesPerKey": "MAX_WRITES_PER_KEY",
    "predicateGroupCount": "PREDICATE_GROUP_COUNT",
    "mopDelayMs": "MOP_DELAY_MS",
    "kvPredicateAnomaly": "KV_PREDICATE_ANOMALY",
    "kvPredicateAnomalyDelayMs": "KV_PREDICATE_ANOMALY_DELAY_MS",
    "terminals": "TERMINALS",
    "works/work/time": "TIME_SECONDS",
    "works/work/rate": "RATE",
}

for path, env_name in overrides.items():
    set_text(path, env_name)

if os.environ.get("DB_HOST") or os.environ.get("DB_NAME") or os.environ.get("DB_USER"):
    host = os.environ.get("DB_HOST", "127.0.0.1")
    port = os.environ.get("DB_PORT", "5432")
    name = os.environ.get("DB_NAME", "kvpredicate_trace_benchbase")
    user = os.environ.get("DB_USER", "kvpredicate_user")
    ensure("url").text = (
        f"jdbc:postgresql://{host}:{port}/{name}"
        "?sslmode=disable&ApplicationName=benchbase-kvpredicate-ser-trace"
        "&reWriteBatchedInserts=true"
    )
    ensure("username").text = user
    if os.environ.get("DB_PASSWORD"):
        set_text("password", "DB_PASSWORD")

tree.write(out_config, encoding="utf-8", xml_declaration=True)
PY

if [[ "$BUILD" == "true" || ! -f "$BENCHBASE_JAR" ]]; then
  (
    cd "$BENCHBASE_SRC"
    ./mvnw -q -DskipTests -Dfmt.skip=true -Ddescriptors=src/main/assembly/dir.xml -P postgres package
    if [[ -f target/benchbase-postgres.zip ]]; then
      unzip -oq target/benchbase-postgres.zip -d target
    fi
  )
fi

export BENCHBASE_JAR
export KVPREDICATE_DSN
export KVPREDICATE_CONFIG
export CASE_NAME
export PGPASSFILE
export EXPECTED_VERDICT
export SERIAL_ORDER

run_args=()
if [[ "$LOAD" == "true" ]]; then
  run_args+=(--load)
fi

"$KVPREDICATE_DIR/run_kvpredicate_trace.sh" "${run_args[@]}"

case_dir="$REPO_ROOT/PolySIHistories/kvpredicate/$CASE_NAME/hist-00000"

echo "Runtime config: $KVPREDICATE_CONFIG"
echo "PRHIST case: $case_dir"
