#!/usr/bin/env python3
import json
import tempfile
import unittest
from pathlib import Path

from run_ser_ablation import (
    CONFIGS,
    derive_metrics,
    history_dimensions,
    parse_peak_rss,
    summarize,
)


class SerAblationTest(unittest.TestCase):
    def test_pruning_ladder_has_expected_control_settings(self):
        self.assertEqual(CONFIGS["NONE"].ww_pruning, "NONE")
        self.assertEqual(CONFIGS["REACHABILITY"].ww_pruning, "REACHABILITY")
        self.assertEqual(CONFIGS["GMWR"].ww_pruning, "REACHABILITY")
        self.assertEqual(CONFIGS["GMWR"].propagation, "WW_GMWR_ONEWAY")
        self.assertEqual(CONFIGS["GMWR_WWFeedback"].propagation, "WW_GMWR")

    def test_derived_metrics_separate_preprocessing_from_solver(self):
        metrics = {
            "WW_INITIAL_CHOICES": 100,
            "WW_AFTER_GMWR": 40,
            "WW_REACHABILITY_FORCED": 10,
            "GMWR_INITIAL_CONSTRAINTS": 30,
            "GMWR_RESIDUAL_CONSTRAINTS": 10,
            "GMWR_FORCED_FACTS": 3,
            "GMWR_TO_WW_FORCED": 2,
            "SER_GMWR_INTERVAL_CANDIDATES_PRUNED_COUNT": 7,
            "SER_GMWR_RESIDUAL_CLAUSES_COUNT": 5,
            "SER_GEN_PREC_GRAPH": 4,
            "WW_REACHABILITY_PRUNE_MS": 6,
            "GMWR_BUILD_MS": 8,
            "GMWR_REDUCTION_MS": 10,
            "SER_MONOSAT_SOLVE": 999,
        }
        result = derive_metrics(metrics)
        self.assertEqual(result["initial_candidates"], 130)
        self.assertEqual(result["deleted_candidates"], 80)
        self.assertEqual(result["forced_orders"], 15)
        self.assertEqual(result["branch_pruned_candidates"], 87)
        self.assertEqual(result["residual_constraints"], 50)
        self.assertEqual(result["preprocessing_ms"], 28)

    def test_history_dimensions_cover_requested_axes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "manifest.json").write_text(
                json.dumps({"initial_keys": 4}), encoding="utf-8")
            transactions = [
                {"txn": 1, "ops": [
                    {"type": "w", "key": "x", "value": 1},
                    {"type": "pr", "result": {"values": [{"x": 1}]}}]},
                {"txn": 2, "ops": [
                    {"type": "w", "key": "x", "value": 2},
                    {"type": "pr", "result": {"values": []}}]},
            ]
            (root / "history.prhist.jsonl").write_text(
                "\n".join(json.dumps(txn) for txn in transactions) + "\n",
                encoding="utf-8")
            result = history_dimensions(root)
            self.assertEqual(result["operation_count"], 4)
            self.assertEqual(result["transaction_count"], 2)
            self.assertEqual(result["key_count"], 4)
            self.assertEqual(result["point_read_count"], 0)
            self.assertEqual(result["predicate_count"], 2)
            self.assertEqual(result["predicate_transaction_count"], 2)
            self.assertEqual(result["predicate_transaction_ratio"], 1.0)
            self.assertEqual(result["predicate_operation_ratio"], 0.5)
            self.assertEqual(result["predicate_selectivity"], 0.125)
            self.assertEqual(result["absent_result_ratio"], 0.5)
            self.assertEqual(result["returned_result_cardinality_mean"], 0.5)
            self.assertEqual(result["returned_result_cardinality_median"], 0.5)
            self.assertEqual(result["returned_result_cardinality_max"], 1)
            self.assertEqual(result["written_key_count"], 1)
            self.assertEqual(result["writers_per_key_mean_all_keys"], 0.5)
            self.assertEqual(result["writers_per_key_mean"], 2.0)
            self.assertEqual(result["writers_per_key_max"], 2)

    def test_peak_rss_is_read_from_gnu_time(self):
        with tempfile.TemporaryDirectory() as temporary:
            resource = Path(temporary) / "resource.log"
            resource.write_text(
                "Maximum resident set size (kbytes): 2048\n", encoding="utf-8")
            self.assertEqual(parse_peak_rss(resource), (2048, 2.0))

    def test_summary_reports_timeout_rate_and_causal_chain(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            common = {"history": "/h", "variant": "single", "config": "REACHABILITY"}
            rows = [
                {**common, "status": "COMPLETE", "verdict": "ACCEPT",
                 "repeat": 0, "initial_candidates": 10,
                 "deleted_candidates": 4, "residual_constraints": 6,
                 "SER_MONOSAT_SOLVE": 7},
                {**common, "status": "TIMEOUT", "verdict": "TIMEOUT", "repeat": 1},
            ]
            self.assertEqual(summarize(rows, ["REACHABILITY"], [("single", "")], root), 0)
            with (root / "summary.csv").open(encoding="utf-8") as stream:
                summary = list(__import__("csv").DictReader(stream))[0]
            self.assertEqual(summary["attempted_runs"], "2")
            self.assertEqual(summary["complete_runs"], "1")
            self.assertEqual(summary["timeout_runs"], "1")
            self.assertEqual(summary["timeout_rate"], "0.5")
            self.assertTrue((root / "causal_chain.csv").is_file())


if __name__ == "__main__":
    unittest.main()
