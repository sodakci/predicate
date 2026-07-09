#!/usr/bin/env python3
"""
Lightweight static validation for generated PRHIST v2 black-box suites.

This checker validates source_write_id integrity, session sequencing, predicate
result shape, ACCEPT serial replay completeness, and REJECT conflicts.json
references.  It does not invoke the Java SER solver.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys
from dataclasses import dataclass
from typing import Dict, Iterable, List, Optional, Sequence, Tuple


@dataclass(frozen=True)
class SourceVersion:
    key: str
    value: int
    semantic: int
    write_id: int
    source_txn: int
    source_op_index: int


class ValidationError(Exception):
    pass


def required_int(node: dict, field: str, context: str) -> int:
    if field not in node or not isinstance(node[field], int):
        raise ValidationError(f"{context}: missing integer field {field}")
    return node[field]


def required_str(node: dict, field: str, context: str) -> str:
    if field not in node or not isinstance(node[field], str):
        raise ValidationError(f"{context}: missing string field {field}")
    return node[field]


def find_hist_dirs(root: pathlib.Path) -> List[pathlib.Path]:
    if root.is_file() and root.name == "history.prhist.jsonl":
        return [root.parent]
    if (root / "history.prhist.jsonl").is_file():
        return [root]
    return sorted(path.parent for path in root.rglob("history.prhist.jsonl"))


def load_json(path: pathlib.Path) -> object:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def load_history(path: pathlib.Path) -> List[dict]:
    rows = []
    with path.open("r", encoding="utf-8") as f:
        for line_number, line in enumerate(f, start=1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValidationError(f"{path}:{line_number}: invalid JSON: {exc}") from exc
            rows.append(row)
    return rows


def source_from_row(row: dict, context: str) -> SourceVersion:
    return SourceVersion(
        key=required_str(row, "key", context),
        value=required_int(row, "value", context),
        semantic=required_int(row, "semantic", context),
        write_id=required_int(row, "source_write_id", context),
        source_txn=required_int(row, "source_txn", context),
        source_op_index=required_int(row, "source_op_index", context),
    )


def write_from_op(op: dict, txn_id: int, op_index: int, context: str) -> SourceVersion:
    return SourceVersion(
        key=required_str(op, "key", context),
        value=required_int(op, "value", context),
        semantic=required_int(op, "semantic", context),
        write_id=required_int(op, "write_id", context),
        source_txn=txn_id,
        source_op_index=op_index,
    )


def validate_hist(hist_dir: pathlib.Path) -> Tuple[str, str]:
    initial_path = hist_dir / "initial_state.json"
    history_path = hist_dir / "history.prhist.jsonl"
    manifest_path = hist_dir / "manifest.json"
    if not initial_path.is_file():
        raise ValidationError(f"{hist_dir}: missing initial_state.json")
    if not manifest_path.is_file():
        raise ValidationError(f"{hist_dir}: missing manifest.json")

    initial_rows = load_json(initial_path)
    if not isinstance(initial_rows, list):
        raise ValidationError(f"{initial_path}: initial state must be a JSON array")
    manifest = load_json(manifest_path)
    if not isinstance(manifest, dict):
        raise ValidationError(f"{manifest_path}: manifest must be a JSON object")
    history = load_history(history_path)

    version_by_id: Dict[int, SourceVersion] = {}
    initial_keys = set()
    for index, row in enumerate(initial_rows):
        if not isinstance(row, dict):
            raise ValidationError(f"{initial_path}: initial row {index} is not an object")
        version = source_from_row(row, f"{initial_path}:initial[{index}]")
        if version.write_id in version_by_id:
            raise ValidationError(f"{hist_dir}: duplicate write_id {version.write_id}")
        if version.key in initial_keys:
            raise ValidationError(f"{hist_dir}: duplicate initial key {version.key}")
        initial_keys.add(version.key)
        version_by_id[version.write_id] = version

    txn_by_id: Dict[int, dict] = {}
    last_seq_by_session: Dict[int, int] = {}
    seen_session_seq = set()
    for line_index, txn in enumerate(history):
        context = f"{history_path}:txn_line[{line_index}]"
        if not isinstance(txn, dict):
            raise ValidationError(f"{context}: transaction row is not an object")
        txn_id = required_int(txn, "txn", context)
        session = required_int(txn, "session", context)
        session_seq = required_int(txn, "session_seq", context)
        if txn_id in txn_by_id:
            raise ValidationError(f"{hist_dir}: duplicate txn id {txn_id}")
        if txn.get("status", "").lower() != "commit":
            raise ValidationError(f"{context}: status must be commit")
        if not isinstance(txn.get("ops"), list):
            raise ValidationError(f"{context}: ops must be an array")
        if session in last_seq_by_session and session_seq <= last_seq_by_session[session]:
            raise ValidationError(
                f"{hist_dir}: session {session} session_seq is not strictly increasing in JSONL order"
            )
        if (session, session_seq) in seen_session_seq:
            raise ValidationError(f"{hist_dir}: duplicate session/session_seq {(session, session_seq)}")
        seen_session_seq.add((session, session_seq))
        last_seq_by_session[session] = session_seq
        txn_by_id[txn_id] = txn

        for op_index, op in enumerate(txn["ops"]):
            op_context = f"{context}:op[{op_index}]"
            if not isinstance(op, dict):
                raise ValidationError(f"{op_context}: op is not an object")
            op_type = required_str(op, "type", op_context)
            if op_type == "w":
                version = write_from_op(op, txn_id, op_index, op_context)
                if version.write_id in version_by_id:
                    raise ValidationError(f"{hist_dir}: duplicate write_id {version.write_id}")
                version_by_id[version.write_id] = version
            elif op_type == "r":
                source_from_row(op, op_context)
            elif op_type == "pr":
                validate_predicate_shape(op, None, op_context)
            else:
                raise ValidationError(f"{op_context}: unsupported op type {op_type}")

    # A second pass catches reads that refer to writes appearing later in JSONL.
    for line_index, txn in enumerate(history):
        for op_index, op in enumerate(txn["ops"]):
            op_context = f"{history_path}:txn_line[{line_index}]:op[{op_index}]"
            if op["type"] == "r":
                validate_source_row(op, version_by_id, op_context)
            elif op["type"] == "pr":
                validate_predicate_shape(op, version_by_id, op_context)

    expected = required_str(manifest, "expected_verdict", str(manifest_path)).upper()
    case_kind = required_str(manifest, "case_kind", str(manifest_path))
    if expected == "ACCEPT":
        validate_accept_replay(hist_dir, manifest, history, txn_by_id, initial_rows)
    elif expected == "REJECT":
        validate_reject_conflicts(hist_dir, history, txn_by_id)
    else:
        raise ValidationError(f"{manifest_path}: expected_verdict must be ACCEPT or REJECT")

    return expected, case_kind


def validate_source_row(row: dict, version_by_id: Dict[int, SourceVersion], context: str) -> SourceVersion:
    version = source_from_row(row, context)
    source = version_by_id.get(version.write_id)
    if source is None:
        raise ValidationError(f"{context}: dangling source_write_id {version.write_id}")
    if (version.key, version.value, version.semantic) != (source.key, source.value, source.semantic):
        raise ValidationError(
            f"{context}: source_write_id {version.write_id} points to "
            f"({source.key},{source.value},{source.semantic}), got "
            f"({version.key},{version.value},{version.semantic})"
        )
    if (version.source_txn, version.source_op_index) != (source.source_txn, source.source_op_index):
        raise ValidationError(
            f"{context}: source location for write_id {version.write_id} is "
            f"({version.source_txn},{version.source_op_index}), expected "
            f"({source.source_txn},{source.source_op_index})"
        )
    return source


def validate_predicate_shape(op: dict, version_by_id: Optional[Dict[int, SourceVersion]], context: str) -> None:
    predicate = op.get("predicate")
    if not isinstance(predicate, dict):
        raise ValidationError(f"{context}: predicate must be an object")
    results = op.get("results")
    if not isinstance(results, list):
        raise ValidationError(f"{context}: results must be an array")
    seen_keys = set()
    for result_index, result in enumerate(results):
        if not isinstance(result, dict):
            raise ValidationError(f"{context}: result {result_index} is not an object")
        key = required_str(result, "key", f"{context}:result[{result_index}]")
        if key in seen_keys:
            raise ValidationError(f"{context}: duplicate predicate result key {key}")
        seen_keys.add(key)
        if version_by_id is None:
            source_from_row(result, f"{context}:result[{result_index}]")
        else:
            validate_source_row(result, version_by_id, f"{context}:result[{result_index}]")


def validate_accept_replay(
    hist_dir: pathlib.Path,
    manifest: dict,
    history: List[dict],
    txn_by_id: Dict[int, dict],
    initial_rows: List[dict],
) -> None:
    serial_order = manifest.get("serial_order")
    if not isinstance(serial_order, list) or not all(isinstance(txn_id, int) for txn_id in serial_order):
        oracle_path = hist_dir / "oracle_notes.json"
        if oracle_path.is_file():
            oracle = load_json(oracle_path)
            serial_order = oracle.get("serial_order") if isinstance(oracle, dict) else None
    if not isinstance(serial_order, list) or not all(isinstance(txn_id, int) for txn_id in serial_order):
        raise ValidationError(f"{hist_dir}: ACCEPT case lacks a declared serial_order")
    if set(serial_order) != set(txn_by_id):
        missing = sorted(set(txn_by_id) - set(serial_order))
        extra = sorted(set(serial_order) - set(txn_by_id))
        raise ValidationError(f"{hist_dir}: serial_order mismatch missing={missing[:5]} extra={extra[:5]}")

    state: Dict[str, SourceVersion] = {}
    for index, row in enumerate(initial_rows):
        version = source_from_row(row, f"{hist_dir}:initial[{index}]")
        state[version.key] = version

    for txn_id in serial_order:
        txn = txn_by_id[txn_id]
        for op_index, op in enumerate(txn["ops"]):
            context = f"{hist_dir}:txn={txn_id}:op={op_index}"
            op_type = op["type"]
            if op_type == "r":
                key = required_str(op, "key", context)
                expected = state.get(key)
                if expected is None:
                    raise ValidationError(f"{context}: read key {key} is absent from replay state")
                assert_row_matches_version(op, expected, context)
            elif op_type == "pr":
                predicate = op["predicate"]
                expected_rows = {
                    version.key: version
                    for version in state.values()
                    if predicate_matches(predicate, version)
                }
                actual_rows = {required_str(result, "key", context): result for result in op["results"]}
                if set(actual_rows) != set(expected_rows):
                    missing = sorted(set(expected_rows) - set(actual_rows))
                    extra = sorted(set(actual_rows) - set(expected_rows))
                    raise ValidationError(
                        f"{context}: predicate result is not complete; missing={missing[:10]} extra={extra[:10]}"
                    )
                for key, expected in expected_rows.items():
                    assert_row_matches_version(actual_rows[key], expected, f"{context}:result[{key}]")
            elif op_type == "w":
                version = write_from_op(op, txn_id, op_index, context)
                state[version.key] = version
            else:
                raise ValidationError(f"{context}: unsupported op type {op_type}")


def assert_row_matches_version(row: dict, version: SourceVersion, context: str) -> None:
    got = (
        required_str(row, "key", context),
        required_int(row, "value", context),
        required_int(row, "semantic", context),
        required_int(row, "source_write_id", context),
        required_int(row, "source_txn", context),
        required_int(row, "source_op_index", context),
    )
    expected = (
        version.key,
        version.value,
        version.semantic,
        version.write_id,
        version.source_txn,
        version.source_op_index,
    )
    if got != expected:
        raise ValidationError(f"{context}: row does not match replay source; got={got} expected={expected}")


def predicate_matches(predicate: dict, version: SourceVersion) -> bool:
    key = version.key
    semantic = version.semantic
    kind = predicate.get("kind")
    if kind == "inventory_threshold":
        return key.startswith(required_str(predicate, "key_prefix", "predicate")) and compare_semantic(
            semantic,
            required_str(predicate, "comparator", "predicate"),
            required_int(predicate, "threshold", "predicate"),
        )
    if kind == "order_filter":
        allowed = predicate.get("allowed_semantics") or []
        if not isinstance(allowed, list):
            raise ValidationError("predicate: allowed_semantics must be an array")
        return (
            key.startswith(required_str(predicate, "key_prefix", "predicate"))
            and compare_semantic(
                semantic,
                required_str(predicate, "comparator", "predicate"),
                required_int(predicate, "threshold", "predicate"),
            )
            and (not allowed or semantic in allowed)
        )
    if kind == "search_ranked_docs":
        return key.startswith(required_str(predicate, "key_prefix", "predicate")) and semantic >= required_int(
            predicate, "min_score", "predicate"
        )
    raise ValidationError(f"predicate: unsupported kind {kind}")


def compare_semantic(semantic: int, comparator: str, threshold: int) -> bool:
    if comparator == "ge":
        return semantic >= threshold
    if comparator == "gt":
        return semantic > threshold
    if comparator == "le":
        return semantic <= threshold
    if comparator == "lt":
        return semantic < threshold
    if comparator == "eq":
        return semantic == threshold
    raise ValidationError(f"unsupported comparator: {comparator}")


def validate_reject_conflicts(hist_dir: pathlib.Path, history: List[dict], txn_by_id: Dict[int, dict]) -> None:
    conflicts_path = hist_dir / "conflicts.json"
    if not conflicts_path.is_file():
        raise ValidationError(f"{hist_dir}: REJECT case missing conflicts.json")
    conflicts = load_json(conflicts_path)
    if not isinstance(conflicts, dict):
        raise ValidationError(f"{conflicts_path}: conflicts.json must be an object")
    declared_txns = conflicts.get("transactions")
    if not isinstance(declared_txns, list) or not declared_txns:
        raise ValidationError(f"{conflicts_path}: transactions must be a non-empty array")
    for index, item in enumerate(declared_txns):
        txn_id = item.get("txn") if isinstance(item, dict) else item
        if not isinstance(txn_id, int) or txn_id not in txn_by_id:
            raise ValidationError(f"{conflicts_path}: declared transaction {index} not found in history: {txn_id}")

    operations = conflicts.get("operations")
    if not isinstance(operations, dict) or not operations:
        raise ValidationError(f"{conflicts_path}: operations must be a non-empty object")
    for txn_id_text, ops in operations.items():
        try:
            txn_id = int(txn_id_text)
        except ValueError as exc:
            raise ValidationError(f"{conflicts_path}: operations key is not a txn id: {txn_id_text}") from exc
        if txn_id not in txn_by_id:
            raise ValidationError(f"{conflicts_path}: operations reference missing txn {txn_id}")
        if not isinstance(ops, list):
            raise ValidationError(f"{conflicts_path}: operations[{txn_id_text}] must be an array")
        for op_decl in ops:
            validate_declared_operation(op_decl, txn_by_id, txn_id, f"{conflicts_path}:operations[{txn_id_text}]")

    edges = conflicts.get("expected_edges")
    if not isinstance(edges, list) or not edges:
        raise ValidationError(f"{conflicts_path}: expected_edges must be a non-empty array")
    for index, edge in enumerate(edges):
        validate_conflict_edge(edge, txn_by_id, f"{conflicts_path}:expected_edges[{index}]")

    cycle = conflicts.get("expected_cycle")
    if not isinstance(cycle, list) or len(cycle) < 2:
        raise ValidationError(f"{conflicts_path}: expected_cycle must contain at least two edges")
    for index, edge in enumerate(cycle):
        if not isinstance(edge, dict):
            raise ValidationError(f"{conflicts_path}:expected_cycle[{index}] must be an object")
        from_txn = required_int(edge, "from", f"{conflicts_path}:expected_cycle[{index}]")
        to_txn = required_int(edge, "to", f"{conflicts_path}:expected_cycle[{index}]")
        if from_txn not in txn_by_id or to_txn not in txn_by_id:
            raise ValidationError(f"{conflicts_path}:expected_cycle[{index}] references missing transaction")


def validate_conflict_edge(edge: dict, txn_by_id: Dict[int, dict], context: str) -> None:
    if not isinstance(edge, dict):
        raise ValidationError(f"{context}: edge must be an object")
    edge_type = required_str(edge, "type", context)
    from_txn = required_int(edge, "from", context)
    to_txn = required_int(edge, "to", context)
    key = required_str(edge, "key", context)
    if from_txn not in txn_by_id or to_txn not in txn_by_id:
        raise ValidationError(f"{context}: from/to transaction not found in history")
    if edge_type not in {"RW", "PR_RW", "PR_WR"}:
        raise ValidationError(f"{context}: unsupported edge type {edge_type}")
    if "read_op" in edge:
        op = resolve_op_ref(edge["read_op"], txn_by_id, f"{context}:read_op")
        if op.get("type") != "r" or op.get("key") != key:
            raise ValidationError(f"{context}: read_op does not reference read key {key}")
    if "predicate_op" in edge:
        op = resolve_op_ref(edge["predicate_op"], txn_by_id, f"{context}:predicate_op")
        if op.get("type") != "pr":
            raise ValidationError(f"{context}: predicate_op is not a predicate read")
        predicate = op.get("predicate", {})
        key_prefix = predicate.get("key_prefix")
        if isinstance(key_prefix, str) and not key.startswith(key_prefix):
            raise ValidationError(f"{context}: predicate key_prefix {key_prefix} does not cover key {key}")
        if "source_write_id" in edge:
            source_write_id = required_int(edge, "source_write_id", context)
            if not any(result.get("source_write_id") == source_write_id for result in op.get("results", [])):
                raise ValidationError(f"{context}: predicate result does not include source_write_id {source_write_id}")
    if "write_op" in edge:
        op = resolve_op_ref(edge["write_op"], txn_by_id, f"{context}:write_op")
        if op.get("type") != "w" or op.get("key") != key:
            raise ValidationError(f"{context}: write_op does not reference write key {key}")


def validate_declared_operation(op_decl: object, txn_by_id: Dict[int, dict], txn_id: int, context: str) -> None:
    if not isinstance(op_decl, dict):
        raise ValidationError(f"{context}: declared operation must be an object")
    op_index = required_int(op_decl, "op_index", context)
    ops = txn_by_id[txn_id].get("ops", [])
    if op_index < 0 or op_index >= len(ops):
        raise ValidationError(f"{context}: op_index {op_index} out of range for txn {txn_id}")
    actual = ops[op_index]
    for field, value in op_decl.items():
        if field == "op_index":
            continue
        if actual.get(field) != value:
            raise ValidationError(
                f"{context}: declared operation field {field} does not match history for txn {txn_id} op {op_index}"
            )


def resolve_op_ref(ref: object, txn_by_id: Dict[int, dict], context: str) -> dict:
    if not isinstance(ref, dict):
        raise ValidationError(f"{context}: op ref must be an object")
    txn_id = required_int(ref, "txn", context)
    op_index = required_int(ref, "op_index", context)
    txn = txn_by_id.get(txn_id)
    if txn is None:
        raise ValidationError(f"{context}: txn {txn_id} not found")
    ops = txn.get("ops")
    if not isinstance(ops, list) or op_index < 0 or op_index >= len(ops):
        raise ValidationError(f"{context}: op_index {op_index} out of range")
    return ops[op_index]


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate a PRHIST v2 black-box suite statically.")
    parser.add_argument("path", help="Suite root, case hist-00000 directory, or history.prhist.jsonl file")
    return parser.parse_args(argv)


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)
    root = pathlib.Path(args.path)
    hist_dirs = find_hist_dirs(root)
    if not hist_dirs:
        raise SystemExit(f"no history.prhist.jsonl files found under {root}")

    counts = {"ACCEPT": 0, "REJECT": 0}
    errors = 0
    for hist_dir in hist_dirs:
        try:
            verdict, case_kind = validate_hist(hist_dir)
            counts[verdict] += 1
            print(f"OK {verdict:6s} {case_kind:40s} {hist_dir}")
        except ValidationError as exc:
            errors += 1
            print(f"FAIL {hist_dir}: {exc}", file=sys.stderr)

    print(f"validated={len(hist_dirs)} ACCEPT={counts['ACCEPT']} REJECT={counts['REJECT']} FAIL={errors}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
