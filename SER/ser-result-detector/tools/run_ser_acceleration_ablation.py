#!/usr/bin/env python3
"""Run the final two-switch SER acceleration ablation.

The causal chain is:
  NO_GMWR -> NO_PREPROP -> FULL

This attributes the first step to the combined GMWR/frontier mechanism and
the second step to GMWR prepropagation.  FULL versus NO_GMWR reports the
end-to-end effect.  WW reachability, predicate-witness coalescing, and graph
edge interning stay enabled in every configuration because production no
longer exposes them as switches.
"""
from __future__ import annotations

import argparse
import csv
from datetime import datetime
from decimal import Decimal
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import shutil
import signal
import statistics
import subprocess
import sys
import time


PROJECT = Path(__file__).resolve().parents[1]
WORKSPACE = PROJECT.parents[1]
CONFIGS = {
    "FULL": (True, True),
    "NO_PREPROP": (True, False),
    "NO_GMWR": (False, False),
}
COMPARISONS = {
    "gmwr_frontier": ("NO_GMWR", "NO_PREPROP"),
    "prepropagation": ("NO_PREPROP", "FULL"),
    "full_stack": ("NO_GMWR", "FULL"),
}
METRICS = [
    "ENTIRE_EXPERIMENT",
    "GMWR_BUILD_MS",
    "GMWR_REDUCTION_MS",
    "SER_AR_ENCODE",
    "SER_AR_ENCODE_SETUP",
    "SER_AR_ENCODE_PREDICATE",
    "SER_MONOSAT_SOLVE",
    "GMWR_INITIAL_CONSTRAINTS",
    "GMWR_RESIDUAL_CONSTRAINTS",
    "GMWR_REMOVED_CANDIDATES",
    "GMWR_FORCED_FACTS",
    "SER_GMWR_ITEM_OBLIGATIONS_COUNT",
    "SER_GMWR_MATERIALIZED_ITEM_CLAUSES_COUNT",
    "SER_GMWR_RESIDUAL_CLAUSES_COUNT",
    "SER_GMWR_RESIDUAL_LITERALS_COUNT",
    "SER_GMWR_INTERVAL_CANDIDATES_PRUNED_COUNT",
    "SER_PRED_FRONTIER_CANDIDATES_COUNT",
    "SER_PRED_LATEST_WRITER_INPUT_WRITES_COUNT",
    "SER_PRED_LATEST_WRITER_RESULTS_COUNT",
    "SER_PROP_RESIDUAL_SAT_VARIABLES_COUNT",
    "SER_PROP_RESIDUAL_SAT_CONSTRAINTS_COUNT",
    "SER_PROP_MONOSAT_GRAPH_EDGES_COUNT",
    "SER_PROP_MONOSAT_PROPAGATIONS_COUNT",
    "SER_PROP_MONOSAT_CONFLICTS_COUNT",
    "SER_PRECEDENCE_RELATIONS_COUNT",
]
TIMEOUTS = {"SOLVER_TIMEOUT", "PROCESS_TIMEOUT"}
METRIC_RE = re.compile(r"^([A-Z][A-Z0-9_]*):\s+([0-9]+)(?:ms)?\s*$")
MAX_MEMORY_RE = re.compile(r"^Max memory:\s+(.+?)\s*$")
MAX_RSS_RE = re.compile(r"Maximum resident set size \(kbytes\):\s*(\d+)")


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def dump_json(path, value):
    path = Path(path)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(value, indent=2, ensure_ascii=False, default=str) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def write_csv(path, rows, fields=None):
    path = Path(path)
    if fields is None:
        fields = list(dict.fromkeys(key for row in rows for key in row))
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)
    temporary.replace(path)


