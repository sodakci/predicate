"""目录实验的最终结论、退出码和外部超时契约。"""

import importlib.util
import pathlib
import subprocess
import tempfile
import unittest
from unittest.mock import patch


RUNNER_PATH = pathlib.Path(__file__).resolve().parents[3] / "tools" / "run_catalog_experiment.py"
SPEC = importlib.util.spec_from_file_location("si_catalog_runner", RUNNER_PATH)
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


class CatalogRunnerTest(unittest.TestCase):
    def run_result(self, log, exit_code=0, timeout=False):
        args = runner.parse_args(["catalog.json"])
        case = {
            "suite": "contract", "case_index": 0, "case": "one",
            "hist_dir": "/tmp/unused-history", "expected_verdict": "REJECT",
            "catalog_entry": {},
        }
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            logs = root / "logs"
            logs.mkdir()
            response = subprocess.CompletedProcess([], exit_code, stdout=log)
            failure = subprocess.TimeoutExpired([], 1, output=log) if timeout else None
            with patch.object(runner.subprocess, "run", return_value=response, side_effect=failure):
                return runner.run_case(case, args, root, logs)

    def test_final_error_overrides_earlier_reject(self):
        result = self.run_result("[[[[ REJECT ]]]]\nSI audit result: ERROR\n", 1)
        self.assertEqual("ERROR", result["actual_verdict"])
        self.assertFalse(result["matched_expected"])

    def test_truncated_output_does_not_reuse_prior_verdict(self):
        for log in ("[[[[ REJECT ]]]]\n", "SI audit result: REJECT\nSI audit result: ERR"):
            with self.subTest(log=log):
                result = self.run_result(log, 255)
                self.assertEqual("RUNTIME_ERROR", result["actual_verdict"])
                self.assertFalse(result["matched_expected"])

    def test_exit_code_must_match_final_verdict(self):
        for verdict, code in (("ACCEPT", 1), ("REJECT", 0), ("REJECT", 1), ("ERROR", 0)):
            with self.subTest(verdict=verdict, code=code):
                result = self.run_result(f"SI audit result: {verdict}\n", code)
                self.assertEqual("RUNTIME_ERROR", result["actual_verdict"])
                self.assertFalse(result["matched_expected"])

    def test_valid_final_verdicts(self):
        for verdict, code in (("ACCEPT", 0), ("REJECT", 255), ("ERROR", 1)):
            with self.subTest(verdict=verdict):
                result = self.run_result(f"SI audit result: {verdict}\n", code)
                self.assertEqual(verdict, result["actual_verdict"])
                self.assertEqual(verdict == "REJECT", result["matched_expected"])

    def test_process_timeout_overrides_any_partial_verdict(self):
        result = self.run_result("SI audit result: REJECT\n", timeout=True)
        self.assertEqual("PROCESS_TIMEOUT", result["actual_verdict"])
        self.assertTrue(result["timed_out"])
        self.assertFalse(result["matched_expected"])

    def test_command_has_only_external_timeout(self):
        result = self.run_result("SI audit result: ACCEPT\n")
        self.assertNotIn("--solver-timeout-seconds", result["command"])

    def test_parses_residual_metrics(self):
        metrics = runner.parse_metrics(
            "SI_PROP_RESIDUAL_SAT_VARIABLES_COUNT: 17\n"
            "SI_PRED_FRONTIER_CANDIDATES_COUNT: 9\n"
            "GMWR_REMOVED_CANDIDATES: 3\n"
            "SI_MONOSAT_SOLVE: 12ms\nSI audit result: ACCEPT\n")
        self.assertEqual(17, metrics.get("si_prop_residual_sat_variables_count"))
        self.assertEqual(9, metrics.get("si_pred_frontier_candidates_count"))
        self.assertEqual(3, metrics.get("gmwr_removed_candidates"))
        self.assertEqual(12, metrics.get("time_si_monosat_solve_ms"))


if __name__ == "__main__":
    unittest.main()
