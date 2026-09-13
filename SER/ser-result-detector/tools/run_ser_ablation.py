#!/usr/bin/env python3
"""Run correctness-checked SER pruning, encoding, and graph-structure ablations."""

from __future__ import annotations

import argparse
import csv
import json
import os
import platform
import re
import statistics
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

from run_ser_baseline_vs_gmwr import (
    HARD_TIMEOUT_SECONDS,
    discover_histories,
    parse_stderr,
    safe_label,
    terminate_tree,
)


@dataclass(frozen=True)
class Config:
    name: str
    ww_pruning: str
    predicate: str
    propagation: str
    prepropagation: bool

    def cli_args(self) -> list[str]:
        return [
            "--ww-pruning", self.ww_pruning,
            "--predicate-encoding", self.predicate,
            "--ser-propagation-mode", self.propagation,
            ("--gmwr-prepropagation" if self.prepropagation
             else "--no-gmwr-prepropagation"),
            "--predicate-witness-coalescing",
            "--graph-edge-interning",
        ]


CONFIGS = {
    "NONE": Config("NONE", "NONE", "EAGER", "WW_ONLY", False),
    "REACHABILITY": Config(
        "REACHABILITY", "REACHABILITY", "EAGER", "WW_ONLY", False),
    "GMWR": Config("GMWR", "REACHABILITY", "GMWR", "WW_GMWR_ONEWAY", True),
    "GMWR_WWFeedback": Config(
        "GMWR_WWFeedback", "REACHABILITY", "GMWR", "WW_GMWR", True),
}

SUITES = {
    "pruning": list(CONFIGS),
    "eager-gmwr": ["REACHABILITY", "GMWR"],
    "structure": ["REACHABILITY"],
}

METRICS = [
    "ENTIRE_EXPERIMENT",
    "ONESHOT_CONS",
    "ONESHOT_SOLVE",
    "SER_GEN_PREC_GRAPH",
    "SER_AR_ENCODE",
    "SER_AR_ENCODE_PREDICATE",
    "SER_AR_SOLVE",
    "SER_MONOSAT_SOLVE",
    "WW_REACHABILITY_PRUNE_MS",
    "WW_INITIAL_CHOICES",
    "WW_REACHABILITY_FORCED",
    "WW_AFTER_REACHABILITY",
    "WW_AFTER_GMWR",
    "SER_GMWR_BUILD",
    "SER_GMWR_RESOLUTION",
    "GMWR_BUILD_MS",
    "GMWR_REDUCTION_MS",
    "GMWR_WW_BRIDGE_MS",
    "GMWR_INITIAL_CONSTRAINTS",
    "GMWR_RESIDUAL_CONSTRAINTS",
    "GMWR_REMOVED_CANDIDATES",
    "GMWR_FORCED_FACTS",
    "GMWR_TO_WW_FORCED",
    "GMWR_WW_BRIDGE_SCANS",
    "GMWR_WW_BRIDGE_CONSTRAINTS_SCANNED",
    "GMWR_WW_FIXPOINT_ROUNDS",
    "SER_GMWR_INTERVAL_CANDIDATES_PRUNED_COUNT",
    "SER_GMWR_RESIDUAL_CLAUSES_COUNT",
    "SER_GMWR_RESIDUAL_LITERALS_COUNT",
    "SER_PROP_RESIDUAL_SAT_VARIABLES_COUNT",
    "SER_PROP_RESIDUAL_SAT_CONSTRAINTS_COUNT",
    "SER_PROP_MONOSAT_GRAPH_NODES_COUNT",
    "SER_PROP_MONOSAT_GRAPH_EDGES_COUNT",
    "SER_PROP_MONOSAT_PROPAGATIONS_COUNT",
    "SER_PROP_MONOSAT_CONFLICTS_COUNT",
    "SER_PRECEDENCE_CLOSURE_BUILDS_COUNT",
    "SER_PRECEDENCE_ADD_ATTEMPTS_COUNT",
    "SER_PRECEDENCE_CLOSURE_UPDATES_COUNT",
    "SER_PRECEDENCE_REJECTED_ADDS_COUNT",
    "SER_PRECEDENCE_CYCLE_CHECKS_COUNT",
    "SER_PRECEDENCE_RELATIONS_COUNT",
]

