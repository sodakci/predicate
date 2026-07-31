#!/usr/bin/env python3
"""Audit a generated multi-table KV join-predicate PRHIST case."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any

from generate_multikv_join_history import (
    ANOMALY_MODES,
    ANOMALY_NONE,
    ANOMALY_WRITE_SKEW,
    count_stats,
)


class AuditError(ValueError):
    """Raised when a generated multi-table KV history is internally inconsistent."""


ANOMALY_LOST_UPDATE = "lost-update"
REAL_ANOMALY_MODES = tuple(ANOMALY_MODES) + (ANOMALY_LOST_UPDATE,)
LOST_UPDATE_KEY = "items:lu0"
ITEM_KEY_FILTER = re.compile(r"i\.value\.item_key = '([^']+)'")


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as handle:
        for lineno, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as exc:
                raise AuditError(f"{path}:{lineno}: invalid JSON: {exc}") from exc
            if not isinstance(row, dict):
                raise AuditError(f"{path}:{lineno}: expected JSON object")
            rows.append(row)
    return rows


def require_int(value: Any, context: str) -> int:
    if isinstance(value, bool):
        raise AuditError(f"{context}: expected integer, got boolean")
    try:
        return int(value)
    except (TypeError, ValueError) as exc:
        raise AuditError(f"{context}: expected integer, got {value!r}") from exc


def require_text(value: Any, context: str) -> str:
    if not isinstance(value, str) or not value:
        raise AuditError(f"{context}: expected non-empty string")
    return value


def load_case(case_dir: Path) -> tuple[list[dict[str, Any]], list[dict[str, Any]], dict[str, Any]]:
    initial_path = case_dir / "initial_state.json"
    history_path = case_dir / "history.prhist.jsonl"
    manifest_path = case_dir / "manifest.json"
    if not initial_path.is_file() or not history_path.is_file():
        raise AuditError(f"{case_dir}: missing initial_state.json or history.prhist.jsonl")
    initial = json.loads(initial_path.read_text(encoding="utf-8"))
    if not isinstance(initial, list):
        raise AuditError(f"{initial_path}: expected JSON array")
    transactions = load_jsonl(history_path)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.is_file() else {}
    if manifest and manifest.get("format") != "prhist-v4-multikv-join":
        raise AuditError(f"{manifest_path}: unexpected format {manifest.get('format')!r}")
    return initial, transactions, manifest


def relational_value_from_record(record: dict[str, Any], context: str) -> tuple[str, dict[str, Any]]:
    key = require_text(record.get("key"), f"{context}.key")
    if ":" not in key:
        raise AuditError(f"{context}: key must include table namespace, got {key!r}")
    table, local_key = key.split(":", 1)
    value = record.get("value")
    if not isinstance(value, dict):
        raise AuditError(f"{context}.value: expected relational row object")
    expected_columns = {
        "users": {"user_key", "region", "active"},
        "items": {"item_key", "stock", "price"},
        "orders": {"order_key", "user_key", "item_key", "status"},
    }.get(table)
    if expected_columns is None or set(value) != expected_columns:
        raise AuditError(f"{context}.value: unexpected columns for table {table!r}")
    primary_key_column = {
        "users": "user_key",
        "items": "item_key",
        "orders": "order_key",
    }[table]
    if require_text(value.get(primary_key_column), f"{context}.value.{primary_key_column}") != local_key:
        raise AuditError(f"{context}.value: primary key does not match {key!r}")
    if table == "users":
        require_text(value.get("region"), f"{context}.value.region")
        if not isinstance(value.get("active"), bool):
            raise AuditError(f"{context}.value.active: expected boolean")
    elif table == "items":
        require_int(value.get("stock"), f"{context}.value.stock")
        require_int(value.get("price"), f"{context}.value.price")
    else:
        require_text(value.get("user_key"), f"{context}.value.user_key")
        require_text(value.get("item_key"), f"{context}.value.item_key")
        require_text(value.get("status"), f"{context}.value.status")
    return key, value


def write_version_from_record(
    record: dict[str, Any],
    context: str,
) -> tuple[str, dict[str, Any]]:
    key, value = relational_value_from_record(record, context)
    if "write_id" in record or "source_write_id" in record:
        raise AuditError(f"{context}: aligned outer format cannot contain write provenance")
    return key, value


def check_read_value(
    ref: dict[str, Any],
    state: dict[str, dict[str, Any]],
    context: str,
) -> None:
    key, value = relational_value_from_record(ref, context)
    if "write_id" in ref or "source_write_id" in ref:
        raise AuditError(f"{context}: read value cannot contain write provenance")
    current = state.get(key)
    if current is None:
        raise AuditError(f"{context}: key {key} is not present")
    if current != value:
        raise AuditError(f"{context}: read value does not match current value")


def version_identity(key: str, value: dict[str, Any]) -> tuple[str, str]:
    return key, json.dumps(value, sort_keys=True, separators=(",", ":"))


def check_known_read_value(
    ref: dict[str, Any],
    known_key_values: set[tuple[str, str]],
    context: str,
) -> None:
    key, value = relational_value_from_record(ref, context)
    if "write_id" in ref or "source_write_id" in ref:
        raise AuditError(f"{context}: read value cannot contain write provenance")
    if version_identity(key, value) not in known_key_values:
        raise AuditError(f"{context}: unresolved read version for {key}")


def compare_join_predicate(
    op: dict[str, Any],
    state: dict[str, dict[str, Any]],
    context: str,
    known_key_values: set[tuple[str, str]] | None = None,
) -> str | None:
    query = op.get("query")
    if not isinstance(query, dict):
        raise AuditError(f"{context}.query: expected object")
    where = query.get("where")
    item_key_filter: str | None = None
    if where == ["i.value.stock > 0"]:
        pass
    elif isinstance(where, list) and len(where) == 2 and where[0] == "i.value.stock > 0":
        item_key_match = ITEM_KEY_FILTER.fullmatch(str(where[1]))
        if item_key_match is None:
            raise AuditError(f"{context}.query: unexpected structured join query")
        item_key_filter = item_key_match.group(1)
    else:
        raise AuditError(f"{context}.query: unexpected structured join query")
    expected_query = {
        "select": {
            "columns": [
                "o.value.order_key",
                "i.value.item_key",
                "o.value.user_key",
                "i.value.stock",
            ],
            "distinct": False,
        },
        "from": {"relation": "orders", "alias": "o"},
        "joins": [
            {
                "type": "INNER",
                "relation": "items",
                "alias": "i",
                "on": ["o.value.item_key = i.value.item_key"],
            }
        ],
        "where": where,
    }
    if query != expected_query:
        raise AuditError(f"{context}.query: unexpected structured join query")
    result = op.get("result")
    if not isinstance(result, dict):
        raise AuditError(f"{context}.result: expected object")
    values = result.get("values")
    inputs = result.get("inputs")
    if not isinstance(values, list) or not isinstance(inputs, list):
        raise AuditError(f"{context}.result: expected values and inputs lists")
    for value_index, value_row in enumerate(values):
        if not isinstance(value_row, dict):
            raise AuditError(f"{context}.result.values[{value_index}]: expected object")
        if set(value_row) != {"order_key", "item_key", "user_key", "stock"}:
            raise AuditError(f"{context}.result.values[{value_index}]: expected SQL result columns only")
    for index, item in enumerate(inputs):
        if not isinstance(item, dict):
            raise AuditError(f"{context}.result.inputs[{index}]: expected object")
        if known_key_values is None:
            check_read_value(item, state, f"{context}.result.inputs[{index}]")
        else:
            check_known_read_value(
                item,
                known_key_values,
                f"{context}.result.inputs[{index}]",
            )

    if known_key_values is not None:
        inputs_by_key: dict[str, dict[str, Any]] = {}
        for index, item in enumerate(inputs):
            key = item["key"]
            if key in inputs_by_key:
                raise AuditError(f"{context}.result.inputs[{index}]: duplicate key {key!r}")
            inputs_by_key[key] = item

        items_by_local_key = {
            item["value"]["item_key"]: item
            for key, item in inputs_by_key.items()
            if key.startswith("items:")
        }
        orders = sorted(
            (
                (key, item)
                for key, item in inputs_by_key.items()
                if key.startswith("orders:")
            ),
            key=lambda entry: entry[0],
        )
        expected_values: list[dict[str, Any]] = []
        expected_inputs: list[dict[str, Any]] = []
        seen_inputs: set[str] = set()
        for order_key, order_input in orders:
            order_value = order_input["value"]
            item_input = items_by_local_key.get(order_value["item_key"])
            if item_input is None:
                continue
            item_key = item_input["key"]
            item_value = item_input["value"]
            stock = require_int(
                item_value.get("stock"),
                f"{context}.result.inputs[{item_key}].stock",
            )
            if stock <= 0:
                continue
            if (
                item_key_filter is not None
                and item_value["item_key"] != item_key_filter
            ):
                continue
            projected = {
                "order_key": order_value["order_key"],
                "item_key": item_value["item_key"],
                "user_key": order_value["user_key"],
                "stock": stock,
            }
            expected_values.append(projected)
            for source_key in (order_key, item_key):
                if source_key not in seen_inputs:
                    seen_inputs.add(source_key)
                    expected_inputs.append(inputs_by_key[source_key])
        if values != expected_values:
            raise AuditError(
                f"{context}.result.values: does not match relational query evaluation"
            )
        if inputs != expected_inputs:
            raise AuditError(
                f"{context}.result.inputs: does not match relational query provenance"
            )
        return item_key_filter

    items_by_key = {
        value["item_key"]: (key, value)
        for key, value in state.items()
        if key.startswith("items:")
    }
    orders = sorted(
        (
            (key, value)
            for key, value in state.items()
            if key.startswith("orders:")
        ),
        key=lambda item: item[0],
    )
    expected_values: list[dict[str, Any]] = []
    expected_inputs: list[dict[str, Any]] = []
    seen_inputs: set[str] = set()
    for order_key, order in orders:
        item_source = items_by_key.get(order["item_key"])
        if item_source is None:
            continue
        item_key, item = item_source
        stock = require_int(item.get("stock"), f"{context}.state[{item_key}].stock")
        if stock <= 0:
            continue
        if item_key_filter is not None and item["item_key"] != item_key_filter:
            continue
        expected_values.append({
            "order_key": order["order_key"],
            "item_key": item["item_key"],
            "user_key": order["user_key"],
            "stock": stock,
        })
        for source_key, source_value in (
            (order_key, order),
            (item_key, item),
        ):
            if source_key in seen_inputs:
                continue
            seen_inputs.add(source_key)
            expected_inputs.append({
                "key": source_key,
                "value": source_value,
            })
    if values != expected_values:
        raise AuditError(f"{context}.result.values: does not match relational query evaluation")
    if inputs != expected_inputs:
        raise AuditError(f"{context}.result.inputs: does not match relational query provenance")
    return item_key_filter


def validate_write_skew_core(
    core_records: list[dict[str, Any]],
    snapshot: dict[str, dict[str, Any]],
) -> None:
    if len(core_records) != 2:
        raise AuditError("write-skew mode requires exactly two leading core transactions")
    if core_records[0]["session"] == core_records[1]["session"]:
        raise AuditError("write-skew core transactions must use different sessions")

    write_keys = [record["writes"][0][0] for record in core_records if len(record["writes"]) == 1]
    if len(write_keys) != 2 or write_keys[0] == write_keys[1]:
        raise AuditError("write-skew core transactions must each write one different key")

    for index, record in enumerate(core_records):
        if record["op_types"] != ["pr", "w"]:
            raise AuditError(
                f"txn={record['txn']}: write-skew core must contain one predicate read then one write"
            )
        if len(record["predicate_filters"]) != 1 or len(record["writes"]) != 1:
            raise AuditError(f"txn={record['txn']}: incomplete write-skew core")

        own_write_key, own_write_value = record["writes"][0]
        peer_write_key = write_keys[1 - index]
        peer_local_key = peer_write_key.split(":", 1)[1]
        if record["predicate_filters"][0] != peer_local_key:
            raise AuditError(
                f"txn={record['txn']}: predicate must target the item written by the peer"
            )
        if peer_write_key not in record["predicate_inputs"]:
            raise AuditError(
                f"txn={record['txn']}: predicate inputs do not include peer write key"
            )
        if own_write_key in record["predicate_inputs"]:
            raise AuditError(
                f"txn={record['txn']}: predicate must not read its own write key"
            )

        before = snapshot.get(own_write_key)
        if (
            not own_write_key.startswith("items:")
            or before is None
            or int(before.get("stock", 0)) <= 0
            or int(own_write_value.get("stock", 0)) > 0
        ):
            raise AuditError(
                f"txn={record['txn']}: write must make an initially available item unavailable"
            )


def validate_lost_update_core(
    core_records: list[dict[str, Any]],
    snapshot: dict[str, dict[str, Any]],
) -> None:
    if len(core_records) != 2:
        raise AuditError("lost-update mode requires exactly two leading core transactions")
    if core_records[0]["session"] == core_records[1]["session"]:
        raise AuditError("lost-update core transactions must use different sessions")

    initial_value = snapshot.get(LOST_UPDATE_KEY)
    if initial_value is None:
        raise AuditError(f"lost-update initial state lacks {LOST_UPDATE_KEY}")
    written_values: list[dict[str, Any]] = []
    for record in core_records:
        if record["op_types"] != ["r", "w"]:
            raise AuditError(
                f"txn={record['txn']}: lost-update core must contain one point read then one write"
            )
        if len(record["reads"]) != 1 or len(record["writes"]) != 1:
            raise AuditError(f"txn={record['txn']}: incomplete lost-update core")
        read_key, read_value = record["reads"][0]
        write_key, write_value = record["writes"][0]
        if read_key != LOST_UPDATE_KEY or write_key != LOST_UPDATE_KEY:
            raise AuditError(
                f"txn={record['txn']}: lost-update core must access only {LOST_UPDATE_KEY}"
            )
        if read_value != initial_value:
            raise AuditError(
                f"txn={record['txn']}: lost-update read must observe the initial value"
            )
        if write_value == initial_value:
            raise AuditError(
                f"txn={record['txn']}: lost-update write must differ from the initial value"
            )
        written_values.append(write_value)
    if written_values[0] == written_values[1]:
        raise AuditError("lost-update core transactions must write different values")


def audit(case_dir: Path) -> dict[str, Any]:
    initial, transactions, manifest = load_case(case_dir)
    state: dict[str, dict[str, Any]] = {}
    known_key_values: set[tuple[str, str]] = set()

    def register_key_value(key: str, value: dict[str, Any], context: str) -> None:
        key_value = version_identity(key, value)
        if key_value in known_key_values:
            raise AuditError(f"{context}: duplicate write (key,value) for {key}")
        known_key_values.add(key_value)

    for index, item in enumerate(initial):
        if not isinstance(item, dict):
            raise AuditError(f"initial[{index}]: expected object")
        key, value = write_version_from_record(item, f"initial[{index}]")
        register_key_value(key, value, f"initial[{index}]")
        state[key] = value

    is_real_trace = manifest.get("case_kind") == "real_postgresql_multikv_join"
    if is_real_trace:
        for txn_index, transaction in enumerate(transactions):
            ops = transaction.get("ops")
            if not isinstance(ops, list):
                raise AuditError(f"transactions[{txn_index}].ops: expected list")
            for op_index, op in enumerate(ops):
                if isinstance(op, dict) and op.get("type") == "w":
                    context = f"txn={transaction.get('txn')}.ops[{op_index}]"
                    key, value = write_version_from_record(op, context)
                    register_key_value(key, value, context)

    anomaly_mode = manifest.get("anomaly_mode", ANOMALY_NONE) if manifest else ANOMALY_NONE
    if anomaly_mode not in REAL_ANOMALY_MODES:
        raise AuditError(f"manifest.anomaly_mode: unsupported mode {anomaly_mode!r}")
    scripted_anomaly = anomaly_mode in {ANOMALY_WRITE_SKEW, ANOMALY_LOST_UPDATE}
    if scripted_anomaly and len(transactions) < 2:
        raise AuditError(f"{anomaly_mode} mode requires two leading core transactions")
    anomaly_snapshot = dict(state)
    anomaly_records: list[dict[str, Any]] = []
    pending_anomaly_writes: list[tuple[str, dict[str, Any]]] = []

    seen_txns: set[int] = set()
    last_session_seq: dict[int, int] = {}
    for txn_index, transaction in enumerate(transactions):
        txn_id = require_int(transaction.get("txn"), f"transactions[{txn_index}].txn")
        if txn_id in seen_txns:
            raise AuditError(f"duplicate transaction id {txn_id}")
        seen_txns.add(txn_id)
        if transaction.get("status") != "commit":
            raise AuditError(f"txn={txn_id}: expected committed transaction")
        if "txn_type" in transaction:
            raise AuditError(f"txn={txn_id}: aligned outer format cannot contain txn_type")
        session = require_int(transaction.get("session"), f"txn={txn_id}.session")
        session_seq = require_int(transaction.get("session_seq"), f"txn={txn_id}.session_seq")
        previous_seq = last_session_seq.get(session)
        if previous_seq is not None and session_seq <= previous_seq:
            raise AuditError(f"session {session} is not strictly ordered at txn {txn_id}")
        last_session_seq[session] = session_seq

        ops = transaction.get("ops")
        if not isinstance(ops, list):
            raise AuditError(f"txn={txn_id}.ops: expected list")
        in_anomaly_core = scripted_anomaly and txn_index < 2
        transaction_state = dict(anomaly_snapshot) if in_anomaly_core else state
        core_record: dict[str, Any] = {
            "txn": txn_id,
            "session": session,
            "op_types": [],
            "reads": [],
            "predicate_filters": [],
            "predicate_inputs": set(),
            "writes": [],
        }
        for op_index, op in enumerate(ops):
            if not isinstance(op, dict):
                raise AuditError(f"txn={txn_id}.ops[{op_index}]: expected object")
            context = f"txn={txn_id}.ops[{op_index}]"
            op_type = op.get("type")
            core_record["op_types"].append(op_type)
            if (
                anomaly_mode == ANOMALY_LOST_UPDATE
                and not in_anomaly_core
                and (
                    op.get("key") == LOST_UPDATE_KEY
                    or any(
                        isinstance(reference, dict)
                        and reference.get("key") == LOST_UPDATE_KEY
                        for reference in op.get("result", {}).get("inputs", [])
                    )
                )
            ):
                raise AuditError(
                    f"non-core transaction {txn_id} references {LOST_UPDATE_KEY}"
                )
            if op_type == "r":
                if is_real_trace and not in_anomaly_core:
                    check_known_read_value(op, known_key_values, context)
                else:
                    check_read_value(op, transaction_state, context)
                core_record["reads"].append(
                    relational_value_from_record(op, context)
                )
            elif op_type == "w":
                key, value = write_version_from_record(op, context)
                if not is_real_trace:
                    register_key_value(key, value, context)
                transaction_state[key] = value
                core_record["writes"].append((key, value))
            elif op_type == "pr":
                item_key_filter = compare_join_predicate(
                    op,
                    transaction_state,
                    context,
                    (
                        known_key_values
                        if is_real_trace and not in_anomaly_core
                        else None
                    ),
                )
                core_record["predicate_filters"].append(item_key_filter)
                core_record["predicate_inputs"].update(
                    item["key"] for item in op["result"]["inputs"]
                )
            else:
                raise AuditError(f"{context}: unsupported operation type {op_type!r}")
        if in_anomaly_core:
            anomaly_records.append(core_record)
            pending_anomaly_writes.extend(core_record["writes"])
            if txn_index == 1:
                if anomaly_mode == ANOMALY_WRITE_SKEW:
                    validate_write_skew_core(anomaly_records, anomaly_snapshot)
                else:
                    validate_lost_update_core(anomaly_records, anomaly_snapshot)
                expected_core_ids = [record["txn"] for record in anomaly_records]
                if (
                    "anomaly_core_transactions" in manifest
                    and manifest.get("anomaly_core_transactions") != expected_core_ids
                ):
                    raise AuditError(
                        "manifest.anomaly_core_transactions does not match the leading core"
                    )
                for key, value in pending_anomaly_writes:
                    state[key] = value

    stats = count_stats(initial, transactions)
    if manifest:
        for key, value in stats.items():
            if manifest.get(key) != value:
                raise AuditError(f"manifest.{key}: expected {value}, got {manifest.get(key)!r}")
    return {"valid": True, "case_dir": str(case_dir), **stats}


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate a multi-table KV join-predicate PRHIST case.")
    parser.add_argument("case_dir", type=Path)
    args = parser.parse_args()
    try:
        report = audit(args.case_dir)
    except (AuditError, OSError, json.JSONDecodeError) as exc:
        print(json.dumps({"valid": False, "error": str(exc)}, indent=2), file=sys.stderr)
        return 1
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
