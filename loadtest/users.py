"""Creates the load-test users in the local Keycloak (docker-compose.real.yml): load-rm-01..NN (RM) and
load-wrm-01..NN (APPROVER), with the shared demo password KC_DEMO_PASSWORD from deploy/.env.

Usage:  python loadtest/users.py --rms 20

Local test accounts only: the admin call goes to the local Keycloak on :8180 with KC_ADMIN_PASSWORD from
deploy/.env. Re-runnable (existing users are kept). Prints no secret.
"""
from __future__ import annotations

import argparse
import json
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

KC = "http://localhost:8180"
ENV = dict(line.split("=", 1) for line in (Path(__file__).resolve().parent.parent / "deploy" / ".env")
           .read_text().splitlines() if "=" in line and not line.lstrip().startswith("#"))


def call(method: str, path: str, token: str | None = None, body: object | None = None,
         form: dict | None = None) -> tuple[int, object]:
    headers = {}
    data = None
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(KC + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read()
            return resp.status, json.loads(raw) if raw[:1] in (b"{", b"[") else None
    except urllib.error.HTTPError as err:
        return err.code, None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rms", type=int, default=20)
    args = parser.parse_args()
    status, tok = call("POST", "/realms/master/protocol/openid-connect/token", form={
        "grant_type": "password", "client_id": "admin-cli", "username": "admin",
        "password": ENV["KC_ADMIN_PASSWORD"].strip()})
    if status != 200:
        raise SystemExit(f"Keycloak admin sign-in failed: HTTP {status}")
    admin = tok["access_token"]
    roles = {r: call("GET", f"/admin/realms/covenant/roles/{r}", admin)[1] for r in ("RM", "APPROVER")}
    created = existing = 0
    for n in range(1, args.rms + 1):
        for name, role in ((f"load-rm-{n:02d}", "RM"), (f"load-wrm-{n:02d}", "APPROVER")):
            status, _ = call("POST", "/admin/realms/covenant/users", admin, {
                "username": name, "enabled": True, "email": f"{name}@covenant.local", "emailVerified": True,
                "firstName": "Load", "lastName": name,
                "credentials": [{"type": "password", "value": ENV["KC_DEMO_PASSWORD"].strip(), "temporary": False}]})
            if status == 409:
                existing += 1
            elif status == 201:
                created += 1
            else:
                raise SystemExit(f"Creating {name} failed: HTTP {status}")
            _, found = call("GET", f"/admin/realms/covenant/users?exact=true&username={name}", admin)
            uid = found[0]["id"]
            call("POST", f"/admin/realms/covenant/users/{uid}/role-mappings/realm", admin, [roles[role]])
    print(f"users created={created} existing={existing}")


if __name__ == "__main__":
    main()