def select_histories(normal_root, percent_root, history_name):
    result = []
    for family, root in (("test-ser", Path(normal_root)),
                         ("test-ser%", Path(percent_root))):
        if not root.is_dir():
            raise ValueError(f"Missing history root: {root}")
        groups = sorted(
            path for path in root.iterdir()
            if path.is_dir() and re.match(r"^\d+_\d+_\d+_\d+_", path.name)
        )
        for group in groups:
            try:
                ratio = Decimal(group.name.split("_")[4])
            except Exception as exc:
                raise ValueError(f"Cannot parse directory ratio: {group}") from exc
            if family == "test-ser%" and ratio >= Decimal("0.10"):
                continue
            history = group / history_name
            manifest_path = history / "manifest.json"
            if not manifest_path.is_file():
                raise ValueError(
                    f"Required history missing; not silently skipping: {manifest_path}"
                )
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            file_name = manifest.get("files", {}).get("history")
            candidates = ([history / file_name] if file_name else [
                history / "history.prhist.jsonl.zst",
                history / "history.prhist.jsonl",
            ])
            input_file = next((path for path in candidates if path.is_file()), None)
            if input_file is None:
                raise ValueError(f"History data missing: {history}")
            operations = manifest.get("operations")
            predicates = manifest.get("predicate_reads")
            result.append({
                "case_id": f"{family}--{group.name}--{history_name}",
                "family": family,
                "dataset": group.name,
                "history": str(history.resolve()),
                "input_file": str(input_file.resolve()),
                "nominal_ratio": float(ratio),
                "actual_predicate_ratio": (
                    predicates / operations
                    if predicates is not None and operations else None
                ),
                "transactions": manifest.get("transactions"),
                "operations": operations,
                "predicate_reads": predicates,
                "initial_keys": manifest.get("initial_keys"),
                "writes": manifest.get("writes"),
                "input_sha256": sha256(input_file),
                "manifest_sha256": sha256(manifest_path),
            })
    if not result:
        raise ValueError("No matching histories")
    return result


def build_command(config, *, java, heap, native_dir, classpath, history,
                  solver_seconds):
    gmwr, prepropagation = CONFIGS[config]
    return [
        java,
        "-Xmx" + heap,
        "-Djava.library.path=" + str(native_dir),
        "-cp", classpath,
        "Main", "audit",
        "--gmwr" if gmwr else "--no-gmwr",
        ("--gmwr-prepropagation" if prepropagation
         else "--no-gmwr-prepropagation"),
        "--solver-stats",
        "--solver-timeout-seconds", str(solver_seconds),
        str(history),
    ]


def parse_stderr(text):
    if "SER audit result: ACCEPT" in text or "[[[[ ACCEPT ]]]]" in text:
        verdict = "ACCEPT"
    elif "SER audit result: REJECT" in text or "[[[[ REJECT ]]]]" in text:
        verdict = "REJECT"
    elif "SER audit result: TIMEOUT" in text or "[[[[ TIMEOUT ]]]]" in text:
        verdict = "TIMEOUT"
    elif "[[[[ INVALID_HISTORY ]]]]" in text:
        verdict = "INVALID_HISTORY"
    else:
        verdict = None
    metrics = {}
    java_memory = None
    for line in text.splitlines():
        metric = METRIC_RE.match(line.strip())
        if metric:
            metrics[metric.group(1)] = int(metric.group(2))
        memory = MAX_MEMORY_RE.match(line.strip())
        if memory:
            java_memory = memory.group(1)
    return verdict, metrics, java_memory


def parse_result(stderr, returncode, reason):
    verdict, metrics, java_memory = parse_stderr(stderr)
    if reason == "INTERRUPTED":
        status = "INTERRUPTED"
    elif reason == "MEMORY_GUARD":
        status = "RESOURCE_STOP"
    elif "OutOfMemoryError" in stderr or "Java heap space" in stderr:
        status = "JAVA_OOM"
    elif reason == "PROCESS_TIMEOUT":
        status = "PROCESS_TIMEOUT"
    elif verdict == "TIMEOUT":
        status = "SOLVER_TIMEOUT"
    elif verdict in ("ACCEPT", "REJECT"):
        status = "COMPLETE"
    elif verdict == "INVALID_HISTORY":
        status = "INVALID_HISTORY"
    else:
        status = "KILLED_UNKNOWN" if returncode < 0 else "ERROR"
    return {
        "status": status,
        "verdict": verdict,
        "returncode": returncode,
        "stop_reason": reason,
        "metrics": metrics,
        "java_peak_memory": java_memory,
    }


def available_memory_mb():
    try:
        for line in Path("/proc/meminfo").read_text(encoding="utf-8").splitlines():
            if line.startswith("MemAvailable:"):
                return int(line.split()[1]) / 1024
    except OSError:
        return None
    return None


