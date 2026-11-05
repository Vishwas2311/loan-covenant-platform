"""Seeds load-test CPs into the running local stack (deploy/docker-compose.yml).

Usage:  python loadtest/seed.py --rms 20 --per-rm 60      (1,200 CPs LT-01001 ... LT-20060)

CP LT-RRNNN belongs to load-rm-RR (RM) and load-wrm-RR (approver); create those users first with
loadtest/users.py. With the real-sign-in stack (docker-compose.real.yml) the script signs in as admin-1
on the local-only covenant-cli client (KC_DEMO_PASSWORD from deploy/.env); in dev-header mode it sends
X-User headers.

For each CP it:
  1. answers the credit-proposal and attributes lookups through WireMock's admin API (one templated
     stub for every LT-* id; not persisted, so a bank-stubs restart drops them);
  2. inserts two quarters of metrics into cp_metrics_demo (the demo MSSQL source);
  3. applies the 4 CP-1002 covenants (DSCR, leverage, interest cover, tangible net worth) DAILY through
     Core's API, so Core registers the Quartz jobs as it does in production.

Safe to re-run: existing CPs are skipped.
"""
from __future__ import annotations

import argparse
import json
import os
import random
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

CORE = "http://localhost:9310"
WIREMOCK = "http://localhost:9311"
MASTERS = [f"c0000000-0000-0000-0000-00000000000{i}" for i in range(1, 5)]
ENV_FILE = Path(__file__).resolve().parent.parent / "deploy" / ".env"
TOKEN_URL = "http://localhost:8180/realms/covenant/protocol/openid-connect/token"


def env(name: str) -> str:
    return next(line.split("=", 1)[1].strip() for line in ENV_FILE.read_text().splitlines()
                if line.startswith(f"{name}="))


def admin_headers() -> dict:
    """Bearer token for admin-1 when Keycloak runs, else the dev headers."""
    try:
        form = urllib.parse.urlencode({"grant_type": "password", "client_id": "covenant-cli", "username": "admin-1",
                                       "password": env("KC_DEMO_PASSWORD"), "scope": "openid"}).encode()
        with urllib.request.urlopen(urllib.request.Request(TOKEN_URL, data=form), timeout=10) as resp:
            return {"Authorization": "Bearer " + json.load(resp)["access_token"]}
    except (OSError, StopIteration, KeyError):
        return {"X-User": "admin-1", "X-Roles": "ADMIN"}


ADMIN: dict = {}


def cp_id(rm: int, n: int) -> str:
    return f"LT-{rm:02d}{n:03d}"


def http(method: str, url: str, body: object | None = None, headers: dict | None = None) -> tuple[int, str]:
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as err:
        return err.code, err.read().decode()


def stub_bank_services(rms: int) -> None:
    for rm in range(1, rms + 1):
        cp = {
            "cpId": "{{request.pathSegments.[2]}}", "name": "Load Test {{request.pathSegments.[2]}}",
            "businessSegment": "CORPORATE", "sector": "MANUFACTURING", "purpose": "Load test",
            "approvalStatus": "APPROVED", "obligorId": "OBL-{{request.pathSegments.[2]}}", "tags": ["load-test"],
            "rm": {"userId": f"load-rm-{rm:02d}", "name": "Load RM", "email": f"load-rm-{rm:02d}@example.com"},
            "wrm": {"userId": f"load-wrm-{rm:02d}", "name": "Load WRM", "email": f"load-wrm-{rm:02d}@example.com"},
        }
        mapping = {
            "priority": 1,
            "request": {"method": "GET", "urlPathPattern": f"/api/credit-proposals/LT-{rm:02d}[0-9]{{3}}"},
            "response": {"status": 200, "headers": {"Content-Type": "application/json"},
                         "jsonBody": cp, "transformers": ["response-template"]},
        }
        status, text = http("POST", f"{WIREMOCK}/__admin/mappings", mapping)
        if status != 201:
            sys.exit(f"WireMock stub for RM {rm} failed: {status} {text[:200]}")
    attrs = {"cpId": "{{request.pathSegments.[2]}}", "businessSegment": "CORPORATE", "sector": "MANUFACTURING",
             "attributes": {"ratingGrade": "A", "loanType": "TERM_LOAN", "facilityAmount": 500,
                            "facilityAmountUnit": "INR_CRORE", "tenorMonths": 60}}
    for path, body in (("attributes", attrs),):
        mapping = {
            "priority": 1,
            "request": {"method": "GET", "urlPathPattern": f"/api/{path}/LT-[0-9]+"},
            "response": {"status": 200, "headers": {"Content-Type": "application/json"},
                         "jsonBody": body, "transformers": ["response-template"]},
        }
        status, text = http("POST", f"{WIREMOCK}/__admin/mappings", mapping)
        if status != 201:
            sys.exit(f"WireMock stub for {path} failed: {status} {text[:200]}")