DERIVED = [
    "initial_candidates",
    "deleted_candidates",
    "forced_orders",
    "branch_pruned_candidates",
    "residual_constraints",
    "preprocessing_ms",
]

META_FIELDS = [
    "operation_count",
    "transaction_count",
    "key_count",
    "point_read_count",
    "predicate_count",
    "predicate_transaction_count",
    "predicate_transaction_ratio",
    "predicate_operation_ratio",
    "predicate_selectivity",
    "absent_result_ratio",
    "returned_result_cardinality_mean",
    "returned_result_cardinality_median",
    "returned_result_cardinality_max",
    "written_key_count",
    "writers_per_key_mean_all_keys",
    "writers_per_key_mean",
    "writers_per_key_max",
]

BASE_FIELDS = [
    "history", "label", "repeat", "variant", "variant_classpath_prefix",
    "config", "ww_pruning", "predicate_mode", "propagation_mode",
    "prepropagation", "status", "verdict", "return_code", "wall_seconds",
    "peak_rss_kb", "peak_rss_mb", "stdout_log", "stderr_log", "resource_log",
    "stop_reason",
]
RAW_FIELDS = BASE_FIELDS + META_FIELDS + METRICS + DERIVED + ["all_metrics_json"]

RSS_RE = re.compile(r"Maximum resident set size \(kbytes\):\s*(\d+)")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="SER pruning/EAGER-GMWR/graph-structure ablation runner")
    parser.add_argument("history_root", type=Path)
    parser.add_argument("--project-root", type=Path,
                        default=Path(__file__).resolve().parents[1])
    parser.add_argument("--out-dir", type=Path)
    parser.add_argument("--suite", choices=sorted(SUITES), default="pruning")
    parser.add_argument("--config", action="append", choices=list(CONFIGS),
                        dest="configs")
    parser.add_argument(
        "--variant", action="append", default=[], metavar="NAME[=CLASSPATH_PREFIX]",
        help="repeatable implementation variant; default single, e.g. dual=/tmp/dual/classes")
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--xmx", default="5g")
    parser.add_argument("--java", default="java")
    parser.add_argument("--timeout-seconds", type=int, default=HARD_TIMEOUT_SECONDS)
    parser.add_argument(
        "--min-available-memory-mb", type=float, default=2048.0,
        help="stop only the current JVM when MemAvailable falls below this value; "
             "default 2048 MiB")
    parser.add_argument(
        "--post-verdict-grace-seconds", type=float, default=5.0,
        help="stop a JVM that printed a final verdict but did not exit; default 5 seconds")
    parser.add_argument("--extra-jvm-arg", action="append", default=[])
    parser.add_argument("--rerun", action="store_true")
    return parser.parse_args()


def parse_variants(values: list[str]) -> list[tuple[str, str]]:
    if not values:
        return [("single", "")]
    variants: list[tuple[str, str]] = []
    seen: set[str] = set()
    for value in values:
        name, sep, prefix = value.partition("=")
        if not name or name in seen:
            raise SystemExit(f"Invalid or duplicate --variant: {value}")
        seen.add(name)
        if sep and not Path(prefix).is_dir():
            raise SystemExit(f"Variant classpath prefix does not exist: {prefix}")
        variants.append((name, str(Path(prefix).resolve()) if sep else ""))
    return variants


