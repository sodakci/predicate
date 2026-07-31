import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from audit_multikv_join_history import AuditError, audit
from generate_multikv_join_history import ANOMALY_WRITE_SKEW
from multikv_trace_to_prhist import (
    ANOMALY_LOST_UPDATE,
    ConversionError,
    convert,
)


def initial(table, key, value):
    return {
        "record_type": "initial",
        "key": f"{table}:{key}",
        "value": value,
        "table": table,
        "local_key": key,
        "row": {"k": key, "value": value},
    }


def join_op(index, item_key, order_value, item_value, reverse_inputs=False):
    inputs = [
        {"key": f"orders:{order_value['order_key']}", "value": order_value},
        {"key": f"items:{item_value['item_key']}", "value": item_value},
    ]
    if reverse_inputs:
        inputs.reverse()
    return {
        "op_index": index,
        "type": "pr",
        "predicate": {"kind": "join_open_available", "item_key": item_key},
        "results": [
            {
                "order_key": order_value["order_key"],
                "item_key": item_value["item_key"],
                "user_key": order_value["user_key"],
                "stock": item_value["stock"],
            }
        ],
        "read_versions": inputs,
        "sql": "SELECT ... FROM orders o JOIN items i ...",
        "parameters": [] if item_key is None else [item_key],
    }


def transaction(txn_id, session, session_seq, ops):
    return {
        "record_type": "txn",
        "txn": txn_id,
        "session": session,
        "session_seq": session_seq,
        "txn_type": "Txn",
        "status": "commit",
        "ops": ops,
    }


