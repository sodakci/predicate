#!/usr/bin/env python3
"""Convert committed BenchBase/PostgreSQL kvpredicate evidence to PRHIST v2."""

from __future__ import annotations

import argparse
import json
import shutil
from collections import Counter
from pathlib import Path
from typing import Any, Iterable

ABSENT_BASE = -1_000_000_000_000
SOURCE_FIELDS = frozenset({"source_write_id", "source_txn", "source_op_index"})


class ConversionError(ValueError):
    """Raised when evidence cannot honestly be represented as PRHIST."""


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as handle:
        for lineno, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ConversionError(f"{path}:{lineno}: invalid JSON: {exc}") from exc
            if not isinstance(row, dict):
                raise ConversionError(f"{path}:{lineno}: expected JSON object")
            rows.append(row)
    return rows


def require_int(value: Any, context: str) -> int:
    if isinstance(value, bool):
        raise ConversionError(f"{context}: expected integer, got boolean")
    try:
        return int(value)
    except (TypeError, ValueError) as exc:
        raise ConversionError(f"{context}: expected integer, got {value!r}") from exc


def require_text(value: Any, context: str) -> str:
    if not isinstance(value, str) or not value:
        raise ConversionError(f"{context}: expected non-empty string")
    return value


def key_id(key: str, context: str) -> int:
    if not key.startswith("kv:"):
        raise ConversionError(f"{context}: expected kv:<id> key, got {key!r}")
    return require_int(key[3:], context)


def reject_source_fields(row: dict[str, Any], context: str) -> None:
    present = sorted(SOURCE_FIELDS.intersection(row))
    if present:
        raise ConversionError(f"{context}: source provenance fields are not supported: {present}")


def version_reference(row: dict[str, Any], context: str) -> dict[str, Any]:
    reject_source_fields(row, context)
    return {
        "key": require_text(row.get("key"), f"{context}.key"),
        "value": require_int(row.get("value"), f"{context}.value"),
    }


def write_row(row: dict[str, Any], context: str) -> dict[str, Any]:
    reject_source_fields(row, context)
    return {
        "type": "w",
        "key": require_text(row.get("key"), f"{context}.key"),
        "value": require_int(row.get("value"), f"{context}.value"),
    }


def point_read_row(row: dict[str, Any], context: str) -> dict[str, Any]:
    reject_source_fields(row, context)
    return {
        "type": "r",
        "key": require_text(row.get("key"), f"{context}.key"),
        "value": require_int(row.get("value"), f"{context}.value"),
    }


def predicate_to_query(predicate: dict[str, Any], context: str) -> dict[str, Any]:
    kind = require_text(predicate.get("kind"), f"{context}.predicate.kind")
    if kind == "true":
        where = ["TRUE"]
    elif kind == "eq":
        where = [f"value = {require_int(predicate.get('value'), f'{context}.predicate.value')}"]
    elif kind == "mod":
        modulus = require_int(predicate.get("modulus"), f"{context}.predicate.modulus")
        target = require_int(predicate.get("target"), f"{context}.predicate.target")
        where = [f"value % {modulus} = {target}"]
    elif kind == "gt":
        where = [f"value > {require_int(predicate.get('value'), f'{context}.predicate.value')}"]
    elif kind == "lt":
        where = [f"value < {require_int(predicate.get('value'), f'{context}.predicate.value')}"]
    else:
        raise ConversionError(f"{context}: unsupported kvpredicate predicate kind {kind!r}")
    return {
        "select": {"distinct": False, "columns": ["k", "value"]},
        "from": {"relation": "kv"},
        "where": where,
    }


def predicate_row(row: dict[str, Any], context: str) -> dict[str, Any]:
    predicate = row.get("predicate")
    results = row.get("results")
    read_versions = row.get("read_versions")
    if not isinstance(predicate, dict):
        raise ConversionError(f"{context}.predicate: expected object")
    if not isinstance(results, list):
        raise ConversionError(f"{context}.results: expected list")
    if not isinstance(read_versions, list):
        raise ConversionError(f"{context}.read_versions: expected list captured at predicate read time")
    values = []
    for index, item in enumerate(results):
        if not isinstance(item, dict):
            raise ConversionError(f"{context}.results[{index}]: expected object")
        reject_source_fields(item, f"{context}.results[{index}]")
        key = require_text(item.get("key"), f"{context}.results[{index}].key")
        values.append({
            "k": str(key_id(key, f"{context}.results[{index}].key")),
            "value": require_int(item.get("semantic"), f"{context}.results[{index}].semantic"),
        })
    return {
        "type": "pr",
        "query": predicate_to_query(predicate, context),
        "result": {
            "values": values,
            "inputs": [
                version_reference(item, f"{context}.read_versions[{index}]")
                for index, item in enumerate(read_versions)
            ],
        },
    }


