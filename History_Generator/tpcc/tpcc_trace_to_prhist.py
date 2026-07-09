#!/usr/bin/env python3
"""Convert committed BenchBase/PostgreSQL TPC-C evidence to PRHIST v2.

The input is the JSONL emitted by ``sql/02_export_tpcc_trace.sql``.  It is an
evidence file, not a history format: it retains SQL, bound parameters, returned
JDBC rows, old/new tuples, LSNs, and the PostgreSQL transaction identity.  This
converter writes only the fields accepted by PredicateHistoryLoader, while the
raw file remains alongside the generated case for audit.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
from collections import Counter
from pathlib import Path
from typing import Any, Iterable


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


def source_row(row: dict[str, Any], context: str) -> dict[str, Any]:
    return {
        "key": require_text(row.get("key"), f"{context}.key"),
        "value": require_int(row.get("value"), f"{context}.value"),
        "semantic": require_int(row.get("semantic"), f"{context}.semantic"),
        "source_write_id": require_int(row.get("source_write_id"), f"{context}.source_write_id"),
        "source_txn": require_int(row.get("source_txn"), f"{context}.source_txn"),
        "source_op_index": require_int(row.get("source_op_index"), f"{context}.source_op_index"),
    }


KEY_PATTERNS: tuple[tuple[str, re.Pattern[str], tuple[str, ...]], ...] = (
    ("warehouse", re.compile(r"^warehouse:w=(?P<w_id>\\d+)$"), ("w_id",)),
    ("district", re.compile(r"^district:w=(?P<d_w_id>\\d+):d=(?P<d_id>\\d+)$"), ("d_w_id", "d_id")),
    ("customer", re.compile(r"^customer:w=(?P<c_w_id>\\d+):d=(?P<c_d_id>\\d+):c=(?P<c_id>\\d+)$"), ("c_w_id", "c_d_id", "c_id")),
    ("item", re.compile(r"^item:i=(?P<i_id>\\d+)$"), ("i_id",)),
    ("stock", re.compile(r"^stock:w=(?P<s_w_id>\\d+):i=(?P<s_i_id>\\d+)$"), ("s_w_id", "s_i_id")),
    ("oorder", re.compile(r"^oorder:w=(?P<o_w_id>\\d+):d=(?P<o_d_id>\\d+):o=(?P<o_id>\\d+)$"), ("o_w_id", "o_d_id", "o_id")),
    ("new_order", re.compile(r"^new_order:w=(?P<no_w_id>\\d+):d=(?P<no_d_id>\\d+):o=(?P<no_o_id>\\d+)$"), ("no_w_id", "no_d_id", "no_o_id")),
    ("order_line", re.compile(r"^order_line:w=(?P<ol_w_id>\\d+):d=(?P<ol_d_id>\\d+):o=(?P<ol_o_id>\\d+):n=(?P<ol_number>\\d+)$"), ("ol_w_id", "ol_d_id", "ol_o_id", "ol_number")),
    ("history", re.compile(r"^history:id=(?P<ser_tpcc_history_id>\\d+)$"), ("ser_tpcc_history_id",)),
)


def relation_and_pk(row: dict[str, Any], context: str) -> tuple[str, dict[str, int]]:
    """Derive the original SQL relation and typed primary key from trace data."""
    relation = row.get("table")
    pk = row.get("pk")
    if isinstance(relation, str) and isinstance(pk, dict):
        return relation, {str(key): require_int(value, f"{context}.pk.{key}") for key, value in pk.items()}
    key = require_text(row.get("key"), f"{context}.key")
    for candidate, pattern, fields in KEY_PATTERNS:
        match = pattern.match(key)
        if match:
            return candidate, {field: int(match.group(field)) for field in fields}
    raise ConversionError(f"{context}.key: unsupported TPC-C object key {key!r}")


def relational_reference(row: dict[str, Any], context: str) -> dict[str, Any]:
    relation, pk = relation_and_pk(row, context)
    return {"relation": relation, "pk": pk, "version": require_int(row.get("value"), f"{context}.value")}


def write_row(row: dict[str, Any], context: str) -> dict[str, Any]:
    result = {
        "type": "w",
        "key": require_text(row.get("key"), f"{context}.key"),
        "value": require_int(row.get("value"), f"{context}.value"),
    }
    if row.get("before_value") is not None:
        result["before_value"] = require_int(row.get("before_value"), f"{context}.before_value")
    if row.get("new_row") is not None:
        result["row"] = row["new_row"]
    return result


def predicate_row(row: dict[str, Any], context: str) -> dict[str, Any]:
    predicate = row.get("predicate")
    results = row.get("results")
    if not isinstance(predicate, dict):
        raise ConversionError(f"{context}.predicate: expected object")
    if not isinstance(results, list):
        raise ConversionError(f"{context}.results: expected list")
    kind = require_text(predicate.get("kind"), f"{context}.predicate.kind")
    if kind != "tpcc_stock_level":
        raise ConversionError(f"{context}: relational export supports tpcc_stock_level only, got {kind!r}")
    warehouse = require_int(predicate.get("warehouse_id"), f"{context}.predicate.warehouse_id")
    district = require_int(predicate.get("district_id"), f"{context}.predicate.district_id")
    lower = require_int(predicate.get("order_id_from"), f"{context}.predicate.order_id_from")
    upper = require_int(predicate.get("order_id_to_exclusive"), f"{context}.predicate.order_id_to_exclusive")
    threshold = require_int(predicate.get("stock_quantity_lt"), f"{context}.predicate.stock_quantity_lt")
    read_versions = row.get("read_versions")
    if not isinstance(read_versions, list):
        raise ConversionError(f"{context}.read_versions: expected list captured at predicate read time")
    return {
        "type": "pr",
        "query": {
            "select": {"distinct": True, "columns": ["s.s_i_id"]},
            "from": {"relation": "order_line", "alias": "ol"},
            "joins": [{"type": "INNER", "relation": "stock", "alias": "s",
                       "on": ["ol.ol_w_id = s.s_w_id", "ol.ol_i_id = s.s_i_id"]}],
            "where": [f"ol.ol_w_id = {warehouse}", f"ol.ol_d_id = {district}",
                      f"ol.ol_o_id >= {lower}", f"ol.ol_o_id < {upper}", f"s.s_quantity < {threshold}"],
        },
        "result": {
            "values": [{"s_i_id": require_int(item.get("semantic"), f"{context}.results[{index}].semantic")}
                       for index, item in enumerate(results)],
            "inputs": [
                {"key": require_text(item.get("key"), f"{context}.read_versions[{index}].key"),
                 "value": require_int(item.get("value"), f"{context}.read_versions[{index}].value")}
                for index, item in enumerate(read_versions)
            ],
        },
    }


def convert_transaction(row: dict[str, Any], context: str) -> dict[str, Any]:
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
        op_type = operation.get("type")
        if op_type == "w":
            ops.append(write_row(operation, op_context))
        elif op_type == "r":
            converted = {
                "type": "r",
                "key": require_text(operation.get("key"), f"{op_context}.key"),
                "value": require_int(operation.get("value"), f"{op_context}.value"),
            }
            ops.append(converted)
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
    txn_type = row.get("txn_type")
    if isinstance(txn_type, str) and txn_type:
        txn["kind"] = f"tpcc.{txn_type}"
    # These are optional metadata fields in existing PRHIST samples.  The
    # trace's last operation timestamp is deliberately labelled as pre-commit:
    # PostgreSQL's atomic persistence, not a client guess, establishes commit.
    if row.get("begin_ts") is not None:
        txn["begin_ts"] = row["begin_ts"]
    if row.get("last_op_ts") is not None:
        txn["pre_commit_ts"] = row["last_op_ts"]
    return txn


def validate_structure(initial: Iterable[dict[str, Any]], transactions: Iterable[dict[str, Any]]) -> dict[str, int]:
    """Check unique KV versions and that every point/predicate read resolves."""
    known_versions: dict[int, str] = {}
    for index, item in enumerate(initial):
        version = require_int(item.get("value"), f"initial[{index}].value")
        if version in known_versions:
            raise ConversionError(f"duplicate initial version {version}")
        known_versions[version] = require_text(item.get("key"), f"initial[{index}].key")

    txn_list = list(transactions)
    operation_counts: Counter[str] = Counter()
    seen_txns: set[int] = set()
    for row_index, txn in enumerate(txn_list):
        txn_id = require_int(txn.get("txn"), f"transactions[{row_index}].txn")
        if txn_id in seen_txns:
            raise ConversionError(f"duplicate transaction id {txn_id}")
        seen_txns.add(txn_id)
        for op_index, op in enumerate(txn["ops"]):
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
        "initial_keys": len(initial),
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
    initial = []
    for index, row in enumerate(rows):
        if row.get("record_type") != "initial":
            continue
        version = {
            "key": require_text(row.get("key"), f"raw.initial[{index}].key"),
            "value": require_int(row.get("value"), f"raw.initial[{index}].value"),
        }
        if row.get("row") is not None:
            version["row"] = row["row"]
        initial.append(version)
    raw_txns = [row for row in rows if row.get("record_type") == "txn"]
    raw_aborts = [row for row in rows if row.get("record_type") == "abort"]
    unknown = [row.get("record_type") for row in rows if row.get("record_type") not in {"initial", "txn", "abort"}]
    if unknown:
        raise ConversionError(f"unknown raw trace record types: {unknown[:3]!r}")
    if not initial:
        raise ConversionError("raw trace has no initial versions; run snapshot_initial_state before execute")
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

    raw_destination = case_dir / "raw_tpcc_trace.jsonl"
    if raw_path.resolve() != raw_destination.resolve():
        shutil.copyfile(raw_path, raw_destination)
    manifest: dict[str, Any] = {
        "dataset_name": case_dir.parent.name,
        "format": "prhist-v2-kv-relational-predicate",
        "case_kind": "real_postgresql_tpcc",
        "generator": "tpcc/tpcc_trace_to_prhist.py",
        "source": "BenchBase TPC-C on PostgreSQL with JDBC reads and trigger writes",
        "files": {
            "initial_state": "initial_state.json",
            "history": "history.prhist.jsonl",
            "raw_trace": "raw_tpcc_trace.jsonl",
        },
        "predicate_mapping": "StockLevel emits SQL-shaped query, actual relational read_versions, and result rows; requires relational predicate support in the detector.",
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
    parser = argparse.ArgumentParser(description="Convert committed TPC-C trace evidence to the repository PRHIST v2 format.")
    parser.add_argument("--raw", type=Path, required=True, help="JSONL generated by sql/02_export_tpcc_trace.sql")
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
