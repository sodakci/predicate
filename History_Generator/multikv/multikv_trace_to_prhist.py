#!/usr/bin/env python3
"""Convert committed BenchBase/PostgreSQL multikv evidence to PRHIST v4."""

from __future__ import annotations

import argparse
import copy
import json
import re
import shutil
from collections import Counter
from pathlib import Path
from typing import Any, Iterable

from generate_multikv_join_history import (
    ANOMALY_MODES,
    ANOMALY_NONE,
    ANOMALY_WRITE_SKEW,
    JOIN_OPEN_AVAILABLE_QUERY,
)


RAW_TRACE_NAME = "raw_multikv_trace.jsonl"
ANOMALY_LOST_UPDATE = "lost-update"
REAL_ANOMALY_MODES = tuple(ANOMALY_MODES) + (ANOMALY_LOST_UPDATE,)
LOST_UPDATE_KEY = "items:lu0"
ITEM_KEY_FILTER = re.compile(r"i\.value\.item_key = '([^']+)'")
EXPECTED_COLUMNS = {
    "users": {"user_key", "region", "active"},
    "items": {"item_key", "stock", "price"},
    "orders": {"order_key", "user_key", "item_key", "status"},
}
PRIMARY_KEY_COLUMNS = {
    "users": "user_key",
    "items": "item_key",
    "orders": "order_key",
}


class ConversionError(ValueError):
    """Raised when PostgreSQL evidence cannot be represented honestly."""


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


def relational_value(
    row: dict[str, Any],
    context: str,
) -> tuple[str, str, str, dict[str, Any]]:
    key = require_text(row.get("key"), f"{context}.key")
    if ":" not in key:
        raise ConversionError(f"{context}.key: expected <table>:<local-key>, got {key!r}")
    table, local_key = key.split(":", 1)
    if table not in EXPECTED_COLUMNS or not local_key:
        raise ConversionError(f"{context}.key: unsupported multikv key {key!r}")
    value = row.get("value")
    if not isinstance(value, dict):
        raise ConversionError(f"{context}.value: expected relational row object")
    if set(value) != EXPECTED_COLUMNS[table]:
        raise ConversionError(f"{context}.value: unexpected columns for table {table!r}")
    primary_key = PRIMARY_KEY_COLUMNS[table]
    if require_text(value.get(primary_key), f"{context}.value.{primary_key}") != local_key:
        raise ConversionError(f"{context}.value: primary key does not match {key!r}")
    if table == "users":
        require_text(value.get("region"), f"{context}.value.region")
        if not isinstance(value.get("active"), bool):
            raise ConversionError(f"{context}.value.active: expected boolean")
    elif table == "items":
        require_int(value.get("stock"), f"{context}.value.stock")
        require_int(value.get("price"), f"{context}.value.price")
    else:
        require_text(value.get("user_key"), f"{context}.value.user_key")
        require_text(value.get("item_key"), f"{context}.value.item_key")
        require_text(value.get("status"), f"{context}.value.status")
    return key, table, local_key, copy.deepcopy(value)


def version_reference(row: dict[str, Any], context: str) -> dict[str, Any]:
    key, _, _, value = relational_value(row, context)
    return {"key": key, "value": value}


def point_read_row(row: dict[str, Any], context: str) -> dict[str, Any]:
    return {"type": "r", **version_reference(row, context)}


def write_row(row: dict[str, Any], context: str) -> dict[str, Any]:
    return {"type": "w", **version_reference(row, context)}


def predicate_filter(predicate: dict[str, Any], context: str) -> str | None:
    if require_text(predicate.get("kind"), f"{context}.predicate.kind") != "join_open_available":
        raise ConversionError(f"{context}.predicate: unsupported predicate kind")
    item_key = predicate.get("item_key")
    if item_key is None:
        return None
    item_key = require_text(item_key, f"{context}.predicate.item_key")
    if "'" in item_key:
        raise ConversionError(f"{context}.predicate.item_key: apostrophes are not supported")
    return item_key