def terminate_tree(process):
    if process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        process.wait(timeout=2)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait()


def parse_peak_rss(path):
    try:
        match = MAX_RSS_RE.search(Path(path).read_text(encoding="utf-8"))
    except OSError:
        match = None
    value = int(match.group(1)) if match else 0
    return value, value / 1024 if value else 0


def run_process(command, prefix, process_seconds, min_available_mib):
    prefix = Path(prefix)
    prefix.parent.mkdir(parents=True, exist_ok=True)
    stdout_path = prefix.with_suffix(".stdout")
    stderr_path = prefix.with_suffix(".stderr")
    resource_path = prefix.with_suffix(".resource.log")
    timed = ["/usr/bin/time", "-v", "-o", str(resource_path), *map(str, command)]
    start = time.monotonic()
    reason = ""
    with stdout_path.open("w", encoding="utf-8") as stdout, \
            stderr_path.open("w", encoding="utf-8") as stderr:
        process = subprocess.Popen(
            timed,
            stdout=stdout,
            stderr=stderr,
            text=True,
            start_new_session=True,
        )
        try:
            while process.poll() is None:
                elapsed = time.monotonic() - start
                if elapsed >= process_seconds:
                    reason = "PROCESS_TIMEOUT"
                    terminate_tree(process)
                    break
                available = available_memory_mb()
                if (min_available_mib > 0 and available is not None
                        and available < min_available_mib):
                    reason = "MEMORY_GUARD"
                    terminate_tree(process)
                    break
                time.sleep(min(0.2, max(0.01, process_seconds - elapsed)))
        except KeyboardInterrupt:
            reason = "INTERRUPTED"
            terminate_tree(process)
        returncode = process.wait()
    stderr_text = stderr_path.read_text(encoding="utf-8", errors="replace")
    row = parse_result(stderr_text, returncode, reason)
    peak_rss_kb, peak_rss_mb = parse_peak_rss(resource_path)
    row.update({
        "wall_seconds": round(time.monotonic() - start, 6),
        "peak_rss_kb": peak_rss_kb or None,
        "peak_rss_mb": peak_rss_mb or None,
        "stdout_log": str(stdout_path),
        "stderr_log": str(stderr_path),
        "resource_log": str(resource_path),
        "command": command,
    })
    return row


def latest_rows(rows):
    return {
        (row["case_id"], row["repeat"], row["config"]): row
        for row in rows
    }


def paired_rows(rows):
    indexed = latest_rows(rows)
    blocks = sorted({
        (row["case_id"], row["repeat"], row["family"])
        for row in indexed.values()
    })
    result = []
    for case_id, repeat, family in blocks:
        for comparison, (off_name, on_name) in COMPARISONS.items():
            off = indexed.get((case_id, repeat, off_name))
            on = indexed.get((case_id, repeat, on_name))
            row = {
                "case_id": case_id,
                "family": family,
                "repeat": repeat,
                "comparison": comparison,
                "off": off_name,
                "on": on_name,
                "off_status": off["status"] if off else None,
                "on_status": on["status"] if on else None,
                "outcome": "MISSING_PARTNER",
                "total_speedup": None,
                "solve_speedup": None,
                "memory_reduction": None,
            }
            for label, item in (("off", off), ("on", on)):
                row[label + "_total_ms"] = (
                    item["metrics"].get("ENTIRE_EXPERIMENT") if item else None
                )
                row[label + "_solve_ms"] = (
                    item["metrics"].get("SER_MONOSAT_SOLVE") if item else None
                )
                row[label + "_peak_rss_kb"] = (
                    item.get("peak_rss_kb") if item else None
                )
            if off and on:
                off_status, on_status = off["status"], on["status"]
                if off_status == on_status == "COMPLETE":
                    row["outcome"] = (
                        "COMPLETE_PAIR" if off["verdict"] == on["verdict"]
                        else "VERDICT_MISMATCH"
                    )
                    if row["outcome"] == "COMPLETE_PAIR":
                        for metric, output in (
                                ("ENTIRE_EXPERIMENT", "total_speedup"),
                                ("SER_MONOSAT_SOLVE", "solve_speedup")):
                            before = off["metrics"].get(metric)
                            after = on["metrics"].get(metric)
                            if before is not None and after is not None and after > 0:
                                row[output] = before / after
                        before = off.get("peak_rss_kb")
                        after = on.get("peak_rss_kb")
                        if before and after:
                            row["memory_reduction"] = (before - after) / before
                elif "RESOURCE_STOP" in (off_status, on_status):
                    row["outcome"] = "RESOURCE_CENSORED"
                elif off_status == "JAVA_OOM" and on_status == "COMPLETE":
                    row["outcome"] = "OOM_RESCUE"
                elif on_status == "JAVA_OOM" and off_status == "COMPLETE":
                    row["outcome"] = "OOM_REGRESSION"
                elif off_status in TIMEOUTS and on_status == "COMPLETE":
                    row["outcome"] = "TIMEOUT_RESCUE"
                elif on_status in TIMEOUTS and off_status == "COMPLETE":
                    row["outcome"] = "TIMEOUT_REGRESSION"
                else:
                    row["outcome"] = "CENSORED_PAIR"
            result.append(row)
    return result


