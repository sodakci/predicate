#!/usr/bin/env python3
"""Plot controlled-variable FULL GMWR versus NO_GMWR runtime curves."""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import csv
import json
from pathlib import Path
import re
import statistics
import sys
from xml.sax.saxutils import escape


DATASET_RE = re.compile(
    r"^(?P<sessions>\d+)_(?P<transactions>\d+)_(?P<operations>\d+)_"
    r"(?P<keys>\d+)_(?P<ratio>\d+(?:\.\d+)?)_(?P<distribution>[^_]+)$"
)
BASELINE = {
    "sessions": 20,
    "transactions": 100,
    "operations": 15,
    "keys": 5000,
    "ratio": 0.20,
    "distribution": "uniform",
}
VARIABLES = (
    ("transactions", "Transactions per session", "transactions.svg"),
    ("operations", "Operations per transaction", "operations_per_transaction.svg"),
    ("keys", "Key count", "keys.svg"),
    ("ratio", "Predicate-read ratio", "predicate_ratio.svg"),
)
CONFIG_LABELS = {
    "FULL": "GMWR (FULL)",
    "NO_GMWR": "NO_GMWR",
}
REQUIRED_COLUMNS = {
    "family", "dataset", "config", "status", "ENTIRE_EXPERIMENT",
}


def arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("raw_csv", type=Path,
                        help="raw.csv produced by run_ser_acceleration_ablation.py")
    parser.add_argument("--out-dir", type=Path, required=True,
                        help="directory for the summary CSV and four SVG files")
    return parser.parse_args(argv)


def parse_dataset(name):
    match = DATASET_RE.match(name)
    if not match:
        return None
    return {
        "sessions": int(match["sessions"]),
        "transactions": int(match["transactions"]),
        "operations": int(match["operations"]),
        "keys": int(match["keys"]),
        "ratio": float(match["ratio"]),
        "distribution": match["distribution"],
    }


def is_controlled(parameters, variable):
    return all(
        name == variable or parameters[name] == expected
        for name, expected in BASELINE.items()
    )


def read_measurements(path):
    with path.open(newline="", encoding="utf-8") as stream:
        reader = csv.DictReader(stream)
        missing = REQUIRED_COLUMNS - set(reader.fieldnames or ())
        if missing:
            raise ValueError("raw.csv missing columns: " + ", ".join(sorted(missing)))
        return list(reader)


def completed_milliseconds(rows):
    values = []
    for row in rows:
        if row.get("status") != "COMPLETE":
            continue
        try:
            value = float(row["ENTIRE_EXPERIMENT"])
        except (TypeError, ValueError):
            continue
        if value >= 0:
            values.append(value)
    return values


def format_number(value):
    return f"{value:g}"


def build_summary(raw_rows):
    grouped = defaultdict(list)
    parameters_by_dataset = {}
    for row in raw_rows:
        if row.get("family") != "test-ser":
            continue
        config = row.get("config")
        if config not in CONFIG_LABELS:
            continue
        dataset = row.get("dataset", "")
        parameters = parse_dataset(dataset)
        if parameters is None:
            continue
        parameters_by_dataset[dataset] = parameters
        grouped[(dataset, config)].append(row)

    summary = []
    for variable, _, _ in VARIABLES:
        datasets = sorted(
            (dataset for dataset, parameters in parameters_by_dataset.items()
             if is_controlled(parameters, variable)),
            key=lambda dataset: parameters_by_dataset[dataset][variable],
        )
        for dataset in datasets:
            parameters = parameters_by_dataset[dataset]
            by_config = {
                config: grouped.get((dataset, config), [])
                for config in CONFIG_LABELS
            }
            completed = {
                config: completed_milliseconds(rows)
                for config, rows in by_config.items()
            }
            medians = {
                config: (statistics.median(values) / 1000 if values else None)
                for config, values in completed.items()
            }
            gmwr = medians["FULL"]
            no_gmwr = medians["NO_GMWR"]
            summary.append({
                "variable": variable,
                "x": format_number(parameters[variable]),
                "dataset": dataset,
                "gmwr_all_runs": len(by_config["FULL"]),
                "gmwr_complete_runs": len(completed["FULL"]),
                "gmwr_statuses": json.dumps(
                    Counter(row.get("status", "") for row in by_config["FULL"]),
                    sort_keys=True,
                ),
                "gmwr_median_seconds": f"{gmwr:.6f}" if gmwr is not None else "",
                "no_gmwr_all_runs": len(by_config["NO_GMWR"]),
                "no_gmwr_complete_runs": len(completed["NO_GMWR"]),
                "no_gmwr_statuses": json.dumps(
                    Counter(row.get("status", "") for row in by_config["NO_GMWR"]),
                    sort_keys=True,
                ),
                "no_gmwr_median_seconds": (
                    f"{no_gmwr:.6f}" if no_gmwr is not None else ""
                ),
                "speedup_no_gmwr_over_gmwr": (
                    f"{no_gmwr / gmwr:.6f}"
                    if gmwr is not None and no_gmwr is not None and gmwr > 0
                    else ""
                ),
            })
    return summary


def write_summary(path, rows):
    fields = (
        "variable", "x", "dataset",
        "gmwr_all_runs", "gmwr_complete_runs", "gmwr_statuses",
        "gmwr_median_seconds",
        "no_gmwr_all_runs", "no_gmwr_complete_runs", "no_gmwr_statuses",
        "no_gmwr_median_seconds", "speedup_no_gmwr_over_gmwr",
    )
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def paired_points(rows, variable):
    points = []
    for row in rows:
        if row["variable"] != variable:
            continue
        if not row["gmwr_median_seconds"] or not row["no_gmwr_median_seconds"]:
            continue
        points.append((
            float(row["x"]),
            float(row["gmwr_median_seconds"]),
            float(row["no_gmwr_median_seconds"]),
        ))
    return sorted(points)


