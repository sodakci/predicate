#!/usr/bin/env python3
"""Reliably fetch the pinned project-local Temurin JDK 23 archive."""

from __future__ import annotations

import os
import re
import time
from pathlib import Path
from urllib.request import Request, urlopen


URL = (
    "https://github.com/adoptium/temurin23-binaries/releases/download/"
    "jdk-23.0.2%2B7/OpenJDK23U-jdk_x64_linux_hotspot_23.0.2_7.tar.gz"
)
TARGET = Path(__file__).with_name("OpenJDK23U-jdk_x64_linux_hotspot_23.0.2_7.tar.gz")
TOTAL_BYTES = 214_525_906
CHUNK_BYTES = 8 * 1024 * 1024


def main() -> None:
    position = TARGET.stat().st_size if TARGET.exists() else 0
    if position > TOTAL_BYTES:
        raise SystemExit(f"{TARGET} is larger than the expected archive")
    with TARGET.open("ab") as output:
        while position < TOTAL_BYTES:
            end = min(TOTAL_BYTES - 1, position + CHUNK_BYTES - 1)
            expected_bytes = end - position + 1
            for attempt in range(1, 11):
                try:
                    request = Request(
                        URL,
                        headers={"Range": f"bytes={position}-{end}", "User-Agent": "tpcc-java23-bootstrap"},
                    )
                    with urlopen(request, timeout=20) as response:
                        content_range = response.headers.get("Content-Range", "")
                        match = re.fullmatch(r"bytes (\d+)-(\d+)/(\d+)", content_range)
                        if response.status != 206 or not match:
                            raise RuntimeError(f"expected HTTP 206 range response, got {response.status} {content_range!r}")
                        start, actual_end, declared_total = map(int, match.groups())
                        if (start, actual_end, declared_total) != (position, end, TOTAL_BYTES):
                            raise RuntimeError(f"unexpected content range {content_range!r}")
                        data = response.read()
                    if len(data) != expected_bytes:
                        raise RuntimeError(f"expected {expected_bytes} bytes, received {len(data)}")
                    output.write(data)
                    output.flush()
                    os.fsync(output.fileno())
                    position += len(data)
                    print(f"{position}/{TOTAL_BYTES}", flush=True)
                    break
                except Exception as exc:
                    if attempt == 10:
                        raise
                    print(f"retrying range {position}-{end}: {exc}", flush=True)
                    time.sleep(attempt)
    print("download-complete", flush=True)


if __name__ == "__main__":
    main()
