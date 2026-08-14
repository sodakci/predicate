#!/usr/bin/env python3
"""Convert committed BenchBase/PostgreSQL kvpredicate evidence to PRHIST v2."""

from __future__ import annotations

import argparse
import json
import shutil
from collections import Counter
from pathlib import Path
from typing import Any, Iterable, Iterator

ABSENT_BASE = -1_000_000_000_000
SOURCE_FIELDS = frozenset({"source_write_id", "source_txn", "source_op_index"})
ANOMALY_TRACE_MARKER = "|kvpredicate-anomaly:"
WRITE_SKEW_ROLES = frozenset({"left", "right"})


class ConversionError(ValueError):
    """Raised when evidence cannot honestly be represented as PRHIST."""


def iter_jsonl(path: Path) -> Iterator[dict[str, Any]]:
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
            yield row


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    return list(iter_jsonl(path))


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
    if read_versions is None:
        read_versions = results
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


def parse_anomaly_trace_tag(row: dict[str, Any], context: str) -> dict[str, Any] | None:
    txn_type = row.get("txn_type")
    if not isinstance(txn_type, str) or ANOMALY_TRACE_MARKER not in txn_type:
        return None
    payload = txn_type.rsplit(ANOMALY_TRACE_MARKER, 1)[1]
    fields = payload.split(":")
    if len(fields) != 5:
        raise ConversionError(f"{context}.txn_type: malformed anomaly trace tag")
    mode, variant, role, seed_text, isolate_text = fields
    if mode != "write-skew":
        raise ConversionError(f"{context}.txn_type: unsupported anomaly mode {mode!r}")
    if variant not in {"injected", "control"}:
        raise ConversionError(f"{context}.txn_type: unsupported anomaly variant {variant!r}")
    if role not in WRITE_SKEW_ROLES:
        raise ConversionError(f"{context}.txn_type: unsupported write-skew role {role!r}")
    if isolate_text not in {"true", "false"}:
        raise ConversionError(f"{context}.txn_type: invalid background isolation flag")
    return {
        "mode": mode,
        "variant": variant,
        "role": role,
        "layout_seed": require_int(seed_text, f"{context}.txn_type.seed"),
        "background_predicates_isolated": isolate_text == "true",
    }


def anomaly_core_transaction(
    transaction: dict[str, Any], tag: dict[str, Any], context: str
) -> dict[str, Any]:
    ops = transaction["ops"]
    predicate_indexes = [index for index, op in enumerate(ops) if op.get("type") == "pr"]
    write_indexes = [index for index, op in enumerate(ops) if op.get("type") == "w"]
    if len(predicate_indexes) != 1 or len(write_indexes) != 1:
        raise ConversionError(
            f"{context}: tagged write-skew transaction must contain one predicate and one write"
        )
    predicate_index = predicate_indexes[0]
    write_index = write_indexes[0]
    if predicate_index >= write_index:
        raise ConversionError(f"{context}: write-skew predicate must precede its write")

    role = tag["role"]
    expected_where = ["value = 0"] if role == "left" else ["value = 1"]
    expected_predicate_key = "kv:0" if role == "left" else "kv:1"
    expected_padding_key = "kv:3" if role == "left" else "kv:4"
    if role == "left":
        expected_write_key = "kv:1"
    elif tag["variant"] == "injected":
        expected_write_key = "kv:0"
    else:
        expected_write_key = "kv:2"

    predicate = ops[predicate_index]
    if predicate["query"]["where"] != expected_where:
        raise ConversionError(f"{context}: write-skew predicate does not match role {role}")
    predicate_input_keys = [item["key"] for item in predicate["result"]["inputs"]]
    predicate_result_keys = [f"kv:{item['k']}" for item in predicate["result"]["values"]]
    if predicate_input_keys != [expected_predicate_key]:
        raise ConversionError(
            f"{context}: write-skew predicate inputs must be [{expected_predicate_key!r}]"
        )
    if predicate_result_keys != [expected_predicate_key]:
        raise ConversionError(
            f"{context}: write-skew predicate result must be [{expected_predicate_key!r}]"
        )

    write = ops[write_index]
    if write["key"] != expected_write_key:
        raise ConversionError(
            f"{context}: {role} {tag['variant']} write must target {expected_write_key}"
        )
    for index, op in enumerate(ops):
        if index in {predicate_index, write_index}:
            continue
        if op.get("type") != "r" or op.get("key") != expected_padding_key:
            raise ConversionError(
                f"{context}.ops[{index}]: write-skew padding must read {expected_padding_key}"
            )

    return {
        **tag,
        "txn": transaction["txn"],
        "session": transaction["session"],
        "session_seq": transaction["session_seq"],
        "visible_operation_count": len(ops),
        "predicate_op_index": predicate_index,
        "predicate_where": expected_where,
        "predicate_input_keys": predicate_input_keys,
        "predicate_result_keys": predicate_result_keys,
        "write_op_index": write_index,
        "write_key": write["key"],
        "write_value": write["value"],
        "padding_read_key": expected_padding_key,
    }