def median(values):
    selected = [value for value in values if value is not None]
    return statistics.median(selected) if selected else None


def summarize_pairs(pairs, repeats):
    result = []
    for family in ("ALL", "test-ser", "test-ser%"):
        for comparison in COMPARISONS:
            selected = [
                pair for pair in pairs
                if pair["comparison"] == comparison
                and (family == "ALL" or pair["family"] == family)
            ]
            cases = sorted({pair["case_id"] for pair in selected})
            histories = []
            for case_id in cases:
                case_pairs = [pair for pair in selected if pair["case_id"] == case_id]
                if (len(case_pairs) == repeats
                        and all(pair["outcome"] == "COMPLETE_PAIR"
                                for pair in case_pairs)):
                    histories.append({
                        "ratio": median(pair["total_speedup"] for pair in case_pairs),
                        "memory": median(
                            pair["memory_reduction"] for pair in case_pairs
                        ),
                        "off_time": median(
                            pair["off_total_ms"] for pair in case_pairs
                        ),
                    })
            ratios = [
                history["ratio"] for history in histories
                if history["ratio"] is not None and history["ratio"] > 0
            ]
            ordered = sorted(
                (history for history in histories if history["off_time"] is not None),
                key=lambda history: history["off_time"],
                reverse=True,
            )
            tail = ordered[:max(1, math.ceil(len(ordered) * 0.1))] if ordered else []
            result.append({
                "family": family,
                "comparison": comparison,
                "histories_seen": len(cases),
                "complete_all_repeat_histories": len(histories),
                "paired_runtime_median": median(ratios),
                "paired_runtime_geomean": (
                    math.exp(sum(map(math.log, ratios)) / len(ratios))
                    if ratios else None
                ),
                "paired_memory_reduction_median": median(
                    history["memory"] for history in histories
                ),
                "hard_tail_histories": len(tail),
                "hard_tail_runtime_median": median(
                    history["ratio"] for history in tail
                ),
                "stable_oom_rescued": sum(
                    len(case_pairs := [pair for pair in selected
                                      if pair["case_id"] == case_id]) == repeats
                    and all(pair["outcome"] == "OOM_RESCUE" for pair in case_pairs)
                    for case_id in cases
                ),
                "stable_timeout_rescued": sum(
                    len(case_pairs := [pair for pair in selected
                                      if pair["case_id"] == case_id]) == repeats
                    and all(pair["outcome"] == "TIMEOUT_RESCUE" for pair in case_pairs)
                    for case_id in cases
                ),
                "resource_censored_pairs": sum(
                    pair["outcome"] == "RESOURCE_CENSORED" for pair in selected
                ),
                "missing_pairs": sum(
                    pair["outcome"] == "MISSING_PARTNER" for pair in selected
                ),
            })
    return result


