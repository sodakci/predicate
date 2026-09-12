#!/usr/bin/env python3
"""Compare SER constraint counts with WW reachability pruning on and off."""

from __future__ import annotations

import argparse
import csv
import os
import re
import subprocess
from pathlib import Path


HISTORY_NAME = "history.prhist.jsonl"
STRATEGIES = (
    ("NONE", "NONE"),
    ("REACHABILITY", "REACHABILITY"),
)
STATS_RE = re.compile(
    r"CONSTRAINT_STATS ww_pruning=(\w+) "
    r"constraints_before=(\d+) constraints_after=(\d+) "
    r"implications_before=(\d+) implications_after=(\d+) "
    r"internally_consistent=(true|false) pruning_inconsistent=(true|false)"
)


def parse_args() -> argparse.Namespace:
    project = Path(__file__).resolve().parents[1]
    default_histories = project.parents[1] / "predicateHistories" / "kvpredicate" / "test"
    parser = argparse.ArgumentParser(
        description="Count SER constraints with WW reachability pruning on and off; never solve.")
    parser.add_argument("history_root", nargs="?", type=Path, default=default_histories)
    parser.add_argument("--project-root", type=Path, default=project)
    parser.add_argument("--out", type=Path,
                        default=project / "results" / "pruning_constraint_comparison.csv")
    parser.add_argument("--java", default="java")
    parser.add_argument("--xmx", default="8g")
    return parser.parse_args()


def discover_histories(root: Path) -> list[Path]:
    root = root.resolve()
    if root.is_file():
        if root.name != HISTORY_NAME:
            raise SystemExit(f"Expected {HISTORY_NAME}, got: {root}")
        return [root.parent]
    if not root.is_dir():
        raise SystemExit(f"History path does not exist: {root}")
    if (root / HISTORY_NAME).is_file():
        return [root]
    histories = sorted({path.parent for path in root.rglob(HISTORY_NAME)})
    if not histories:
        raise SystemExit(f"No {HISTORY_NAME} found under {root}")
    return histories


def classpath(project: Path) -> str:
    entries: list[str] = []
    gradle_classes = project / "build" / "classes" / "java" / "main"
    if gradle_classes.is_dir():
        entries.append(str(gradle_classes))
    bin_main = project / "bin" / "main"
    if bin_main.is_dir():
        entries.append(str(bin_main))
    lib_dir = project / "build" / "install" / "ser-result-detector" / "lib"
    if not lib_dir.is_dir():
        raise SystemExit("Missing build/install/ser-result-detector/lib; build the project first.")
    entries.extend(str(path) for path in sorted(lib_dir.glob("*.jar")))
    return os.pathsep.join(entries)


def main() -> int:
    args = parse_args()
    project = args.project_root.resolve()
    histories = discover_histories(args.history_root)
    output = args.out.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    cp = classpath(project)
    rows: list[dict[str, object]] = []
    total = len(histories) * len(STRATEGIES)
    index = 0

    for history in histories:
        for strategy, mode in STRATEGIES:
            index += 1
            print(f"[{index}/{total}] {history.name} strategy={strategy}", flush=True)
            command = [
                args.java,
                f"-Xmx{args.xmx}",
                "-cp", cp,
                "Main", "constraint-stat",
                "--ww-pruning", mode,
                str(history),
            ]
            completed = subprocess.run(command, text=True, capture_output=True)
            match = STATS_RE.search(completed.stdout)
            if completed.returncode != 0 or match is None:
                raise SystemExit(
                    f"constraint-stat failed for {history} mode={mode}\n"
                    f"stdout:\n{completed.stdout}\nstderr:\n{completed.stderr}")
            ww_pruning, before, after, implications_before, implications_after, \
                internally_consistent, pruning_inconsistent = match.groups()
            if ww_pruning != mode:
                raise SystemExit(
                    f"Expected WW pruning {mode}, got {ww_pruning} for {history}")
            before_i = int(before)
            after_i = int(after)
            implications_before_i = int(implications_before)
            implications_after_i = int(implications_after)
            rows.append({
                "history": str(history),
                "strategy": strategy,
                "constraints_before": before_i,
                "constraints_after": after_i,
                "constraints_pruned": before_i - after_i,
                "implications_before": implications_before_i,
                "implications_after": implications_after_i,
                "implications_pruned": implications_before_i - implications_after_i,
                "internally_consistent": internally_consistent,
                "pruning_inconsistent": pruning_inconsistent,
            })

    with output.open("w", newline="", encoding="utf-8") as output_file:
        writer = csv.DictWriter(output_file, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    print(f"Constraint comparison: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
