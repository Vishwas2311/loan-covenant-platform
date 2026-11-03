"""Upload burst against DPS through the UI gateway: N agreements at once, then waits for every job.

Usage:  python loadtest/uploads.py --count 50

Uses the golden-set agreements, each made unique with a trailing PDF comment so DPS's content
de-duplication does not skip it, uploaded to the LT-* CPs as rm-load (owner, from seed.py). Prints
upload latency, queue drain time and the outcome of every job.
"""
from __future__ import annotations

import argparse
import json
import statistics
import time
import urllib.request
import uuid
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

BASE = "http://localhost:8080"
HEADERS = {"X-User": "rm-load", "X-Roles": "RM"}
DOCS = sorted((Path(__file__).resolve().parent.parent / "golden-set" / "documents").glob("AGR-*.pdf"))
TERMINAL = {"DONE", "NEEDS_REVIEW", "FAILED", "DEAD"}


def upload(n: int) -> tuple[str, float]:
    source = DOCS[n % len(DOCS)]
    data = source.read_bytes() + f"\n% load-test {uuid.uuid4()}\n".encode()
    boundary = uuid.uuid4().hex
    parts = [
        ("cpIds", f"LT-{n + 1:04d}".encode()),
        ("type", b"LOAN_AGREEMENT"),
    ]
    body = b"".join(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n".encode() + v + b"\r\n"
                    for k, v in parts)
    body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"load-{n}-{source.name}\"\r\n"
             "Content-Type: application/pdf\r\n\r\n").encode() + data + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(f"{BASE}/dps/v1/documents", data=body, method="POST",
                                 headers={**HEADERS, "Content-Type": f"multipart/form-data; boundary={boundary}"})
    started = time.perf_counter()
    # Same bytes on a retry, so DPS's content de-duplication returns the first upload's job.
    job_id = read_json(req, timeout=120)["jobId"]
    return job_id, (time.perf_counter() - started) * 1000


def read_json(req: urllib.request.Request, timeout: int) -> dict:
    for attempt in range(3):  # Docker Desktop's Windows port forward resets the odd connection
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return json.load(resp)
        except ConnectionError:
            if attempt == 2:
                raise
            time.sleep(1)
    raise AssertionError("unreachable")


def job(job_id: str) -> dict:
    return read_json(urllib.request.Request(f"{BASE}/dps/v1/jobs/{job_id}", headers=HEADERS), timeout=30)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--count", type=int, default=50)
    parser.add_argument("--timeout", type=int, default=3600)
    args = parser.parse_args()

    started = time.time()
    with ThreadPoolExecutor(10) as pool:
        uploaded = list(pool.map(upload, range(args.count)))
    upload_ms = sorted(ms for _, ms in uploaded)
    print(f"uploaded={len(uploaded)} in {time.time() - started:.1f}s; "
          f"upload p50={statistics.median(upload_ms):.0f} ms max={upload_ms[-1]:.0f} ms")

    pending = {job_id for job_id, _ in uploaded}
    finished: dict[str, dict] = {}
    while pending and time.time() - started < args.timeout:
        for job_id in list(pending):
            state = job(job_id)
            if state["status"] in TERMINAL:
                finished[job_id] = state
                pending.discard(job_id)
        if pending:
            time.sleep(5)
    drained = time.time() - started
    seconds = [s.get("seconds", 0) for j in finished.values() for s in j["steps"] if s.get("step") == "END"]
    print(f"drained={len(finished)}/{len(uploaded)} in {drained:.0f}s "
          f"({len(finished) / drained * 60:.1f} documents/min); still pending={len(pending)}")
    if seconds:
        print(f"per-job processing p50={statistics.median(seconds):.1f}s max={max(seconds):.1f}s")
    print("outcomes:", dict(Counter(j["status"] for j in finished.values())),
          "pools:", dict(Counter(j["pool"] for j in finished.values())))


if __name__ == "__main__":
    main()
