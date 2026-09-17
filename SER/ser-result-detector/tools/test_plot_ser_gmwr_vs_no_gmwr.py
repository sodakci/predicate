import csv
import subprocess
import sys
from pathlib import Path
import tempfile
import unittest


class GmwrPlotTest(unittest.TestCase):
    SCRIPT = Path(__file__).with_name("plot_ser_gmwr_vs_no_gmwr.py")

    def write_raw(self, path):
        rows = []

        def add(dataset, config, elapsed, repeat=0, status="COMPLETE",
                family="test-ser"):
            rows.append({
                "family": family,
                "dataset": dataset,
                "config": config,
                "repeat": repeat,
                "status": status,
                "verdict": "ACCEPT" if status == "COMPLETE" else "",
                "ENTIRE_EXPERIMENT": elapsed,
            })

        datasets = {
            "20_100_15_5000_0.20_uniform": (1000, 4000),
            "20_50_15_5000_0.20_uniform": (1200, 3600),
            "20_100_10_5000_0.20_uniform": (1400, 3500),
            "20_100_15_3000_0.20_uniform": (1600, 3200),
            "20_100_15_5000_0.10_uniform": (1800, 2700),
        }
        for dataset, (full, no_gmwr) in datasets.items():
            add(dataset, "FULL", full, repeat=0)
            add(dataset, "FULL", full + 2000, repeat=1)
            add(dataset, "NO_GMWR", no_gmwr, repeat=0)
            add(dataset, "NO_GMWR", no_gmwr + 2000, repeat=1)

        # These rows must not influence controlled, paired medians.
        add("20_100_15_5000_0.20_uniform", "FULL", 999999,
            repeat=2, status="SOLVER_TIMEOUT")
        add("20_100_15_5000_0.20_uniform", "FULL", 999999,
            repeat=3, family="test-ser%")
        add("20_50_10_5000_0.20_uniform", "FULL", 10)
        add("20_50_10_5000_0.20_uniform", "NO_GMWR", 20)

        with path.open("w", newline="", encoding="utf-8") as stream:
            writer = csv.DictWriter(stream, fieldnames=rows[0])
            writer.writeheader()
            writer.writerows(rows)

    def test_cli_generates_four_controlled_svg_charts_and_median_table(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            raw = root / "raw.csv"
            output = root / "charts"
            self.write_raw(raw)

            result = subprocess.run(
                [sys.executable, str(self.SCRIPT), str(raw), "--out-dir", str(output)],
                capture_output=True,
                text=True,
            )

            self.assertEqual(result.returncode, 0, result.stderr)
            expected = {
                "transactions.svg",
                "operations_per_transaction.svg",
                "keys.svg",
                "predicate_ratio.svg",
            }
            self.assertEqual({path.name for path in output.glob("*.svg")}, expected)
            for name in expected:
                svg = (output / name).read_text(encoding="utf-8")
                self.assertIn("GMWR (FULL)", svg)
                self.assertIn("NO_GMWR", svg)
                self.assertNotIn("999999", svg)

            with (output / "gmwr_vs_no_gmwr.csv").open(
                    newline="", encoding="utf-8") as stream:
                rows = list(csv.DictReader(stream))
            self.assertEqual(len(rows), 8)
            baseline = next(
                row for row in rows
                if row["variable"] == "transactions" and row["x"] == "100"
            )
            self.assertEqual(baseline["gmwr_complete_runs"], "2")
            self.assertEqual(baseline["no_gmwr_complete_runs"], "2")
            self.assertEqual(baseline["gmwr_median_seconds"], "2.000000")
            self.assertEqual(baseline["no_gmwr_median_seconds"], "5.000000")
            self.assertEqual(baseline["speedup_no_gmwr_over_gmwr"], "2.500000")
            self.assertFalse(any("20_50_10_5000" in row["dataset"] for row in rows))

    def test_cli_rejects_results_without_complete_full_and_no_gmwr_pair(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            raw = root / "raw.csv"
            with raw.open("w", newline="", encoding="utf-8") as stream:
                writer = csv.DictWriter(stream, fieldnames=(
                    "family", "dataset", "config", "status", "ENTIRE_EXPERIMENT"))
                writer.writeheader()
                writer.writerow({
                    "family": "test-ser",
                    "dataset": "20_100_15_5000_0.20_uniform",
                    "config": "FULL",
                    "status": "SOLVER_TIMEOUT",
                    "ENTIRE_EXPERIMENT": "90000",
                })

            result = subprocess.run(
                [sys.executable, str(self.SCRIPT), str(raw),
                 "--out-dir", str(root / "charts")],
                capture_output=True,
                text=True,
            )

            self.assertEqual(result.returncode, 2)
            self.assertIn("no paired COMPLETE FULL/NO_GMWR measurements", result.stderr)


if __name__ == "__main__":
    unittest.main()
