#!/usr/bin/env python3
"""
Run a reproducible PRHIST catalog experiment.

The runner consumes generated suite catalog.json files, executes every case
with fixed JVM/SI solver parameters, preserves raw logs, and writes parsed
JSON/CSV outputs suitable for paper tables.
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import hashlib
import json
import os
import pathlib
import platform
import re
import shlex
import socket
import subprocess
import sys
import time
from typing import Any, Dict, List, Optional


ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_JAR = ROOT / "build" / "libs" / "si-result-detector-1.0.0-SNAPSHOT.jar"
DEFAULT_MONOSAT_NATIVE_DIR = ROOT / "build" / "monosat"
VERDICT_RE = re.compile(r"SI audit result:\s*(ACCEPT|REJECT|ERROR)")
TIMER_RE = re.compile(r"^([A-Z0-9_]+):\s+([0-9]+)ms$", re.MULTILINE)
COUNTER_RE = re.compile(r"^([A-Z][A-Z0-9_]+):\s+([0-9]+)\s*$", re.MULTILINE)
COUNT_RES = {
    "transactions_count": re.compile(r"Transactions:\s*([0-9,]+)"),
    "events_count": re.compile(r"Events:\s*([0-9,]+)"),
    "ww_initial_choices": re.compile(r"WW_INITIAL_CHOICES:\s*([0-9]+)"),
    "ww_after_reachability": re.compile(r"WW_AFTER_REACHABILITY:\s*([0-9]+)"),
    "gmwr_initial_constraints": re.compile(r"GMWR_INITIAL_CONSTRAINTS:\s*([0-9]+)"),
    "gmwr_residual_constraints": re.compile(r"GMWR_RESIDUAL_CONSTRAINTS:\s*([0-9]+)"),
    "gmwr_forced_facts": re.compile(r"GMWR_FORCED_FACTS:\s*([0-9]+)"),
    "gmwr_removed_candidates": re.compile(r"GMWR_REMOVED_CANDIDATES:\s*([0-9]+)"),
    "si_oracle_builds": re.compile(r"SI_ORACLE_BUILDS:\s*([0-9]+)"),
    "si_oracle_updates": re.compile(r"SI_ORACLE_UPDATES:\s*([0-9]+)"),
}
MAX_MEMORY_RE = re.compile(r"(?:Peak|Max) memory:\s*(.+)")


def load_json(path: pathlib.Path) -> Any:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def dump_json(path: pathlib.Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def command_output(args: List[str], cwd: pathlib.Path = ROOT) -> Optional[str]:
    try:
        completed = subprocess.run(args, cwd=cwd, text=True, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, check=False, timeout=20)
    except Exception:
        return None
    return completed.stdout.strip()


def file_sha256(path: pathlib.Path) -> Optional[str]:
    if not path.is_file():
        return None
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def machine_info(java_bin: str, jar: pathlib.Path) -> Dict[str, Any]:
    info: Dict[str, Any] = {
        "captured_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "hostname": socket.gethostname(),
        "platform": platform.platform(),
        "system": platform.system(),
        "release": platform.release(),
        "machine": platform.machine(),
        "processor": platform.processor(),
        "python": sys.version.replace("\n", " "),
        "cpu_count": os.cpu_count(),
        "java_version": command_output([java_bin, "-version"]),
        "git_commit": command_output(["git", "rev-parse", "HEAD"]),
        "git_status_short": command_output(["git", "status", "--short"]),
        "jar": str(jar),
        "jar_sha256": file_sha256(jar),
    }
    lscpu = command_output(["lscpu"])
    if lscpu:
        info["lscpu"] = lscpu
    meminfo = pathlib.Path("/proc/meminfo")
    if meminfo.is_file():
        info["meminfo_first_lines"] = "\n".join(meminfo.read_text(encoding="utf-8").splitlines()[:8])
    return info


def normalize_catalog(catalog_path: pathlib.Path) -> List[Dict[str, Any]]:
    catalog = load_json(catalog_path)
    if isinstance(catalog, dict):
        raw_cases = catalog.get("cases")
        suite_name = catalog.get("suite") or catalog.get("name") or catalog_path.parent.name
    elif isinstance(catalog, list):
        raw_cases = catalog
        suite_name = catalog_path.parent.name
    else:
        raise ValueError(f"{catalog_path}: catalog must be a JSON array or object with cases")
    if not isinstance(raw_cases, list):
        raise ValueError(f"{catalog_path}: catalog cases must be a JSON array")

    cases: List[Dict[str, Any]] = []
    for index, raw in enumerate(raw_cases):
        if not isinstance(raw, dict):
            raise ValueError(f"{catalog_path}: case {index} is not an object")
        case = dict(raw)
        name = case.get("case") or case.get("dataset_name") or case.get("case_kind") or f"case-{index:05d}"
        expected = case.get("expected_verdict") or case.get("expected")
        manifest_expected = load_manifest_expected(catalog_path.parent, case)
        if expected is None:
            expected = manifest_expected
        if expected not in ("ACCEPT", "REJECT"):
            raise ValueError(f"{catalog_path}: case {name} missing expected ACCEPT/REJECT verdict")
        hist_dir = resolve_case_path(catalog_path.parent, case, str(name))
        cases.append({
            "suite": suite_name,
            "case_index": index,
            "case": str(name),
            "expected_verdict": expected,
            "manifest_expected_verdict": manifest_expected,
            "hist_dir": hist_dir,
            "catalog_entry": case,
        })
    return cases


def load_manifest_expected(catalog_root: pathlib.Path, case: Dict[str, Any]) -> Optional[str]:
    try:
        hist_dir = resolve_case_path(catalog_root, case, str(case.get("dataset_name") or case.get("case") or ""))
        manifest = load_json(hist_dir / "manifest.json")
    except Exception:
        return None
    if isinstance(manifest, dict):
        return manifest.get("expected_verdict")
    return None


def resolve_case_path(catalog_root: pathlib.Path, case: Dict[str, Any], name: str) -> pathlib.Path:
    raw_path = case.get("path")
    if isinstance(raw_path, str) and raw_path:
        path = pathlib.Path(raw_path)
        return path if path.is_absolute() else (catalog_root / path).resolve()
    return (catalog_root / name / "hist-00000").resolve()


def safe_name(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", value).strip("_") or "case"


def parse_metrics(log_text: str) -> Dict[str, Any]:
    metrics: Dict[str, Any] = {}
    lines = log_text.rstrip().splitlines()
    verdict_match = VERDICT_RE.fullmatch(lines[-1].strip()) if lines else None
    if verdict_match:
        metrics["actual_verdict"] = verdict_match.group(1)
    for key, regex in COUNT_RES.items():
        match = regex.search(log_text)
        if match:
            metrics[key] = int(match.group(1).replace(",", ""))
    for name, millis in TIMER_RE.findall(log_text):
        metrics[f"time_{name.lower()}_ms"] = int(millis)
    for name, count in COUNTER_RE.findall(log_text):
        metrics[name.lower()] = int(count)
    summary = re.search(r"PRUNING_COMPARISON_STATS (.+)", log_text)
    if summary:
        fields = {name: int(value) for name, value in re.findall(r"([a-z_]+)=([0-9]+)", summary.group(1))}
        for source, target in {
            "ww_original": "ww_initial_choices", "ww_residual": "ww_after_reachability",
            "gmwr_obligations_original": "gmwr_initial_constraints",
            "gmwr_obligations_residual": "gmwr_residual_constraints",
            "ww_time_ms": "time_ww_reachability_prune_ms",
        }.items():
            if source in fields:
                metrics.setdefault(target, fields[source])
    ww = re.search(r"^WW\n([0-9,]+) -> ([0-9,]+)", log_text, re.MULTILINE)
    if ww:
        metrics.setdefault("ww_initial_choices", int(ww.group(1).replace(",", "")))
        metrics.setdefault("ww_after_reachability", int(ww.group(2).replace(",", "")))
    memory_match = MAX_MEMORY_RE.search(log_text)
    if memory_match:
        metrics["max_memory"] = memory_match.group(1).strip()
    return metrics


def run_case(case: Dict[str, Any], args: argparse.Namespace, output_root: pathlib.Path,
             logs_dir: pathlib.Path) -> Dict[str, Any]:
    hist_dir = pathlib.Path(case["hist_dir"])
    log_name = f"{case['case_index']:04d}_{safe_name(case['case'])}.log"
    log_path = logs_dir / log_name
    cmd = [
        args.java,
        *args.jvm_opt,
        f"-Djava.library.path={args.monosat_native_dir}",
        f"-Xmx{args.heap}",
        f"-Xss{args.stack}",
        "-jar",
        str(args.jar),
        "audit",
        "--gmwr" if args.gmwr else "--no-gmwr",
    ]
    if args.no_gmwr_prepropagation:
        cmd.append("--no-gmwr-prepropagation")
    if args.solver_stats:
        cmd.append("--solver-stats")
    cmd.append(str(hist_dir))

    started = dt.datetime.now(dt.timezone.utc)
    start = time.monotonic()
    timed_out = False
    try:
        completed = subprocess.run(cmd, cwd=ROOT, text=True, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, timeout=args.timeout_seconds,
                                   check=False)
        exit_code = completed.returncode
        log_text = completed.stdout
    except subprocess.TimeoutExpired as exc:
        timed_out = True
        exit_code = None
        stdout = exc.stdout or ""
        stderr = exc.stderr or ""
        if isinstance(stdout, bytes):
            stdout = stdout.decode("utf-8", errors="replace")
        if isinstance(stderr, bytes):
            stderr = stderr.decode("utf-8", errors="replace")
        log_text = stdout + stderr + f"\n[[RUNNER TIMEOUT after {args.timeout_seconds}s]]\n"

    elapsed_ms = int((time.monotonic() - start) * 1000)
    log_path.write_text(log_text, encoding="utf-8", errors="replace")
    metrics = parse_metrics(log_text)
    reported = metrics.pop("actual_verdict", None)
    expected_exit = {"ACCEPT": 0, "REJECT": 255, "ERROR": 1}
    if timed_out:
        actual = "PROCESS_TIMEOUT"
    elif reported is not None and exit_code == expected_exit[reported]:
        actual = reported
    else:
        actual = "RUNTIME_ERROR"
    expected = case["expected_verdict"]
    matched = actual == expected
    return {
        "suite": case["suite"],
        "case_index": case["case_index"],
        "case": case["case"],
        "hist_dir": str(hist_dir),
        "expected_verdict": expected,
        "manifest_expected_verdict": case.get("manifest_expected_verdict"),
        "actual_verdict": actual,
        "matched_expected": matched,
        "timed_out": timed_out,
        "exit_code": exit_code,
        "elapsed_wall_ms": elapsed_ms,
        "started_at": started.isoformat(),
        "raw_log": str(log_path.relative_to(output_root)),
        "command": shlex.join(cmd),
        **metrics,
        "catalog_entry": case["catalog_entry"],
    }


def write_results(output_root: pathlib.Path, results: List[Dict[str, Any]], config: Dict[str, Any]) -> None:
    with (output_root / "results.jsonl").open("w", encoding="utf-8") as f:
        for result in results:
            f.write(json.dumps(result, ensure_ascii=False, sort_keys=True) + "\n")

    metric_keys = sorted({
        key
        for result in results
        for key in result.keys()
        if key.startswith("time_") or key.endswith("_count")
        or key in COUNT_RES or key in ("max_memory",)
    })
    fieldnames = [
        "suite", "case_index", "case", "expected_verdict", "manifest_expected_verdict", "actual_verdict",
        "matched_expected", "timed_out", "exit_code", "elapsed_wall_ms",
        *metric_keys, "hist_dir", "raw_log", "command",
    ]
    with (output_root / "results.csv").open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(results)

    paper_fields = [
        "suite", "case", "expected_verdict", "manifest_expected_verdict", "actual_verdict", "matched_expected",
        "transactions_count", "events_count", "ww_initial_choices", "ww_after_reachability",
        "gmwr_initial_constraints", "gmwr_residual_constraints", "gmwr_forced_facts",
        "si_oracle_builds", "si_oracle_updates",
        "time_entire_experiment_ms", "time_oneshot_cons_ms",
        "time_ww_reachability_prune_ms", "time_oneshot_solve_ms",
        "elapsed_wall_ms", "max_memory",
    ]
    with (output_root / "paper_table.csv").open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=paper_fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(results)

    summary = summarize(results, config)
    dump_json(output_root / "summary.json", summary)


def summarize(results: List[Dict[str, Any]], config: Dict[str, Any]) -> Dict[str, Any]:
    total = len(results)
    matched = sum(1 for r in results if r["matched_expected"])
    timed_out = sum(1 for r in results if r["timed_out"])
    by_verdict: Dict[str, int] = {}
    for result in results:
        by_verdict[result["actual_verdict"]] = by_verdict.get(result["actual_verdict"], 0) + 1
    return {
        "total_cases": total,
        "matched_expected": matched,
        "mismatched_or_failed": total - matched,
        "timed_out": timed_out,
        "actual_verdict_counts": by_verdict,
        "all_matched": matched == total,
        "config": config,
    }


def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run all cases in a PRHIST catalog and emit reproducible JSON/CSV experiment results.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    parser.add_argument("catalog", type=pathlib.Path, help="Path to catalog.json")
    parser.add_argument("--output-dir", type=pathlib.Path, default=ROOT / "experiment-results",
                        help="Root directory for experiment outputs")
    parser.add_argument("--run-id", default=None, help="Stable run id; default uses UTC timestamp")
    parser.add_argument("--jar", type=pathlib.Path, default=DEFAULT_JAR, help="Detector jar path")
    parser.add_argument("--build-jar", action="store_true", help="Run ./gradlew jar before the experiment")
    parser.add_argument("--java", default="java", help="Java executable")
    parser.add_argument("--heap", default="8g", help="JVM -Xmx value")
    parser.add_argument("--stack", default="256m", help="JVM -Xss value")
    parser.add_argument("--jvm-opt", action="append", default=[],
                        help="Extra JVM option; repeat for multiple options")
    parser.add_argument("--monosat-native-dir", type=pathlib.Path, default=DEFAULT_MONOSAT_NATIVE_DIR,
                        help="Directory containing MonoSAT native library, e.g. libmonosat.so")
    parser.add_argument("--timeout-seconds", type=int, default=2100,
                        help="Wall-clock timeout per case enforced by this runner")
    parser.add_argument("--solver-stats", action="store_true", help="Print and parse solver stats when supported")
    gmwr = parser.add_mutually_exclusive_group()
    gmwr.add_argument("--gmwr", dest="gmwr", action="store_true")
    gmwr.add_argument("--no-gmwr", dest="gmwr", action="store_false")
    parser.set_defaults(gmwr=True)
    parser.add_argument("--no-gmwr-prepropagation", action="store_true",
                        help="Disable SI vis/repair prepropagation")
    parser.add_argument("--limit", type=int, default=None, help="Run only the first N catalog cases")
    parser.add_argument("--fail-fast", action="store_true", help="Stop after first mismatch, runtime error, or timeout")
    return parser.parse_args(argv)


def main(argv: Optional[List[str]] = None) -> int:
    args = parse_args(argv)
    catalog = args.catalog.resolve()
    if args.build_jar:
        subprocess.run(["./gradlew", "jar"], cwd=ROOT, check=True)
    args.jar = args.jar.resolve()
    args.monosat_native_dir = args.monosat_native_dir.resolve()
    if not args.jar.is_file():
        print(f"detector jar not found: {args.jar}", file=sys.stderr)
        print("Run ./gradlew jar or pass --build-jar.", file=sys.stderr)
        return 2
    native_library_names = ("libmonosat.so", "libmonosat.dylib", "monosat.dll")
    if not any((args.monosat_native_dir / name).is_file() for name in native_library_names):
        print(f"MonoSAT native library not found in: {args.monosat_native_dir}", file=sys.stderr)
        print("Run ./gradlew jar or pass --build-jar, or pass --monosat-native-dir.", file=sys.stderr)
        return 2
    if not catalog.is_file():
        print(f"catalog not found: {catalog}", file=sys.stderr)
        return 2

    run_id = args.run_id or dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    output_root = (args.output_dir / run_id).resolve()
    logs_dir = output_root / "logs"
    logs_dir.mkdir(parents=True, exist_ok=True)

    cases = normalize_catalog(catalog)
    if args.limit is not None:
        cases = cases[:args.limit]
    config = {
        "catalog": str(catalog),
        "output_root": str(output_root),
        "case_count": len(cases),
        "java": args.java,
        "jar": str(args.jar),
        "monosat_native_dir": str(args.monosat_native_dir),
        "heap": args.heap,
        "stack": args.stack,
        "jvm_opt": args.jvm_opt,
        "runner_timeout_seconds": args.timeout_seconds,
        "solver_stats": args.solver_stats,
        "predicate_encoding": "GMWR" if args.gmwr else "EAGER",
        "gmwr_prepropagation": args.gmwr and not args.no_gmwr_prepropagation,
    }
    dump_json(output_root / "config.json", config)
    dump_json(output_root / "machine.json", machine_info(args.java, args.jar))

    results: List[Dict[str, Any]] = []
    for case in cases:
        print(f"[{case['case_index'] + 1}/{len(cases)}] {case['case']} expected={case['expected_verdict']}",
              flush=True)
        result = run_case(case, args, output_root, logs_dir)
        results.append(result)
        print(f"  -> {result['actual_verdict']} wall={result['elapsed_wall_ms']}ms "
              f"match={result['matched_expected']} log={result['raw_log']}", flush=True)
        write_results(output_root, results, config)
        if args.fail_fast and not result["matched_expected"]:
            break

    write_results(output_root, results, config)
    summary = summarize(results, config)
    print(f"wrote {output_root}")
    print(f"summary: total={summary['total_cases']} matched={summary['matched_expected']} "
          f"failed={summary['mismatched_or_failed']} timed_out={summary['timed_out']}")
    return 0 if summary["all_matched"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
