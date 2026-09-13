#!/usr/bin/env python3
"""Paper-style SER experiment: EAGER baselines vs current GMWR implementation.

Configurations are intentionally identical to SERSolverARDifferentialTest:
  E1 = EAGER + WW_ONLY       + no GMWR preprop + no graph-edge interning
  E2 = EAGER + WW_ONLY       + no GMWR preprop + graph-edge interning
  G1 = GMWR  + WW_GMWR_ONEWAY + GMWR preprop   + graph-edge interning
  G2 = GMWR  + WW_GMWR        + GMWR preprop   + graph-edge interning

The authoritative timeout is a hard 180-second wall-clock limit per process.
The same 180 seconds are also passed to the MonoSAT backend. A timeout/error is
INCOMPLETE, not a semantic mismatch. Verdict equivalence is checked only when
both compared runs finish with ACCEPT/REJECT.
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import platform
import re
import shutil
import signal
import statistics
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

HISTORY_NAME = "history.prhist.jsonl"
HARD_TIMEOUT_SECONDS = 180

# Keep these columns stable so outputs can be concatenated across machines/runs.
METRICS = [
    "ENTIRE_EXPERIMENT",
    "ONESHOT_CONS",
    "ONESHOT_SOLVE",
    "SER_VERIFY_INT",
    "SER_GEN_PREC_GRAPH",
    "SER_PRUNE",
    "WW_REACHABILITY_PRUNE_MS",
    "WW_INITIAL_CHOICES",
    "WW_REACHABILITY_FORCED",
    "WW_AFTER_REACHABILITY",
    "WW_AFTER_GMWR",
    "SER_AR_ENCODE",
    "SER_AR_ENCODE_PREDICATE",
    "SER_AR_SOLVE",
    "SER_MONOSAT_SOLVE",
    "SER_AR_PREDICATE_REFINEMENT",
    "SER_GMWR_BUILD",
    "SER_GMWR_RESOLUTION",
    "SER_GMWR_ITEM_OBLIGATIONS_COUNT",
    "SER_GMWR_SEMANTIC_ITEM_OBLIGATIONS_COUNT",
    "SER_GMWR_ABSENT_ITEM_OBLIGATIONS_COUNT",
    "SER_GMWR_BUNDLES_COUNT",
    "SER_GMWR_UNIQUE_ITEM_CLAUSES_COUNT",
    "SER_GMWR_MATERIALIZED_ITEM_CLAUSES_COUNT",
    "SER_GMWR_DUPLICATE_ITEM_CLAUSES_COUNT",
    "SER_GMWR_SUBSUMED_ITEM_CLAUSES_COUNT",
    "SER_GMWR_RESOLVED_BUNDLES_COUNT",
    "SER_GMWR_RESIDUAL_BUNDLES_COUNT",
    "SER_GMWR_RESIDUAL_CLAUSES_COUNT",
    "SER_GMWR_RESIDUAL_LITERALS_COUNT",
    "SER_GMWR_FORCED_ORDERS_COUNT",
    "SER_GMWR_INTERVAL_CANDIDATES_PRUNED_COUNT",
    "SER_GMWR_RESOLUTION_ROUNDS_COUNT",
    "SER_PROP_WW_CHOICE_VARIABLES_COUNT",
    "SER_PROP_WW_CHOICE_CONSTRAINTS_COUNT",
    "SER_PROP_RESIDUAL_SAT_VARIABLES_COUNT",
    "SER_PROP_RESIDUAL_SAT_CONSTRAINTS_COUNT",
    "SER_PRED_PR_WR_SOURCE_ALTERNATIVES_COUNT",
    "SER_PRED_PR_WR_REACHABILITY_PRUNED_COUNT",
    "SER_PRED_PR_WR_PR_RW_CYCLE_PRUNED_COUNT",
    "SER_PRED_PR_WR_REACHABILITY_FORCED_COUNT",
    "SER_PRED_DEPENDENCY_ATTEMPTS_COUNT",
    "SER_PRED_DEPENDENCY_DUPLICATES_COUNT",
    "SER_PRED_DEPENDENCY_SKIPPED_COUNT",
    "SER_PRED_DEPENDENCY_QUEUED_COUNT",
    "SER_PRED_DEPENDENCY_CANDIDATES_COUNT",
    "SER_PRED_DEPENDENCY_PHYSICAL_EDGES_COUNT",
    "SER_PRED_DEPENDENCY_PHYSICAL_PR_WR_EDGES_COUNT",
    "SER_PRED_DEPENDENCY_PHYSICAL_PR_RW_EDGES_COUNT",
    "SER_PRED_DEPENDENCY_COALESCED_COUNT",
    "GMWR_INITIAL_CONSTRAINTS",
    "GMWR_RESIDUAL_CONSTRAINTS",
    "GMWR_REMOVED_CANDIDATES",
    "GMWR_FORCED_FACTS",
    "GMWR_TO_WW_FORCED",
    "GMWR_TO_WW_CONFLICTS",
    "GMWR_WW_BRIDGE_SCANS",
    "GMWR_WW_BRIDGE_CONSTRAINTS_SCANNED",
    "GMWR_WW_FIXPOINT_ROUNDS",
]

METRIC_RE = re.compile(r"^([A-Z][A-Z0-9_]*):\s+([0-9]+)(ms)?\s*$")
MEM_RE = re.compile(r"^Max memory:\s+(.+?)\s*$")


@dataclass(frozen=True)
class Config:
    name: str
    predicate: str
    propagation: str
    preprop: bool
    interning: bool

    def cli_args(self) -> list[str]:
        return [
            "--predicate-encoding", self.predicate,
            "--ser-propagation-mode", self.propagation,
            "--gmwr-prepropagation" if self.preprop else "--no-gmwr-prepropagation",
            "--predicate-witness-coalescing",
            "--graph-edge-interning" if self.interning else "--no-graph-edge-interning",
        ]


CONFIGS = {
    "E1": Config("E1", "EAGER", "WW_ONLY", False, False),
    "E2": Config("E2", "EAGER", "WW_ONLY", False, True),
    "G1": Config("G1", "GMWR", "WW_GMWR_ONEWAY", True, True),
    "G2": Config("G2", "GMWR", "WW_GMWR", True, True),
}


RAW_FIELDS = [
    "history", "label", "repeat", "config", "predicate_mode", "propagation_mode",
    "ww_pruning", "prepropagation", "graph_edge_interning",
    "status", "verdict", "return_code", "wall_seconds", "max_memory", "max_memory_mb",
    "stdout_log", "stderr_log",
] + METRICS + ["all_metrics_json"]


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="Run E2/G2 SER comparison or selected E1/G1 ablations with a hard 180s timeout per run.")
    p.add_argument("history_root", type=Path,
                   help="history dir/file or a tree containing history.prhist.jsonl")
    p.add_argument("--project-root", type=Path,
                   default=Path(__file__).resolve().parent,
                   help="ser-result-detector root; default: script directory")
    p.add_argument("--out-dir", type=Path, default=None)
    p.add_argument("--repeats", type=int, default=3)
    p.add_argument("--ww-pruning", choices=["NONE", "REACHABILITY"],
                   default="REACHABILITY",
                   help="fixed identically for all configs; default REACHABILITY")
    p.add_argument("--xmx", default="8g")
    p.add_argument("--java", default="java")
    p.add_argument("--config", action="append", choices=list(CONFIGS), dest="configs",
                   help="repeatable; default E2,G2")
    p.add_argument("--limit", type=int, default=0,
                   help="only first N histories; 0 = all")
    p.add_argument("--extra-jvm-arg", action="append", default=[])
    p.add_argument("--rerun", action="store_true",
                   help="rerun even if raw.csv already contains this history/repeat/config")
    return p.parse_args()


def discover_histories(root: Path) -> list[Path]:
    root = root.resolve()
    if root.is_file():
        if root.name != HISTORY_NAME:
            raise SystemExit(f"Expected {HISTORY_NAME}, got {root}")
        return [root.parent]
    if not root.is_dir():
        raise SystemExit(f"History root does not exist: {root}")
    if (root / HISTORY_NAME).is_file():
        return [root]
    found = sorted({p.parent.resolve() for p in root.rglob(HISTORY_NAME)})
    if not found:
        raise SystemExit(f"No {HISTORY_NAME} under {root}")
    return found


def build_classpath(project: Path) -> str:
    entries: list[str] = []
    gradle_classes = project / "build" / "classes" / "java" / "main"
    if gradle_classes.is_dir():
        entries.append(str(gradle_classes))
    bin_main = project / "bin" / "main"
    if bin_main.is_dir():
        entries.append(str(bin_main))
    lib_dir = project / "build" / "install" / "ser-result-detector" / "lib"
    if not lib_dir.is_dir():
        raise SystemExit(
            f"Missing {lib_dir}. Run './gradlew installDist' in the project first.")
    entries.extend(str(p) for p in sorted(lib_dir.glob("*.jar")))
    if not entries:
        raise SystemExit("Empty Java classpath")
    return os.pathsep.join(entries)


def safe_label(history: Path, root: Path) -> str:
    try:
        text = str(history.relative_to(root.resolve()))
    except ValueError:
        text = history.name
    if text in ("", "."):
        text = history.name
    return re.sub(r"[^A-Za-z0-9._-]+", "__", text)


def memory_to_mb(text: str) -> float | str:
    m = re.match(r"([0-9.]+)\s*([KMGT]?B)", text.strip(), re.I)
    if not m:
        return ""
    v = float(m.group(1))
    unit = m.group(2).upper()
    factors = {"KB": 1 / 1024, "MB": 1, "GB": 1024, "TB": 1024 * 1024}
    f = factors.get(unit)
    return "" if f is None else round(v * f, 3)


def parse_stderr(text: str) -> tuple[str, str, dict[str, int], str]:
    if "SER audit result: ACCEPT" in text or "[[[[ ACCEPT ]]]]" in text:
        verdict = "ACCEPT"
    elif "SER audit result: REJECT" in text or "[[[[ REJECT ]]]]" in text:
        verdict = "REJECT"
    elif "SER audit result: TIMEOUT" in text or "[[[[ TIMEOUT ]]]]" in text:
        verdict = "TIMEOUT"
    elif "[[[[ INVALID_HISTORY ]]]]" in text:
        verdict = "INVALID_HISTORY"
    else:
        verdict = ""

    metrics: dict[str, int] = {}
    memory = ""
    for line in text.splitlines():
        s = line.strip()
        m = METRIC_RE.match(s)
        if m:
            metrics[m.group(1)] = int(m.group(2))
            continue
        m = MEM_RE.match(s)
        if m:
            memory = m.group(1)

    if verdict in ("ACCEPT", "REJECT"):
        status = "COMPLETE"
    elif verdict == "TIMEOUT":
        status = "TIMEOUT"
    elif verdict == "INVALID_HISTORY":
        status = "INVALID_HISTORY"
    else:
        status = "ERROR"
    return status, verdict or status, metrics, memory


def terminate_tree(proc: subprocess.Popen[str]) -> None:
    if proc.poll() is not None:
        return
    try:
        os.killpg(proc.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        proc.wait(timeout=2)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        proc.wait()


def run_one(cmd: list[str], env: dict[str, str], stdout_path: Path,
            stderr_path: Path, timeout_seconds: int = HARD_TIMEOUT_SECONDS) -> tuple[int, float, bool]:
    start = time.monotonic()
    with stdout_path.open("w", encoding="utf-8") as out, \
            stderr_path.open("w", encoding="utf-8") as err:
        proc = subprocess.Popen(
            cmd, stdout=out, stderr=err, env=env, text=True, start_new_session=True)
        try:
            rc = proc.wait(timeout=timeout_seconds)
            return rc, time.monotonic() - start, False
        except subprocess.TimeoutExpired:
            terminate_tree(proc)
            return 124, time.monotonic() - start, True


def read_existing(path: Path) -> list[dict[str, str]]:
    if not path.is_file():
        return []
    with path.open(newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def write_csv_atomic(path: Path, rows: list[dict[str, object]], fieldnames: list[str]) -> None:
    tmp = path.with_suffix(path.suffix + ".tmp")
    with tmp.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames, extrasaction="ignore")
        w.writeheader()
        w.writerows(rows)
    os.replace(tmp, path)


def median_num(rows: Iterable[dict[str, object]], field: str) -> float | str:
    vals: list[float] = []
    for r in rows:
        v = r.get(field, "")
        if v not in ("", None):
            try:
                vals.append(float(v))
            except (TypeError, ValueError):
                pass
    return round(statistics.median(vals), 6) if vals else ""


def speedup(base: float | str, improved: float | str) -> float | str:
    if isinstance(base, (int, float)) and isinstance(improved, (int, float)) and improved > 0:
        return round(base / improved, 6)
    return ""


def reduction_pct(base: float | str, improved: float | str) -> float | str:
    if isinstance(base, (int, float)) and isinstance(improved, (int, float)) and base > 0:
        return round((base - improved) / base * 100.0, 6)
    return ""


def machine_info(project: Path, args: argparse.Namespace, selected: list[str]) -> dict[str, object]:
    def command_output(cmd: list[str]) -> str:
        try:
            return subprocess.check_output(cmd, text=True, stderr=subprocess.STDOUT, timeout=10).strip()
        except Exception as e:  # diagnostic only
            return f"unavailable: {e}"

    return {
        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S %z"),
        "hostname": platform.node(),
        "platform": platform.platform(),
        "python": sys.version.replace("\n", " "),
        "java": command_output([args.java, "-version"]),
        "git_commit": command_output(["git", "-C", str(project), "rev-parse", "HEAD"]),
        "project_root": str(project),
        "history_root": str(args.history_root.resolve()),
        "configs": selected,
        "ww_pruning": args.ww_pruning,
        "repeats": args.repeats,
        "hard_timeout_seconds": HARD_TIMEOUT_SECONDS,
        "solver_timeout_seconds": HARD_TIMEOUT_SECONDS,
        "xmx": args.xmx,
    }


def summarize(raw: list[dict[str, object]], selected: list[str], out_dir: Path) -> tuple[int, int]:
    # Keep all attempts, but performance medians only use semantically complete runs.
    by_hist: dict[str, list[dict[str, object]]] = {}
    for r in raw:
        by_hist.setdefault(str(r["history"]), []).append(r)

    summaries: list[dict[str, object]] = []
    mismatch_count = 0
    incomplete_histories = 0

    for history, rows in sorted(by_hist.items()):
        cfg_rows = {c: [r for r in rows if r["config"] == c] for c in selected}
        complete = {c: [r for r in rs if r.get("status") == "COMPLETE"]
                    for c, rs in cfg_rows.items()}
        verdicts = {c: sorted({str(r["verdict"]) for r in rs}) for c, rs in complete.items()}

        # Semantic mismatch only if completed configurations disagree.
        completed_verdict_sets = [set(v) for v in verdicts.values() if v]
        semantic_mismatch = bool(completed_verdict_sets) and any(
            v != completed_verdict_sets[0] for v in completed_verdict_sets[1:])
        if semantic_mismatch:
            mismatch_count += 1

        all_configs_have_complete = all(bool(complete[c]) for c in selected)
        if not all_configs_have_complete:
            incomplete_histories += 1

        row: dict[str, object] = {
            "history": history,
            "semantic_mismatch": int(semantic_mismatch),
            "all_configs_complete": int(all_configs_have_complete),
        }
        for c in selected:
            row[f"{c}_verdicts"] = ";".join(verdicts[c])
            row[f"{c}_complete_runs"] = len(complete[c])
            row[f"{c}_timeouts"] = sum(1 for r in cfg_rows[c] if r.get("status") == "TIMEOUT")
            row[f"{c}_errors"] = sum(1 for r in cfg_rows[c]
                                     if r.get("status") in ("ERROR", "INVALID_HISTORY"))
            row[f"{c}_entire_ms_median"] = median_num(complete[c], "ENTIRE_EXPERIMENT")
            row[f"{c}_wall_s_median"] = median_num(complete[c], "wall_seconds")
            row[f"{c}_encode_ms_median"] = median_num(complete[c], "SER_AR_ENCODE")
            row[f"{c}_predicate_encode_ms_median"] = median_num(complete[c], "SER_AR_ENCODE_PREDICATE")
            row[f"{c}_monosat_ms_median"] = median_num(complete[c], "SER_MONOSAT_SOLVE")
            row[f"{c}_memory_mb_median"] = median_num(complete[c], "max_memory_mb")
            row[f"{c}_sat_vars_median"] = median_num(complete[c], "SER_PROP_RESIDUAL_SAT_VARIABLES_COUNT")
            row[f"{c}_sat_constraints_median"] = median_num(complete[c], "SER_PROP_RESIDUAL_SAT_CONSTRAINTS_COUNT")
            row[f"{c}_pred_physical_edges_median"] = median_num(complete[c], "SER_PRED_DEPENDENCY_PHYSICAL_EDGES_COUNT")

        def add_pair(tag: str, a: str, b: str) -> None:
            if a not in selected or b not in selected:
                return
            a_ms = row.get(f"{a}_entire_ms_median", "")
            b_ms = row.get(f"{b}_entire_ms_median", "")
            a_solve = row.get(f"{a}_monosat_ms_median", "")
            b_solve = row.get(f"{b}_monosat_ms_median", "")
            a_vars = row.get(f"{a}_sat_vars_median", "")
            b_vars = row.get(f"{b}_sat_vars_median", "")
            a_cons = row.get(f"{a}_sat_constraints_median", "")
            b_cons = row.get(f"{b}_sat_constraints_median", "")
            row[f"{tag}_total_speedup_x"] = speedup(a_ms, b_ms)
            row[f"{tag}_total_reduction_pct"] = reduction_pct(a_ms, b_ms)
            row[f"{tag}_solve_speedup_x"] = speedup(a_solve, b_solve)
            row[f"{tag}_solve_reduction_pct"] = reduction_pct(a_solve, b_solve)
            row[f"{tag}_sat_vars_reduction_pct"] = reduction_pct(a_vars, b_vars)
            row[f"{tag}_sat_constraints_reduction_pct"] = reduction_pct(a_cons, b_cons)

        # Attribution chain and the two paper-facing comparisons.
        add_pair("E1_to_E2_physical_merge", "E1", "E2")
        add_pair("E2_to_G1_gmwr_encoding", "E2", "G1")
        add_pair("G1_to_G2_ww_feedback", "G1", "G2")
        add_pair("E2_to_G2_fair_main", "E2", "G2")
        add_pair("E1_to_G2_end_to_end", "E1", "G2")
        summaries.append(row)

    summary_path = out_dir / "summary_by_history.csv"
    fields: list[str] = []
    for r in summaries:
        for k in r:
            if k not in fields:
                fields.append(k)
    write_csv_atomic(summary_path, summaries, fields or ["history"])

    # Compact aggregate: completion/timeout + median of per-history paired effects.
    aggregate: dict[str, object] = {
        "histories": len(summaries),
        "semantic_mismatch_histories": mismatch_count,
        "incomplete_histories": incomplete_histories,
        "timeout_seconds": HARD_TIMEOUT_SECONDS,
    }
    for c in selected:
        all_c = [r for r in raw if r["config"] == c]
        aggregate[f"{c}_attempts"] = len(all_c)
        aggregate[f"{c}_complete"] = sum(r.get("status") == "COMPLETE" for r in all_c)
        aggregate[f"{c}_timeouts"] = sum(r.get("status") == "TIMEOUT" for r in all_c)
        aggregate[f"{c}_errors"] = sum(r.get("status") in ("ERROR", "INVALID_HISTORY") for r in all_c)

    for key in [
        "E1_to_E2_physical_merge_total_speedup_x",
        "E2_to_G1_gmwr_encoding_total_speedup_x",
        "G1_to_G2_ww_feedback_total_speedup_x",
        "E2_to_G2_fair_main_total_speedup_x",
        "E1_to_G2_end_to_end_total_speedup_x",
        "E2_to_G2_fair_main_solve_speedup_x",
        "E1_to_G2_end_to_end_solve_speedup_x",
        "E2_to_G2_fair_main_sat_vars_reduction_pct",
        "E2_to_G2_fair_main_sat_constraints_reduction_pct",
    ]:
        aggregate[f"median_{key}"] = median_num(summaries, key)

    with (out_dir / "aggregate.json").open("w", encoding="utf-8") as f:
        json.dump(aggregate, f, indent=2, ensure_ascii=False)

    return mismatch_count, incomplete_histories


def main() -> int:
    args = parse_args()
    if args.repeats < 1:
        raise SystemExit("--repeats must be >= 1")

    project = args.project_root.resolve()
    if not (project / "src" / "main" / "java").is_dir():
        raise SystemExit(
            f"{project} does not look like ser-result-detector. Pass --project-root explicitly.")

    histories = discover_histories(args.history_root)
    if args.limit > 0:
        histories = histories[:args.limit]
    selected = args.configs or ["E2", "G2"]
    selected = list(dict.fromkeys(selected))

    stamp = time.strftime("%Y%m%d-%H%M%S")
    out_dir = (args.out_dir or project / f"ser-baseline-vs-gmwr-{stamp}").resolve()
    logs_dir = out_dir / "logs"
    logs_dir.mkdir(parents=True, exist_ok=True)

    cp = build_classpath(project)
    env = os.environ.copy()
    native_dir = project / "build" / "monosat"
    env["LD_LIBRARY_PATH"] = str(native_dir) + (
        os.pathsep + env["LD_LIBRARY_PATH"] if env.get("LD_LIBRARY_PATH") else "")

    with (out_dir / "machine_and_config.json").open("w", encoding="utf-8") as f:
        json.dump(machine_info(project, args, selected), f, indent=2, ensure_ascii=False)

    raw_path = out_dir / "raw.csv"
    raw: list[dict[str, object]] = list(read_existing(raw_path))
    done = {(str(r.get("history")), str(r.get("repeat")), str(r.get("config"))) for r in raw}

    total = len(histories) * args.repeats * len(selected)
    ordinal = 0
    root_for_label = args.history_root if args.history_root.is_dir() else args.history_root.parent

    for h_idx, history in enumerate(histories):
        label = safe_label(history, root_for_label)
        for repeat in range(args.repeats):
            # Rotate the configuration order across history/repeat to reduce systematic cache bias.
            shift = (h_idx + repeat) % len(selected)
            ordered = selected[shift:] + selected[:shift]
            for cfg_name in ordered:
                ordinal += 1
                cfg = CONFIGS[cfg_name]
                key = (str(history.resolve()), str(repeat), cfg_name)
                if key in done and not args.rerun:
                    print(f"[{ordinal}/{total}] {label} r={repeat} {cfg_name}: SKIP", flush=True)
                    continue

                stem = f"{label}__r{repeat:02d}__{cfg_name}"
                stdout_path = logs_dir / f"{stem}.stdout.log"
                stderr_path = logs_dir / f"{stem}.stderr.log"
                cmd = [
                    args.java,
                    f"-Xmx{args.xmx}",
                    *args.extra_jvm_arg,
                    "-cp", cp,
                    "Main", "audit",
                    "--ww-pruning", args.ww_pruning,
                    "--solver-stats",
                    "--solver-timeout-seconds", str(HARD_TIMEOUT_SECONDS),
                    *cfg.cli_args(),
                    str(history),
                ]

                print(f"[{ordinal}/{total}] {label} r={repeat} {cfg_name} (limit={HARD_TIMEOUT_SECONDS}s)",
                      flush=True)
                rc, wall, externally_timed_out = run_one(cmd, env, stdout_path, stderr_path)
                stderr_text = stderr_path.read_text(encoding="utf-8", errors="replace")
                status, verdict, metrics, memory = parse_stderr(stderr_text)
                if externally_timed_out:
                    status, verdict = "TIMEOUT", "TIMEOUT"

                row: dict[str, object] = {
                    "history": str(history.resolve()),
                    "label": label,
                    "repeat": repeat,
                    "config": cfg.name,
                    "predicate_mode": cfg.predicate,
                    "propagation_mode": cfg.propagation,
                    "ww_pruning": args.ww_pruning,
                    "prepropagation": int(cfg.preprop),
                    "graph_edge_interning": int(cfg.interning),
                    "status": status,
                    "verdict": verdict,
                    "return_code": rc,
                    "wall_seconds": round(wall, 6),
                    "max_memory": memory,
                    "max_memory_mb": memory_to_mb(memory),
                    "stdout_log": str(stdout_path),
                    "stderr_log": str(stderr_path),
                    "all_metrics_json": json.dumps(metrics, sort_keys=True),
                }
                for m in METRICS:
                    row[m] = metrics.get(m, "")
                raw.append(row)
                done.add(key)
                write_csv_atomic(raw_path, raw, RAW_FIELDS)

                print(
                    f"    status={status} verdict={verdict} wall={wall:.3f}s "
                    f"total_ms={metrics.get('ENTIRE_EXPERIMENT', '')} "
                    f"solve_ms={metrics.get('SER_MONOSAT_SOLVE', '')} "
                    f"vars={metrics.get('SER_PROP_RESIDUAL_SAT_VARIABLES_COUNT', '')} "
                    f"cons={metrics.get('SER_PROP_RESIDUAL_SAT_CONSTRAINTS_COUNT', '')}",
                    flush=True,
                )

    write_csv_atomic(raw_path, raw, RAW_FIELDS)
    mismatch_count, incomplete_count = summarize(raw, selected, out_dir)

    print("\nOutputs:")
    print(f"  raw:       {raw_path}")
    print(f"  summary:   {out_dir / 'summary_by_history.csv'}")
    print(f"  aggregate: {out_dir / 'aggregate.json'}")
    print(f"  logs:      {logs_dir}")
    print(f"Semantic mismatches: {mismatch_count}")
    print(f"Incomplete histories (timeout/error in >=1 selected config): {incomplete_count}")

    # Correctness mismatch is fatal. Timeouts/errors make the experiment incomplete but are not mismatches.
    return 2 if mismatch_count else 0


if __name__ == "__main__":
    raise SystemExit(main())
