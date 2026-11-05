"""Step and soak load test for the real-sign-in stack (docker-compose.real.yml), through the UI gateway.

Usage:
  python loadtest/scale.py --steps 10,25,50,100,150,200 --step-seconds 120 --think 0 --out stress.json
  python loadtest/scale.py --steps 300 --step-seconds 1800 --think 3-8 --out soak.json

Each virtual user signs in as one of the load accounts (load-rm-NN, load-wrm-NN from loadtest/users.py,
plus admin-1 and auditor-1 for the bank-wide views) with a real Keycloak token, refreshed before it
expires, and repeats a mix of the requests people make: about 85% reads (portfolio, CP list, a CP's
review grid and status history, tasks, schedule, library, documents, alerts, analytics, sometimes the
open-breach report) and 15% writes (waiver request then withdraw, a task note, an Excel export, a
small statement upload). Ask AI is left out: it calls Azure OpenAI and is rate-limited per user.

--think 0 sends the next request as soon as the last one answers (stress); --think 3-8 waits 3 to 8
seconds between requests like a person reading. Every 10 s it samples container CPU and memory and
Core's database pool and heap (actuator on :9320). Writes a JSON report; prints no secret.
"""
from __future__ import annotations

import argparse
import json
import random
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from collections import defaultdict
from datetime import date, timedelta
from pathlib import Path

GATEWAY = "http://localhost:8080"
TOKEN_URL = "http://localhost:8180/realms/covenant/protocol/openid-connect/token"
ACTUATOR = "http://localhost:9320/actuator/metrics/"
ENV = dict(line.split("=", 1) for line in (Path(__file__).resolve().parent.parent / "deploy" / ".env")
           .read_text().splitlines() if "=" in line and not line.lstrip().startswith("#"))


class Tokens:
    """One token per account, renewed a minute before its 5-minute lifetime ends."""

    def __init__(self) -> None:
        self._tokens: dict[str, tuple[str, float]] = {}
        self._lock = threading.Lock()

    def get(self, user: str) -> str:
        with self._lock:
            tok, until = self._tokens.get(user, ("", 0.0))
            if time.time() < until:
                return tok
        form = urllib.parse.urlencode({"grant_type": "password", "client_id": "covenant-cli", "username": user,
                                       "password": ENV["KC_DEMO_PASSWORD"].strip(), "scope": "openid"}).encode()
        with urllib.request.urlopen(urllib.request.Request(TOKEN_URL, data=form), timeout=30) as resp:
            body = json.load(resp)
        with self._lock:
            self._tokens[user] = (body["access_token"], time.time() + body.get("expires_in", 300) - 60)
        return body["access_token"]


class Stats:
    def __init__(self) -> None:
        self.lat: dict[str, list[float]] = defaultdict(list)
        self.errors: dict[str, int] = defaultdict(int)
        self.lock = threading.Lock()

    def add(self, name: str, status: int, seconds: float) -> None:
        with self.lock:
            self.lat[name].append(seconds)
            if status >= 400 or status == 0:
                self.errors[f"{name} {status}"] += 1


def request(method: str, path: str, token: str, body: bytes | None = None, ctype: str | None = None,
            timeout: float = 120) -> tuple[int, bytes, float]:
    headers = {"Authorization": f"Bearer {token}"}
    if ctype:
        headers["Content-Type"] = ctype
    req = urllib.request.Request(GATEWAY + path, data=body, method=method, headers=headers)
    t = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read(), time.perf_counter() - t
    except urllib.error.HTTPError as err:
        return err.code, err.read(), time.perf_counter() - t
    except Exception:  # noqa: BLE001 - a refused or reset connection counts as an error
        return 0, b"", time.perf_counter() - t


def jsonish(raw: bytes):
    try:
        return json.loads(raw)
    except ValueError:
        return None


