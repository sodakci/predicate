#!/usr/bin/env python3
"""Batch EAGER vs GMWR comparison for SER predicate histories.

The script deliberately runs all modes through the same verifier, WW pruning,
JVM heap, and history set. It checkpoints each completed run in the raw CSV and
resumes from that file when the same output directory is reused. It also writes
per-history summary CSV and complete stdout/stderr logs for later diagnosis.
"""

from __future__ import annotations

import argparse
import csv
import os
import re
import statistics
import subprocess
import sys
import time
from pathlib import Path

HISTORY_NAME = "history.prhist.jsonl"
DEFAULT_METRICS = [
    "ENTIRE_EXPERIMENT",
    "ONESHOT_CONS",
    "ONESHOT_SOLVE",
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
    "SER_GMWR_ITEM_OBLIGATIONS_COUNT",
    "SER_GMWR_RETURNED_ITEM_OBLIGATIONS_COUNT",
    "SER_GMWR_ABSENT_ITEM_OBLIGATIONS_COUNT",
    "SER_GMWR_BUNDLES_COUNT",
    "SER_GMWR_UNIQUE_ITEM_CLAUSES_COUNT",
    "SER_GMWR_MATERIALIZED_ITEM_CLAUSES_COUNT",
    "SER_GMWR_DUPLICATE_ITEM_CLAUSES_COUNT",
    "SER_GMWR_RESOLVED_BUNDLES_COUNT",
    "SER_GMWR_RESIDUAL_BUNDLES_COUNT",
    "SER_GMWR_RESIDUAL_CLAUSES_COUNT",
    "SER_GMWR_RESIDUAL_LITERALS_COUNT",
    "SER_GMWR_FORCED_ORDERS_COUNT",
    "SER_GMWR_RESOLUTION_ROUNDS_COUNT",
]

METRIC_RE = re.compile(r"^([A-Z][A-Z0-9_]*):\s+([0-9]+)(ms)?\s*$")
MEM_RE = re.compile(r"^Max memory:\s+(.+?)\s*$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run the same SER histories with EAGER and independent GMWR.")
    parser.add_argument("history_root", type=Path,
                        help="history directory/file, or a tree containing history.prhist.jsonl")
    parser.add_argument("--project-root", type=Path,
                        default=Path(__file__).resolve().parents[1])
    parser.add_argument("--out-dir", type=Path, default=None)
    parser.add_argument("--repeats", type=int, default=1)
    parser.add_argument("--warmup", type=int, default=0)
    parser.add_argument("--ww-pruning",
                        choices=["NONE", "REACHABILITY"],
                        default="REACHABILITY",
                        help="use the same WW pruning setting for all modes")
    parser.add_argument("--xmx", default="4g")
    parser.add_argument("--timeout-seconds", type=int, default=900)
    parser.add_argument("--min-available-memory-mb", type=float, default=0,
                        help="stop only the current JVM when system MemAvailable falls below "
                             "this value; 0 disables the guard")
    parser.add_argument("--post-verdict-grace-seconds", type=float, default=5.0,
                        help="kill a JVM that printed a final verdict but fails to exit promptly")
    parser.add_argument("--java", default="java")
    parser.add_argument("--mode", action="append", choices=["EAGER", "GMWR"], dest="modes",
                        help="repeatable; default is EAGER and GMWR")
    parser.add_argument("--extra-jvm-arg", action="append", default=[])
    parser.add_argument("--limit", type=int, default=0,
                        help="run only the first N discovered histories; 0 means all")
    return parser.parse_args()


def discover_histories(root: Path) -> list[Path]:
    root = root.resolve()
    if root.is_file():
        if root.name != HISTORY_NAME:
            raise SystemExit(f"Expected {HISTORY_NAME}, got: {root}")
        return [root.parent]
    if not root.is_dir():
        raise SystemExit(f"History path does not exist: {root}")
    direct = root / HISTORY_NAME
    if direct.is_file():
        return [root]
    histories = sorted({p.parent for p in root.rglob(HISTORY_NAME)})
    if not histories:
        raise SystemExit(f"No {HISTORY_NAME} found under {root}")
    return histories


