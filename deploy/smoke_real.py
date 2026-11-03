"""Smoke test for the "real" local stack (docker-compose.real.yml): sign-in, roles, service tokens, AI.

Usage:  python deploy/smoke_real.py

Gets real Keycloak tokens (password grant on the local-only covenant-cli client) and checks, through
the UI gateway on :8080, that Core and DPS accept them, refuse missing or wrong-role tokens, that DPS
reaches Core with its own client-credentials token, and that Ask AI answers from Azure OpenAI.
Reads the demo password from deploy/.env; prints no secret.
"""
from __future__ import annotations

import base64
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

GATEWAY = "http://localhost:8080"
TOKEN_URL = "http://localhost:8180/realms/covenant/protocol/openid-connect/token"
ENV = dict(line.split("=", 1) for line in (Path(__file__).resolve().parent / ".env").read_text().splitlines()
           if "=" in line and not line.lstrip().startswith("#"))
failures: list[str] = []


def token(user: str) -> str:
    form = urllib.parse.urlencode({"grant_type": "password", "client_id": "covenant-cli", "username": user,
                                   "password": ENV["KC_DEMO_PASSWORD"].strip(), "scope": "openid"}).encode()
    with urllib.request.urlopen(urllib.request.Request(TOKEN_URL, data=form), timeout=30) as resp:
        return json.load(resp)["access_token"]


def claims(jwt: str) -> dict:
    part = jwt.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))


def call(method: str, path: str, jwt: str | None = None, body: dict | None = None) -> tuple[int, object]:
    headers = {"Content-Type": "application/json"}
    if jwt:
        headers["Authorization"] = f"Bearer {jwt}"
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(GATEWAY + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            raw = resp.read()
            return resp.status, (json.loads(raw) if raw[:1] in (b"{", b"[") else raw)
    except urllib.error.HTTPError as err:
        return err.code, err.read()[:200]


def check(name: str, ok: bool, detail: str = "") -> None:
    print(f"{'PASS' if ok else 'FAIL'}  {name}{('  (' + detail + ')') if detail else ''}")
    if not ok:
        failures.append(name)


def main() -> None:
    rm, approver, support = token("rm-anil"), token("wrm-meera"), token("support-1")
    c = claims(rm)
    check("token issuer, audience, user and roles", c.get("iss") == "http://localhost:8180/realms/covenant"
          and "covenant-platform" in (c["aud"] if isinstance(c["aud"], list) else [c["aud"]])
          and c.get("preferred_username") == "rm-anil" and "RM" in c.get("roles", []),
          f"user={c.get('preferred_username')} roles={c.get('roles')}")

    check("Core refuses a request without a token", call("GET", "/api/v1/monitoring/summary")[0] == 401)
    status, summary = call("GET", "/api/v1/monitoring/summary", rm)
    check("Core accepts the RM's token", status == 200, f"HTTP {status}")
    check("RM sees own CP", call("GET", "/api/v1/credit-proposals/CP-1001/applied-covenants", rm)[0] == 200)
    check("RM cannot see another RM's CP",
          call("GET", "/api/v1/credit-proposals/CP-1008/applied-covenants", rm)[0] == 404)
    check("RM may not use the support toolkit", call("GET", "/api/v1/ops/audit?targetType=cp&targetId=CP-1001",
                                                     rm)[0] == 403)
    check("approver role reaches Core", call("GET", "/api/v1/monitoring/summary", approver)[0] == 200)

    status, _ = call("GET", "/dps/v1/documents?cpId=CP-1001", rm)
    check("DPS accepts the RM's token and checks CP access with Core (user token passed on)",
          status == 200, f"HTTP {status}")
    check("DPS refuses a request without a token", call("GET", "/dps/v1/documents?cpId=CP-1001")[0] == 401)
    status, jobs = call("GET", "/dps/v1/jobs?limit=5", support)
    check("DPS support role lists jobs", status == 200, f"HTTP {status}")

    status, recs = call("GET", "/api/v1/credit-proposals/CP-1001/onboarding-recommendations", rm)
    check("Core calls DPS with its client-credentials token (onboarding recommendations)",
          status == 200, f"HTTP {status}")

    started = time.time()
    status, ask = call("POST", "/dps/v1/ask", rm, {"question": "Which of my covenants are breached?"})
    method = ask.get("method") if isinstance(ask, dict) else None
    check("Ask AI answers with Azure OpenAI", status == 200 and method not in (None, "RULES"),
          f"HTTP {status}, method={method}, {time.time() - started:.1f}s")
    if isinstance(ask, dict):
        print("      answer:", (ask.get("answer") or "")[:160].replace("\n", " "))

    print(f"\n{'All checks passed' if not failures else f'{len(failures)} check(s) failed'}")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