class VirtualUser(threading.Thread):
    def __init__(self, n: int, user: str, cps: list[str], tokens: Tokens, stats: Stats, stop: threading.Event,
                 think: tuple[float, float]) -> None:
        super().__init__(daemon=True)
        self.user, self.cps, self.tokens, self.stats, self.stop, self.think = user, cps, tokens, stats, stop, think
        self.rnd = random.Random(n)
        self.breached: dict[str, list[str]] = {}
        self.tasks: list[str] = []

    def call(self, name: str, method: str, path: str, **kw) -> tuple[int, bytes]:
        status, raw, dt = request(method, path, self.tokens.get(self.user), **kw)
        self.stats.add(name, status, dt)
        return status, raw

    def cp(self) -> str:
        return self.rnd.choice(self.cps) if self.cps else "LT-01001"

    def step(self) -> None:
        r = self.rnd.random()
        today = date.today()
        if r < 0.12:
            self.call("summary", "GET", "/api/v1/monitoring/summary")
        elif r < 0.22:
            self.call("cps", "GET", "/api/v1/analytics/cps")
        elif r < 0.40:
            cp = self.cp()
            status, raw = self.call("review", "GET", f"/api/v1/monitoring/cp/{cp}/review")
            rows = jsonish(raw) or []
            self.breached[cp] = [x["appliedCovenantId"] for x in rows if isinstance(x, dict)
                                 and x.get("latestResult") == "BREACHED" and not x.get("approvalStatus")]
        elif r < 0.47:
            self.call("history", "GET", f"/api/v1/monitoring/cp/{self.cp()}/status-audit")
        elif r < 0.55:
            status, raw = self.call("tasks", "GET", "/api/v1/tasks?scope=mine&open=true")
            self.tasks = [t["id"] for t in (jsonish(raw) or [])[:20] if isinstance(t, dict)]
        elif r < 0.60:
            self.call("schedule", "GET", f"/api/v1/monitoring/schedule?from={today}&to={today + timedelta(days=30)}")
        elif r < 0.66:
            self.call("library", "GET", f"/api/v1/covenants?page={self.rnd.randint(0, 1)}&size=25")
        elif r < 0.72:
            self.call("documents", "GET", f"/dps/v1/documents?cpId={self.cp()}")
        elif r < 0.77:
            self.call("alerts", "GET", "/api/v1/alerts")
        elif r < 0.81:
            self.call("analytics", "GET", "/api/v1/analytics/portfolio")
        elif r < 0.85:
            self.call("breaches", "GET", "/api/v1/reports/open-breaches?escalationDays=14")
        elif r < 0.90:
            # Write: a waiver request on one of this user's breached covenants, withdrawn straight after.
            cp = next((c for c, ids in self.breached.items() if ids), None)
            if cp and self.user.startswith("load-rm"):
                ac = self.breached[cp].pop()
                body = json.dumps({"status": "WAIVER", "note": "load test"}).encode()
                status, _ = self.call("waiver", "PUT", f"/api/v1/monitoring/cp/{cp}/covenants/{ac}/status",
                                      body=body, ctype="application/json")
                if status == 200:
                    self.call("withdraw", "POST", f"/api/v1/monitoring/cp/{cp}/covenants/{ac}/status/withdraw",
                              body=b'{"note":"load test"}', ctype="application/json")
            else:
                self.call("summary", "GET", "/api/v1/monitoring/summary")
        elif r < 0.94:
            if self.tasks:
                body = json.dumps({"note": f"load test {self.rnd.randint(1, 999)}"}).encode()
                self.call("task-note", "PATCH", f"/api/v1/tasks/{self.rnd.choice(self.tasks)}", body=body,
                          ctype="application/json")
            else:
                self.call("tasks", "GET", "/api/v1/tasks?scope=mine&open=true")
        elif r < 0.98:
            self.call("excel", "GET", "/api/v1/reports/open-breaches.xlsx?escalationDays=14")
        else:
            if self.user.startswith("load-"):
                boundary = uuid.uuid4().hex
                csv = (f"Amounts in INR crore,\nEBITDA,{self.rnd.randint(300, 900)}\nTotal equity,2000\n"
                       f"Prepared,{uuid.uuid4()}\n").encode()
                parts = [f'--{boundary}\r\nContent-Disposition: form-data; name="cpIds"\r\n\r\n{self.cp()}\r\n',
                         f'--{boundary}\r\nContent-Disposition: form-data; name="type"\r\n\r\nFINANCIAL_STATEMENT\r\n',
                         f'--{boundary}\r\nContent-Disposition: form-data; name="asOfDate"\r\n\r\n2026-09-30\r\n']
                body = "".join(parts).encode() + (
                    f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="load.csv"\r\n'
                    f"Content-Type: text/csv\r\n\r\n").encode() + csv + f"\r\n--{boundary}--\r\n".encode()
                self.call("upload", "POST", "/dps/v1/documents", body=body,
                          ctype=f"multipart/form-data; boundary={boundary}")
            else:
                self.call("cps", "GET", "/api/v1/analytics/cps")

    def run(self) -> None:
        while not self.stop.is_set():
            try:
                self.step()
            except Exception:  # noqa: BLE001 - a token or client error must not end the user
                self.stats.add("client", 0, 0)
            if self.think[1] > 0:
                self.stop.wait(self.rnd.uniform(*self.think))


