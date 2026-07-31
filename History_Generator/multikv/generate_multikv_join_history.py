#!/usr/bin/env python3
"""Generate a synthetic multi-table KV history with join predicate reads."""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any


DEFAULT_CASE_DIR = Path("History_Generator/multikv/output/multikv_join_smoke/hist-00000")
TABLES = ("users", "items", "orders")
ANOMALY_NONE = "none"
ANOMALY_WRITE_SKEW = "write-skew"
ANOMALY_MODES = (ANOMALY_NONE, ANOMALY_WRITE_SKEW)

JOIN_OPEN_AVAILABLE_QUERY = {
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
    "where": ["i.value.stock > 0"],
}


class GenerationError(ValueError):
    """Raised when the synthetic workload cannot be represented honestly."""


@dataclass(frozen=True)
class VersionedRow:
    table: str
    kv_key: str
    write_id: int
    row: dict[str, Any]

    @property
    def canonical_key(self) -> str:
        return f"{self.table}:{self.kv_key}"

    def as_initial_version(self) -> dict[str, Any]:
        return {
            "key": self.canonical_key,
            "value": self.row,
        }

    def as_read_value(self) -> dict[str, Any]:
        return {
            "key": self.canonical_key,
            "value": self.row,
        }

    def as_read_op(self) -> dict[str, Any]:
        return {"type": "r", **self.as_read_value()}

    def as_write_op(self) -> dict[str, Any]:
        return {"type": "w", **self.as_read_value()}


class WriteIdAllocator:
    def __init__(self, start: int = 1) -> None:
        self._next = start
        self._key_values: set[tuple[str, str]] = set()

    def next(self) -> int:
        value = self._next
        self._next += 1
        return value

    def register(self, row: VersionedRow) -> None:
        key_value = (
            row.canonical_key,
            json.dumps(row.row, sort_keys=True, separators=(",", ":")),
        )
        if key_value in self._key_values:
            raise GenerationError(
                f"duplicate write (key,value) for {row.canonical_key}: {row.row!r}"
            )
        self._key_values.add(key_value)


def make_row(table: str, kv_key: str, write_id: int, payload: dict[str, Any]) -> VersionedRow:
    if table not in TABLES:
        raise GenerationError(f"unknown table {table!r}")
    if table == "users":
        row = {"user_key": kv_key, **payload}
    elif table == "items":
        row = {"item_key": kv_key, **payload}
    else:
        row = {"order_key": kv_key, **payload}
    return VersionedRow(table=table, kv_key=kv_key, write_id=write_id, row=row)


def put_row(
    state: dict[str, VersionedRow],
    write_ids: WriteIdAllocator,
    table: str,
    kv_key: str,
    payload: dict[str, Any],
) -> VersionedRow:
    write_id = write_ids.next()
    row = make_row(table, kv_key, write_id, payload)
    write_ids.register(row)
    state[row.canonical_key] = row
    return row


def txn(txn_id: int, session: int, session_seq: int, txn_type: str, ops: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "session": session,
        "session_seq": session_seq,
        "txn": txn_id,
        "status": "commit",
        "ops": ops,
    }