def classpath(project: Path) -> str:
    # bin/main is first because the supplied package contains the verified GMWR
    # classes there.  A normal Gradle build may also populate build/classes.
    entries: list[str] = []
    # Prefer Gradle output so a normal rebuild automatically takes precedence.
    # The supplied experimental package also contains verified compiled GMWR
    # classes here for immediate execution without a rebuild.
    gradle_classes = project / "build" / "classes" / "java" / "main"
    if gradle_classes.is_dir():
        entries.append(str(gradle_classes))
    bin_main = project / "bin" / "main"
    if bin_main.is_dir():
        entries.append(str(bin_main))
    lib_dir = project / "build" / "install" / "ser-result-detector" / "lib"
    if not lib_dir.is_dir():
        raise SystemExit(
            "Missing build/install/ser-result-detector/lib. Build/install the project first.")
    entries.extend(str(p) for p in sorted(lib_dir.glob("*.jar")))
    return os.pathsep.join(entries)


def read_new_text(path: Path, offset: int) -> tuple[str, int]:
    try:
        with path.open("r", encoding="utf-8", errors="replace") as f:
            f.seek(offset)
            data = f.read()
            return data, f.tell()
    except FileNotFoundError:
        return "", offset


def available_memory_mb() -> float | None:
    try:
        with Path("/proc/meminfo").open(encoding="ascii") as meminfo:
            for line in meminfo:
                if line.startswith("MemAvailable:"):
                    return float(line.split()[1]) / 1024.0
    except (FileNotFoundError, OSError, ValueError, IndexError):
        pass
    return None


def terminate_process(proc: subprocess.Popen[object]) -> None:
    proc.terminate()
    try:
        proc.wait(timeout=2)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()


def run_one(cmd: list[str], env: dict[str, str], stdout_path: Path, stderr_path: Path,
            timeout_seconds: int, post_verdict_grace: float,
            min_available_memory_mb: float) -> tuple[int, float, bool, str]:
    started = time.monotonic()
    verdict_seen_at: float | None = None
    stderr_offset = 0
    buffer_tail = ""
    forced_after_verdict = False

    with stdout_path.open("w", encoding="utf-8") as out, \
            stderr_path.open("w", encoding="utf-8") as err:
        proc = subprocess.Popen(cmd, stdout=out, stderr=err, env=env)

    while proc.poll() is None:
        available_mb = available_memory_mb()
        if (min_available_memory_mb > 0 and available_mb is not None
                and available_mb < min_available_memory_mb):
            terminate_process(proc)
            print(f"    memory guard: MemAvailable={available_mb:.1f}MB is below "
                  f"{min_available_memory_mb:.1f}MB; stopped current JVM", file=sys.stderr,
                  flush=True)
            return 125, time.monotonic() - started, forced_after_verdict, "MEMORY_GUARD"

        time.sleep(0.20)
        chunk, stderr_offset = read_new_text(stderr_path, stderr_offset)
        if chunk:
            buffer_tail = (buffer_tail + chunk)[-4096:]
            if ("[[[[ TIMEOUT ]]]]" in buffer_tail
                    or "[[[[ ACCEPT ]]]]" in buffer_tail
                    or "[[[[ REJECT ]]]]" in buffer_tail):
                if verdict_seen_at is None:
                    verdict_seen_at = time.monotonic()

        now = time.monotonic()
        if verdict_seen_at is not None and now - verdict_seen_at > post_verdict_grace:
            forced_after_verdict = True
            terminate_process(proc)
            break
        if now - started > timeout_seconds:
            terminate_process(proc)
            return 124, now - started, forced_after_verdict, "TIMEOUT"

    rc = proc.wait()
    reason = "POST_VERDICT_GRACE" if forced_after_verdict else ""
    return rc, time.monotonic() - started, forced_after_verdict, reason


def parse_log(stderr_text: str) -> tuple[str, dict[str, int], str]:
    timeout_marker = "[[[[ TIMEOUT ]]]]" in stderr_text
    accept_marker = "[[[[ ACCEPT ]]]]" in stderr_text
    reject_marker = "[[[[ REJECT ]]]]" in stderr_text
    if timeout_marker:
        verdict = "TIMEOUT"
    elif accept_marker:
        verdict = "ACCEPT"
    elif reject_marker:
        verdict = "REJECT"
    else:
        verdict = "ERROR"
    metrics: dict[str, int] = {}
    memory = ""
    for line in stderr_text.splitlines():
        match = METRIC_RE.match(line.strip())
        if match:
            metrics[match.group(1)] = int(match.group(2))
            continue
        match = MEM_RE.match(line.strip())
        if match:
            memory = match.group(1)
    return verdict, metrics, memory


