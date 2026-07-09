import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from audit_kvpredicate_prhist import audit
from kvpredicate_trace_to_prhist import ConversionError, convert


class KvPredicateTraceToPrhistTest(unittest.TestCase):
    def test_conversion_preserves_kv_predicate_workload(self):
        rows = [
            {
                "record_type": "initial",
                "key": "kv:0",
                "value": 0,
                "semantic": 0,
                "row": {"k": "k0", "value": 0},
            },
            {
                "record_type": "initial",
                "key": "kv:1",
                "value": 1,
                "semantic": 1,
                "row": {"k": "k1", "value": 1},
            },
            {
                "record_type": "txn",
                "txn": 10,
                "session": 0,
                "session_seq": 1,
                "txn_type": "Txn",
                "status": "commit",
                "ops": [
                    {
                        "op_index": 0,
                        "type": "r",
                        "key": "kv:0",
                        "value": 0,
                        "semantic": 0,
                    },
                    {
                        "op_index": 1,
                        "type": "w",
                        "key": "kv:1",
                        "value": 2,
                        "semantic": 2,
                        "before_value": 1,
                        "new_row": {"k": "k1", "value": 2},
                    },
                ],
            },
            {
                "record_type": "txn",
                "txn": 11,
                "session": 0,
                "session_seq": 2,
                "txn_type": "Txn",
                "status": "commit",
                "ops": [
                    {
                        "op_index": 0,
                        "type": "pr",
                        "predicate": {"kind": "mod", "modulus": 2, "target": 0},
                        "results": [
                            {"key": "kv:0", "key_id": 0, "value": 0, "semantic": 0},
                            {"key": "kv:1", "key_id": 1, "value": 2, "semantic": 2},
                        ],
                        "read_versions": [
                            {"key": "kv:0", "key_id": 0, "value": 0, "semantic": 0},
                            {"key": "kv:1", "key_id": 1, "value": 2, "semantic": 2},
                        ],
                    }
                ],
            },
            {
                "record_type": "txn",
                "txn": 12,
                "session": 0,
                "session_seq": 3,
                "txn_type": "Txn",
                "status": "commit",
                "ops": [
                    {
                        "op_index": 0,
                        "type": "r",
                        "key": "kv:11",
                        "value": -1000000000011,
                        "semantic": -1,
                        "absent": True,
                    }
                ],
            },
            {
                "record_type": "txn",
                "txn": 13,
                "session": 0,
                "session_seq": 4,
                "txn_type": "Txn",
                "status": "commit",
                "ops": [
                    {
                        "op_index": 0,
                        "type": "pr",
                        "predicate": {"kind": "gt", "value": 0},
                        "results": [
                            {"key": "kv:1", "key_id": 1, "value": 2, "semantic": 2},
                        ],
                        "read_versions": [
                            {"key": "kv:1", "key_id": 1, "value": 2, "semantic": 2},
                        ],
                    }
                ],
            },
            {
                "record_type": "txn",
                "txn": 14,
                "session": 0,
                "session_seq": 5,
                "txn_type": "Txn",
                "status": "commit",
                "ops": [
                    {
                        "op_index": 0,
                        "type": "pr",
                        "predicate": {"kind": "lt", "value": 2},
                        "results": [
                            {"key": "kv:0", "key_id": 0, "value": 0, "semantic": 0},
                        ],
                        "read_versions": [
                            {"key": "kv:0", "key_id": 0, "value": 0, "semantic": 0},
                        ],
                    }
                ],
            },
        ]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw = root / "raw.jsonl"
            raw.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
            case = root / "kvpredicate_smoke" / "hist-00000"

            manifest = convert(raw, case)
            report = audit(case)
            history = [
                json.loads(line)
                for line in (case / "history.prhist.jsonl").read_text(encoding="utf-8").splitlines()
            ]
            initial = json.loads((case / "initial_state.json").read_text(encoding="utf-8"))

            self.assertEqual(manifest["case_kind"], "real_postgresql_kvpredicate")
            self.assertTrue(report["valid"])
            self.assertEqual(history[1]["ops"][0]["query"]["where"], ["value % 2 = 0"])
            self.assertEqual(history[1]["ops"][0]["result"]["values"], [{"k": "0", "value": 0}, {"k": "1", "value": 2}])
            self.assertEqual(history[3]["ops"][0]["query"]["where"], ["value > 0"])
            self.assertEqual(history[4]["ops"][0]["query"]["where"], ["value < 2"])
            self.assertIn({"absent": True, "key": "kv:11", "value": -1000000000011}, initial)

    def test_conversion_rejects_source_provenance_fields(self):
        rows = [
            {
                "record_type": "initial",
                "key": "kv:0",
                "value": 0,
                "semantic": 0,
                "source_write_id": 0,
                "row": {"k": "k0", "value": 0},
            },
        ]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw = root / "raw.jsonl"
            raw.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")

            with self.assertRaisesRegex(ConversionError, "source provenance"):
                convert(raw, root / "case" / "hist-00000")


if __name__ == "__main__":
    unittest.main()
