#!/usr/bin/env python3
"""Measure WW then GMWR pruning once per history and render two SVG charts."""

from __future__ import annotations

import argparse
import csv
import json
import os
import re
import selectors
import signal
import statistics
import subprocess
import time
from collections import defaultdict
from pathlib import Path
from xml.sax.saxutils import escape


HISTORY_FILES = ("history.prhist.jsonl.zst", "history.prhist.jsonl")
DEFAULT_HISTORIES = Path(
    "/home/lc/Desktop/predicate/predicateHistories/kvpredicate/test-ser%")
STATS_RE = re.compile(
    r"^PRUNING_COMPARISON_STATS "
    r"ww_original=(\d+) ww_residual=(\d+) ww_reduced=(\d+) ww_time_ms=(\d+) "
    r"gmwr_original=(\d+) gmwr_residual=(\d+) gmwr_reduced=(\d+) gmwr_time_ms=(\d+) "
    r"gmwr_obligations_original=(\d+) gmwr_obligations_residual=(\d+)$")
RAW_FIELDS = (
    "history", "dataset", "predicate_ratio",
    "ww_original", "ww_residual", "ww_reduced", "ww_reduction_percent",
    "ww_time_ms", "gmwr_original", "gmwr_residual", "gmwr_reduced",
    "gmwr_reduction_percent", "gmwr_time_ms", "gmwr_obligations_original",
    "gmwr_obligations_residual", "gmwr_obligations_reduced",
)
SUMMARY_FIELDS = ("predicate_ratio", "histories", *RAW_FIELDS[3:])


def arguments() -> argparse.Namespace:
    project = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(
        description="Compare sequential WW/GMWR pruning without SAT solving")
    parser.add_argument("history_root", nargs="?", type=Path,
                        default=DEFAULT_HISTORIES)
    parser.add_argument("--project-root", type=Path, default=project)
    parser.add_argument("--out-dir", type=Path)
    parser.add_argument("--java", default="java")
    parser.add_argument("--xmx", default="8g")
    parser.add_argument("--timeout-seconds", type=float, default=180.0)
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--predicate-ratio-below", type=float, metavar="RATIO",
                        help="only run histories with predicate ratio below RATIO")
    return parser.parse_args()


def discover_histories(root: Path) -> list[Path]:
    root = root.resolve()
    if root.is_file():
        if root.name not in HISTORY_FILES:
            raise SystemExit(f"Expected {' or '.join(HISTORY_FILES)}, got {root}")
        return [root.parent]
    if not root.is_dir():
        raise SystemExit(f"History root does not exist: {root}")
    if any((root / name).is_file() for name in HISTORY_FILES):
        return [root]
    histories = sorted({
        path.parent.resolve()
        for name in HISTORY_FILES
        for path in root.rglob(name)
    })
    if not histories:
        raise SystemExit(f"No {' or '.join(HISTORY_FILES)} under {root}")
    return histories


def build_classpath(project: Path) -> str:
    entries: list[str] = []
    classes = project / "build" / "classes" / "java" / "main"
    if classes.is_dir():
        entries.append(str(classes))
    installed = project / "build" / "install" / "ser-result-detector" / "lib"
    if not installed.is_dir():
        raise SystemExit(f"Missing {installed}; run './gradlew installDist' first")
    entries.extend(str(path) for path in sorted(installed.glob("*.jar")))
    return os.pathsep.join(entries)


