#!/usr/bin/env python3
"""Audit a generated BenchBase kvpredicate PRHIST case without trusting its manifest."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

from kvpredicate_trace_to_prhist import ConversionError, load_jsonl, validate_structure


def load_case(case_dir: Path) -> tuple[list[dict], list[dict], dict]:
    initial_path = case_dir / "initial_state.json"
    history_path = case_dir / "history.prhist.jsonl"
    manifest_path = case_dir / "manifest.json"
    if not initial_path.is_file() or not history_path.is_file():
        raise ConversionError(f"{case_dir}: missing initial_state.json or history.prhist.jsonl")
    initial = json.loads(initial_path.read_text(encoding="utf-8"))
    if not isinstance(initial, list):
        raise ConversionError(f"{initial_path}: expected JSON array")
    transactions = load_jsonl(history_path)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.is_file() else {}
    return initial, transactions, manifest


def audit(case_dir: Path) -> dict:
    initial, transactions, manifest = load_case(case_dir)
    stats = validate_structure(initial, transactions)
    raw_path = case_dir / "raw_kvpredicate_trace.jsonl"
    if raw_path.is_file():
        raw_rows = load_jsonl(raw_path)
        raw_txns = [row for row in raw_rows if row.get("record_type") == "txn"]
        if len(raw_txns) != stats["transactions"]:
            raise ConversionError(
                f"raw trace transaction count {len(raw_txns)} != PRHIST transaction count {stats['transactions']}"
            )
        raw_initial = [row for row in raw_rows if row.get("record_type") == "initial"]
        absent_initial = [row for row in initial if row.get("absent")]
        if len(raw_initial) + len(absent_initial) != stats["initial_keys"]:
            raise ConversionError(
                "raw trace initial count plus virtual absent versions "
                f"{len(raw_initial)}+{len(absent_initial)} != PRHIST initial count {stats['initial_keys']}"
            )
    if manifest and manifest.get("format") not in {"prhist-v2", "prhist-v2-kv-relational-predicate"}:
        raise ConversionError(f"{case_dir}/manifest.json: unexpected format {manifest.get('format')!r}")
    return {"valid": True, "case_dir": str(case_dir), "raw_trace_present": raw_path.is_file(), **stats}


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate source versions and session order for a kvpredicate PRHIST case.")
    parser.add_argument("case_dir", type=Path)
    parser.add_argument("--detector-jar", type=Path, help="optional built SER detector jar; runs its parser/audit after local checks")
    parser.add_argument("--java-library-path", type=Path, help="optional MonoSAT native-library directory")
    args = parser.parse_args()
    try:
        report = audit(args.case_dir)
        if args.detector_jar:
            command = ["java"]
            if args.java_library_path:
                command.append(f"-Djava.library.path={args.java_library_path}")
            command.extend(["-jar", str(args.detector_jar), "audit", str(args.case_dir)])
            completed = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
            report["detector_exit_code"] = completed.returncode
            report["detector_output"] = completed.stdout
            if completed.returncode != 0:
                raise ConversionError("SER detector parser/audit failed; see detector_output")
    except (ConversionError, OSError, json.JSONDecodeError) as exc:
        print(json.dumps({"valid": False, "error": str(exc)}, indent=2), file=sys.stderr)
        return 1
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