class MultiKvTraceToPrhistTest(unittest.TestCase):
    def fixture_rows(self):
        user0 = {"user_key": "u0", "region": "north", "active": True}
        user1 = {"user_key": "u1", "region": "south", "active": True}
        item0 = {"item_key": "ws0", "stock": 1, "price": 30}
        item1 = {"item_key": "ws1", "stock": 1, "price": 40}
        base_item = {"item_key": "i0", "stock": 3, "price": 10}
        order0 = {
            "order_key": "ws0",
            "user_key": "u0",
            "item_key": "ws0",
            "status": "open",
        }
        order1 = {
            "order_key": "ws1",
            "user_key": "u1",
            "item_key": "ws1",
            "status": "open",
        }
        base_order = {
            "order_key": "o0",
            "user_key": "u0",
            "item_key": "i0",
            "status": "open",
        }
        return [
            initial("users", "u0", user0),
            initial("users", "u1", user1),
            initial("items", "i0", base_item),
            initial("items", "ws0", item0),
            initial("items", "ws1", item1),
            initial("orders", "o0", base_order),
            initial("orders", "ws0", order0),
            initial("orders", "ws1", order1),
            transaction(
                10,
                0,
                1,
                [
                    join_op(0, "ws0", order0, item0, reverse_inputs=True),
                    {
                        "op_index": 1,
                        "type": "w",
                        "key": "items:ws1",
                        "value": {"item_key": "ws1", "stock": 0, "price": 40},
                    },
                ],
            ),
            # PostgreSQL export groups by session. The converter must still put
            # both session_seq=1 anomaly transactions before this later txn.
            transaction(
                11,
                0,
                2,
                [
                    {
                        "op_index": 0,
                        "type": "w",
                        "key": "users:u0",
                        "value": {
                            "user_key": "u0",
                            "region": "north-r1",
                            "active": True,
                        },
                    },
                ],
            ),
            transaction(
                20,
                1,
                1,
                [
                    join_op(0, "ws1", order1, item1),
                    {
                        "op_index": 1,
                        "type": "w",
                        "key": "items:ws0",
                        "value": {"item_key": "ws0", "stock": 0, "price": 30},
                    },
                ],
            ),
            # This transaction may legally keep its older PostgreSQL snapshot
            # even though converter ordering places txn 11 before it.
            transaction(
                21,
                1,
                2,
                [
                    {
                        "op_index": 0,
                        "type": "r",
                        "key": "users:u0",
                        "value": user0,
                    },
                    join_op(1, None, base_order, base_item),
                ],
            ),
            {
                "record_type": "abort",
                "txn": 30,
                "session": 1,
                "session_seq": 3,
                "txn_type": "Txn",
                "status": "RETRY",
            },
        ]

    def lost_update_fixture_rows(self):
        user0 = {"user_key": "u0", "region": "north", "active": True}
        item0 = {"item_key": "i0", "stock": 3, "price": 10}
        initial_lu = {"item_key": "lu0", "stock": 10, "price": 50}
        return [
            initial("users", "u0", user0),
            initial("items", "i0", item0),
            initial("items", "lu0", initial_lu),
            transaction(
                10,
                0,
                1,
                [
                    {
                        "op_index": 0,
                        "type": "r",
                        "key": "items:lu0",
                        "value": initial_lu,
                    },
                    {
                        "op_index": 1,
                        "type": "w",
                        "key": "items:lu0",
                        "value": {"item_key": "lu0", "stock": 9, "price": 50},
                    },
                ],
            ),
            transaction(
                11,
                0,
                2,
                [
                    {
                        "op_index": 0,
                        "type": "r",
                        "key": "items:i0",
                        "value": item0,
                    },
                ],
            ),
            transaction(
                20,
                1,
                1,
                [
                    {
                        "op_index": 0,
                        "type": "r",
                        "key": "items:lu0",
                        "value": initial_lu,
                    },
                    {
                        "op_index": 1,
                        "type": "w",
                        "key": "items:lu0",
                        "value": {"item_key": "lu0", "stock": 8, "price": 50},
                    },
                ],
            ),
        ]

    def write_raw(self, path, rows):
        path.write_text(
            "".join(json.dumps(row, sort_keys=True) + "\n" for row in rows),
            encoding="utf-8",
        )

    def test_convert_real_write_skew_core_first_and_audit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw = root / "export.jsonl"
            self.write_raw(raw, self.fixture_rows())
            case = root / "multikv_real_write_skew" / "hist-00000"

            manifest = convert(raw, case, anomaly_mode=ANOMALY_WRITE_SKEW)
            report = audit(case)
            history = [
                json.loads(line)
                for line in (case / "history.prhist.jsonl").read_text(
                    encoding="utf-8"
                ).splitlines()
            ]

            self.assertTrue(report["valid"])
            self.assertEqual(manifest["format"], "prhist-v4-multikv-join")
            self.assertEqual(manifest["case_kind"], "real_postgresql_multikv_join")
            self.assertEqual(manifest["expected_verdict"], "REJECT")
            self.assertEqual(manifest["anomaly_core_transactions"], [10, 20])
            self.assertEqual(manifest["captured_aborted_attempts"], 1)
            self.assertEqual([txn["txn"] for txn in history], [10, 20, 11, 21])
            self.assertEqual(manifest["point_reads"], 1)
            self.assertEqual(manifest["predicate_reads"], 3)
            self.assertEqual(manifest["writes"], 3)
            self.assertEqual(
                [op["type"] for op in history[-1]["ops"]],
                ["r", "pr"],
            )
            self.assertEqual(
                [item["key"] for item in history[0]["ops"][0]["result"]["inputs"]],
                ["orders:ws0", "items:ws0"],
            )
            self.assertEqual(
                (case / "raw_multikv_trace.jsonl").read_text(encoding="utf-8"),
                raw.read_text(encoding="utf-8"),
            )

    def test_real_audit_rejects_duplicate_join_result(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw = root / "export.jsonl"
            self.write_raw(raw, self.fixture_rows())
            case = root / "multikv_real_write_skew" / "hist-00000"
            convert(raw, case, anomaly_mode=ANOMALY_WRITE_SKEW)

            history_path = case / "history.prhist.jsonl"
            history = [
                json.loads(line)
                for line in history_path.read_text(encoding="utf-8").splitlines()
            ]
            normal_join = history[-1]["ops"][1]
            normal_join["result"]["values"].append(
                dict(normal_join["result"]["values"][0])
            )
            history_path.write_text(
                "".join(
                    json.dumps(transaction, sort_keys=True) + "\n"
                    for transaction in history
                ),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(AuditError, "query evaluation"):
                audit(case)

    def test_write_skew_requires_two_committed_core_transactions(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rows = [
                row
                for row in self.fixture_rows()
                if not (row.get("record_type") == "txn" and row.get("txn") == 20)
            ]
            raw = root / "export.jsonl"
            self.write_raw(raw, rows)

            with self.assertRaisesRegex(
                ConversionError,
                "requires exactly two committed core transactions",
            ):
                convert(
                    raw,
                    root / "case" / "hist-00000",
                    anomaly_mode=ANOMALY_WRITE_SKEW,
                )

    def test_rejects_join_projection_not_backed_by_input_versions(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rows = self.fixture_rows()
            core = next(
                row
                for row in rows
                if row.get("record_type") == "txn" and row.get("txn") == 10
            )
            core["ops"][0]["results"][0]["stock"] = 999
            raw = root / "export.jsonl"
            self.write_raw(raw, rows)

            with self.assertRaisesRegex(ConversionError, "projection disagrees"):
                convert(
                    raw,
                    root / "case" / "hist-00000",
                    anomaly_mode=ANOMALY_WRITE_SKEW,
                )

    def test_convert_real_lost_update_core_first_and_audit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw = root / "export.jsonl"
            self.write_raw(raw, self.lost_update_fixture_rows())
            case = root / "multikv_real_lost_update" / "hist-00000"

            manifest = convert(raw, case, anomaly_mode=ANOMALY_LOST_UPDATE)
            report = audit(case)
            history = [
                json.loads(line)
                for line in (case / "history.prhist.jsonl").read_text(
                    encoding="utf-8"
                ).splitlines()
            ]

            self.assertTrue(report["valid"])
            self.assertEqual(manifest["expected_verdict"], "REJECT")
            self.assertEqual(manifest["anomaly_core_transactions"], [10, 20])
            self.assertEqual([txn["txn"] for txn in history], [10, 20, 11])
            self.assertEqual(manifest["point_reads"], 3)
            self.assertEqual(manifest["predicate_reads"], 0)
            self.assertEqual(manifest["writes"], 2)
            self.assertEqual(
                [[op["type"] for op in transaction["ops"]] for transaction in history[:2]],
                [["r", "w"], ["r", "w"]],
            )
            self.assertEqual(
                history[0]["ops"][0]["value"],
                history[1]["ops"][0]["value"],
            )
            self.assertNotEqual(
                history[0]["ops"][1]["value"],
                history[1]["ops"][1]["value"],
            )

    def test_lost_update_rejects_non_core_reference_to_dedicated_key(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rows = self.lost_update_fixture_rows()
            normal = next(
                row
                for row in rows
                if row.get("record_type") == "txn" and row.get("txn") == 11
            )
            normal["ops"][0]["key"] = "items:lu0"
            normal["ops"][0]["value"] = {
                "item_key": "lu0",
                "stock": 9,
                "price": 50,
            }
            raw = root / "export.jsonl"
            self.write_raw(raw, rows)

            with self.assertRaisesRegex(ConversionError, "non-core transaction"):
                convert(
                    raw,
                    root / "case" / "hist-00000",
                    anomaly_mode=ANOMALY_LOST_UPDATE,
                )


if __name__ == "__main__":
    unittest.main()