def render_svg(path, title, x_label, points):
    width, height = 780, 500
    left, right, top, bottom = 105, 35, 60, 90
    plot_width = width - left - right
    plot_height = height - top - bottom
    x_values = [point[0] for point in points]
    y_values = [value for point in points for value in point[1:]]
    x_min, x_max = min(x_values), max(x_values)
    if x_min == x_max:
        x_min -= 1
        x_max += 1
    y_max = max(y_values) * 1.12 if max(y_values) > 0 else 1

    def x_position(value):
        return left + (value - x_min) * plot_width / (x_max - x_min)

    def y_position(value):
        return top + plot_height - value * plot_height / y_max

    lines = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" '
        f'height="{height}" viewBox="0 0 {width} {height}">',
        '<rect width="100%" height="100%" fill="white"/>',
        '<style>text{font-family:Arial,sans-serif;font-size:14px}'
        '.axis{stroke:#222;stroke-width:1}.grid{stroke:#ddd;stroke-width:1}'
        '.gmwr{stroke:#1565c0;fill:none;stroke-width:2.5}'
        '.no-gmwr{stroke:#d32f2f;fill:none;stroke-width:2.5}</style>',
        f'<title>{escape(title)}</title>',
        f'<text x="{width / 2}" y="32" text-anchor="middle" '
        f'font-size="19">{escape(title)}</text>',
        f'<line class="axis" x1="{left}" y1="{top}" x2="{left}" '
        f'y2="{top + plot_height}"/>',
        f'<line class="axis" x1="{left}" y1="{top + plot_height}" '
        f'x2="{left + plot_width}" y2="{top + plot_height}"/>',
    ]
    for index in range(6):
        value = y_max * index / 5
        y = y_position(value)
        lines.extend((
            f'<line class="grid" x1="{left}" y1="{y:.2f}" '
            f'x2="{left + plot_width}" y2="{y:.2f}"/>',
            f'<text x="{left - 10}" y="{y + 5:.2f}" '
            f'text-anchor="end">{value:.1f}</text>',
        ))
    for value in x_values:
        x = x_position(value)
        label = format_number(value)
        lines.extend((
            f'<line class="axis" x1="{x:.2f}" y1="{top + plot_height}" '
            f'x2="{x:.2f}" y2="{top + plot_height + 6}"/>',
            f'<text x="{x:.2f}" y="{top + plot_height + 27}" '
            f'text-anchor="middle">{escape(label)}</text>',
        ))
    for css, column, color, label in (
            ("gmwr", 1, "#1565c0", CONFIG_LABELS["FULL"]),
            ("no-gmwr", 2, "#d32f2f", CONFIG_LABELS["NO_GMWR"])):
        coordinates = " ".join(
            f"{x_position(point[0]):.2f},{y_position(point[column]):.2f}"
            for point in points
        )
        lines.append(f'<polyline class="{css}" points="{coordinates}"/>')
        for point in points:
            x = x_position(point[0])
            y = y_position(point[column])
            lines.append(
                f'<circle cx="{x:.2f}" cy="{y:.2f}" r="4" '
                f'fill="white" stroke="{color}" stroke-width="2">'
                f'<title>{escape(label)}: {point[column]:.6f} s</title></circle>'
            )
    legend_x, legend_y = width - 235, 68
    lines.extend((
        f'<rect x="{legend_x}" y="{legend_y}" width="195" height="58" '
        f'fill="white" stroke="#777"/>',
        f'<line class="gmwr" x1="{legend_x + 12}" y1="{legend_y + 19}" '
        f'x2="{legend_x + 46}" y2="{legend_y + 19}"/>',
        f'<text x="{legend_x + 54}" y="{legend_y + 24}">GMWR (FULL)</text>',
        f'<line class="no-gmwr" x1="{legend_x + 12}" y1="{legend_y + 43}" '
        f'x2="{legend_x + 46}" y2="{legend_y + 43}"/>',
        f'<text x="{legend_x + 54}" y="{legend_y + 48}">NO_GMWR</text>',
        f'<text x="{width / 2}" y="{height - 24}" text-anchor="middle">'
        f'{escape(x_label)}</text>',
        f'<text x="25" y="{height / 2}" text-anchor="middle" '
        f'transform="rotate(-90 25 {height / 2})">Median total time (s)</text>',
        '</svg>',
    ))
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main(argv=None):
    args = arguments(argv)
    if not args.raw_csv.is_file():
        raise ValueError(f"raw.csv not found: {args.raw_csv}")
    summary = build_summary(read_measurements(args.raw_csv))
    paired = {
        variable: paired_points(summary, variable)
        for variable, _, _ in VARIABLES
    }
    if not any(paired.values()):
        raise ValueError("no paired COMPLETE FULL/NO_GMWR measurements")

    args.out_dir.mkdir(parents=True, exist_ok=True)
    write_summary(args.out_dir / "gmwr_vs_no_gmwr.csv", summary)
    for variable, label, filename in VARIABLES:
        points = paired[variable]
        if points:
            render_svg(
                args.out_dir / filename,
                f"GMWR versus NO_GMWR: {label}",
                label,
                points,
            )
    print(f"wrote {args.out_dir}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError) as exception:
        print("error:", exception, file=sys.stderr)
        raise SystemExit(2)