def memory_to_mb(text: str) -> float | None:
    if not text:
        return None
    match = re.match(r"([0-9.]+)\s*([KMGT]?B)", text, re.I)
    if not match:
        return None
    value = float(match.group(1))
    unit = match.group(2).upper()
    factor = {"KB": 1 / 1024, "MB": 1, "GB": 1024, "TB": 1024 * 1024}.get(unit)
    return None if factor is None else value * factor


def safe_name(history: Path, root: Path) -> str:
    try:
        relative = history.relative_to(root.resolve())
        text = str(relative)
    except ValueError:
        text = history.name
    if text in ("", "."):
        text = history.name
    return re.sub(r"[^A-Za-z0-9._-]+", "__", text)


def median(values: list[float]) -> float | str:
    return statistics.median(values) if values else ""


def run_key(row: dict[str, object]) -> tuple[str, int, str, str]:
    return (
        str(Path(str(row["history"])).resolve()),
        int(row["repeat"]),
        str(row["mode"]),
        str(row["ww_pruning"]),
    )


def verdict_return_code(verdict: str) -> int | None:
    if verdict == "ACCEPT":
        return 0
    if verdict == "REJECT":
        return -1
    if verdict == "TIMEOUT":
        return 124
    return None


def load_completed_rows(path: Path, modes: list[str], ww_pruning: str) -> list[dict[str, object]]:
    if not path.is_file():
        return []
    with path.open(newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    return [
        row for row in rows
        if row.get("verdict") in ("ACCEPT", "REJECT")
        and row.get("mode") in modes
        and row.get("ww_pruning") == ww_pruning
    ]


def write_raw_results(path: Path, rows: list[dict[str, object]]) -> None:
    if not rows:
        return
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    os.replace(temporary, path)


def result_row(history: Path, label: str, repeat: int, mode: str, ww_pruning: str,
               return_code: int, wall_seconds: float | str, forced_exit: bool,
               verdict: str, metrics: dict[str, int], memory: str) -> dict[str, object]:
    row: dict[str, object] = {
        "history": str(history),
        "label": label,
        "repeat": repeat,
        "mode": mode,
        "ww_pruning": ww_pruning,
        "return_code": return_code,
        "verdict": verdict,
        "wall_seconds": round(wall_seconds, 6) if isinstance(wall_seconds, float) else wall_seconds,
        "max_memory": memory,
        "max_memory_mb": memory_to_mb(memory) or "",
        "forced_exit_after_verdict": int(forced_exit),
    }
    for metric in DEFAULT_METRICS:
        row[metric] = metrics.get(metric, "")
    return row


def main() -> int:
    args = parse_args()
    project = args.project_root.resolve()
    histories = discover_histories(args.history_root)
    if args.limit > 0:
        histories = histories[:args.limit]
    modes = args.modes or ["EAGER", "GMWR"]

    timestamp = time.strftime("%Y%m%d-%H%M%S")
    out_dir = (args.out_dir or project / f"gmwr-comparison-{timestamp}").resolve()
    logs_dir = out_dir / "logs"
    logs_dir.mkdir(parents=True, exist_ok=True)
    raw_path = out_dir / "gmwr_comparison_raw.csv"

    cp = classpath(project)
    env = os.environ.copy()
    native = str(project / "build" / "monosat")
    env["LD_LIBRARY_PATH"] = native + (os.pathsep + env["LD_LIBRARY_PATH"]
                                                 if env.get("LD_LIBRARY_PATH") else "")

    raw_rows = load_completed_rows(raw_path, modes, args.ww_pruning)
    completed = {run_key(row) for row in raw_rows}
    if raw_rows:
        print(f"Resuming from {raw_path}: {len(completed)} completed run(s).", flush=True)
    total_runs = len(histories) * len(modes) * args.repeats
    run_index = 0

    # Optional warmup: first history, alternating modes, not recorded.
    if not raw_rows:
        for warm in range(args.warmup):
            for mode in modes:
                dummy_out = logs_dir / f"warmup-{warm}-{mode}.stdout.log"
                dummy_err = logs_dir / f"warmup-{warm}-{mode}.stderr.log"
                cmd = [args.java, f"-Xmx{args.xmx}", *args.extra_jvm_arg,
                       "-cp", cp, "Main", "audit", "--ww-pruning", args.ww_pruning,
                       "--solver-stats", "--predicate-mode", mode,
                       str(histories[0])]
                run_one(cmd, env, dummy_out, dummy_err,
                        args.timeout_seconds, args.post_verdict_grace_seconds,
                        args.min_available_memory_mb)

    for history in histories:
        label = safe_name(history, args.history_root if args.history_root.is_dir()
                          else args.history_root.parent)
        for repeat in range(args.repeats):
            # Rotate mode order across repetitions so neither mode is
            # systematically first or last.
            shift = repeat % len(modes)
            ordered_modes = modes[shift:] + modes[:shift]
            for mode in ordered_modes:
                run_index += 1
                key = (str(history.resolve()), repeat, mode, args.ww_pruning)
                stem = f"{label}__r{repeat:02d}__{mode.lower()}"
                stdout_path = logs_dir / f"{stem}.stdout.log"
                stderr_path = logs_dir / f"{stem}.stderr.log"
                if key in completed:
                    print(f"[{run_index}/{total_runs}] {label} repeat={repeat} mode={mode} "
                          "SKIP (completed)", flush=True)
                    continue
                if stderr_path.is_file():
                    stderr_text = stderr_path.read_text(encoding="utf-8", errors="replace")
                    verdict, metrics, memory = parse_log(stderr_text)
                    recovered_rc = verdict_return_code(verdict)
                    if recovered_rc is not None and verdict in ("ACCEPT", "REJECT"):
                        row = result_row(history, label, repeat, mode, args.ww_pruning,
                                         recovered_rc, "", False, verdict, metrics, memory)
                        raw_rows.append(row)
                        completed.add(key)
                        write_raw_results(raw_path, raw_rows)
                        print(f"[{run_index}/{total_runs}] {label} repeat={repeat} mode={mode} "
                              "SKIP (recovered from log)", flush=True)
                        continue
                cmd = [args.java, f"-Xmx{args.xmx}", *args.extra_jvm_arg,
                       "-cp", cp, "Main", "audit", "--ww-pruning", args.ww_pruning,
                       "--solver-stats", "--predicate-mode", mode,
                       str(history)]
                print(f"[{run_index}/{total_runs}] {label} repeat={repeat} mode={mode}",
                      flush=True)
                rc, wall_s, forced_exit, termination_reason = run_one(
                    cmd, env, stdout_path, stderr_path,
                    args.timeout_seconds, args.post_verdict_grace_seconds,
                    args.min_available_memory_mb)
                stderr_text = stderr_path.read_text(encoding="utf-8", errors="replace")
                verdict, metrics, memory = parse_log(stderr_text)
                row = result_row(history, label, repeat, mode, args.ww_pruning,
                                 rc, wall_s, forced_exit, verdict, metrics, memory)
                raw_rows.append(row)
                if verdict in ("ACCEPT", "REJECT"):
                    completed.add(run_key(row))
                write_raw_results(raw_path, raw_rows)
                print(f"    verdict={verdict} profiler_ms={metrics.get('ENTIRE_EXPERIMENT', '')} "
                      f"wall={wall_s:.3f}s memory={memory or '?'} "
                      f"termination={termination_reason or '-'}", flush=True)

    write_raw_results(raw_path, raw_rows)

    summary_rows: list[dict[str, object]] = []
    by_history: dict[str, list[dict[str, object]]] = {}
    for row in raw_rows:
        by_history.setdefault(str(row["history"]), []).append(row)

    def metric_median(mode_rows: list[dict[str, object]], metric: str) -> float | str:
        values = [float(r[metric]) for r in mode_rows if r.get(metric, "") != ""]
        return median(values)

    def percent_faster(baseline: float | str, candidate: float | str) -> float | str:
        if isinstance(baseline, (int, float)) and isinstance(candidate, (int, float)) and baseline:
            return (baseline - candidate) / baseline * 100.0
        return ""

    for history, rows in by_history.items():
        eager = [r for r in rows if r["mode"] == "EAGER"]
        gmwr = [r for r in rows if r["mode"] == "GMWR"]

        e_ms = [float(r["ENTIRE_EXPERIMENT"]) for r in eager if r["ENTIRE_EXPERIMENT"] != ""]
        g_ms = [float(r["ENTIRE_EXPERIMENT"]) for r in gmwr if r["ENTIRE_EXPERIMENT"] != ""]
        e_med = median(e_ms)
        g_med = median(g_ms)

        e_mem = [float(r["max_memory_mb"]) for r in eager if r["max_memory_mb"] != ""]
        g_mem = [float(r["max_memory_mb"]) for r in gmwr if r["max_memory_mb"] != ""]

        mode_rows = {"EAGER": eager, "GMWR": gmwr}
        selected_present = [mode_rows[m] for m in modes if mode_rows[m]]
        verdict_sets = [{str(r["verdict"]) for r in rs} for rs in selected_present]
        verdict_match_all = (
            len(selected_present) == len(modes) and
            bool(verdict_sets) and
            all(v == verdict_sets[0] for v in verdict_sets[1:])
        )

        last_g = gmwr[-1] if gmwr else {}
        items = last_g.get("SER_GMWR_ITEM_OBLIGATIONS_COUNT", "")
        bundles = last_g.get("SER_GMWR_BUNDLES_COUNT", "")
        bundle_reduction = ""
        try:
            if float(items) > 0:
                bundle_reduction = (1.0 - float(bundles) / float(items)) * 100.0
        except (TypeError, ValueError):
            pass

        summary_rows.append({
            "history": history,
            "verdict_match_all": int(verdict_match_all),
            "eager_verdicts": ";".join(sorted({str(r["verdict"]) for r in eager})),
            "gmwr_verdicts": ";".join(sorted({str(r["verdict"]) for r in gmwr})),
            "eager_entire_ms_median": e_med,
            "gmwr_entire_ms_median": g_med,
            "gmwr_vs_eager_speedup_percent": percent_faster(e_med, g_med),
            "eager_encode_ms_median": metric_median(eager, "SER_AR_ENCODE"),
            "gmwr_encode_ms_median": metric_median(gmwr, "SER_AR_ENCODE"),
            "eager_monosat_ms_median": metric_median(eager, "SER_MONOSAT_SOLVE"),
            "gmwr_monosat_ms_median": metric_median(gmwr, "SER_MONOSAT_SOLVE"),
            "eager_oneshot_solve_ms_median": metric_median(eager, "ONESHOT_SOLVE"),
            "gmwr_oneshot_solve_ms_median": metric_median(gmwr, "ONESHOT_SOLVE"),
            "eager_memory_mb_median": median(e_mem),
            "gmwr_memory_mb_median": median(g_mem),
            "gmwr_build_ms_median": metric_median(gmwr, "SER_GMWR_BUILD"),
            "gmwr_resolution_ms_median": metric_median(gmwr, "SER_GMWR_RESOLUTION"),
            "gmwr_item_obligations": items,
            "gmwr_bundles": bundles,
            "gmwr_bundle_reduction_percent": bundle_reduction,
            "gmwr_retained_item_clauses": last_g.get("SER_GMWR_MATERIALIZED_ITEM_CLAUSES_COUNT", ""),
            "gmwr_resolved_bundles": last_g.get("SER_GMWR_RESOLVED_BUNDLES_COUNT", ""),
            "gmwr_residual_bundles": last_g.get("SER_GMWR_RESIDUAL_BUNDLES_COUNT", ""),
            "gmwr_residual_clauses": last_g.get("SER_GMWR_RESIDUAL_CLAUSES_COUNT", ""),
            "gmwr_residual_literals": last_g.get("SER_GMWR_RESIDUAL_LITERALS_COUNT", ""),
            "gmwr_forced_orders": last_g.get("SER_GMWR_FORCED_ORDERS_COUNT", ""),
        })

    summary_path = out_dir / "gmwr_comparison_summary.csv"
    with summary_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=list(summary_rows[0].keys()) if summary_rows else [])
        if summary_rows:
            writer.writeheader()
            writer.writerows(summary_rows)

    mismatches = [r for r in summary_rows if r["verdict_match_all"] == 0]
    print(f"\nRaw results:     {raw_path}")
    print(f"Summary results: {summary_path}")
    print(f"Logs:            {logs_dir}")
    if mismatches:
        print(f"WARNING: {len(mismatches)} history/histories have a verdict mismatch among selected modes.",
              file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