def evaluate_open_available_join(
    state: dict[str, VersionedRow],
    item_key_filter: str | None = None,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    items_by_key = {
        row.row["item_key"]: row
        for row in state.values()
        if row.table == "items"
    }
    orders = sorted(
        (row for row in state.values() if row.table == "orders"),
        key=lambda row: row.kv_key,
    )
    values: list[dict[str, Any]] = []
    inputs: list[dict[str, Any]] = []
    seen_inputs: set[str] = set()
    for order in orders:
        item = items_by_key.get(str(order.row.get("item_key")))
        if item is None or int(item.row.get("stock", 0)) <= 0:
            continue
        if item_key_filter is not None and item.kv_key != item_key_filter:
            continue
        values.append({
            "order_key": order.row["order_key"],
            "item_key": item.row["item_key"],
            "user_key": order.row["user_key"],
            "stock": item.row["stock"],
        })
        for source in (order, item):
            if source.canonical_key not in seen_inputs:
                seen_inputs.add(source.canonical_key)
                inputs.append(source.as_read_value())
    return values, inputs


def join_predicate_op(
    state: dict[str, VersionedRow],
    item_key_filter: str | None = None,
) -> dict[str, Any]:
    values, inputs = evaluate_open_available_join(state, item_key_filter)
    query = JOIN_OPEN_AVAILABLE_QUERY
    if item_key_filter is not None:
        query = {
            **JOIN_OPEN_AVAILABLE_QUERY,
            "where": [
                *JOIN_OPEN_AVAILABLE_QUERY["where"],
                f"i.value.item_key = '{item_key_filter}'",
            ],
        }
    return {
        "type": "pr",
        "query": query,
        "result": {
            "values": values,
            "inputs": inputs,
        },
    }


def build_history(
    anomaly_mode: str = ANOMALY_NONE,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], dict[str, Any]]:
    if anomaly_mode not in ANOMALY_MODES:
        raise GenerationError(f"unsupported anomaly mode {anomaly_mode!r}")

    write_ids = WriteIdAllocator()
    state: dict[str, VersionedRow] = {}

    u0 = put_row(state, write_ids, "users", "u0", {"region": "north", "active": True})
    u1 = put_row(state, write_ids, "users", "u1", {"region": "south", "active": True})
    i0 = put_row(state, write_ids, "items", "i0", {"stock": 3, "price": 10})
    i1 = put_row(state, write_ids, "items", "i1", {"stock": 0, "price": 20})
    o0 = put_row(state, write_ids, "orders", "o0", {"user_key": "u0", "item_key": "i0", "status": "open"})
    o1 = put_row(state, write_ids, "orders", "o1", {"user_key": "u1", "item_key": "i1", "status": "open"})
    initial_rows = [u0, u1, i0, i1, o0, o1]

    if anomaly_mode == ANOMALY_WRITE_SKEW:
        ws0 = put_row(state, write_ids, "items", "ws0", {"stock": 1, "price": 30})
        ws1 = put_row(state, write_ids, "items", "ws1", {"stock": 1, "price": 40})
        ws_order0 = put_row(
            state,
            write_ids,
            "orders",
            "ws0",
            {"user_key": "u0", "item_key": "ws0", "status": "open"},
        )
        ws_order1 = put_row(
            state,
            write_ids,
            "orders",
            "ws1",
            {"user_key": "u1", "item_key": "ws1", "status": "open"},
        )
        initial_rows.extend((ws0, ws1, ws_order0, ws_order1))

    initial = [row.as_initial_version() for row in initial_rows]

    transactions: list[dict[str, Any]] = []
    session_seq_offset = 0

    if anomaly_mode == ANOMALY_WRITE_SKEW:
        write_skew_snapshot = dict(state)
        read_ws0 = join_predicate_op(write_skew_snapshot, item_key_filter="ws0")
        read_ws1 = join_predicate_op(write_skew_snapshot, item_key_filter="ws1")
        ws1_unavailable = put_row(
            state,
            write_ids,
            "items",
            "ws1",
            {"stock": 0, "price": 40},
        )
        ws0_unavailable = put_row(
            state,
            write_ids,
            "items",
            "ws0",
            {"stock": 0, "price": 30},
        )
        transactions.extend([
            txn(
                9001,
                session=0,
                session_seq=1,
                txn_type="WriteSkew",
                ops=[read_ws0, ws1_unavailable.as_write_op()],
            ),
            txn(
                9002,
                session=1,
                session_seq=1,
                txn_type="WriteSkew",
                ops=[read_ws1, ws0_unavailable.as_write_op()],
            ),
        ])
        session_seq_offset = 1

    o2 = put_row(state, write_ids, "orders", "o2", {"user_key": "u0", "item_key": "i0", "status": "open"})
    i0_after_order = put_row(state, write_ids, "items", "i0", {"stock": 2, "price": 10})
    transactions.append(txn(
        1001,
        session=0,
        session_seq=1 + session_seq_offset,
        txn_type="PlaceOrder",
        ops=[
            u0.as_read_op(),
            i0.as_read_op(),
            o2.as_write_op(),
            i0_after_order.as_write_op(),
        ],
    ))

    transactions.append(txn(
        1002,
        session=0,
        session_seq=2 + session_seq_offset,
        txn_type="JoinOpenAvailableOrders",
        ops=[join_predicate_op(state)],
    ))

    i1_restocked = put_row(state, write_ids, "items", "i1", {"stock": 5, "price": 20})
    transactions.append(txn(
        1003,
        session=1,
        session_seq=1 + session_seq_offset,
        txn_type="RestockItem",
        ops=[
            i1.as_read_op(),
            i1_restocked.as_write_op(),
        ],
    ))

    transactions.append(txn(
        1004,
        session=1,
        session_seq=2 + session_seq_offset,
        txn_type="JoinOpenAvailableOrders",
        ops=[join_predicate_op(state)],
    ))

    o0_closed = put_row(state, write_ids, "orders", "o0", {"user_key": "u0", "item_key": "i0", "status": "closed"})
    transactions.append(txn(
        1005,
        session=0,
        session_seq=3 + session_seq_offset,
        txn_type="CloseOrder",
        ops=[
            o0.as_read_op(),
            o0_closed.as_write_op(),
        ],
    ))

    transactions.append(txn(
        1006,
        session=1,
        session_seq=3 + session_seq_offset,
        txn_type="JoinOpenAvailableOrders",
        ops=[join_predicate_op(state)],
    ))

    stats = count_stats(initial, transactions)
    manifest: dict[str, Any] = {
        "dataset_name": "multikv_join_smoke",
        "format": "prhist-v4-multikv-join",
        "case_kind": "synthetic_multikv_join",
        "anomaly_mode": anomaly_mode,
        "generator": "multikv/generate_multikv_join_history.py",
        "source": "Synthetic multi-table KV workload with explicit join predicate reads",
        "tables": {
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
        },
        "join_predicate_mapping": (
            "Predicate reads evaluate orders JOIN items ON orders.value.item_key = "
            "items.value.item_key WHERE items.value.stock > 0."
        ),
        "key_mapping": "History key is table-qualified as <table>:<table-local-key> so ordinary reads/writes remain compatible with KV histories.",
        "value_mapping": "Every business table has KV columns only; top-level value is the complete immutable business row and every written (key,value) combination is unique.",
        "version_mapping": "Initial values and all operations use the reference PRHIST outer key/value shape without write provenance fields.",
        "files": {
            "initial_state": "initial_state.json",
            "history": "history.prhist.jsonl",
        },
        **stats,
    }
    if anomaly_mode == ANOMALY_WRITE_SKEW:
        manifest.update({
            "expected_verdict": "REJECT",
            "anomaly_mapping": (
                "Two sessions read opposite item-qualified JOIN results from the same "
                "snapshot, then each makes the item read by the other session unavailable."
            ),
        })
    return initial, transactions, manifest