def load_ratio(history: Path) -> float:
    manifest_path = history / "manifest.json"
    if not manifest_path.is_file():
        raise ValueError(f"Missing manifest.json for {history}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    operations = int(manifest.get("operations", 0))
    predicates = int(manifest.get("predicate_reads", 0))
    if operations <= 0:
        raise ValueError(f"Invalid operations in {manifest_path}")
    return predicates / operations


def terminate(proc: subprocess.Popen[str]) -> None:
    if proc.poll() is not None:
        return
    try:
        os.killpg(proc.pid, signal.SIGTERM)
        proc.wait(timeout=2)
    except ProcessLookupError:
        return
    except subprocess.TimeoutExpired:
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        proc.wait()


def run_preprocessing(command: list[str], log_path: Path,
                      timeout_seconds: float) -> tuple[int, ...]:
    started = time.monotonic()
    with log_path.open("w", encoding="utf-8") as log:
        proc = subprocess.Popen(
            command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, bufsize=1, start_new_session=True)
        assert proc.stdout is not None
        selector = selectors.DefaultSelector()
        selector.register(proc.stdout, selectors.EVENT_READ)
        try:
            while True:
                remaining = timeout_seconds - (time.monotonic() - started)
                if remaining <= 0:
                    raise TimeoutError(f"preprocessing timed out after {timeout_seconds:g}s")
                events = selector.select(min(remaining, 0.5))
                if not events:
                    if proc.poll() is not None:
                        raise RuntimeError(
                            f"checker exited with {proc.returncode} before publishing stats")
                    continue
                line = proc.stdout.readline()
                if not line:
                    raise RuntimeError(
                        f"checker exited with {proc.poll()} before publishing stats")
                log.write(line)
                log.flush()
                match = STATS_RE.match(line.strip())
                if match:
                    return tuple(map(int, match.groups()))
        finally:
            selector.close()
            terminate(proc)


def percent(reduced: int, original: int) -> float:
    return round(reduced * 100.0 / original, 6) if original else 0.0


def write_csv(path: Path, rows: list[dict[str, object]], fields: tuple[str, ...]) -> None:
    with path.open("w", newline="", encoding="utf-8") as target:
        writer = csv.DictWriter(target, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def summarize(rows: list[dict[str, object]]) -> list[dict[str, object]]:
    groups: dict[float, list[dict[str, object]]] = defaultdict(list)
    for row in rows:
        groups[float(row["predicate_ratio"])].append(row)
    result: list[dict[str, object]] = []
    for ratio, items in sorted(groups.items()):
        summary: dict[str, object] = {
            "predicate_ratio": ratio,
            "histories": len(items),
        }
        for field in RAW_FIELDS[3:]:
            summary[field] = round(statistics.median(
                float(item[field]) for item in items), 6)
        result.append(summary)
    return result


def grouped_bar_svg(
        path: Path, title: str, subtitle: str, y_label: str,
        rows: list[dict[str, object]], series: list[tuple[str, str, str]],
        *, scale: float = 1.0, value_suffix: str = "",
        value_decimals: int = 1, y_limit: float | None = None) -> None:
    width, height, left, right, top, bottom = 980, 560, 90, 30, 110, 85
    plot_width, plot_height = width - left - right, height - top - bottom
    values = [float(row[field]) * scale for row in rows for _, _, field in series]
    y_max = y_limit if y_limit is not None else (max(values) * 1.18 if max(values) else 1.0)
    group_width = plot_width / len(rows)
    bar_width = min(36.0, group_width / (len(series) + 1))

    def y_pos(value: float) -> float:
        return top + plot_height - value * plot_height / y_max

    lines = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        '<rect width="100%" height="100%" fill="white"/>',
        '<style>text{font-family:Arial,sans-serif;font-size:14px}.axis{stroke:#222;stroke-width:1.2}.grid{stroke:#ddd;stroke-width:1}</style>',
        f'<text x="{width / 2}" y="28" text-anchor="middle" font-size="19">{escape(title)}</text>',
        f'<text x="{width / 2}" y="52" text-anchor="middle" fill="#444">{escape(subtitle)}</text>',
        f'<line class="axis" x1="{left}" y1="{top}" x2="{left}" y2="{top + plot_height}"/>',
        f'<line class="axis" x1="{left}" y1="{top + plot_height}" x2="{left + plot_width}" y2="{top + plot_height}"/>',
    ]
    for index in range(6):
        value = y_max * index / 5
        y = y_pos(value)
        lines.extend((
            f'<line class="grid" x1="{left}" y1="{y:.2f}" x2="{left + plot_width}" y2="{y:.2f}"/>',
            f'<text x="{left - 10}" y="{y + 5:.2f}" text-anchor="end">{value:,.1f}{escape(value_suffix)}</text>',
        ))

    for row_index, row in enumerate(rows):
        center = left + group_width * (row_index + 0.5)
        ratio = float(row["predicate_ratio"])
        lines.extend((
            f'<line class="axis" x1="{center:.2f}" y1="{top + plot_height}" x2="{center:.2f}" y2="{top + plot_height + 6}"/>',
            f'<text x="{center:.2f}" y="{top + plot_height + 27}" text-anchor="middle">{ratio * 100:g}%</text>',
        ))

        series_width = len(series) * bar_width
        for series_index, (_, color, field) in enumerate(series):
            value = float(row[field]) * scale
            x = center - series_width / 2 + series_index * bar_width
            y = y_pos(value)
            bar_height = top + plot_height - y
            label_y = max(top + 14, y - 7)
            value_label = f"{value:,.{value_decimals}f}{value_suffix}"
            lines.extend((
                f'<rect x="{x:.2f}" y="{y:.2f}" width="{bar_width - 3:.2f}" height="{bar_height:.2f}" fill="{color}"/>',
                f'<text x="{x + (bar_width - 3) / 2:.2f}" y="{label_y:.2f}" text-anchor="middle" font-size="12">{escape(value_label)}</text>',
            ))

    legend_width = 250
    legend_x = (width - legend_width * len(series)) / 2
    legend_y = 72
    for index, (label, color, _) in enumerate(series):
        x = legend_x + index * legend_width
        lines.extend((
            f'<rect x="{x:.2f}" y="{legend_y}" width="18" height="14" fill="{color}"/>',
            f'<text x="{x + 26:.2f}" y="{legend_y + 12}">{escape(label)}</text>',
        ))
    lines.extend((
        f'<text x="{width / 2}" y="{height - 22}" text-anchor="middle">Predicate operation ratio</text>',
        f'<text x="24" y="{height / 2}" text-anchor="middle" transform="rotate(-90 24 {height / 2})">{escape(y_label)}</text>',
        '</svg>',
    ))
    path.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    args = arguments()
    if args.timeout_seconds <= 0:
        raise SystemExit("--timeout-seconds must be positive")
    histories = discover_histories(args.history_root)
    if args.predicate_ratio_below is not None:
        if not 0.0 <= args.predicate_ratio_below <= 1.0:
            raise SystemExit("--predicate-ratio-below must be between 0 and 1")
        histories = [
            history for history in histories
            if load_ratio(history) < args.predicate_ratio_below
        ]
        if not histories:
            raise SystemExit(
                "No histories have a predicate ratio below "
                f"{args.predicate_ratio_below:g}")
    if args.limit > 0:
        histories = histories[:args.limit]
    project = args.project_root.resolve()
    out_dir = (args.out_dir or project / "results" /
               f"ww-gmwr-pruning-{time.strftime('%Y%m%d-%H%M%S')}").resolve()
    logs = out_dir / "logs"
    logs.mkdir(parents=True, exist_ok=True)
    native = project / "build" / "monosat"
    if not native.is_dir():
        raise SystemExit(f"Missing {native}; run './gradlew buildMonoSAT' first")
    base = [args.java, f"-Xmx{args.xmx}",
            f"-Djava.library.path={native}", "-cp", build_classpath(project),
            "Main", "audit", "--solver-stats", "--ww-pruning", "REACHABILITY",
            "--predicate-encoding", "GMWR", "--ser-propagation-mode", "WW_GMWR",
            "--gmwr-prepropagation", "--predicate-witness-coalescing",
            "--graph-edge-interning"]
    rows: list[dict[str, object]] = []
    for index, history in enumerate(histories, 1):
        dataset = history.parent.name
        label = re.sub(r"[^A-Za-z0-9._-]+", "__", f"{dataset}__{history.name}")
        print(f"[{index}/{len(histories)}] {dataset}/{history.name}", flush=True)
        values = run_preprocessing(
            [*base, str(history)], logs / f"{label}.log", args.timeout_seconds)
        ww_original, ww_residual, ww_reduced, ww_ms, gmwr_original, \
            gmwr_residual, gmwr_reduced, gmwr_ms, obligations_original, \
            obligations_residual = values
        rows.append({
            "history": str(history), "dataset": dataset,
            "predicate_ratio": load_ratio(history),
            "ww_original": ww_original, "ww_residual": ww_residual,
            "ww_reduced": ww_reduced,
            "ww_reduction_percent": percent(ww_reduced, ww_original),
            "ww_time_ms": ww_ms, "gmwr_original": gmwr_original,
            "gmwr_residual": gmwr_residual, "gmwr_reduced": gmwr_reduced,
            "gmwr_reduction_percent": percent(gmwr_reduced, gmwr_original),
            "gmwr_time_ms": gmwr_ms,
            "gmwr_obligations_original": obligations_original,
            "gmwr_obligations_residual": obligations_residual,
            "gmwr_obligations_reduced": obligations_original - obligations_residual,
        })
    write_csv(out_dir / "raw.csv", rows, RAW_FIELDS)
    summary = summarize(rows)
    write_csv(out_dir / "summary.csv", summary, SUMMARY_FIELDS)
    grouped_bar_svg(
        out_dir / "pruning_time.svg", "WW and GMWR pruning time",
        "Median time spent in each pruning stage", "Median stage time (s)",
        summary, [
            ("WW reachability", "#1565c0", "ww_time_ms"),
            ("GMWR feedback", "#d32f2f", "gmwr_time_ms"),
        ], scale=0.001, value_suffix=" s", value_decimals=3)
    grouped_bar_svg(
        out_dir / "pruned_constraints.svg", "WW and GMWR pruning percentages",
        "GMWR reduction is measured against the WW residual constraints",
        "Median constraints pruned (%)", summary, [
            ("WW / initial WW", "#1565c0", "ww_reduction_percent"),
            ("GMWR / WW residual", "#d32f2f", "gmwr_reduction_percent"),
        ], value_suffix="%", y_limit=100.0)
    print(f"Results: {out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