def summarize(output, rows, repeats):
    current = list(latest_rows(rows).values())
    flat = []
    for row in current:
        entry = {key: value for key, value in row.items() if key != "metrics"}
        entry.update({metric: row["metrics"].get(metric) for metric in METRICS})
        entry["all_metrics_json"] = json.dumps(row["metrics"], sort_keys=True)
        entry["command"] = " ".join(map(str, row.get("command", [])))
        flat.append(entry)
    write_csv(output / "raw.csv", flat)
    pairs = paired_rows(rows)
    write_csv(output / "paired.csv", pairs)
    write_csv(output / "summary.csv", summarize_pairs(pairs, repeats))
    groups = []
    for case_id, config in sorted({
            (row["case_id"], row["config"]) for row in current}):
        group = [
            row for row in current
            if row["case_id"] == case_id and row["config"] == config
        ]
        complete = [row for row in group if row["status"] == "COMPLETE"]
        entry = {
            "case_id": case_id,
            "family": group[0]["family"],
            "config": config,
            "runs": len(group),
            "complete_runs": len(complete),
            "statuses": json.dumps([row["status"] for row in group]),
            "peak_rss_kb_median": median(
                row.get("peak_rss_kb") for row in complete
            ),
        }
        for metric in METRICS:
            entry[metric] = median(
                row["metrics"].get(metric) for row in complete
            )
        groups.append(entry)
    write_csv(output / "by_history.csv", groups)


def _git_capture(*args):
    completed = subprocess.run(
        ["git", *args], cwd=WORKSPACE, capture_output=True, text=True
    )
    return completed.stdout if completed.returncode == 0 else completed.stderr


def create_runtime(output):
    completed = subprocess.run(
        ["./gradlew", "installDist"], cwd=PROJECT, text=True
    )
    if completed.returncode:
        raise RuntimeError("./gradlew installDist failed")
    runtime = output / "runtime"
    classes = runtime / "classes"
    libraries = runtime / "lib"
    native = runtime / "native"
    shutil.copytree(PROJECT / "build/classes/java/main", classes)
    libraries.mkdir(parents=True)
    for jar in sorted((PROJECT / "build/install/ser-result-detector/lib").glob("*.jar")):
        if not jar.name.startswith("ser-result-detector-"):
            shutil.copy2(jar, libraries / jar.name)
    native.mkdir(parents=True)
    native_source = PROJECT / "build/monosat/libmonosat.so"
    shutil.copy2(native_source, native / native_source.name)
    classpath = os.pathsep.join([
        str(classes),
        *(str(path) for path in sorted(libraries.glob("*.jar"))),
    ])
    metadata = {
        "created_at": datetime.now().astimezone().isoformat(),
        "runtime_classpath": classpath,
        "native_dir": str(native),
        "native_sha256": sha256(native / native_source.name),
        "class_files": len(list(classes.rglob("*.class"))),
    }
    dump_json(runtime / "runtime.json", metadata)
    return metadata


def build_plan(cases, configs, repeats):
    return {
        "sample_count": len(cases),
        "test_ser_count": sum(case["family"] == "test-ser" for case in cases),
        "test_ser_percent_count": sum(
            case["family"] == "test-ser%" for case in cases
        ),
        "history_name": Path(cases[0]["history"]).name,
        "configs": configs,
        "comparisons": COMPARISONS,
        "repeats": repeats,
        "planned_runs": len(cases) * len(configs) * repeats,
        "cases": cases,
    }


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    histories = WORKSPACE / "predicateHistories/kvpredicate"
    parser.add_argument("--normal-root", type=Path, default=histories / "test-ser")
    parser.add_argument("--percent-root", type=Path, default=histories / "test-ser%")
    parser.add_argument("--history-name", default="hist-00000")
    parser.add_argument("--out-dir", type=Path)
    parser.add_argument("--repeats", type=int, default=2)
    parser.add_argument(
        "--configs", nargs="+", choices=CONFIGS, default=list(CONFIGS)
    )
    parser.add_argument("--heap", default="3g")
    parser.add_argument("--java", default="java")
    parser.add_argument("--solver-timeout-seconds", type=int, default=90)
    parser.add_argument("--process-timeout-seconds", type=float, default=240)
    parser.add_argument("--min-available-memory-mb", type=float, default=1536)
    parser.add_argument("--plan-only", action="store_true")
    parser.add_argument("--resume", action="store_true")
    return parser.parse_args(argv)


