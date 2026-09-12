#!/usr/bin/env python3
"""Render controlled-variable SVG charts for run_ser_baseline_vs_gmwr.py."""

from __future__ import annotations

import argparse
import csv
import re
import statistics
from collections import defaultdict
from pathlib import Path
from xml.sax.saxutils import escape


DATASET_RE = re.compile(
    r"20_(?P<txns>\d+)_(?P<ops>\d+)_(?P<keys>\d+)_"
    r"(?P<ratio>0(?:\.\d+)?)_uniform"
)
BASELINE = {"txns": 100, "ops": 15, "keys": 5000, "ratio": 0.20}
VARIABLES = (
    ("keys", "Key count", "key_count.svg"),
    ("ratio", "Predicate-read ratio", "predicate_read_ratio.svg"),
    ("ops", "Operations per transaction", "operations_per_transaction.svg"),
    ("txns", "Transactions per session", "transactions_per_session.svg"),
)
CONFIGS = (
    ("E1", "#c62828", "circle"),
    ("E2", "#ef6c00", "square"),
    ("G1", "#1565c0", "triangle"),
    ("G2", "#2e7d32", "diamond"),
)
TIMEOUT_SECONDS = 180.0


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Plot median wall time for an E1/E2/G1/G2 experiment.")
    parser.add_argument("raw_csv", type=Path)
    parser.add_argument("--out-dir", type=Path, required=True)
    return parser.parse_args()


def load(path: Path) -> dict[tuple[int, int, int, float], dict[str, list[float]]]:
    values: dict[tuple[int, int, int, float], dict[str, list[float]]] = defaultdict(
        lambda: defaultdict(list))
    with path.open(newline="", encoding="utf-8") as source:
        for row in csv.DictReader(source):
            match = DATASET_RE.search(row.get("history", ""))
            config = row.get("config", "")
            status = row.get("status", "")
            if not match or config not in {item[0] for item in CONFIGS}:
                continue
            if status == "TIMEOUT":
                wall = TIMEOUT_SECONDS
            elif status == "COMPLETE":
                try:
                    wall = float(row["wall_seconds"])
                except (KeyError, TypeError, ValueError):
                    continue
            else:
                continue
            key = (
                int(match["txns"]),
                int(match["ops"]),
                int(match["keys"]),
                float(match["ratio"]),
            )
            values[key][config].append(min(wall, TIMEOUT_SECONDS))
    if not values:
        raise SystemExit(f"No COMPLETE/TIMEOUT measurements in {path}")
    return values


def is_controlled(key: tuple[int, int, int, float], variable: str) -> bool:
    actual = {"txns": key[0], "ops": key[1], "keys": key[2], "ratio": key[3]}
    return all(name == variable or actual[name] == expected
               for name, expected in BASELINE.items())


def marker(lines: list[str], kind: str, x: float, y: float, color: str) -> None:
    if kind == "circle":
        lines.append(
            f'<circle cx="{x:.2f}" cy="{y:.2f}" r="4" fill="white" '
            f'stroke="{color}" stroke-width="1.8"/>')
    elif kind == "square":
        lines.append(
            f'<rect x="{x - 4:.2f}" y="{y - 4:.2f}" width="8" height="8" '
            f'fill="white" stroke="{color}" stroke-width="1.8"/>')
    elif kind == "triangle":
        lines.append(
            f'<path d="M {x:.2f} {y - 5:.2f} L {x - 5:.2f} {y + 4:.2f} '
            f'L {x + 5:.2f} {y + 4:.2f} Z" fill="white" '
            f'stroke="{color}" stroke-width="1.8"/>')
    else:
        lines.append(
            f'<path d="M {x:.2f} {y - 5:.2f} L {x - 5:.2f} {y:.2f} '
            f'L {x:.2f} {y + 5:.2f} L {x + 5:.2f} {y:.2f} Z" fill="white" '
            f'stroke="{color}" stroke-width="1.8"/>')


