from __future__ import annotations

from urllib.parse import parse_qs

import httpx
import pytest

from dps.core_client import CoreClient, CoreUnavailable
from dps.service_auth import ClientCredentialsToken, TokenError
from dps.settings import Settings

TOKEN_URL = "https://login.example/tenant/oauth2/v2.0/token"


class Clock:
    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now


def token_endpoint(responses: list[httpx.Response], seen: list[dict]):
    def handler(request: httpx.Request) -> httpx.Response:
        seen.append({k: v[0] for k, v in parse_qs(request.content.decode()).items()})
        return responses.pop(0)
    return httpx.Client(transport=httpx.MockTransport(handler))


def provider(client: httpx.Client, clock: Clock) -> ClientCredentialsToken:
    return ClientCredentialsToken(TOKEN_URL, "dps-app", "s3cret", "api://covenant/.default", client=client,
                                  clock=clock)


def test_fetches_caches_and_refreshes_before_expiry():
    seen: list[dict] = []
    clock = Clock()
    client = token_endpoint([httpx.Response(200, json={"access_token": "t1", "expires_in": 3600}),
                             httpx.Response(200, json={"access_token": "t2", "expires_in": 3600})], seen)
    p = provider(client, clock)

    assert p.token() == "t1"
    clock.now += 3000
    assert p.token() == "t1"
    assert len(seen) == 1
    assert seen[0] == {"grant_type": "client_credentials", "client_id": "dps-app", "client_secret": "s3cret",
                       "scope": "api://covenant/.default"}
    clock.now += 560  # 59 min 20 s: inside the 60 s refresh margin
    assert p.token() == "t2"


def test_missing_expiry_defaults_to_five_minutes():
    seen: list[dict] = []
    clock = Clock()
    p = provider(token_endpoint([httpx.Response(200, json={"access_token": "t1"}),
                                 httpx.Response(200, json={"access_token": "t2"})], seen), clock)
    p.token()
    clock.now += 241
    assert p.token() == "t2"


@pytest.mark.parametrize("response, message", [
    (httpx.Response(401, text="invalid_client s3cret"), "HTTP 401"),
    (httpx.Response(200, json={}), "no access_token"),
])
def test_refused_or_empty_responses_fail_without_echoing_the_secret(response, message):
    p = provider(token_endpoint([response], []), Clock())
    with pytest.raises(TokenError, match=message) as err:
        p.token()
    assert "s3cret" not in str(err.value)


def test_unreachable_token_endpoint():
    def boom(request):
        raise httpx.ConnectError("down")
    p = provider(httpx.Client(transport=httpx.MockTransport(boom)), Clock())
    with pytest.raises(TokenError, match="unreachable"):
        p.token()


def test_incomplete_settings_refused():
    with pytest.raises(ValueError, match="DPS_CORE_TOKEN_URL"):
        ClientCredentialsToken(TOKEN_URL, "dps-app", "", "api://covenant/.default")


def test_core_client_sends_the_oauth_token_and_maps_token_failures_to_core_unavailable():
    calls: list[str] = []

    def core(request: httpx.Request) -> httpx.Response:
        calls.append(request.headers.get("Authorization", ""))
        return httpx.Response(202, json={"status": "accepted"})

    oauth = provider(token_endpoint([httpx.Response(200, json={"access_token": "t1", "expires_in": 3600})], []),
                     Clock())
    client = CoreClient("http://core", 1.0, client=httpx.Client(base_url="http://core",
                                                                transport=httpx.MockTransport(core)), oauth=oauth)
    assert client.send_data_event({"cpId": "CP-1"}) == {"status": "accepted"}
    assert calls == ["Bearer t1"]

    broken = provider(token_endpoint([httpx.Response(500)], []), Clock())
    failing = CoreClient("http://core", 1.0, client=httpx.Client(base_url="http://core",
                                                                 transport=httpx.MockTransport(core)), oauth=broken)
    with pytest.raises(CoreUnavailable, match="HTTP 500"):
        failing.send_data_event({"cpId": "CP-1"})


def test_from_settings_picks_oauth_only_when_configured():
    plain = CoreClient.from_settings(Settings(core_service_token="fixed"))
    assert plain._oauth is None
    assert plain._auth_headers() == {"Authorization": "Bearer fixed"}

    oauth = CoreClient.from_settings(Settings(core_token_url=TOKEN_URL, core_client_id="dps-app",
                                              core_client_secret="s3cret", core_scope="api://covenant/.default"))
    assert isinstance(oauth._oauth, ClientCredentialsToken)