def sample(stop: threading.Event, out: list) -> None:
    while not stop.is_set():
        row = {"t": time.strftime("%H:%M:%S")}
        try:
            text = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{.Name}},{{.CPUPerc}},{{.MemUsage}}"],
                                  capture_output=True, text=True, timeout=30).stdout
            for line in text.splitlines():
                name, cpu, mem = line.split(",", 2)
                if any(k in name for k in ("core-1", "sqlserver", "dps-api", "dps-worker-1", "ui-1", "keycloak")):
                    row[name.replace("covenant-platform-", "")] = {"cpu": float(cpu.rstrip("%") or 0),
                                                                    "mem": mem.split(" / ")[0]}
        except Exception:  # noqa: BLE001
            pass
        for metric in ("hikaricp.connections.active", "hikaricp.connections.pending", "jvm.memory.used"):
            try:
                with urllib.request.urlopen(ACTUATOR + metric, timeout=5) as resp:
                    row[metric] = json.load(resp)["measurements"][0]["value"]
            except Exception:  # noqa: BLE001
                pass
        out.append(row)
        stop.wait(10)


def accounts(rms: int) -> list[tuple[str, list[str]]]:
    out = []
    for n in range(1, rms + 1):
        own = [f"LT-{n:02d}{i:03d}" for i in range(1, 61)]
        out.append((f"load-rm-{n:02d}", own))
        out.append((f"load-wrm-{n:02d}", own))
    every = [f"LT-{n:02d}{i:03d}" for n in range(1, rms + 1) for i in range(1, 61)]
    out.append(("admin-1", every))
    out.append(("auditor-1", every))
    return out


def summarise(stats: Stats, seconds: float) -> dict:
    total = sum(len(v) for v in stats.lat.values())
    per = {}
    for name, values in sorted(stats.lat.items()):
        values = sorted(values)

        def q(f: float, v=values) -> int:
            return round(v[min(len(v) - 1, int(len(v) * f))] * 1000)
        per[name] = {"n": len(values), "p50": q(0.5), "p95": q(0.95), "p99": q(0.99), "max": q(1.0)}
    errors = sum(stats.errors.values())
    return {"requests": total, "rps": round(total / seconds, 1), "errors": errors,
            "error_rate": round(errors / total, 4) if total else 0, "errors_by": dict(stats.errors), "per": per}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--steps", default="10,25,50,100")
    parser.add_argument("--step-seconds", type=int, default=120)
    parser.add_argument("--think", default="0", help="seconds between requests: 0, or a range like 3-8")
    parser.add_argument("--rms", type=int, default=20)
    parser.add_argument("--out", default="loadtest-result.json")
    args = parser.parse_args()
    think = tuple(float(x) for x in args.think.split("-")) if "-" in args.think else (0.0, 0.0)
    pool = accounts(args.rms)
    tokens = Tokens()
    for user, _ in pool:
        tokens.get(user)
    report = {"think": args.think, "steps": []}
    for users in (int(x) for x in args.steps.split(",")):
        stats, stop, samples = Stats(), threading.Event(), []
        sampler = threading.Thread(target=sample, args=(stop, samples), daemon=True)
        sampler.start()
        vus = [VirtualUser(i, *pool[i % len(pool)], tokens, stats, stop, think) for i in range(users)]
        started = time.perf_counter()
        for i, vu in enumerate(vus):
            vu.start()
            if think[1] > 0:
                time.sleep(min(0.05, think[0] / max(1, users)))  # spread the start, like people arriving
        stop.wait(args.step_seconds)
        stop.set()
        for vu in vus:
            vu.join(timeout=150)
        result = summarise(stats, time.perf_counter() - started)
        result.update({"users": users, "seconds": args.step_seconds, "samples": samples})
        report["steps"].append(result)
        worst = max(result["per"].items(), key=lambda kv: kv[1]["p95"]) if result["per"] else ("-", {"p95": 0})
        print(f"{users:4d} users: {result['rps']:7.1f} req/s, {result['requests']} requests, errors "
              f"{result['errors']} ({result['error_rate']:.2%}), slowest p95 {worst[0]} {worst[1]['p95']} ms",
              flush=True)
        Path(args.out).write_text(json.dumps(report, indent=1))
        time.sleep(15)  # let queues drain between steps


if __name__ == "__main__":
    main()