def build_classpath(project: Path, prefix: str) -> str:
    entries: list[str] = []
    if prefix:
        entries.append(prefix)
    classes = project / "build" / "classes" / "java" / "main"
    if classes.is_dir():
        entries.append(str(classes))
    installed = project / "build" / "install" / "ser-result-detector" / "lib"
    if not installed.is_dir():
        raise SystemExit(f"Missing {installed}; run './gradlew installDist' first")
    entries.extend(str(path) for path in sorted(installed.glob("*.jar")))
    return os.pathsep.join(entries)


def _list_size(value: object) -> int:
    return len(value) if isinstance(value, list) else 0


def history_dimensions(history_dir: Path) -> dict[str, float | int]:
    predicate_count = 0
    predicate_transaction_count = 0
    predicate_rows = 0
    predicate_cardinalities: list[int] = []
    absent_results = 0
    operation_count = 0
    point_read_count = 0
    transaction_count = 0
    writers_by_key: dict[str, set[str]] = {}

    with (history_dir / "history.prhist.jsonl").open(encoding="utf-8") as stream:
        for line in stream:
            if not line.strip():
                continue
            txn = json.loads(line)
            transaction_count += 1
            txn_id = str(txn.get("txn", transaction_count))
            transaction_has_predicate = False
            for op in txn.get("ops", []):
                operation_count += 1
                if op.get("type") == "w" and "key" in op:
                    writers_by_key.setdefault(str(op["key"]), set()).add(txn_id)
                elif op.get("type") == "r":
                    point_read_count += 1
                elif op.get("type") == "pr":
                    predicate_count += 1
                    transaction_has_predicate = True
                    values = op.get("result", {}).get("values", [])
                    rows = _list_size(values)
                    predicate_rows += rows
                    predicate_cardinalities.append(rows)
                    if rows == 0:
                        absent_results += 1
            predicate_transaction_count += int(transaction_has_predicate)

    manifest_path = history_dir / "manifest.json"
    universe = 0
    if manifest_path.is_file():
        try:
            universe = int(json.loads(manifest_path.read_text(encoding="utf-8"))
                           .get("initial_keys", 0))
        except (ValueError, TypeError, json.JSONDecodeError):
            universe = 0
    if universe <= 0:
        universe = max(1, len(writers_by_key))
    writers = [len(txns) for txns in writers_by_key.values()]
    return {
        "operation_count": operation_count,
        "transaction_count": transaction_count,
        "key_count": universe,
        "point_read_count": point_read_count,
        "predicate_count": predicate_count,
        "predicate_transaction_count": predicate_transaction_count,
        "predicate_transaction_ratio": round(
            predicate_transaction_count / transaction_count, 8)
            if transaction_count else 0.0,
        "predicate_operation_ratio": round(
            predicate_count / operation_count, 8) if operation_count else 0.0,
        "predicate_selectivity": round(
            predicate_rows / (predicate_count * universe), 8)
            if predicate_count else 0.0,
        "absent_result_ratio": round(absent_results / predicate_count, 8)
            if predicate_count else 0.0,
        "returned_result_cardinality_mean": round(
            statistics.mean(predicate_cardinalities), 8)
            if predicate_cardinalities else 0.0,
        "returned_result_cardinality_median": round(
            statistics.median(predicate_cardinalities), 8)
            if predicate_cardinalities else 0.0,
        "returned_result_cardinality_max": max(predicate_cardinalities, default=0),
        "written_key_count": len(writers),
        "writers_per_key_mean_all_keys": round(sum(writers) / universe, 8),
        "writers_per_key_mean": round(statistics.mean(writers), 8) if writers else 0.0,
        "writers_per_key_max": max(writers, default=0),
    }


def metric(metrics: dict[str, int], name: str) -> int:
    return int(metrics.get(name, 0))