def predicate_query(item_key: str | None) -> dict[str, Any]:
    query = copy.deepcopy(JOIN_OPEN_AVAILABLE_QUERY)
    if item_key is not None:
        query["where"].append(f"i.value.item_key = '{item_key}'")
    return query


def projected_result(row: dict[str, Any], context: str) -> dict[str, Any]:
    if set(row) != {"order_key", "item_key", "user_key", "stock"}:
        raise ConversionError(f"{context}: expected JOIN projection columns only")
    return {
        "order_key": require_text(row.get("order_key"), f"{context}.order_key"),
        "item_key": require_text(row.get("item_key"), f"{context}.item_key"),
        "user_key": require_text(row.get("user_key"), f"{context}.user_key"),
        "stock": require_int(row.get("stock"), f"{context}.stock"),
    }


def canonical_predicate_inputs(
    values: list[dict[str, Any]],
    raw_inputs: list[Any],
    context: str,
) -> list[dict[str, Any]]:
    inputs_by_key: dict[str, dict[str, Any]] = {}
    for index, item in enumerate(raw_inputs):
        if not isinstance(item, dict):
            raise ConversionError(f"{context}.read_versions[{index}]: expected object")
        reference = version_reference(item, f"{context}.read_versions[{index}]")
        key = reference["key"]
        if key in inputs_by_key:
            raise ConversionError(f"{context}.read_versions: duplicate key {key!r}")
        inputs_by_key[key] = reference

    ordered_keys: list[str] = []
    seen_keys: set[str] = set()
    for index, value in enumerate(values):
        order_key = f"orders:{value['order_key']}"
        item_key = f"items:{value['item_key']}"
        for key in (order_key, item_key):
            if key not in seen_keys:
                seen_keys.add(key)
                ordered_keys.append(key)
        order_ref = inputs_by_key.get(order_key)
        item_ref = inputs_by_key.get(item_key)
        if order_ref is None or item_ref is None:
            raise ConversionError(
                f"{context}.read_versions: result[{index}] lacks order/item input versions"
            )
        order_value = order_ref["value"]
        item_value = item_ref["value"]
        if (
            order_value["order_key"] != value["order_key"]
            or order_value["item_key"] != value["item_key"]
            or order_value["user_key"] != value["user_key"]
            or item_value["item_key"] != value["item_key"]
            or require_int(item_value["stock"], f"{context}.read_versions[{item_key}].stock")
            != value["stock"]
        ):
            raise ConversionError(
                f"{context}.results[{index}]: projection disagrees with input row versions"
            )

    if set(inputs_by_key) != seen_keys:
        extras = sorted(set(inputs_by_key) - seen_keys)
        raise ConversionError(f"{context}.read_versions: inputs not used by JOIN results: {extras}")
    return [inputs_by_key[key] for key in ordered_keys]