def render(path: Path, title: str, x_label: str,
           points: list[tuple[float, dict[str, float]]]) -> None:
    width, height = 780, 500
    left, right, top, bottom = 88, 35, 55, 105
    plot_width, plot_height = width - left - right, height - top - bottom
    xs = [point[0] for point in points]
    x_min, x_max = min(xs), max(xs)
    if x_min == x_max:
        x_min -= 1
        x_max += 1
    y_max = TIMEOUT_SECONDS

    def x_pos(value: float) -> float:
        return left + (value - x_min) * plot_width / (x_max - x_min)

    def y_pos(value: float) -> float:
        return top + plot_height - value * plot_height / y_max

    lines = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
        f'viewBox="0 0 {width} {height}">',
        '<rect width="100%" height="100%" fill="white"/>',
        '<style>text{font-family:"Times New Roman",serif;font-size:15px}'
        '.axis{stroke:#111;stroke-width:1}.grid{stroke:#d7d7d7;stroke-width:1}'
        '.timeout{stroke:#777;stroke-width:1;stroke-dasharray:5 4}</style>',
        f'<text x="{width / 2}" y="29" text-anchor="middle" font-size="19">'
        f'{escape(title)}</text>',
        f'<line class="axis" x1="{left}" y1="{top}" x2="{left}" '
        f'y2="{top + plot_height}"/>',
        f'<line class="axis" x1="{left}" y1="{top + plot_height}" '
        f'x2="{left + plot_width}" y2="{top + plot_height}"/>',
    ]
    for value in range(0, 181, 30):
        y = y_pos(float(value))
        css = "timeout" if value == int(TIMEOUT_SECONDS) else "grid"
        lines.extend((
            f'<line class="{css}" x1="{left}" y1="{y:.2f}" '
            f'x2="{left + plot_width}" y2="{y:.2f}"/>',
            f'<text x="{left - 10}" y="{y + 5:.2f}" text-anchor="end">{value}</text>',
        ))
    for value in xs:
        x = x_pos(value)
        label = f"{value:g}"
        lines.extend((
            f'<line class="axis" x1="{x:.2f}" y1="{top + plot_height}" '
            f'x2="{x:.2f}" y2="{top + plot_height + 6}"/>',
            f'<text x="{x:.2f}" y="{top + plot_height + 27}" '
            f'text-anchor="middle">{label}</text>',
        ))

    for config, color, kind in CONFIGS:
        available = [(x, medians[config]) for x, medians in points if config in medians]
        coordinates = " ".join(
            f"{x_pos(x):.2f},{y_pos(value):.2f}" for x, value in available)
        if coordinates:
            lines.append(
                f'<polyline points="{coordinates}" fill="none" stroke="{color}" '
                f'stroke-width="2.4"/>')
        for x, value in available:
            marker(lines, kind, x_pos(x), y_pos(value), color)

    legend_x, legend_y = left + 12, top + 12
    lines.append(
        f'<rect x="{legend_x}" y="{legend_y}" width="112" height="105" '
        f'fill="white" fill-opacity="0.92" stroke="#888"/>')
    for index, (config, color, kind) in enumerate(CONFIGS):
        y = legend_y + 19 + index * 23
        lines.append(
            f'<line x1="{legend_x + 10}" y1="{y}" x2="{legend_x + 42}" y2="{y}" '
            f'stroke="{color}" stroke-width="2.4"/>')
        marker(lines, kind, legend_x + 26, y, color)
        lines.append(f'<text x="{legend_x + 51}" y="{y + 5}">{config}</text>')

    lines.extend((
        f'<text x="{width / 2}" y="{height - 52}" text-anchor="middle">'
        f'{escape(x_label)}</text>',
        f'<text x="24" y="{top + plot_height / 2}" text-anchor="middle" '
        f'transform="rotate(-90 24 {top + plot_height / 2})">Median wall time (s)</text>',
        f'<text x="{width / 2}" y="{height - 20}" text-anchor="middle" '
        f'font-size="13" fill="#555">TIMEOUT values are capped at 180 s</text>',
        '</svg>',
    ))
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    args = arguments()
    values = load(args.raw_csv)
    args.out_dir.mkdir(parents=True, exist_ok=True)
    for variable, label, filename in VARIABLES:
        points: list[tuple[float, dict[str, float]]] = []
        for key, config_values in values.items():
            if not is_controlled(key, variable):
                continue
            x = {"txns": key[0], "ops": key[1], "keys": key[2], "ratio": key[3]}[variable]
            medians = {
                config: statistics.median(samples)
                for config, samples in config_values.items()
                if samples
            }
            points.append((float(x), medians))
        if points:
            render(args.out_dir / filename, f"SER EAGER/GMWR runtime vs {label}",
                   label, sorted(points))


if __name__ == "__main__":
    main()
