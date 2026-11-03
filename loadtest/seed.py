"""Seeds load-test CPs (LT-0001...) into the running local stack (deploy/docker-compose.yml).

Usage:  python loadtest/seed.py --cps 500

For each CP it:
  1. answers the credit-proposal and attributes lookups through WireMock's admin API (one templated
     stub for every LT-* id; not persisted, so a bank-stubs restart drops them);
  2. inserts two quarters of metrics into cp_metrics_demo (the demo MSSQL source);
  3. applies the 4 CP-1002 covenants (DSCR, leverage, interest cover, tangible net worth) DAILY through
     Core's API, so Core registers the Quartz jobs as it does in production.

Owners: rm-load (RM) and wrm-load (approver). Safe to re-run: existing CPs are skipped.
"""
from __future__ import annotations

import argparse
import json
import os
import random
import subprocess
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

CORE = "http://localhost:9310"
WIREMOCK = "http://localhost:9311"
MASTERS = [f"c0000000-0000-0000-0000-00000000000{i}" for i in range(1, 5)]
ADMIN = {"X-User": "admin-1", "X-Roles": "ADMIN"}
ENV_FILE = Path(__file__).resolve().parent.parent / "deploy" / ".env"


def cp_id(n: int) -> str:
    return f"LT-{n:04d}"


def http(method: str, url: str, body: object | None = None, headers: dict | None = None) -> tuple[int, str]:
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as err:
        return err.code, err.read().decode()


def stub_bank_services() -> None:
    cp = {
        "cpId": "{{request.pathSegments.[2]}}", "name": "Load Test {{request.pathSegments.[2]}}",
        "businessSegment": "CORPORATE", "sector": "MANUFACTURING", "purpose": "Load test",
        "approvalStatus": "APPROVED", "obligorId": "OBL-{{request.pathSegments.[2]}}", "tags": ["load-test"],
        "rm": {"userId": "rm-load", "name": "Load RM", "email": "rm-load@example.com"},
        "wrm": {"userId": "wrm-load", "name": "Load WRM", "email": "wrm-load@example.com"},
    }
    attrs = {"cpId": "{{request.pathSegments.[2]}}", "businessSegment": "CORPORATE", "sector": "MANUFACTURING",
             "attributes": {"ratingGrade": "A", "loanType": "TERM_LOAN", "facilityAmount": 500,
                            "facilityAmountUnit": "INR_CRORE", "tenorMonths": 60}}
    for path, body in (("credit-proposals", cp), ("attributes", attrs)):
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


def seed_metrics(count: int) -> None:
    rnd = random.Random(42)
    rows = []
    for n in range(1, count + 1):
        for as_of in ("2025-12-31", "2026-03-31"):
            # Mostly compliant, about one CP in six below a floor or above the leverage ceiling.
            weak = rnd.random() < 0.17
            rows.append(f"('{cp_id(n)}','{as_of}',{1.05 if weak else rnd.uniform(1.3, 2.5):.4f},"
                        f"{rnd.uniform(4.5, 6) if weak else rnd.uniform(1.5, 3.5):.4f},"
                        f"{rnd.uniform(1.8, 6):.4f},{rnd.uniform(500, 60000):.2f},1.0,1.0,1.2,1)")
    statements = ["DELETE FROM cp_metrics_demo WHERE cp_id LIKE 'LT-%';"]
    for i in range(0, len(rows), 500):  # SQL Server allows 1000 rows per VALUES list
        statements.append("INSERT INTO cp_metrics_demo (cp_id, as_of_date, dscr, net_leverage, interest_cover, "
                          "tnw_inr_cr, co2_intensity, crude_output_mt, recv_cover, export_licence_valid) VALUES "
                          + ",\n".join(rows[i:i + 500]) + ";")  # short lines: sqlcmd splits very long ones
    sql("\n".join(statements) + "\nGO\n")


def apply_covenants(n: int) -> str:
    cp = cp_id(n)
    status, text = http("GET", f"{CORE}/api/v1/credit-proposals/{cp}/applied-covenants", headers=ADMIN)
    if status == 200 and json.loads(text):
        return "skipped"
    # Start date defaults to today (past dates are refused), so run the sweep for tomorrow's date.
    body = {"items": [{"covenantMasterId": m, "frequency": "DAILY"} for m in MASTERS]}
    status, text = http("POST", f"{CORE}/api/v1/credit-proposals/{cp}/applied-covenants", body, ADMIN)
    return "applied" if status == 201 else f"failed {status}: {text[:160]}"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cps", type=int, default=500)
    parser.add_argument("--workers", type=int, default=8)
    args = parser.parse_args()

    stub_bank_services()
    seed_metrics(args.cps)
    with ThreadPoolExecutor(args.workers) as pool:
        results = list(pool.map(apply_covenants, range(1, args.cps + 1)))
    failed = [r for r in results if r.startswith("failed")]
    print(f"applied={results.count('applied')} skipped={results.count('skipped')} failed={len(failed)}")
    for line in failed[:5]:
        print(line)


if __name__ == "__main__":
    main()
