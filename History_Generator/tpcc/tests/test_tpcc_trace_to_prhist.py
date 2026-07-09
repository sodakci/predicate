import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from audit_tpcc_prhist import audit
from tpcc_trace_to_prhist import convert


class TpccTraceToPrhistTest(unittest.TestCase):
    def test_conversion_compacts_provenance_and_preserves_multitable_facts(self):
        rows = [
            {
                "record_type": "initial", "key": "stock:w=1:i=7", "value": 1, "semantic": 7,
                "source_write_id": 1, "source_txn": -1, "source_op_index": 0,
                "row": {"s_w_id": 1, "s_i_id": 7, "s_quantity": 8},
            },
            {
                "record_type": "initial", "key": "stock:w=1:i=8", "value": 2, "semantic": 8,
                "source_write_id": 2, "source_txn": -1, "source_op_index": 0,
            },
            {
                "record_type": "txn", "txn": 42, "session": 3, "session_seq": 1,
                "txn_type": "StockLevel", "status": "commit", "begin_ts": "2026-06-23T00:00:00Z",
                "last_op_ts": "2026-06-23T00:00:01Z",
                "ops": [
                    {
                        "op_index": 0, "type": "pr",
                        "predicate": {
                            "kind": "tpcc_stock_level", "warehouse_id": 1, "district_id": 1,
                            "order_id_from": 2980, "order_id_to_exclusive": 3000, "stock_quantity_lt": 15,
                        },
                        "results": [
                            {
                                "key": "stock:w=1:i=7", "value": 1, "semantic": 7,
                                "source_write_id": 1, "source_txn": -1, "source_op_index": 0,
                            }
                        ],
                        "read_versions": [
                            {
                                "key": "stock:w=1:i=7", "value": 1, "semantic": 7,
                                "source_write_id": 1, "source_txn": -1, "source_op_index": 0,
                            }
                        ],
                    },
                    {"op_index": 1, "type": "w", "key": "stock:w=1:i=7", "value": 3, "semantic": 7,
                     "write_id": 3, "new_row": {"s_w_id": 1, "s_i_id": 7, "s_quantity": 6}},
                    {
                        "op_index": 2, "type": "r", "key": "stock:w=1:i=7", "value": 3, "semantic": 7,
                        "source_write_id": 3, "source_txn": 42, "source_op_index": 1,
                    },
                ],
            },
        ]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw = root / "raw.jsonl"
            raw.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
            case = root / "tpcc_smoke" / "hist-00000"
            manifest = convert(raw, case, expected_verdict="ACCEPT", serial_order=[42])
            report = audit(case)
            history = [json.loads(line) for line in (case / "history.prhist.jsonl").read_text(encoding="utf-8").splitlines()]

            self.assertEqual(manifest["format"], "prhist-v2-kv-relational-predicate")
            self.assertTrue(report["valid"])
            self.assertEqual(history[0]["ops"][0]["query"]["from"], {"relation": "order_line", "alias": "ol"})
            self.assertEqual(history[0]["ops"][0]["query"]["joins"][0]["alias"], "s")
            self.assertEqual(history[0]["ops"][0]["result"]["values"], [{"s_i_id": 7}])
            self.assertEqual(history[0]["ops"][0]["result"]["inputs"], [{"key": "stock:w=1:i=7", "value": 1}])
            self.assertEqual(history[0]["ops"][1]["row"], {"s_w_id": 1, "s_i_id": 7, "s_quantity": 6})
            initial = json.loads((case / "initial_state.json").read_text(encoding="utf-8"))
            self.assertEqual(initial[0]["row"], {"s_w_id": 1, "s_i_id": 7, "s_quantity": 8})
            self.assertEqual(history[0]["ops"][2]["value"], 3)
            for operation in history[0]["ops"]:
                self.assertNotIn("source_write_id", operation)
                self.assertNotIn("source_txn", operation)
                self.assertNotIn("source_op_index", operation)
                self.assertNotIn("semantic", operation)
                self.assertNotIn("write_id", operation)
                self.assertNotIn("facts", operation)
                for result in operation.get("results", []):
                    self.assertNotIn("source_write_id", result)
                    self.assertNotIn("source_txn", result)
                    self.assertNotIn("source_op_index", result)
            validator = Path(__file__).resolve().parents[2] / "ser-result-detector/tools/validate_prhist_suite.py"
            if validator.is_file():
                validated = subprocess.run(
                    [sys.executable, str(validator), str(case)], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT
                )
                self.assertEqual(validated.returncode, 0, validated.stdout)


if __name__ == "__main__":
    unittest.main()
