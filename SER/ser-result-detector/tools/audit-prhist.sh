#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
JAR_PATH=${SER_RESULT_DETECTOR_JAR:-${PREDICATE_SER_JAR:-${POLYSI_JAR:-"$PROJECT_DIR/build/libs/ser-result-detector-1.0.0-SNAPSHOT.jar"}}}
MONOSAT_NATIVE_DIR=${MONOSAT_NATIVE_DIR:-"$PROJECT_DIR/build/monosat"}
HEAP_SIZE=${SER_RESULT_DETECTOR_HEAP:-${PREDICATE_SER_HEAP:-${POLYSI_HEAP:-8g}}}
OUTPUT_ROOT=${SER_RESULT_DETECTOR_OUTPUT_DIR:-${PREDICATE_SER_OUTPUT_DIR:-${POLYSI_OUTPUT_DIR:-"/tmp/ser-result-detector-prhist-audit"}}}

usage() {
  cat <<'EOF'
Usage:
  tools/audit-prhist.sh <history-or-root-dir>

Environment variables:
  SER_RESULT_DETECTOR_JAR         Override the detector jar path.
  MONOSAT_NATIVE_DIR              Directory containing libmonosat.so.
  SER_RESULT_DETECTOR_HEAP        JVM heap size passed via -Xmx. Default: 8g
  SER_RESULT_DETECTOR_JAVA_OPTS   Extra JVM options, split on spaces.
  SER_RESULT_DETECTOR_OUTPUT_DIR  Directory for audit logs. Default: /tmp/ser-result-detector-prhist-audit

Examples:
  tools/audit-prhist.sh ../PolySIHistories/predicate_high_intensity
  SER_RESULT_DETECTOR_HEAP=12g tools/audit-prhist.sh ../PolySIHistories/predicate_high_intensity/search_32_420_18_10000_0.5_hotspot/hist-00000
EOF
}

if [[ $# -ne 1 ]]; then
  usage >&2
  exit 2
fi

if [[ ! -f "$JAR_PATH" ]]; then
  echo "ser-result-detector jar not found: $JAR_PATH" >&2
  exit 2
fi

if [[ ! -f "$MONOSAT_NATIVE_DIR/libmonosat.so" && ! -f "$MONOSAT_NATIVE_DIR/libmonosat.dylib" && ! -f "$MONOSAT_NATIVE_DIR/monosat.dll" ]]; then
  echo "MonoSAT native library not found in: $MONOSAT_NATIVE_DIR" >&2
  echo "Run ./gradlew jar first, or set MONOSAT_NATIVE_DIR." >&2
  exit 2
fi

INPUT_PATH=$(realpath "$1")
if [[ ! -e "$INPUT_PATH" ]]; then
  echo "Input path not found: $INPUT_PATH" >&2
  exit 2
fi

declare -a TARGETS=()
if [[ -f "$INPUT_PATH/history.prhist.jsonl" ]]; then
  TARGETS=("$INPUT_PATH")
elif [[ -f "$INPUT_PATH" && "$(basename "$INPUT_PATH")" == "history.prhist.jsonl" ]]; then
  TARGETS=("$(dirname "$INPUT_PATH")")
else
  while IFS= read -r history_file; do
    TARGETS+=("$(dirname "$history_file")")
  done < <(find "$INPUT_PATH" -type f -name history.prhist.jsonl | sort)
fi

if [[ ${#TARGETS[@]} -eq 0 ]]; then
  echo "No predicate histories found under: $INPUT_PATH" >&2
  exit 2
fi

mkdir -p "$OUTPUT_ROOT"

declare -a EXTRA_JAVA_OPTS=()
JAVA_OPTS=${SER_RESULT_DETECTOR_JAVA_OPTS:-${PREDICATE_SER_JAVA_OPTS:-${POLYSI_JAVA_OPTS:-}}}
if [[ -n "$JAVA_OPTS" ]]; then
  # Intentional word splitting for caller-provided JVM flags.
  read -r -a EXTRA_JAVA_OPTS <<<"$JAVA_OPTS"
fi

accept_count=0
reject_count=0
error_count=0

for target in "${TARGETS[@]}"; do
  rel_target=${target#/}
  safe_name=${rel_target//\//__}
  log_path="$OUTPUT_ROOT/${safe_name}.log"

  echo "=== Auditing $target"
  set +e
  java "-Djava.library.path=$MONOSAT_NATIVE_DIR" "-Xmx$HEAP_SIZE" "${EXTRA_JAVA_OPTS[@]}" -jar "$JAR_PATH" audit -t PRHIST "$target" >"$log_path" 2>&1
  status=$?
  set -e

  if grep -q '\[\[\[\[ ACCEPT \]\]\]\]' "$log_path"; then
    result="ACCEPT"
    accept_count=$((accept_count + 1))
  elif grep -q '\[\[\[\[ REJECT \]\]\]\]' "$log_path"; then
    result="REJECT"
    reject_count=$((reject_count + 1))
  else
    result="RUNTIME_ERROR"
    error_count=$((error_count + 1))
  fi

  echo "Result: $result (exit=$status, log=$log_path)"
done

echo
echo "Summary: ACCEPT=$accept_count REJECT=$reject_count RUNTIME_ERROR=$error_count"

if [[ $error_count -gt 0 ]]; then
  exit 1
fi