def predicate_row(row: dict[str, Any], context: str) -> dict[str, Any]:
    predicate = row.get("predicate")
    results = row.get("results")
    read_versions = row.get("read_versions")
    if not isinstance(predicate, dict):
        raise ConversionError(f"{context}.predicate: expected object")
    if not isinstance(results, list):
        raise ConversionError(f"{context}.results: expected list")
    if not isinstance(read_versions, list):
        raise ConversionError(f"{context}.read_versions: expected list")
    item_key = predicate_filter(predicate, context)
    values = [
        projected_result(item, f"{context}.results[{index}]")
        for index, item in enumerate(results)
        if isinstance(item, dict)
    ]
    if len(values) != len(results):
        raise ConversionError(f"{context}.results: every result must be an object")
    values.sort(key=lambda item: item["order_key"])
    if item_key is not None and any(value["item_key"] != item_key for value in values):
        raise ConversionError(f"{context}.results: row violates item_key predicate {item_key!r}")
    return {
        "type": "pr",
        "query": predicate_query(item_key),
        "result": {
            "values": values,
            "inputs": canonical_predicate_inputs(values, read_versions, context),
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
        if op_type == "r":
            ops.append(point_read_row(operation, op_context))
        elif op_type == "w":
            ops.append(write_row(operation, op_context))
        elif op_type == "pr":
            ops.append(predicate_row(operation, op_context))
        else:
            raise ConversionError(f"{op_context}: unsupported trace operation type {op_type!r}")
    return {
        "session": require_int(row.get("session"), f"{context}.session"),
        "session_seq": require_int(row.get("session_seq"), f"{context}.session_seq"),
        "txn": require_int(row.get("txn"), f"{context}.txn"),
        "status": "commit",
        "ops": ops,
    }


def filtered_item_key(op: dict[str, Any]) -> str | None:
    where = op.get("query", {}).get("where")
    if not isinstance(where, list) or len(where) != 2:
        return None
    match = ITEM_KEY_FILTER.fullmatch(str(where[1]))
    return match.group(1) if match is not None else None


def write_skew_candidates(transactions: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    candidates = []
    for transaction in transactions:
        ops = transaction["ops"]
        if (
            len(ops) == 2
            and [op.get("type") for op in ops] == ["pr", "w"]
            and filtered_item_key(ops[0]) is not None
        ):
            candidates.append(transaction)
    return candidates


def validate_write_skew_core(transactions: list[dict[str, Any]]) -> list[int]:
    core = write_skew_candidates(transactions)
    if len(core) != 2:
        raise ConversionError(
            f"write-skew mode requires exactly two committed core transactions, found {len(core)}"
        )
    core.sort(key=lambda txn: (txn["session"], txn["session_seq"], txn["txn"]))
    if core[0]["session"] == core[1]["session"]:
        raise ConversionError("write-skew core transactions must use different sessions")

    filters = [filtered_item_key(txn["ops"][0]) for txn in core]
    write_keys = [require_text(txn["ops"][1].get("key"), "write-skew write key") for txn in core]
    if (
        filters[0] is None
        or filters[1] is None
        or filters[0] == filters[1]
        or write_keys[0] == write_keys[1]
        or write_keys[0] != f"items:{filters[1]}"
        or write_keys[1] != f"items:{filters[0]}"
    ):
        raise ConversionError("write-skew core must cross-read and write two different items")

    minimum_seq: dict[int, int] = {}
    for transaction in transactions:
        session = transaction["session"]
        minimum_seq[session] = min(minimum_seq.get(session, transaction["session_seq"]), transaction["session_seq"])
    for index, transaction in enumerate(core):
        if transaction["session_seq"] != minimum_seq[transaction["session"]]:
            raise ConversionError("write-skew core must be the first committed transaction in each core session")
        inputs = {
            reference["key"]: reference["value"]
            for reference in transaction["ops"][0]["result"]["inputs"]
        }
        peer_key = write_keys[1 - index]
        own_key = write_keys[index]
        if peer_key not in inputs or own_key in inputs:
            raise ConversionError("write-skew predicate must read only the item written by its peer")
        if require_int(inputs[peer_key].get("stock"), f"write-skew input {peer_key}.stock") <= 0:
            raise ConversionError("write-skew predicate must observe an available peer item")
        written_value = transaction["ops"][1]["value"]
        if require_int(written_value.get("stock"), f"write-skew write {own_key}.stock") > 0:
            raise ConversionError("write-skew write must make its item unavailable")
    return [transaction["txn"] for transaction in core]


def lost_update_candidates(
    transactions: Iterable[dict[str, Any]],
) -> list[dict[str, Any]]:
    candidates = []
    for transaction in transactions:
        ops = transaction["ops"]
        if (
            len(ops) == 2
            and [op.get("type") for op in ops] == ["r", "w"]
            and ops[0].get("key") == LOST_UPDATE_KEY
            and ops[1].get("key") == LOST_UPDATE_KEY
        ):
            candidates.append(transaction)
    return candidates


def operation_references_key(operation: dict[str, Any], key: str) -> bool:
    if operation.get("type") in {"r", "w"}:
        return operation.get("key") == key
    if operation.get("type") == "pr":
        return any(
            isinstance(reference, dict) and reference.get("key") == key
            for reference in operation.get("result", {}).get("inputs", [])
        )
    return False


def validate_lost_update_core(
    initial: list[dict[str, Any]],
    transactions: list[dict[str, Any]],
) -> list[int]:
    initial_values = [
        item["value"] for item in initial if item.get("key") == LOST_UPDATE_KEY
    ]
    if len(initial_values) != 1:
        raise ConversionError(
            f"lost-update mode requires exactly one initial {LOST_UPDATE_KEY} row"
        )
    initial_value = initial_values[0]
    core = lost_update_candidates(transactions)
    if len(core) != 2:
        raise ConversionError(
            f"lost-update mode requires exactly two committed core transactions, found {len(core)}"
        )
    core.sort(key=lambda txn: (txn["session"], txn["session_seq"], txn["txn"]))
    if core[0]["session"] == core[1]["session"]:
        raise ConversionError("lost-update core transactions must use different sessions")

    minimum_seq: dict[int, int] = {}
    for transaction in transactions:
        session = transaction["session"]
        minimum_seq[session] = min(
            minimum_seq.get(session, transaction["session_seq"]),
            transaction["session_seq"],
        )

    written_values: list[dict[str, Any]] = []
    for transaction in core:
        if transaction["session_seq"] != minimum_seq[transaction["session"]]:
            raise ConversionError(
                "lost-update core must be the first committed transaction in each core session"
            )
        read_value = transaction["ops"][0]["value"]
        written_value = transaction["ops"][1]["value"]
        if read_value != initial_value:
            raise ConversionError(
                "lost-update core transactions must read the same initial items:lu0 value"
            )
        if written_value == initial_value:
            raise ConversionError(
                "lost-update core writes must differ from the initial items:lu0 value"
            )
        written_values.append(written_value)
    if written_values[0] == written_values[1]:
        raise ConversionError("lost-update core transactions must write different values")

    core_ids = [transaction["txn"] for transaction in core]
    core_id_set = set(core_ids)
    for transaction in transactions:
        if transaction["txn"] in core_id_set:
            continue
        if any(
            operation_references_key(operation, LOST_UPDATE_KEY)
            for operation in transaction["ops"]
        ):
            raise ConversionError(
                f"non-core transaction {transaction['txn']} references {LOST_UPDATE_KEY}"
            )
    return core_ids


def order_transactions(
    transactions: list[dict[str, Any]],
    core_ids: list[int],
) -> list[dict[str, Any]]:
    core_positions = {txn_id: index for index, txn_id in enumerate(core_ids)}
    return sorted(
        transactions,
        key=lambda txn: (
            0 if txn["txn"] in core_positions else 1,
            core_positions.get(txn["txn"], txn["session_seq"]),
            txn["session_seq"],
            txn["session"],
            txn["txn"],
        ),
    )


def validate_versions(
    initial: list[dict[str, Any]],
    transactions: list[dict[str, Any]],
) -> dict[str, int]:
    known_versions: set[tuple[str, str]] = set()
    initial_keys: set[str] = set()

    def register(reference: dict[str, Any], context: str) -> None:
        key = reference["key"]
        encoded = json.dumps(reference["value"], sort_keys=True, separators=(",", ":"))
        identity = (key, encoded)
        if identity in known_versions:
            raise ConversionError(f"{context}: duplicate write (key,value) for {key}")
        known_versions.add(identity)

    for index, item in enumerate(initial):
        if item["key"] in initial_keys:
            raise ConversionError(f"initial[{index}]: duplicate initial key {item['key']!r}")
        initial_keys.add(item["key"])
        register(item, f"initial[{index}]")

    counts: Counter[str] = Counter()
    seen_txns: set[int] = set()
    last_seq: dict[int, int] = {}
    for index, transaction in enumerate(transactions):
        txn_id = transaction["txn"]
        if txn_id in seen_txns:
            raise ConversionError(f"transactions[{index}]: duplicate transaction id {txn_id}")
        seen_txns.add(txn_id)
        session = transaction["session"]
        session_seq = transaction["session_seq"]
        if session in last_seq and session_seq <= last_seq[session]:
            raise ConversionError(f"session {session} is not strictly ordered at txn {txn_id}")
        last_seq[session] = session_seq
        for op_index, operation in enumerate(transaction["ops"]):
            op_type = operation["type"]
            counts[op_type] += 1
            if op_type == "w":
                register(operation, f"txn={txn_id}.ops[{op_index}]")

    for transaction in transactions:
        for op_index, operation in enumerate(transaction["ops"]):
            references: list[dict[str, Any]] = []
            if operation["type"] == "r":
                references.append(operation)
            elif operation["type"] == "pr":
                references.extend(operation["result"]["inputs"])
            for reference in references:
                identity = (
                    reference["key"],
                    json.dumps(reference["value"], sort_keys=True, separators=(",", ":")),
                )
                if identity not in known_versions:
                    raise ConversionError(
                        f"txn={transaction['txn']}.ops[{op_index}]: unresolved read version"
                    )
    return {
        "initial_keys": len(initial),
        "transactions": len(transactions),
        "point_reads": counts["r"],
        "predicate_reads": counts["pr"],
        "writes": counts["w"],
        "operations": sum(counts.values()),
    }


def tables_manifest() -> dict[str, Any]:
    return {
        "users": {
            "primary_key": ["key"],
            "payload_columns": ["value"],
            "value_fields": ["user_key", "region", "active"],
        },
        "items": {
            "primary_key": ["key"],
            "payload_columns": ["value"],
            "value_fields": ["item_key", "stock", "price"],
        },
        "orders": {
            "primary_key": ["key"],
            "payload_columns": ["value"],
            "value_fields": ["order_key", "user_key", "item_key", "status"],
            "value_foreign_keys": {
                "user_key": "users.value.user_key",
                "item_key": "items.value.item_key",
            },
        },
    }


def convert(
    raw_path: Path,
    case_dir: Path,
    anomaly_mode: str = ANOMALY_NONE,
    expected_verdict: str | None = None,
    serial_order: list[int] | None = None,
) -> dict[str, Any]:
    if anomaly_mode not in REAL_ANOMALY_MODES:
        raise ConversionError(f"unsupported anomaly mode {anomaly_mode!r}")
    rows = load_jsonl(raw_path)
    raw_initial = [row for row in rows if row.get("record_type") == "initial"]
    raw_txns = [row for row in rows if row.get("record_type") == "txn"]
    raw_aborts = [row for row in rows if row.get("record_type") == "abort"]
    unknown = [
        row.get("record_type")
        for row in rows
        if row.get("record_type") not in {"initial", "txn", "abort"}
    ]
    if unknown:
        raise ConversionError(f"unknown raw trace record types: {unknown[:3]!r}")
    if not raw_initial:
        raise ConversionError("raw trace has no initial versions; run snapshot_initial_state first")

    initial = [
        version_reference(row, f"raw.initial[{index}]")
        for index, row in enumerate(raw_initial)
    ]
    initial.sort(key=lambda item: item["key"])
    transactions = [
        convert_transaction(row, f"raw.txn[{index}]")
        for index, row in enumerate(raw_txns)
    ]
    if anomaly_mode == ANOMALY_WRITE_SKEW:
        core_ids = validate_write_skew_core(transactions)
    elif anomaly_mode == ANOMALY_LOST_UPDATE:
        core_ids = validate_lost_update_core(initial, transactions)
    else:
        core_ids = []
    transactions = order_transactions(transactions, core_ids)
    stats = validate_versions(initial, transactions)

    case_dir.mkdir(parents=True, exist_ok=True)
    (case_dir / "initial_state.json").write_text(
        json.dumps(initial, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    with (case_dir / "history.prhist.jsonl").open("w", encoding="utf-8") as handle:
        for transaction in transactions:
            handle.write(
                json.dumps(transaction, sort_keys=True, separators=(",", ":")) + "\n"
            )

    raw_destination = case_dir / RAW_TRACE_NAME
    if raw_path.resolve() != raw_destination.resolve():
        shutil.copyfile(raw_path, raw_destination)

    manifest: dict[str, Any] = {
        "dataset_name": case_dir.parent.name,
        "format": "prhist-v4-multikv-join",
        "case_kind": "real_postgresql_multikv_join",
        "anomaly_mode": anomaly_mode,
        "generator": "multikv/multikv_trace_to_prhist.py",
        "source": "BenchBase multikv on PostgreSQL with real JOIN reads and trigger-captured writes",
        "tables": tables_manifest(),
        "join_predicate_mapping": (
            "Predicate reads execute orders JOIN items ON orders.value.item_key = "
            "items.value.item_key WHERE items.value.stock > 0."
        ),
        "key_mapping": "History keys are table-qualified as <table>:<table-local-key>.",
        "value_mapping": "History values are complete immutable JSON business rows.",
        "version_mapping": "Each distinct (key,value) pair is one PRHIST version.",
        "files": {
            "initial_state": "initial_state.json",
            "history": "history.prhist.jsonl",
            "raw_trace": RAW_TRACE_NAME,
        },
        "captured_aborted_attempts": len(raw_aborts),
        **stats,
    }
    verdict = expected_verdict.upper() if expected_verdict is not None else None
    if anomaly_mode == ANOMALY_WRITE_SKEW:
        if verdict not in {None, "REJECT"}:
            raise ConversionError("a complete write-skew core cannot be declared ACCEPT")
        manifest.update(
            {
                "expected_verdict": "REJECT",
                "anomaly_core_transactions": core_ids,
                "anomaly_mapping": (
                    "Two committed sessions read opposite item-qualified JOIN results "
                    "from their concurrent snapshots, then make those peer items unavailable."
                ),
            }
        )
    elif anomaly_mode == ANOMALY_LOST_UPDATE:
        if verdict not in {None, "REJECT"}:
            raise ConversionError("a complete lost-update core cannot be declared ACCEPT")
        manifest.update(
            {
                "expected_verdict": "REJECT",
                "anomaly_core_transactions": core_ids,
                "anomaly_mapping": (
                    "Two committed sessions read the same initial items:lu0 version, "
                    "then write different replacement versions to that same key."
                ),
            }
        )
    elif verdict is not None:
        manifest["expected_verdict"] = verdict
        if verdict == "ACCEPT":
            if serial_order is None:
                raise ConversionError(
                    "ACCEPT manifest requires an externally verified --serial-order"
                )
            transaction_ids = {transaction["txn"] for transaction in transactions}
            if set(serial_order) != transaction_ids or len(serial_order) != len(transaction_ids):
                raise ConversionError(
                    "--serial-order must contain every generated transaction exactly once"
                )
            manifest["serial_order"] = serial_order
    (case_dir / "manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Convert committed PostgreSQL multikv trace evidence to PRHIST v4."
    )
    parser.add_argument(
        "--raw",
        type=Path,
        required=True,
        help="JSONL generated by sql/02_export_multikv_trace.sql",
    )
    parser.add_argument("--case-dir", type=Path, required=True)
    parser.add_argument("--anomaly", choices=REAL_ANOMALY_MODES, default=ANOMALY_NONE)
    parser.add_argument("--expected-verdict", choices=("ACCEPT", "REJECT"))
    parser.add_argument("--serial-order", nargs="+", type=int)
    args = parser.parse_args()
    try:
        manifest = convert(
            args.raw,
            args.case_dir,
            anomaly_mode=args.anomaly,
            expected_verdict=args.expected_verdict,
            serial_order=args.serial_order,
        )
    except ConversionError as exc:
        parser.error(str(exc))
    print(json.dumps(manifest, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