def validate_args(args):
    if args.repeats < 1:
        raise ValueError("--repeats must be at least 1")
    if args.process_timeout_seconds <= 0 or args.solver_timeout_seconds <= 0:
        raise ValueError("timeouts must be positive")
    if len(set(args.configs)) != len(args.configs):
        raise ValueError("--configs must not contain duplicates")


def main(argv=None):
    args = parse_args(argv)
    validate_args(args)
    cases = select_histories(args.normal_root, args.percent_root, args.history_name)
    plan = build_plan(cases, args.configs, args.repeats)
    if args.plan_only:
        print(json.dumps(plan, indent=2, ensure_ascii=False))
        return 0

    output = args.out_dir or PROJECT / "results" / (
        "ser-acceleration-ablation-" + datetime.now().strftime("%Y%m%d-%H%M%S")
    )
    output = output.resolve()
    if output.exists() and not args.resume:
        raise ValueError(f"Output already exists; use --resume: {output}")
    if args.resume:
        metadata_path = output / "runtime/runtime.json"
        if not metadata_path.is_file():
            raise ValueError(f"Cannot resume without runtime metadata: {output}")
        runtime = json.loads(metadata_path.read_text(encoding="utf-8"))
    else:
        output.mkdir(parents=True)
        dump_json(output / "plan.json", plan)
        (output / "worktree.diff").write_text(
            _git_capture("diff", "--binary")
            + "\n# staged changes\n"
            + _git_capture("diff", "--cached", "--binary"),
            encoding="utf-8",
        )
        shutil.copy2(Path(__file__), output / Path(__file__).name)
        runtime = create_runtime(output)
        dump_json(output / "environment.json", {
            "created_at": datetime.now().astimezone().isoformat(),
            "git_head": _git_capture("rev-parse", "HEAD").strip(),
            "git_status": _git_capture("status", "--short"),
            "platform": platform.platform(),
            "python": sys.version,
            "java": subprocess.run(
                [args.java, "-version"], capture_output=True, text=True
            ).stderr,
            "arguments": vars(args),
        })

    raw_path = output / "raw.json"
    rows = json.loads(raw_path.read_text(encoding="utf-8")) \
        if raw_path.is_file() else []
    finished = {
        (row["case_id"], row["repeat"], row["config"])
        for row in rows if row["status"] != "INTERRUPTED"
    }
    interrupted = False
    for repeat in range(args.repeats):
        base_order = args.configs if repeat % 2 == 0 \
            else list(reversed(args.configs))
        for case_index, case in enumerate(cases):
            shift = case_index % len(base_order)
            order = base_order[shift:] + base_order[:shift]
            if sha256(case["input_file"]) != case["input_sha256"]:
                raise ValueError("Input changed during experiment: " + case["input_file"])
            for config in order:
                key = (case["case_id"], repeat, config)
                if key in finished:
                    continue
                safe_case = re.sub(r"[^A-Za-z0-9._-]+", "__", case["case_id"])
                prefix = output / "logs" / safe_case / f"r{repeat}-{config}"
                command = build_command(
                    config,
                    java=args.java,
                    heap=args.heap,
                    native_dir=runtime["native_dir"],
                    classpath=runtime["runtime_classpath"],
                    history=case["history"],
                    solver_seconds=args.solver_timeout_seconds,
                )
                result = run_process(
                    command,
                    prefix,
                    args.process_timeout_seconds,
                    args.min_available_memory_mb,
                )
                result.update(case)
                result.update({
                    "repeat": repeat,
                    "config": config,
                    "attempt": sum(
                        row["case_id"] == case["case_id"]
                        and row["repeat"] == repeat
                        and row["config"] == config
                        for row in rows
                    ) + 1,
                })
                rows.append(result)
                dump_json(raw_path, rows)
                summarize(output, rows, args.repeats)
                print(
                    f"{case['case_id']} repeat={repeat} config={config} "
                    f"status={result['status']} wall={result['wall_seconds']:.3f}s",
                    flush=True,
                )
                if result["status"] == "INTERRUPTED":
                    interrupted = True
                    break
            if interrupted:
                break
        if interrupted:
            break
    summarize(output, rows, args.repeats)
    return 130 if interrupted else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, RuntimeError) as exception:
        print("error:", exception, file=sys.stderr)
        raise SystemExit(2)