def derive_metrics(metrics: dict[str, int]) -> dict[str, int]:
    ww_initial = metric(metrics, "WW_INITIAL_CHOICES")
    ww_residual = metric(metrics, "WW_AFTER_GMWR")
    if "WW_AFTER_GMWR" not in metrics:
        ww_residual = metric(metrics, "WW_AFTER_REACHABILITY")
    gmwr_initial = metric(metrics, "GMWR_INITIAL_CONSTRAINTS")
    gmwr_residual = metric(metrics, "GMWR_RESIDUAL_CONSTRAINTS")
    if "GMWR_RESIDUAL_CONSTRAINTS" not in metrics:
        gmwr_residual = metric(metrics, "SER_GMWR_RESIDUAL_CLAUSES_COUNT")
    ww_deleted = max(0, ww_initial - ww_residual)
    gmwr_deleted = max(0, gmwr_initial - gmwr_residual)
    interval_pruned = metric(metrics, "SER_GMWR_INTERVAL_CANDIDATES_PRUNED_COUNT")
    return {
        "initial_candidates": ww_initial + gmwr_initial,
        "deleted_candidates": ww_deleted + gmwr_deleted,
        "forced_orders": (
            metric(metrics, "WW_REACHABILITY_FORCED")
            + metric(metrics, "GMWR_FORCED_FACTS")
            + metric(metrics, "GMWR_TO_WW_FORCED")
        ),
        "branch_pruned_candidates": ww_deleted + gmwr_deleted + interval_pruned,
        "residual_constraints": ww_residual + gmwr_residual,
        "preprocessing_ms": (
            metric(metrics, "SER_GEN_PREC_GRAPH")
            + metric(metrics, "WW_REACHABILITY_PRUNE_MS")
            + metric(metrics, "GMWR_BUILD_MS")
            + metric(metrics, "GMWR_REDUCTION_MS")
            + metric(metrics, "GMWR_WW_BRIDGE_MS")
        ),
    }


def parse_peak_rss(path: Path) -> tuple[int | str, float | str]:
    if not path.is_file():
        return "", ""
    match = RSS_RE.search(path.read_text(encoding="utf-8", errors="replace"))
    if not match:
        return "", ""
    kb = int(match.group(1))
    return kb, round(kb / 1024.0, 3)


def available_memory_mb() -> float | None:
    try:
        with Path("/proc/meminfo").open(encoding="ascii") as meminfo:
            for line in meminfo:
                if line.startswith("MemAvailable:"):
                    return float(line.split()[1]) / 1024.0
    except (FileNotFoundError, OSError, ValueError, IndexError):
        pass
    return None


def read_new_text(path: Path, offset: int) -> tuple[str, int]:
    try:
        with path.open("r", encoding="utf-8", errors="replace") as stream:
            stream.seek(offset)
            return stream.read(), stream.tell()
    except FileNotFoundError:
        return "", offset


def run_one(cmd: list[str], env: dict[str, str], stdout_path: Path,
            stderr_path: Path, resource_path: Path, timeout: int,
            min_available_memory_mb: float,
            post_verdict_grace_seconds: float) -> tuple[int, float, bool, str]:
    timed = ["/usr/bin/time", "-v", "-o", str(resource_path), *cmd]
    started = time.monotonic()
    stderr_offset = 0
    stderr_tail = ""
    verdict_seen_at: float | None = None
    with stdout_path.open("w", encoding="utf-8") as stdout, \
            stderr_path.open("w", encoding="utf-8") as stderr:
        proc = subprocess.Popen(
            timed, stdout=stdout, stderr=stderr, env=env, text=True,
            start_new_session=True)
    while proc.poll() is None:
        available_mb = available_memory_mb()
        if (min_available_memory_mb > 0 and available_mb is not None
                and available_mb < min_available_memory_mb):
            terminate_tree(proc)
            return 125, time.monotonic() - started, False, "MEMORY_GUARD"

        chunk, stderr_offset = read_new_text(stderr_path, stderr_offset)
        if chunk:
            stderr_tail = (stderr_tail + chunk)[-4096:]
            if any(marker in stderr_tail for marker in (
                    "[[[[ ACCEPT ]]]]", "[[[[ REJECT ]]]]", "[[[[ TIMEOUT ]]]]")):
                if verdict_seen_at is None:
                    verdict_seen_at = time.monotonic()

        now = time.monotonic()
        if now - started > timeout:
            terminate_tree(proc)
            return 124, now - started, True, "TIMEOUT"
        if (verdict_seen_at is not None
                and now - verdict_seen_at > post_verdict_grace_seconds):
            terminate_tree(proc)
            return proc.returncode or 0, now - started, False, "POST_VERDICT_GRACE"
        time.sleep(0.2)
    return proc.wait(), time.monotonic() - started, False, ""


