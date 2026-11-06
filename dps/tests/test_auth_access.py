from __future__ import annotations

import time

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi.testclient import TestClient

from dps.access import AccessCheckUnavailable, CoreCpAccessChecker, require_cp
from dps.auth import Authenticator, Principal
from dps.errors import ApiError
from dps.main import create_app
from dps.settings import AuthMode, Settings
from dps.storage import FileStore

KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
PUB = KEY.public_key().public_bytes(serialization.Encoding.PEM,
                                    serialization.PublicFormat.SubjectPublicKeyInfo).decode()
ISS, AUD = "https://issuer.test/v2.0", "api://dps"


def token(user="rm-anil", roles=("RM",), iss=ISS, aud=AUD, exp_in=300, key=KEY, iat_in=0):
    now = int(time.time()) + iat_in
    claims = {"iss": iss, "aud": aud, "sub": "sub-" + user, "preferred_username": user,
              "roles": list(roles), "iat": now, "exp": now - iat_in + exp_in}
    return jwt.encode(claims, key, algorithm="RS256")


@pytest.fixture
def jwt_client(tmp_path):
    s = Settings(database_url=f"sqlite:///{tmp_path / 'j.db'}", storage_dir=tmp_path / "st",
                 auth_mode=AuthMode.JWT, jwt_issuer=ISS, jwt_audience=AUD, jwt_public_key_pem=PUB)
    from conftest import FakeChecker
    return TestClient(create_app(s, cp_checker=FakeChecker({}), create_schema=True))


def test_jwt_valid_token(jwt_client):
    r = jwt_client.get("/dps/v1/catalog", headers={"Authorization": "Bearer " + token()})
    assert r.status_code == 200


@pytest.mark.parametrize("bad", [
    token(iss="https://evil"), token(aud="other"), token(exp_in=-120),
    token(key=rsa.generate_private_key(public_exponent=65537, key_size=2048)), "garbage",
])
def test_jwt_rejects_bad_tokens(jwt_client, bad):
    assert jwt_client.get("/dps/v1/catalog", headers={"Authorization": "Bearer " + bad}).status_code == 401


@pytest.mark.parametrize("skew", [-10, 10])
def test_jwt_allows_a_small_clock_difference(jwt_client, skew):
    # Issued "in the future" or expired seconds ago: the identity provider's clock is a little off.
    t = token(iat_in=skew) if skew > 0 else token(exp_in=skew)
    assert jwt_client.get("/dps/v1/catalog", headers={"Authorization": "Bearer " + t}).status_code == 200


def test_jwt_ignores_dev_headers(jwt_client):
    assert jwt_client.get("/dps/v1/catalog", headers={"X-User": "rm-anil"}).status_code == 401


def test_jwt_roles_reach_authorisation(jwt_client):
    req = {"cpId": "CP-1", "covenants": []}
    assert jwt_client.post("/dps/v1/ai-suggestions", json=req,
                           headers={"Authorization": "Bearer " + token(roles=("service",))}).status_code == 200


def test_jwt_mode_needs_key_material():
    with pytest.raises(RuntimeError, match="JWKS"):
        Authenticator(Settings(auth_mode=AuthMode.JWT, jwt_issuer=ISS, jwt_audience=AUD))


@pytest.mark.parametrize("issuer, audience", [("", ""), (ISS, ""), ("", AUD)])
def test_jwt_mode_needs_issuer_and_audience(issuer, audience):
    with pytest.raises(RuntimeError, match="ISSUER and DPS_JWT_AUDIENCE"):
        Authenticator(Settings(auth_mode=AuthMode.JWT, jwt_issuer=issuer, jwt_audience=audience,
                               jwt_jwks_url="https://login.example/keys"))


def test_dev_header_mode_refused_in_prod():
    with pytest.raises(RuntimeError, match="DPS_ENV=prod"):
        Authenticator(Settings(auth_mode=AuthMode.DEV_HEADER, env="prod"))
    Authenticator(Settings(auth_mode=AuthMode.DEV_HEADER, env="dev"))


def test_jwt_mode_with_jwks_url_builds():
    Authenticator(Settings(auth_mode=AuthMode.JWT, jwt_issuer=ISS, jwt_audience=AUD,
                           jwt_jwks_url="https://login.example/keys"))


def test_token_without_user_rejected(jwt_client):
    now = int(time.time())
    t = jwt.encode({"iss": ISS, "aud": AUD, "iat": now, "exp": now + 60}, KEY, algorithm="RS256")
    assert jwt_client.get("/dps/v1/catalog", headers={"Authorization": "Bearer " + t}).status_code == 401


