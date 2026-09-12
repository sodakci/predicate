#!/usr/bin/env python3
"""Average three SER configurations and render controlled-variable SVG charts."""

from __future__ import annotations

import argparse
import csv
import re
from collections import defaultdict
from pathlib import Path
from statistics import mean
from xml.sax.saxutils import escape


DATASET_RE = re.compile(
    r"20_(?P<txns>\d+)_(?P<ops>\d+)_(?P<keys>\d+)_(?P<ratio>0\.\d+)_uniform"
)
BASELINE = {"txns": 100, "ops": 15, "keys": 5000, "ratio": 0.20}
VARIABLES = (
    ("keys", "Key count", "key_count.svg"),
    ("ratio", "Predicate-read ratio", "predicate_read_ratio.svg"),
    ("ops", "Operations per transaction", "operations_per_transaction.svg"),
    ("txns", "Transactions per session", "transactions_per_session.svg"),
)


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--gmwr-none", type=Path, required=True)
    parser.add_argument("--eager-reachability", type=Path, required=True)
    parser.add_argument("--eager-none", type=Path, required=True)
    parser.add_argument("--out-dir", type=Path, required=True)
    return parser.parse_args()


def load(path: Path, label: str) -> dict[tuple[int, int, int, float], list[float]]:
    values: dict[tuple[int, int, int, float], list[float]] = defaultdict(list)
    with path.open(newline="", encoding="utf-8") as source:
        for row in csv.DictReader(source):
            raw = row.get("ENTIRE_EXPERIMENT", "")
            if not raw:
                continue
            match = DATASET_RE.search(row["history"])
            if not match:
                continue
            key = (int(match["txns"]), int(match["ops"]), int(match["keys"]), float(match["ratio"]))
            values[key].append(float(raw) / 1000.0)
    if not values:
        raise SystemExit(f"No successful {label} measurements in {path}")
    return values


def is_controlled(key: tuple[int, int, int, float], variable: str) -> bool:
    txns, ops, keys, ratio = key
    actual = {"txns": txns, "ops": ops, "keys": keys, "ratio": ratio}
    return all(name == variable or actual[name] == BASELINE[name] for name in BASELINE)