def build_anomaly_manifest(
    core_transactions: list[dict[str, Any]],
) -> dict[str, Any] | None:
    if not core_transactions:
        return None
    if len(core_transactions) != 2:
        raise ConversionError("write-skew trace must contain exactly two tagged core transactions")
    by_role = {transaction["role"]: transaction for transaction in core_transactions}
    if set(by_role) != WRITE_SKEW_ROLES:
        raise ConversionError("write-skew trace must contain one left and one right core transaction")

    common_fields = ("mode", "variant", "layout_seed", "background_predicates_isolated")
    left = by_role["left"]
    right = by_role["right"]
    for field in common_fields:
        if left[field] != right[field]:
            raise ConversionError(f"write-skew core transactions disagree on {field}")

    expected_edges = [
        {
            "type": "PR_RW",
            "from_role": "right",
            "from_txn": right["txn"],
            "to_role": "left",
            "to_txn": left["txn"],
            "key": "kv:1",
        }
    ]
    if left["variant"] == "injected":
        expected_edges.insert(
            0,
            {
                "type": "PR_RW",
                "from_role": "left",
                "from_txn": left["txn"],
                "to_role": "right",
                "to_txn": right["txn"],
                "key": "kv:0",
            },
        )

    public_transactions = []
    for role in ("left", "right"):
        transaction = by_role[role]
        public_transactions.append(
            {
                key: value
                for key, value in transaction.items()
                if key not in common_fields
            }
        )
    return {
        "mode": left["mode"],
        "variant": left["variant"],
        "layout_seed": left["layout_seed"],
        "background_predicates_isolated": left["background_predicates_isolated"],
        "reserved_keys": {
            "predicate_cycle": ["kv:0", "kv:1"],
            "control_sink": "kv:2",
            "left_padding": "kv:3",
            "right_padding": "kv:4",
        },
        "core_transactions": public_transactions,
        "expected_dependency_edges": expected_edges,
        "expected_cycle": left["variant"] == "injected",
    }


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

    operation_counts: Counter[str] = Counter()
    seen_txns: set[int] = set()
    last_session_seq: dict[int, int] = {}
    unresolved_reads: dict[int, tuple[str, str]] = {}
    transaction_count = 0

    def check_read(reference: dict[str, Any], context: str) -> None:
        version = require_int(reference.get("value"), f"{context}.value")
        key = require_text(reference.get("key"), f"{context}.key")
        expected = known_versions.get(version)
        if expected is None:
            previous = unresolved_reads.get(version)
            if previous is not None and previous[0] != key:
                raise ConversionError(f"{context}: unresolved version {version} disagrees on key")
            unresolved_reads.setdefault(version, (key, context))
        elif key != expected:
            raise ConversionError(f"{context}: version {version} disagrees on key")

    for row_index, txn in enumerate(transactions):
        transaction_count += 1
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
                key = require_text(op.get("key"), f"txn={txn_id}.ops[{op_index}].key")
                unresolved = unresolved_reads.pop(version, None)
                if unresolved is not None and unresolved[0] != key:
                    raise ConversionError(f"{unresolved[1]}: version {version} disagrees on key")
                known_versions[version] = key
            if op.get("type") == "r":
                check_read(op, f"txn={txn_id}.ops[{op_index}]")
            elif op.get("type") == "pr":
                for read_index, reference in enumerate(op["result"]["inputs"]):
                    check_read(
                        reference,
                        f"txn={txn_id}.ops[{op_index}].result.inputs[{read_index}]",
                    )

    if unresolved_reads:
        version, (_, context) = next(iter(unresolved_reads.items()))
        raise ConversionError(f"{context}: unresolved version {version}")

    return {
        "initial_keys": len(initial_list),
        "transactions": transaction_count,
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
    initial: list[dict[str, Any]] = []
    case_dir.mkdir(parents=True, exist_ok=True)
    initial_path = case_dir / "initial_state.json"
    history_path = case_dir / "history.prhist.jsonl"
    history_tmp_path = case_dir / "history.prhist.jsonl.tmp"
    absent_initials: dict[str, dict[str, Any]] = {}
    transaction_ids: set[int] = set()
    initial_count = 0
    transaction_count = 0
    abort_count = 0
    anomaly_core_transactions: list[dict[str, Any]] = []
    section = 0
    previous_transaction_order: tuple[int, int, int] | None = None
    try:
        with history_tmp_path.open("w", encoding="utf-8") as history_handle:
            for row in iter_jsonl(raw_path):
                record_type = row.get("record_type")
                if record_type == "initial":
                    if section > 0:
                        raise ConversionError("raw trace initial rows must precede transactions")
                    context = f"raw.initial[{initial_count}]"
                    reject_source_fields(row, context)
                    initial.append({
                        "key": require_text(row.get("key"), f"{context}.key"),
                        "value": require_int(row.get("value"), f"{context}.value"),
                    })
                    initial_count += 1
                elif record_type == "txn":
                    if not initial:
                        raise ConversionError(
                            "raw trace has no initial versions; run snapshot_initial_state before execute"
                        )
                    if section > 1:
                        raise ConversionError("raw trace transactions must precede abort rows")
                    section = 1
                    transaction = convert_transaction(row, f"raw.txn[{transaction_count}]")
                    anomaly_tag = parse_anomaly_trace_tag(
                        row, f"raw.txn[{transaction_count}]"
                    )
                    if anomaly_tag is not None:
                        anomaly_core_transactions.append(
                            anomaly_core_transaction(
                                transaction,
                                anomaly_tag,
                                f"raw.txn[{transaction_count}]",
                            )
                        )
                    transaction_order = (
                        transaction["session"],
                        transaction["session_seq"],
                        transaction["txn"],
                    )
                    if (
                        previous_transaction_order is not None
                        and transaction_order < previous_transaction_order
                    ):
                        raise ConversionError("raw trace transactions are not in session order")
                    previous_transaction_order = transaction_order
                    transaction_ids.add(transaction["txn"])
                    raw_ops = row.get("ops")
                    if isinstance(raw_ops, list):
                        for op_index, op in enumerate(raw_ops):
                            if not isinstance(op, dict) or op.get("type") != "r":
                                continue
                            absent = absent_initial_from_read(
                                op, f"raw.txn[{transaction_count}].ops[{op_index}]"
                            )
                            if absent is not None:
                                absent_initials.setdefault(absent["key"], absent)
                    history_handle.write(
                        json.dumps(transaction, sort_keys=True, separators=(",", ":")) + "\n"
                    )
                    transaction_count += 1
                elif record_type == "abort":
                    if parse_anomaly_trace_tag(row, f"raw.abort[{abort_count}]") is not None:
                        raise ConversionError("tagged anomaly core transaction aborted")
                    section = 2
                    abort_count += 1
                else:
                    raise ConversionError(f"unknown raw trace record type: {record_type!r}")

        if not initial:
            raise ConversionError(
                "raw trace has no initial versions; run snapshot_initial_state before execute"
            )
        initial.extend(absent_initials.values())
        initial.sort(key=lambda row: row["key"])
        anomaly_injection = build_anomaly_manifest(anomaly_core_transactions)
        stats = validate_structure(initial, iter_jsonl(history_tmp_path))
        initial_path.write_text(
            json.dumps(initial, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        history_tmp_path.replace(history_path)
    except Exception:
        history_tmp_path.unlink(missing_ok=True)
        raise

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
        "captured_aborted_attempts": abort_count,
        **stats,
    }
    if anomaly_injection is not None:
        manifest["anomaly_injection"] = anomaly_injection
    if expected_verdict is not None:
        manifest["expected_verdict"] = expected_verdict.upper()
        if expected_verdict.upper() == "ACCEPT":
            if serial_order is None:
                raise ConversionError("ACCEPT manifest requires an externally verified --serial-order; it is never inferred")
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
