import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from audit_multikv_join_history import AuditError, audit
from generate_multikv_join_history import (
    ANOMALY_NONE,
    ANOMALY_WRITE_SKEW,
    GenerationError,
    WriteIdAllocator,
    build_history,
    generate,
    put_row,
)


class MultiKvJoinHistoryTest(unittest.TestCase):
    def test_generator_rejects_duplicate_key_value(self):
        state = {}
        write_ids = WriteIdAllocator()
        payload = {"stock": 3, "price": 10}
        put_row(state, write_ids, "items", "i0", payload)

        with self.assertRaisesRegex(GenerationError, r"duplicate write \(key,value\) for items:i0"):
            put_row(state, write_ids, "items", "i0", payload)

    def test_generate_and_audit_multikv_join_history(self):
        with tempfile.TemporaryDirectory() as directory:
            case_dir = Path(directory) / "multikv_join_smoke" / "hist-00000"
            manifest = generate(case_dir)
            report = audit(case_dir)

            initial = json.loads((case_dir / "initial_state.json").read_text(encoding="utf-8"))
            history = [
                json.loads(line)
                for line in (case_dir / "history.prhist.jsonl").read_text(encoding="utf-8").splitlines()
            ]
            join_reads = [
                txn["ops"][0]
                for txn in history
                if len(txn["ops"]) == 1 and txn["ops"][0]["type"] == "pr"
            ]

            self.assertEqual(manifest["format"], "prhist-v4-multikv-join")
            self.assertEqual(manifest["anomaly_mode"], ANOMALY_NONE)
            self.assertTrue(report["valid"])
            self.assertEqual(initial[0], {
                "key": "users:u0",
                "value": {"user_key": "u0", "region": "north", "active": True},
            })
            self.assertNotIn("txn_type", history[0])
            self.assertEqual(
                history[0]["ops"][0],
                {
                    "key": "users:u0",
                    "type": "r",
                    "value": {"user_key": "u0", "region": "north", "active": True},
                },
            )
            self.assertEqual(
                history[0]["ops"][2],
                {
                    "key": "orders:o2",
                    "type": "w",
                    "value": {
                        "order_key": "o2",
                        "user_key": "u0",
                        "item_key": "i0",
                        "status": "open",
                    },
                },
            )
            self.assertEqual(
                join_reads[0]["query"],
                {
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
                },
            )
            self.assertEqual(manifest["tables"]["items"]["primary_key"], ["key"])
            self.assertEqual(manifest["tables"]["items"]["payload_columns"], ["value"])
            self.assertEqual([len(op["result"]["values"]) for op in join_reads], [2, 3, 3])
            self.assertEqual(join_reads[0]["result"]["values"][0]["order_key"], "o0")
            self.assertNotIn("order_value", join_reads[0]["result"]["values"][0])
            self.assertNotIn("item_value", join_reads[0]["result"]["values"][0])
            self.assertEqual(join_reads[1]["result"]["values"][1]["item_key"], "i1")
            self.assertEqual(join_reads[2]["result"]["values"][0]["order_key"], "o0")
            self.assertEqual(
                join_reads[0]["result"]["inputs"][0],
                {
                    "key": "orders:o0",
                    "value": {
                        "order_key": "o0",
                        "user_key": "u0",
                        "item_key": "i0",
                        "status": "open",
                    },
                },
            )
            self.assertEqual(
                join_reads[0]["result"]["inputs"][1],
                {
                    "key": "items:i0",
                    "value": {"item_key": "i0", "stock": 2, "price": 10},
                },
            )

    def test_generate_and_audit_write_skew(self):
        with tempfile.TemporaryDirectory() as directory:
            case_dir = Path(directory) / "multikv_join_write_skew" / "hist-00000"
            manifest = generate(case_dir, anomaly_mode=ANOMALY_WRITE_SKEW)
            report = audit(case_dir)
            history = [
                json.loads(line)
                for line in (case_dir / "history.prhist.jsonl").read_text(encoding="utf-8").splitlines()
            ]
            core = history[:2]

            self.assertTrue(report["valid"])
            self.assertEqual(manifest["anomaly_mode"], ANOMALY_WRITE_SKEW)
            self.assertEqual(manifest["dataset_name"], "multikv_join_write_skew")
            self.assertEqual(manifest["expected_verdict"], "REJECT")
            self.assertEqual([txn["session"] for txn in core], [0, 1])
            self.assertEqual(
                {
                    session: [
                        txn["session_seq"]
                        for txn in history
                        if txn["session"] == session
                    ]
                    for session in (0, 1)
                },
                {0: [1, 2, 3, 4], 1: [1, 2, 3, 4]},
            )
            self.assertEqual([[op["type"] for op in txn["ops"]] for txn in core], [
                ["pr", "w"],
                ["pr", "w"],
            ])
            self.assertEqual(
                [txn["ops"][0]["query"]["where"] for txn in core],
                [
                    ["i.value.stock > 0", "i.value.item_key = 'ws0'"],
                    ["i.value.stock > 0", "i.value.item_key = 'ws1'"],
                ],
            )
            self.assertEqual(
                [txn["ops"][0]["result"]["values"][0]["item_key"] for txn in core],
                ["ws0", "ws1"],
            )

            write_keys = [txn["ops"][1]["key"] for txn in core]
            input_keys = [
                {item["key"] for item in txn["ops"][0]["result"]["inputs"]}
                for txn in core
            ]
            self.assertEqual(write_keys, ["items:ws1", "items:ws0"])
            self.assertIn(write_keys[1], input_keys[0])
            self.assertIn(write_keys[0], input_keys[1])
            self.assertNotIn(write_keys[0], input_keys[0])
            self.assertNotIn(write_keys[1], input_keys[1])

    def test_generator_rejects_unknown_anomaly_mode(self):
        with self.assertRaisesRegex(GenerationError, "unsupported anomaly mode"):
            build_history("unknown")

    def test_audit_rejects_stale_input_value(self):
        with tempfile.TemporaryDirectory() as directory:
            case_dir = Path(directory) / "multikv_join_smoke" / "hist-00000"
            generate(case_dir)
            history_path = case_dir / "history.prhist.jsonl"
            history = [json.loads(line) for line in history_path.read_text(encoding="utf-8").splitlines()]
            history[1]["ops"][0]["result"]["inputs"][0]["value"]["status"] = "stale"
            history_path.write_text("".join(json.dumps(row) + "\n" for row in history), encoding="utf-8")

            with self.assertRaisesRegex(AuditError, "read value does not match current value"):
                audit(case_dir)

    def test_audit_rejects_duplicate_key_value(self):
        with tempfile.TemporaryDirectory() as directory:
            case_dir = Path(directory) / "multikv_join_smoke" / "hist-00000"
            generate(case_dir)
            history_path = case_dir / "history.prhist.jsonl"
            history = [json.loads(line) for line in history_path.read_text(encoding="utf-8").splitlines()]

            repeated_value = {
                "order_key": "o0",
                "user_key": "u0",
                "item_key": "i0",
                "status": "open",
            }
            history[4]["ops"][1]["value"] = repeated_value
            history[5]["ops"][0]["result"]["inputs"][0]["value"] = repeated_value
            history_path.write_text("".join(json.dumps(row) + "\n" for row in history), encoding="utf-8")

            with self.assertRaisesRegex(AuditError, r"duplicate write \(key,value\) for orders:o0"):
                audit(case_dir)

    def test_audit_rejects_result_not_computed_from_real_values(self):
        with tempfile.TemporaryDirectory() as directory:
            case_dir = Path(directory) / "multikv_join_smoke" / "hist-00000"
            generate(case_dir)
            history_path = case_dir / "history.prhist.jsonl"
            history = [json.loads(line) for line in history_path.read_text(encoding="utf-8").splitlines()]
            history[1]["ops"][0]["result"]["values"][0]["stock"] = 999
            history_path.write_text("".join(json.dumps(row) + "\n" for row in history), encoding="utf-8")

            with self.assertRaisesRegex(AuditError, "does not match relational query evaluation"):
                audit(case_dir)


if __name__ == "__main__":
    unittest.main()