def read_csv(path: Path) -> list[dict[str, object]]:
    if not path.is_file():
        return []
    with path.open(newline="", encoding="utf-8") as stream:
        return list(csv.DictReader(stream))


def write_csv(path: Path, rows: list[dict[str, object]], fields: list[str]) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)
    os.replace(temporary, path)


def numeric_median(rows: Iterable[dict[str, object]], field: str) -> float | str:
    values: list[float] = []
    for row in rows:
        value = row.get(field, "")
        if value not in ("", None):
            try:
                values.append(float(value))
            except (TypeError, ValueError):
                pass
    return round(statistics.median(values), 6) if values else ""


def summarize(raw: list[dict[str, object]], configs: list[str],
              variants: list[tuple[str, str]], out_dir: Path) -> int:
    groups: dict[tuple[str, str, str], list[dict[str, object]]] = {}
    for row in raw:
        key = (str(row["history"]), str(row["variant"]), str(row["config"]))
        groups.setdefault(key, []).append(row)

    median_fields = [
        *META_FIELDS, *DERIVED, "wall_seconds", "peak_rss_mb", "ENTIRE_EXPERIMENT",
        "SER_AR_ENCODE", "SER_MONOSAT_SOLVE",
        "SER_PROP_RESIDUAL_SAT_VARIABLES_COUNT",
        "SER_PROP_RESIDUAL_SAT_CONSTRAINTS_COUNT",
        "SER_PROP_MONOSAT_GRAPH_NODES_COUNT",
        "SER_PROP_MONOSAT_GRAPH_EDGES_COUNT",
        "SER_PROP_MONOSAT_PROPAGATIONS_COUNT",
        "SER_PRECEDENCE_CLOSURE_BUILDS_COUNT",
        "SER_PRECEDENCE_CLOSURE_UPDATES_COUNT",
        "SER_PRECEDENCE_CYCLE_CHECKS_COUNT",
    ]
    summary: list[dict[str, object]] = []
    for (history, variant, config), rows in sorted(groups.items()):
        complete_rows = [row for row in rows if row.get("status") == "COMPLETE"]
        timeout_runs = sum(row.get("status") == "TIMEOUT" for row in rows)
        item: dict[str, object] = {
            "history": history,
            "variant": variant,
            "config": config,
            "verdict": ";".join(sorted({str(row["verdict"]) for row in complete_rows})),
            "attempted_runs": len(rows),
            "complete_runs": len(complete_rows),
            "timeout_runs": timeout_runs,
            "timeout_rate": round(timeout_runs / len(rows), 8),
            "memory_guard_runs": sum(
                row.get("status") == "MEMORY_GUARD" for row in rows),
            "error_runs": sum(
                row.get("status") not in {"COMPLETE", "TIMEOUT", "MEMORY_GUARD"}
                for row in rows),
        }
        for field in median_fields:
            item[field] = numeric_median(complete_rows, field)
        summary.append(item)
    summary_fields = [
        "history", "variant", "config", "verdict", "attempted_runs", "complete_runs",
        "timeout_runs", "timeout_rate", "memory_guard_runs", "error_runs",
    ] + median_fields
    write_csv(out_dir / "summary.csv", summary, summary_fields)

    causal_fields = [
        "history", "variant", "config", "attempted_runs", "complete_runs",
        "timeout_rate", "initial_candidates", "deleted_candidates",
        "branch_pruned_candidates", "residual_constraints", "preprocessing_ms",
        "SER_AR_ENCODE", "SER_MONOSAT_SOLVE", "ENTIRE_EXPERIMENT", "peak_rss_mb",
        "SER_PROP_RESIDUAL_SAT_VARIABLES_COUNT",
        "SER_PROP_MONOSAT_GRAPH_EDGES_COUNT",
    ]
    write_csv(out_dir / "causal_chain.csv", summary, causal_fields)

    summary_index = {
        (str(row["history"]), str(row["variant"]), str(row["config"])): row
        for row in summary
    }
    delta_metrics = [
        "deleted_candidates", "forced_orders", "residual_constraints",
        "preprocessing_ms", "SER_AR_ENCODE", "SER_MONOSAT_SOLVE",
        "ENTIRE_EXPERIMENT", "peak_rss_mb",
        "SER_PROP_RESIDUAL_SAT_VARIABLES_COUNT",
        "SER_PROP_MONOSAT_GRAPH_EDGES_COUNT",
    ]
    deltas: list[dict[str, object]] = []
    histories = sorted({str(row["history"]) for row in summary})
    for history in histories:
        for variant, _ in variants:
            for previous, current in zip(configs, configs[1:]):
                before = summary_index.get((history, variant, previous))
                after = summary_index.get((history, variant, current))
                if before is None or after is None:
                    continue
                item: dict[str, object] = {
                    "history": history, "variant": variant,
                    "from_config": previous, "to_config": current,
                }
                for field in delta_metrics:
                    left, right = before.get(field, ""), after.get(field, "")
                    item[f"{field}_delta"] = (
                        round(float(right) - float(left), 6)
                        if left not in ("", None) and right not in ("", None) else "")
                deltas.append(item)
    delta_fields = ["history", "variant", "from_config", "to_config"] + [
        f"{field}_delta" for field in delta_metrics]
    write_csv(out_dir / "step_deltas.csv", deltas, delta_fields)

    structure: list[dict[str, object]] = []
    if len(variants) > 1:
        baseline_variant = variants[0][0]
        structure_metrics = [
            "SER_PROP_MONOSAT_GRAPH_EDGES_COUNT",
            "SER_PROP_RESIDUAL_SAT_VARIABLES_COUNT",
            "SER_AR_ENCODE", "SER_MONOSAT_SOLVE", "ENTIRE_EXPERIMENT", "peak_rss_mb",
            "SER_PRECEDENCE_CLOSURE_BUILDS_COUNT",
            "SER_PRECEDENCE_CLOSURE_UPDATES_COUNT",
            "SER_PROP_MONOSAT_PROPAGATIONS_COUNT", "branch_pruned_candidates",
            "preprocessing_ms",
        ]
        for history in histories:
            for config in configs:
                before = summary_index.get((history, baseline_variant, config))
                if before is None:
                    continue
                for current_variant, _ in variants[1:]:
                    after = summary_index.get((history, current_variant, config))
                    if after is None:
                        continue
                    item: dict[str, object] = {
                        "history": history, "config": config,
                        "baseline_variant": baseline_variant,
                        "current_variant": current_variant,
                    }
                    for field in structure_metrics:
                        left, right = before.get(field, ""), after.get(field, "")
                        if left in ("", None) or right in ("", None):
                            item[f"{field}_delta"] = ""
                            item[f"{field}_reduction_pct"] = ""
                            continue
                        left_num, right_num = float(left), float(right)
                        item[f"{field}_delta"] = round(right_num - left_num, 6)
                        item[f"{field}_reduction_pct"] = (
                            round((left_num - right_num) / left_num * 100.0, 6)
                            if left_num else "")
                    structure.append(item)
        structure_fields = [
            "history", "config", "baseline_variant", "current_variant",
        ] + [suffix for field in structure_metrics
             for suffix in (f"{field}_delta", f"{field}_reduction_pct")]
        write_csv(out_dir / "structure_deltas.csv", structure, structure_fields)

    equivalence: list[dict[str, object]] = []
    mismatches = 0
    by_attempt: dict[tuple[str, str], list[dict[str, object]]] = {}
    for row in raw:
        by_attempt.setdefault((str(row["history"]), str(row["repeat"])), []).append(row)
    expected = len(configs) * len(variants)
    for (history, repeat), rows in sorted(by_attempt.items()):
        completed_verdicts = sorted({str(row["verdict"]) for row in rows
                                     if row.get("status") == "COMPLETE"})
        mismatch = len(completed_verdicts) > 1
        mismatches += int(mismatch)
        equivalence.append({
            "history": history,
            "repeat": repeat,
            "completed_runs": sum(row.get("status") == "COMPLETE" for row in rows),
            "expected_runs": expected,
            "verdicts": ";".join(completed_verdicts),
            "semantic_mismatch": int(mismatch),
        })
    write_csv(out_dir / "equivalence.csv", equivalence, [
        "history", "repeat", "completed_runs", "expected_runs", "verdicts",
        "semantic_mismatch",
    ])
    return mismatches