def convert_transaction(row: dict[str, Any], context: str) -> dict[str, Any]:
    reject_source_fields(row, context)
    if row.get("status") != "commit":
        raise ConversionError(f"{context}: raw export contains non-committed transaction")
    raw_ops = row.get("ops")
    if not isinstance(raw_ops, list):
        raise ConversionError(f"{context}.ops: expected list")
    indexed_ops: list[tuple[int, dict[str, Any]]] = []
    seen_indexes: set[int] = set()
    for position, operation in enumerate(raw_ops):
        if not isinstance(operation, dict):
            raise ConversionError(f"{context}.ops[{position}]: expected object")
        op_index = require_int(operation.get("op_index"), f"{context}.ops[{position}].op_index")
        if op_index in seen_indexes:
            raise ConversionError(f"{context}: duplicate trace operation index {op_index}")
        seen_indexes.add(op_index)
        indexed_ops.append((op_index, operation))
    indexed_ops.sort(key=lambda item: item[0])

    ops: list[dict[str, Any]] = []
    for op_index, operation in indexed_ops:
        op_context = f"{context}.ops[{op_index}]"
        reject_source_fields(operation, op_context)
        op_type = operation.get("type")
        if op_type == "w":
            ops.append(write_row(operation, op_context))
        elif op_type == "r":
            ops.append(point_read_row(operation, op_context))
        elif op_type == "pr":
            ops.append(predicate_row(operation, op_context))
        else:
            raise ConversionError(f"{op_context}: unsupported trace operation type {op_type!r}")

    txn = {
        "session": require_int(row.get("session"), f"{context}.session"),
        "session_seq": require_int(row.get("session_seq"), f"{context}.session_seq"),
        "txn": require_int(row.get("txn"), f"{context}.txn"),
        "status": "commit",
        "ops": ops,
    }
    return txn


def absent_initial_from_read(op: dict[str, Any], context: str) -> dict[str, Any] | None:
    reject_source_fields(op, context)
    if not bool(op.get("absent", False)):
        return None
    key = require_text(op.get("key"), f"{context}.key")
    value = require_int(op.get("value"), f"{context}.value")
    expected = ABSENT_BASE - key_id(key, f"{context}.key")
    if value != expected:
        raise ConversionError(f"{context}: absent read version {value} != expected {expected}")
    return {"key": key, "value": value, "absent": True}