def count_stats(initial: list[dict[str, Any]], transactions: list[dict[str, Any]]) -> dict[str, int]:
    writes = 0
    point_reads = 0
    predicate_reads = 0
    for transaction in transactions:
        for op in transaction["ops"]:
            if op["type"] == "w":
                writes += 1
            elif op["type"] == "r":
                point_reads += 1
            elif op["type"] == "pr":
                predicate_reads += 1
    return {
        "initial_keys": len(initial),
        "transactions": len(transactions),
        "point_reads": point_reads,
        "predicate_reads": predicate_reads,
        "writes": writes,
        "operations": point_reads + predicate_reads + writes,
    }


def write_case(case_dir: Path, initial: list[dict[str, Any]], transactions: list[dict[str, Any]], manifest: dict[str, Any]) -> None:
    case_dir.mkdir(parents=True, exist_ok=True)
    (case_dir / "initial_state.json").write_text(
        json.dumps(initial, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    with (case_dir / "history.prhist.jsonl").open("w", encoding="utf-8") as handle:
        for transaction in transactions:
            handle.write(json.dumps(transaction, sort_keys=True, separators=(",", ":")) + "\n")
    (case_dir / "manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def generate(
    case_dir: Path,
    anomaly_mode: str = ANOMALY_NONE,
) -> dict[str, Any]:
    initial, transactions, manifest = build_history(anomaly_mode)
    manifest["dataset_name"] = case_dir.parent.name
    write_case(case_dir, initial, transactions, manifest)
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate a synthetic multi-table KV join-predicate PRHIST case.")
    parser.add_argument("--case-dir", type=Path, default=DEFAULT_CASE_DIR)
    parser.add_argument(
        "--anomaly",
        choices=ANOMALY_MODES,
        default=ANOMALY_NONE,
        help="prepend a two-session scripted anomaly core (default: none)",
    )
    args = parser.parse_args()
    manifest = generate(args.case_dir, anomaly_mode=args.anomaly)
    print(json.dumps({"case_dir": str(args.case_dir), **manifest}, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