def svg(path: Path, title: str, x_label: str,
        points: list[tuple[float, float, float, float]]) -> None:
    width, height, left, right, top, bottom = 720, 460, 105, 35, 55, 85
    plot_width, plot_height = width - left - right, height - top - bottom
    xs = [point[0] for point in points]
    ys = [value for point in points for value in point[1:]]
    x_min, x_max = min(xs), max(xs)
    if x_min == x_max:
        x_min -= 1
        x_max += 1
    y_max = max(ys) * 1.12 if max(ys) else 1.0

    def x_pos(value: float) -> float:
        return left + (value - x_min) * plot_width / (x_max - x_min)

    def y_pos(value: float) -> float:
        return top + plot_height - value * plot_height / y_max

    lines = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        '<rect width="100%" height="100%" fill="white"/>',
        '<style>text{font-family:Times New Roman,serif;font-size:16px}.axis{stroke:#000;stroke-width:1}.grid{stroke:#d0d0d0;stroke-width:1}.gmwr{stroke:#1565c0;fill:none;stroke-width:2.4}.eager-reach{stroke:#d32f2f;fill:none;stroke-width:2.4}.eager-none{stroke:#2e7d32;fill:none;stroke-width:2.4}</style>',
        f'<text x="{width / 2}" y="30" text-anchor="middle" font-size="19">{escape(title)}</text>',
        f'<line class="axis" x1="{left}" y1="{top}" x2="{left}" y2="{top + plot_height}"/>',
        f'<line class="axis" x1="{left}" y1="{top + plot_height}" x2="{left + plot_width}" y2="{top + plot_height}"/>',
    ]
    for index in range(6):
        value = y_max * index / 5
        y = y_pos(value)
        lines += [f'<line class="grid" x1="{left}" y1="{y}" x2="{left + plot_width}" y2="{y}"/>', f'<text x="{left - 10}" y="{y + 5}" text-anchor="end">{value:.1f}</text>']
    for value in xs:
        x = x_pos(value)
        lines += [f'<line class="axis" x1="{x}" y1="{top + plot_height}" x2="{x}" y2="{top + plot_height + 6}"/>', f'<text x="{x}" y="{top + plot_height + 28}" text-anchor="middle">{value:g}</text>']
    for css, column, marker, color in (
            ("gmwr", 1, "square", "#1565c0"),
            ("eager-reach", 2, "triangle", "#d32f2f"),
            ("eager-none", 3, "circle", "#2e7d32")):
        coordinates = " ".join(f"{x_pos(point[0]):.2f},{y_pos(point[column]):.2f}" for point in points)
        lines.append(f'<polyline class="{css}" points="{coordinates}"/>')
        for point in points:
            x, y = x_pos(point[0]), y_pos(point[column])
            if marker == "square":
                lines.append(f'<rect x="{x - 4}" y="{y - 4}" width="8" height="8" fill="white" stroke="{color}" stroke-width="1.7"/>')
            elif marker == "triangle":
                lines.append(f'<path d="M {x} {y - 5} L {x - 5} {y + 4} L {x + 5} {y + 4} Z" fill="white" stroke="{color}" stroke-width="1.7"/>')
            else:
                lines.append(f'<circle cx="{x}" cy="{y}" r="4" fill="white" stroke="{color}" stroke-width="1.7"/>')
    legend_x, legend_y = width - 285, 65
    lines += [f'<rect x="{legend_x}" y="{legend_y}" width="240" height="84" fill="white" stroke="#777"/>', f'<line class="gmwr" x1="{legend_x + 12}" y1="{legend_y + 19}" x2="{legend_x + 47}" y2="{legend_y + 19}"/>', f'<text x="{legend_x + 55}" y="{legend_y + 25}">GMWR + NONE</text>', f'<line class="eager-reach" x1="{legend_x + 12}" y1="{legend_y + 43}" x2="{legend_x + 47}" y2="{legend_y + 43}"/>', f'<text x="{legend_x + 55}" y="{legend_y + 49}">EAGER + REACHABILITY</text>', f'<line class="eager-none" x1="{legend_x + 12}" y1="{legend_y + 67}" x2="{legend_x + 47}" y2="{legend_y + 67}"/>', f'<text x="{legend_x + 55}" y="{legend_y + 73}">EAGER + NONE</text>', f'<text x="{width / 2}" y="{height - 22}" text-anchor="middle">{escape(x_label)}</text>', f'<text x="25" y="{height / 2}" text-anchor="middle" transform="rotate(-90 25 {height / 2})">Mean time (s)</text>', '</svg>']
    path.write_text("\n".join(lines), encoding="utf-8")


def main() -> None:
    args = arguments()
    args.out_dir.mkdir(parents=True, exist_ok=True)
    gmwr = load(args.gmwr_none, "GMWR+NONE")
    eager_reachability = load(args.eager_reachability, "EAGER+REACHABILITY")
    eager_none = load(args.eager_none, "EAGER+NONE")
    common = sorted(set(gmwr) & set(eager_reachability) & set(eager_none))
    with (args.out_dir / "mean_solver_time.csv").open("w", newline="", encoding="utf-8") as target:
        writer = csv.writer(target)
        writer.writerow(("dataset", "histories", "gmwr_none_mean_seconds",
                         "eager_reachability_mean_seconds", "eager_none_mean_seconds"))
        for key in common:
            txns, ops, keys, ratio = key
            writer.writerow((f"20_{txns}_{ops}_{keys}_{ratio:.2f}_uniform",
                             min(len(gmwr[key]), len(eager_reachability[key]), len(eager_none[key])),
                             f"{mean(gmwr[key]):.6f}",
                             f"{mean(eager_reachability[key]):.6f}",
                             f"{mean(eager_none[key]):.6f}"))
    for variable, label, filename in VARIABLES:
        points = []
        for key in common:
            if is_controlled(key, variable):
                value = {"txns": key[0], "ops": key[1], "keys": key[2], "ratio": key[3]}[variable]
                points.append((float(value), mean(gmwr[key]),
                               mean(eager_reachability[key]), mean(eager_none[key])))
        if points:
            svg(args.out_dir / filename, f"SER solver time vs {label}", label, sorted(points))


if __name__ == "__main__":
    main()