def collect_absent_initials(raw_txns: Iterable[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    absent: dict[str, dict[str, Any]] = {}
    for txn_index, txn in enumerate(raw_txns):
        ops = txn.get("ops")
        if not isinstance(ops, list):
            continue
        for op_index, op in enumerate(ops):
            if not isinstance(op, dict) or op.get("type") != "r":
                continue
            initial = absent_initial_from_read(op, f"raw.txn[{txn_index}].ops[{op_index}]")
            if initial is not None:
                absent.setdefault(initial["key"], initial)
    return absent


def validate_structure(initial: Iterable[dict[str, Any]], transactions: Iterable[dict[str, Any]]) -> dict[str, int]:
    known_versions: dict[int, str] = {}
    initial_list = list(initial)
    for index, item in enumerate(initial_list):
        version = require_int(item.get("value"), f"initial[{index}].value")
        if version in known_versions:
            raise ConversionError(f"duplicate initial version {version}")
        known_versions[version] = require_text(item.get("key"), f"initial[{index}].key")

    txn_list = list(transactions)
    operation_counts: Counter[str] = Counter()
    seen_txns: set[int] = set()
    last_session_seq: dict[int, int] = {}
    for row_index, txn in enumerate(txn_list):
        txn_id = require_int(txn.get("txn"), f"transactions[{row_index}].txn")
        if txn_id in seen_txns:
            raise ConversionError(f"duplicate transaction id {txn_id}")
        seen_txns.add(txn_id)
        session = require_int(txn.get("session"), f"transactions[{row_index}].session")
        session_seq = require_int(txn.get("session_seq"), f"transactions[{row_index}].session_seq")
        previous_seq = last_session_seq.get(session)
        if previous_seq is not None and session_seq <= previous_seq:
            raise ConversionError(f"session {session} is not strictly ordered at txn {txn_id}")
        last_session_seq[session] = session_seq
        raw_ops = txn.get("ops")
        if not isinstance(raw_ops, list):
            raise ConversionError(f"transactions[{row_index}].ops: expected list")
        for op_index, op in enumerate(raw_ops):
            operation_counts[str(op.get("type"))] += 1
            if op.get("type") == "w":
                version = require_int(op.get("value"), f"txn={txn_id}.ops[{op_index}].value")
                if version in known_versions:
                    raise ConversionError(f"txn={txn_id}.ops[{op_index}]: duplicate version {version}")
                known_versions[version] = require_text(op.get("key"), f"txn={txn_id}.ops[{op_index}].key")

    def check_read(reference: dict[str, Any], context: str) -> None:
        version = require_int(reference.get("value"), f"{context}.value")
        expected = known_versions.get(version)
        if expected is None:
            raise ConversionError(f"{context}: unresolved version {version}")
        if require_text(reference.get("key"), f"{context}.key") != expected:
            raise ConversionError(f"{context}: version {version} disagrees on key")

    for txn in txn_list:
        for op_index, op in enumerate(txn["ops"]):
            context = f"txn={txn['txn']}.ops[{op_index}]"
            if op["type"] == "r":
                check_read(op, context)
            elif op["type"] == "pr":
                for read_index, reference in enumerate(op["result"]["inputs"]):
                    check_read(reference, f"{context}.result.inputs[{read_index}]")
    return {
        "initial_keys": len(initial_list),
        "transactions": len(txn_list),
        "point_reads": operation_counts["r"],
        "predicate_reads": operation_counts["pr"],
        "writes": operation_counts["w"],
        "operations": sum(operation_counts.values()),
    }


def convert(
    raw_path: Path,
    case_dir: Path,
    expected_verdict: str | None = None,
    serial_order: list[int] | None = None,
) -> dict[str, Any]:
    rows = load_jsonl(raw_path)
    raw_initial = [row for row in rows if row.get("record_type") == "initial"]
    raw_txns = [row for row in rows if row.get("record_type") == "txn"]
    raw_aborts = [row for row in rows if row.get("record_type") == "abort"]
    unknown = [row.get("record_type") for row in rows if row.get("record_type") not in {"initial", "txn", "abort"}]
    if unknown:
        raise ConversionError(f"unknown raw trace record types: {unknown[:3]!r}")
    if not raw_initial:
        raise ConversionError("raw trace has no initial versions; run snapshot_initial_state before execute")

    initial: list[dict[str, Any]] = []
    for index, row in enumerate(raw_initial):
        reject_source_fields(row, f"raw.initial[{index}]")
        initial.append({
            "key": require_text(row.get("key"), f"raw.initial[{index}].key"),
            "value": require_int(row.get("value"), f"raw.initial[{index}].value"),
        })
    initial.extend(collect_absent_initials(raw_txns).values())

    transactions = [convert_transaction(row, f"raw.txn[{index}]") for index, row in enumerate(raw_txns)]
    transactions.sort(key=lambda row: (row["session"], row["session_seq"], row["txn"]))
    initial.sort(key=lambda row: row["key"])
    stats = validate_structure(initial, transactions)

    case_dir.mkdir(parents=True, exist_ok=True)
    initial_path = case_dir / "initial_state.json"
    history_path = case_dir / "history.prhist.jsonl"
    initial_path.write_text(json.dumps(initial, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    with history_path.open("w", encoding="utf-8") as handle:
        for transaction in transactions:
            handle.write(json.dumps(transaction, sort_keys=True, separators=(",", ":")) + "\n")

    raw_destination = case_dir / "raw_kvpredicate_trace.jsonl"
    if raw_path.resolve() != raw_destination.resolve():
        shutil.copyfile(raw_path, raw_destination)
    manifest: dict[str, Any] = {
        "dataset_name": case_dir.parent.name,
        "format": "prhist-v2-kv-relational-predicate",
        "case_kind": "real_postgresql_kvpredicate",
        "generator": "kv/kvpredicate_trace_to_prhist.py",
        "source": "BenchBase kvpredicate on PostgreSQL with JDBC reads and trigger writes",
        "files": {
            "initial_state": "initial_state.json",
            "history": "history.prhist.jsonl",
            "raw_trace": "raw_kvpredicate_trace.jsonl",
        },
        "predicate_mapping": "Predicate reads emit SQL-shaped kv predicates: TRUE, value equality, value modulo, value greater-than, and value less-than.",
        "version_mapping": "kv.value is globally unique and is used as both business value and PRHIST version id.",
        "captured_aborted_attempts": len(raw_aborts),
        **stats,
    }
    if expected_verdict is not None:
        manifest["expected_verdict"] = expected_verdict.upper()
        if expected_verdict.upper() == "ACCEPT":
            if serial_order is None:
                raise ConversionError("ACCEPT manifest requires an externally verified --serial-order; it is never inferred")
            transaction_ids = {transaction["txn"] for transaction in transactions}
            if set(serial_order) != transaction_ids or len(serial_order) != len(transaction_ids):
                raise ConversionError("--serial-order must contain every generated transaction exactly once")
            manifest["serial_order"] = serial_order
    (case_dir / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description="Convert committed kvpredicate trace evidence to PRHIST v2.")
    parser.add_argument("--raw", type=Path, required=True, help="JSONL generated by sql/02_export_kvpredicate_trace.sql")
    parser.add_argument("--case-dir", type=Path, required=True, help="target .../case-name/hist-00000 directory")
    parser.add_argument("--expected-verdict", choices=("ACCEPT", "REJECT"), help="optional known oracle; never guessed by this converter")
    parser.add_argument(
        "--serial-order",
        nargs="+",
        type=int,
        help="externally verified serial order; mandatory only when declaring --expected-verdict ACCEPT",
    )
    args = parser.parse_args()
    try:
        manifest = convert(args.raw, args.case_dir, args.expected_verdict, args.serial_order)
    except ConversionError as exc:
        parser.error(str(exc))
    print(json.dumps(manifest, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
