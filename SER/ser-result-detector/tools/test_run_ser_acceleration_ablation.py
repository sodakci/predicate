import importlib.util
import json
import subprocess
import sys
from pathlib import Path
import tempfile
import unittest


class AccelerationRunnerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        path = Path(__file__).with_name("run_ser_acceleration_ablation.py")
        cls.runner = None
        if path.exists():
            spec = importlib.util.spec_from_file_location("acceleration_runner", path)
            cls.runner = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(cls.runner)

    def setUp(self):
        self.assertIsNotNone(self.runner, "the two-switch ablation runner is missing")

    def make_history(self, root, dataset, predicates=20, name="hist-00000"):
        path = root / dataset / name
        path.mkdir(parents=True)
        (path / "history.prhist.jsonl").write_text("{}\n")
        (path / "manifest.json").write_text(json.dumps({
            "transactions": 100,
            "operations": 1000,
            "predicate_reads": predicates,
            "initial_keys": 5000,
            "writes": 300,
            "files": {"history": "history.prhist.jsonl"},
        }))

    def test_selects_every_normal_group_and_percent_groups_below_ten_percent(self):
        with tempfile.TemporaryDirectory() as tmp:
            normal, percent = Path(tmp) / "test-ser", Path(tmp) / "test-ser%"
            self.make_history(normal, "20_100_15_3000_0.20_uniform")
            self.make_history(normal, "20_100_10_5000_0.40_uniform")
            for ratio in ("0.002", "0.07", "0.10", "0.20"):
                self.make_history(percent, f"20_100_10_5000_{ratio}_uniform", predicates=5)

            rows = self.runner.select_histories(normal, percent, "hist-00000")

            self.assertEqual(len(rows), 4)
            self.assertEqual(sum(row["family"] == "test-ser" for row in rows), 2)
            self.assertEqual(
                [row["nominal_ratio"] for row in rows if row["family"] == "test-ser%"],
                [0.002, 0.07],
            )

    def test_missing_requested_history_is_not_silently_skipped(self):
        with tempfile.TemporaryDirectory() as tmp:
            normal, percent = Path(tmp) / "test-ser", Path(tmp) / "test-ser%"
            self.make_history(normal, "20_100_10_5000_0.20_uniform", name="hist-00001")
            percent.mkdir()
            with self.assertRaises(ValueError):
                self.runner.select_histories(normal, percent, "hist-00000")

    def test_three_configs_form_two_single_switch_steps(self):
        args = dict(
            java="java",
            heap="3g",
            native_dir=Path("/native"),
            classpath="/classes:/lib/a.jar",
            history=Path("/history"),
        )
        full = self.runner.build_command("FULL", **args)
        no_preprop = self.runner.build_command("NO_PREPROP", **args)
        no_gmwr = self.runner.build_command("NO_GMWR", **args)

        self.assertEqual(
            [(left, right) for left, right in zip(full, no_preprop) if left != right],
            [("--gmwr-prepropagation", "--no-gmwr-prepropagation")],
        )
        self.assertEqual(
            [(left, right) for left, right in zip(no_preprop, no_gmwr) if left != right],
            [("--gmwr", "--no-gmwr")],
        )
        for command in (full, no_preprop, no_gmwr):
            self.assertNotIn("--solver-timeout-seconds", command)
            self.assertNotIn("--predicate-encoding", command)
            self.assertNotIn("--ww-pruning", command)

    def test_pairs_attribute_gmwr_frontier_prepropagation_and_full_stack(self):
        rows = [
            self.row("FULL", total=25, rss=700),
            self.row("NO_PREPROP", total=50, rss=800),
            self.row("NO_GMWR", total=100, rss=1000),
        ]
        pairs = {row["comparison"]: row for row in self.runner.paired_rows(rows)}

        self.assertEqual(pairs["gmwr_frontier"]["total_speedup"], 2)
        self.assertEqual(pairs["prepropagation"]["total_speedup"], 2)
        self.assertEqual(pairs["full_stack"]["total_speedup"], 4)
        self.assertAlmostEqual(pairs["full_stack"]["memory_reduction"], 0.3)

    def test_resource_stop_oom_timeout_and_verdict_mismatch_are_censored(self):
        rows = [
            self.row("FULL", case="timeout"),
            self.row("NO_PREPROP", case="timeout"),
            self.row("NO_GMWR", case="timeout", status="PROCESS_TIMEOUT"),
            self.row("FULL", case="memory"),
            self.row("NO_PREPROP", case="memory"),
            self.row("NO_GMWR", case="memory", status="RESOURCE_STOP"),
            self.row("FULL", case="verdict"),
            self.row("NO_PREPROP", case="verdict"),
            self.row("NO_GMWR", case="verdict"),
        ]
        rows[-1]["verdict"] = "REJECT"
        pairs = {
            (row["case_id"], row["comparison"]): row
            for row in self.runner.paired_rows(rows)
        }

        self.assertEqual(pairs[("timeout", "full_stack")]["outcome"], "TIMEOUT_RESCUE")
        self.assertIsNone(pairs[("timeout", "full_stack")]["total_speedup"])
        self.assertEqual(pairs[("memory", "full_stack")]["outcome"], "RESOURCE_CENSORED")
        self.assertEqual(pairs[("verdict", "full_stack")]["outcome"], "VERDICT_MISMATCH")

    def test_plan_only_reports_three_configs_and_creates_no_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            normal, percent = Path(tmp) / "test-ser", Path(tmp) / "test-ser%"
            self.make_history(normal, "20_100_10_5000_0.20_uniform")
            self.make_history(percent, "20_100_10_5000_0.07_uniform")
            out = Path(tmp) / "output"

            result = subprocess.run(
                [
                    sys.executable,
                    str(Path(self.runner.__file__)),
                    "--normal-root", str(normal),
                    "--percent-root", str(percent),
                    "--out-dir", str(out),
                    "--plan-only",
                ],
                capture_output=True,
                text=True,
            )

            self.assertEqual(result.returncode, 0, result.stderr)
            plan = json.loads(result.stdout)
            self.assertEqual(plan["configs"], ["FULL", "NO_PREPROP", "NO_GMWR"])
            self.assertEqual(plan["sample_count"], 2)
            self.assertEqual(plan["planned_runs"], 12)
            self.assertFalse(out.exists())

    def test_process_runner_collects_peak_rss_and_process_timeout(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            complete = self.runner.run_process(
                [
                    sys.executable,
                    "-c",
                    "print('SER audit result: ACCEPT', file=__import__('sys').stderr)",
                ],
                out / "ok",
                process_seconds=10,
                min_available_mib=0,
            )
            timed_out = self.runner.run_process(
                [sys.executable, "-c", "import time; time.sleep(30)"],
                out / "timeout",
                process_seconds=0.1,
                min_available_mib=0,
            )

            self.assertEqual(complete["status"], "COMPLETE")
            self.assertGreater(complete["peak_rss_kb"], 0)
            self.assertEqual(timed_out["status"], "PROCESS_TIMEOUT")

    def row(self, config, status="COMPLETE", total=100, rss=1000,
            case="case", repeat=0):
        return {
            "case_id": case,
            "family": "test-ser",
            "config": config,
            "repeat": repeat,
            "status": status,
            "verdict": "ACCEPT" if status == "COMPLETE" else None,
            "metrics": {"ENTIRE_EXPERIMENT": total},
            "peak_rss_kb": rss,
        }


if __name__ == "__main__":
    unittest.main()