def machine_info(args: argparse.Namespace, project: Path, configs: list[str],
                 variants: list[tuple[str, str]]) -> dict[str, object]:
    return {
        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S %z"),
        "hostname": platform.node(),
        "platform": platform.platform(),
        "python": sys.version.replace("\n", " "),
        "project_root": str(project),
        "history_root": str(args.history_root.resolve()),
        "suite": args.suite,
        "configs": configs,
        "variants": [{"name": name, "classpath_prefix": prefix}
                     for name, prefix in variants],
        "repeats": args.repeats,
        "timeout_seconds": args.timeout_seconds,
        "xmx": args.xmx,
        "min_available_memory_mb": args.min_available_memory_mb,
        "post_verdict_grace_seconds": args.post_verdict_grace_seconds,
    }


def main() -> int:
    args = parse_args()
    if (args.repeats < 1 or args.timeout_seconds < 1
            or args.min_available_memory_mb < 0
            or args.post_verdict_grace_seconds < 0):
        raise SystemExit(
            "--repeats/--timeout-seconds must be positive and memory/grace guards non-negative")
    project = args.project_root.resolve()
    configs = list(dict.fromkeys(args.configs or SUITES[args.suite]))
    variants = parse_variants(args.variant)
    histories = discover_histories(args.history_root)
    if args.limit > 0:
        histories = histories[:args.limit]

    stamp = time.strftime("%Y%m%d-%H%M%S")
    out_dir = (args.out_dir or project / "results" / f"ser-{args.suite}-{stamp}").resolve()
    logs_dir = out_dir / "logs"
    logs_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "machine_and_config.json").write_text(
        json.dumps(machine_info(args, project, configs, variants), indent=2,
                   ensure_ascii=False) + "\n",
        encoding="utf-8")

    env = os.environ.copy()
    native = project / "build" / "monosat"
    env["LD_LIBRARY_PATH"] = str(native) + (
        os.pathsep + env["LD_LIBRARY_PATH"] if env.get("LD_LIBRARY_PATH") else "")
    classpaths = {name: build_classpath(project, prefix) for name, prefix in variants}
    dimensions = {str(history.resolve()): history_dimensions(history) for history in histories}

    raw_path = out_dir / "raw.csv"
    raw = read_csv(raw_path)
    done = {(str(row.get("history")), str(row.get("repeat")),
             str(row.get("variant")), str(row.get("config"))) for row in raw}
    total = len(histories) * args.repeats * len(variants) * len(configs)
    ordinal = 0
    root_for_label = args.history_root if args.history_root.is_dir() else args.history_root.parent

    for history_index, history in enumerate(histories):
        history_key = str(history.resolve())
        label = safe_label(history, root_for_label)
        combinations = [(variant, prefix, config)
                        for variant, prefix in variants for config in configs]
        for repeat in range(args.repeats):
            shift = (history_index + repeat) % len(combinations)
            ordered = combinations[shift:] + combinations[:shift]
            for variant, prefix, config_name in ordered:
                ordinal += 1
                key = (history_key, str(repeat), variant, config_name)
                if key in done and not args.rerun:
                    print(f"[{ordinal}/{total}] {label} r={repeat} {variant}/{config_name}: SKIP",
                          flush=True)
                    continue
                config = CONFIGS[config_name]
                stem = f"{label}__r{repeat:02d}__{variant}__{config_name}"
                stdout_path = logs_dir / f"{stem}.stdout.log"
                stderr_path = logs_dir / f"{stem}.stderr.log"
                resource_path = logs_dir / f"{stem}.resource.log"
                command = [
                    args.java, "-Xms256m", f"-Xmx{args.xmx}",
                    "-XX:+ExitOnOutOfMemoryError", *args.extra_jvm_arg,
                    "-cp", classpaths[variant], "Main", "audit", "--solver-stats",
                    "--solver-timeout-seconds", str(args.timeout_seconds),
                    *config.cli_args(), history_key,
                ]
                print(f"[{ordinal}/{total}] {label} r={repeat} {variant}/{config_name}",
                      flush=True)
                return_code, wall, external_timeout, stop_reason = run_one(
                    command, env, stdout_path, stderr_path, resource_path,
                    args.timeout_seconds, args.min_available_memory_mb,
                    args.post_verdict_grace_seconds)
                stderr_text = stderr_path.read_text(encoding="utf-8", errors="replace")
                status, verdict, metrics, _ = parse_stderr(stderr_text)
                if external_timeout:
                    status, verdict = "TIMEOUT", "TIMEOUT"
                elif stop_reason == "MEMORY_GUARD":
                    status, verdict = "MEMORY_GUARD", "MEMORY_GUARD"
                rss_kb, rss_mb = parse_peak_rss(resource_path)
                row: dict[str, object] = {
                    "history": history_key, "label": label, "repeat": repeat,
                    "variant": variant, "variant_classpath_prefix": prefix,
                    "config": config_name, "ww_pruning": config.ww_pruning,
                    "predicate_mode": config.predicate,
                    "propagation_mode": config.propagation,
                    "prepropagation": int(config.prepropagation),
                    "status": status, "verdict": verdict,
                    "return_code": return_code, "wall_seconds": round(wall, 6),
                    "peak_rss_kb": rss_kb, "peak_rss_mb": rss_mb,
                    "stdout_log": str(stdout_path), "stderr_log": str(stderr_path),
                    "resource_log": str(resource_path),
                    "stop_reason": stop_reason,
                    **dimensions[history_key], **derive_metrics(metrics),
                    "all_metrics_json": json.dumps(metrics, sort_keys=True),
                }
                for name in METRICS:
                    row[name] = metrics.get(name, "")
                if args.rerun:
                    raw = [existing for existing in raw if (
                        str(existing.get("history")), str(existing.get("repeat")),
                        str(existing.get("variant")), str(existing.get("config"))) != key]
                raw.append(row)
                done.add(key)
                write_csv(raw_path, raw, RAW_FIELDS)
                print(
                    f"    {status}/{verdict} total={metrics.get('ENTIRE_EXPERIMENT', '')}ms "
                    f"pre={row['preprocessing_ms']}ms solve={metrics.get('SER_MONOSAT_SOLVE', '')}ms "
                    f"rss={rss_mb}MiB stop={stop_reason or '-'}", flush=True)

    write_csv(raw_path, raw, RAW_FIELDS)
    mismatches = summarize(raw, configs, variants, out_dir)
    print(f"Outputs: {out_dir}")
    print(f"Semantic mismatches: {mismatches}")
    return 2 if mismatches else 0


if __name__ == "__main__":
    raise SystemExit(main())
