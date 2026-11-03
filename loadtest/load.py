"""Concurrent-user load test against the local stack's UI gateway (nginx on :8080).

Usage:  python loadtest/load.py --users 20 --seconds 60

Each virtual user repeats one session: the portfolio summary, analytics, a random CP's status audit,
review and applied-covenant pages, the covenant library, and (one session in five) the open-breach
report and its Excel export. Users act as rm-load, who owns the LT-* CPs from seed.py. Prints latency
percentiles and errors per endpoint.
"""
from __future__ import annotations

import argparse
import random
import statistics
import threading
import time
import urllib.error
import urllib.request
from collections import defaultdict

BASE = "http://localhost:8080"
HEADERS = {"X-User": "rm-load", "X-Roles": "RM"}


def session(cp_count: int, rnd: random.Random) -> list[tuple[str, str]]:
    cp = f"LT-{rnd.randint(1, cp_count):04d}"
    steps = [
        ("summary", "/api/v1/monitoring/summary"),
        ("analytics", "/api/v1/analytics/portfolio"),
        ("cp audit", f"/api/v1/monitoring/cp/{cp}/status-audit"),
        ("cp review", f"/api/v1/monitoring/cp/{cp}/review"),
        ("cp covenants", f"/api/v1/credit-proposals/{cp}/applied-covenants"),
        ("library", "/api/v1/covenants?search=&page=0&size=25"),
    ]
    if rnd.random() < 0.2:
        steps += [("open breaches", "/api/v1/reports/open-breaches?escalationDays=30"),
                  ("breaches xlsx", "/api/v1/reports/open-breaches.xlsx?escalationDays=30")]
    return steps


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--users", type=int, default=20)
    parser.add_argument("--seconds", type=int, default=60)
    parser.add_argument("--cps", type=int, default=700)
    args = parser.parse_args()

    timings: dict[str, list[float]] = defaultdict(list)
    errors: dict[str, list[str]] = defaultdict(list)
    lock = threading.Lock()
    stop = time.time() + args.seconds

    def user(seed: int) -> None:
        rnd = random.Random(seed)
        while time.time() < stop:
            for name, path in session(args.cps, rnd):
                started = time.perf_counter()
                try:
                    req = urllib.request.Request(BASE + path, headers=HEADERS)
                    with urllib.request.urlopen(req, timeout=60) as resp:
                        resp.read()
                    failure = None
                except urllib.error.HTTPError as err:
                    failure = str(err.code)
                except Exception as err:  # noqa: BLE001 - timeouts and resets count as errors
                    failure = type(err).__name__
                elapsed = (time.perf_counter() - started) * 1000
                with lock:
                    timings[name].append(elapsed)
                    if failure:
                        errors[name].append(failure)

    threads = [threading.Thread(target=user, args=(i,)) for i in range(args.users)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    total = sum(len(v) for v in timings.values())
    print(f"users={args.users} seconds={args.seconds} requests={total} rps={total / args.seconds:.1f}")
    print(f"{'endpoint':<15} {'n':>6} {'p50 ms':>8} {'p95 ms':>8} {'p99 ms':>8} {'max ms':>8} errors")
    for name, values in timings.items():
        values.sort()
        q = statistics.quantiles(values, n=100) if len(values) > 1 else values * 99
        errs = errors.get(name, [])
        detail = f"{len(errs)} ({', '.join(sorted(set(errs)))})" if errs else "0"
        print(f"{name:<15} {len(values):>6} {q[49]:>8.0f} {q[94]:>8.0f} {q[98]:>8.0f} {values[-1]:>8.0f} {detail}")


if __name__ == "__main__":
    main()