def test_string_roles_claim(jwt_client):
    now = int(time.time())
    t = jwt.encode({"iss": ISS, "aud": AUD, "iat": now, "exp": now + 60, "preferred_username": "c",
                    "roles": "SERVICE"}, KEY, algorithm="RS256")
    r = jwt_client.post("/dps/v1/ai-suggestions", json={"cpId": "x", "covenants": []},
                        headers={"Authorization": "Bearer " + t})
    assert r.status_code == 200


# ---------- Core-delegated CP access ----------

def checker_with(handler):
    return CoreCpAccessChecker("http://core", 1.0, client=httpx.Client(
        base_url="http://core", transport=httpx.MockTransport(handler)))


def test_core_checker_forwards_token_and_reads_status():
    seen = {}

    def handler(req: httpx.Request) -> httpx.Response:
        seen["auth"] = req.headers.get("Authorization")
        return httpx.Response(200 if req.url.path.endswith("/CP-1") else 404)

    c = checker_with(handler)
    p = Principal("rm-anil", frozenset({"RM"}), token="tok")
    assert c.allowed(p, "CP-1", True) is True
    assert seen["auth"] == "Bearer tok"
    assert c.allowed(p, "CP-2", True) is False


def test_core_checker_dev_headers_bypass_and_errors():
    seen = {}

    def handler(req: httpx.Request) -> httpx.Response:
        seen.update(user=req.headers.get("X-User"), roles=req.headers.get("X-Roles"))
        return httpx.Response(200)

    c = checker_with(handler)
    assert c.allowed(Principal("rm-anil", frozenset({"RM"})), "CP-1", False)
    assert seen == {"user": "rm-anil", "roles": "RM"}
    assert c.allowed(Principal("adm", frozenset({"ADMIN"})), "CP-X", False)
    assert c.allowed(Principal("op", frozenset({"SUPPORT"})), "CP-X", True)
    assert c.allowed(Principal("aud", frozenset({"AUDITOR"})), "CP-X", True)
    assert not c.allowed(Principal("rm", frozenset({"RM"})), " ", True)

    def boom(req):
        raise httpx.ConnectError("down")

    with pytest.raises(AccessCheckUnavailable):  # Core unreachable is not a "no" (review BUG-07)
        checker_with(boom).allowed(Principal("rm", frozenset({"RM"})), "CP-1", True)


def test_core_checker_caches_grants_not_refusals_and_treats_5xx_as_unavailable():
    calls = []
    answers = iter([200, 404, 404, 503])

    def handler(req: httpx.Request) -> httpx.Response:
        calls.append(req.url.path)
        return httpx.Response(next(answers))

    c = checker_with(handler)
    rm = Principal("rm-anil", frozenset({"RM"}))
    assert c.allowed(rm, "CP-1", True) and c.allowed(rm, "CP-1", True)  # the second answer comes from the cache
    assert not c.allowed(rm, "CP-2", True) and not c.allowed(rm, "CP-2", True)  # refusals are asked again
    assert len(calls) == 3
    with pytest.raises(AccessCheckUnavailable):
        c.allowed(rm, "CP-3", True)
    c.MAX_ENTRIES = 1
    c._granted[("x",)] = 0.0  # an expired grant is pruned when the cache is full
    answers2 = iter([200])
    c._client = httpx.Client(transport=httpx.MockTransport(lambda r: httpx.Response(next(answers2))),
                             base_url="http://core")
    assert c.allowed(rm, "CP-4", False)


def test_require_cp_answers_503_when_core_cannot_answer():
    class Down:
        def allowed(self, principal, cp_id, read_only):
            raise AccessCheckUnavailable

    with pytest.raises(ApiError) as err:
        require_cp(Down(), Principal("rm", frozenset({"RM"})), "CP-1")
    assert err.value.status == 503 and err.value.code == "ACCESS_CHECK_UNAVAILABLE"


def test_file_store_rejects_escape(tmp_path):
    store = FileStore(tmp_path)
    digest, rel = store.put(b"abc", ".pdf")
    assert store.path(rel).read_bytes() == b"abc"
    assert store.put(b"abc", ".pdf") == (digest, rel)
    with pytest.raises(ValueError):
        store.path("../outside.pdf")


def test_dps_refuses_sql_server_without_snapshot_isolation():
    """GAP-11: like Core, DPS will not run on SQL Server with READ_COMMITTED_SNAPSHOT off."""
    from unittest.mock import MagicMock

    from dps.db import make_engine, require_snapshot_isolation

    require_snapshot_isolation(make_engine("sqlite://"))  # other databases are not checked

    def engine(snapshot_on):
        e = MagicMock()
        e.dialect.name = "mssql"
        conn = e.connect.return_value.__enter__.return_value
        conn.execute.return_value.scalar.side_effect = [snapshot_on, "dps"]
        return e

    require_snapshot_isolation(engine(True))
    with pytest.raises(RuntimeError, match=r"ALTER DATABASE \[dps\] SET READ_COMMITTED_SNAPSHOT ON"):
        require_snapshot_isolation(engine(False))