def sql(statement: str) -> None:
    password = next(line.split("=", 1)[1].strip() for line in ENV_FILE.read_text().splitlines()
                    if line.startswith("MSSQL_SA_PASSWORD="))
    # The password travels in this process's environment (docker exec -e NAME copies it), never on a
    # command line that an error message could print.
    env = {**os.environ, "SQLCMDPASSWORD": password}
    result = subprocess.run(["docker", "exec", "-i", "-e", "SQLCMDPASSWORD", "covenant-platform-sqlserver-1",
                             "/opt/mssql-tools18/bin/sqlcmd", "-C", "-b", "-S", "localhost", "-U", "sa",
                             "-d", "covenant"],
                            input=statement.encode(), env=env, capture_output=True)
    if result.returncode != 0:
        sys.exit("SQL failed: " + (result.stdout + result.stderr).decode(errors="replace")[:500])


def seed_metrics(ids: list[str]) -> None:
    rnd = random.Random(42)
    rows = []
    for cp in ids:
        for as_of in ("2025-12-31", "2026-03-31"):
            # Mostly compliant, about one CP in six below a floor or above the leverage ceiling.
            weak = rnd.random() < 0.17
            rows.append(f"('{cp}','{as_of}',{1.05 if weak else rnd.uniform(1.3, 2.5):.4f},"
                        f"{rnd.uniform(4.5, 6) if weak else rnd.uniform(1.5, 3.5):.4f},"
                        f"{rnd.uniform(1.8, 6):.4f},{rnd.uniform(500, 60000):.2f},1.0,1.0,1.2,1)")
    statements = ["DELETE FROM cp_metrics_demo WHERE cp_id LIKE 'LT-%';"]
    for i in range(0, len(rows), 500):  # SQL Server allows 1000 rows per VALUES list
        statements.append("INSERT INTO cp_metrics_demo (cp_id, as_of_date, dscr, net_leverage, interest_cover, "
                          "tnw_inr_cr, co2_intensity, crude_output_mt, recv_cover, export_licence_valid) VALUES "
                          + ",\n".join(rows[i:i + 500]) + ";")  # short lines: sqlcmd splits very long ones
    sql("\n".join(statements) + "\nGO\n")


def apply_covenants(cp: str) -> str:
    status, text = http("GET", f"{CORE}/api/v1/credit-proposals/{cp}/applied-covenants", headers=ADMIN)
    if status == 401:  # the 5-minute token ran out during a long seed
        ADMIN.update(admin_headers())
        status, text = http("GET", f"{CORE}/api/v1/credit-proposals/{cp}/applied-covenants", headers=ADMIN)
    if status == 200 and json.loads(text):
        return "skipped"
    # Start date defaults to today (past dates are refused), so run the sweep for tomorrow's date.
    body = {"items": [{"covenantMasterId": m, "frequency": "DAILY"} for m in MASTERS]}
    status, text = http("POST", f"{CORE}/api/v1/credit-proposals/{cp}/applied-covenants", body, ADMIN)
    return "applied" if status == 201 else f"failed {status}: {text[:160]}"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rms", type=int, default=20)
    parser.add_argument("--per-rm", type=int, default=60)
    parser.add_argument("--workers", type=int, default=8)
    args = parser.parse_args()

    ADMIN.update(admin_headers())
    ids = [cp_id(rm, n) for rm in range(1, args.rms + 1) for n in range(1, args.per_rm + 1)]
    stub_bank_services(args.rms)
    seed_metrics(ids)
    with ThreadPoolExecutor(args.workers) as pool:
        results = list(pool.map(apply_covenants, ids))
    failed = [r for r in results if r.startswith("failed")]
    print(f"applied={results.count('applied')} skipped={results.count('skipped')} failed={len(failed)}")
    for line in failed[:5]:
        print(line)


if __name__ == "__main__":
    main()
