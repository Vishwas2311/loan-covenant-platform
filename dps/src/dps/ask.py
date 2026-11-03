"""FR-27 "Ask AI" (R3): questions about the caller's own portfolio, answered only from Core data.

The context is fetched from Core *as the caller* (their token, or their dev headers), so Core's portfolio
scoping applies and nobody can ask about CPs they cannot see. The context is capped at 10k characters,
every answer lists the records it used, and off-topic questions are refused. Without a model the answer
is a plain listing of the matching records.
"""

from __future__ import annotations

import json
import logging
import re
from collections.abc import Callable

import httpx

from dps.advisor import ADVISORY, Completion
from dps.auth import ROLES_HEADER, USER_HEADER, Principal
from dps.errors import ApiError

log = logging.getLogger(__name__)

MAX_CONTEXT = 10_000
TOPIC = re.compile(r"\b(covenant|breach|breached|complian|waiver|extension|escalat|cp|credit proposal|dscr|"
                   r"icr|leverage|ratio|net worth|threshold|portfolio|overdue|error|certificate|due)\w*", re.I)
REFUSAL = "I can only answer questions about the covenants in your portfolio."

Fetch = Callable[[Principal, str], object]


def core_fetch(base_url: str, timeout: float, client: httpx.Client | None = None) -> Fetch:
    # Whole-portfolio reads: allow more than the per-call timeout used for single lookups.
    http = client or httpx.Client(base_url=base_url, timeout=max(timeout, 30.0))

    def fetch(principal: Principal, path: str):
        headers = ({"Authorization": f"Bearer {principal.token}"} if principal.token
                   else {USER_HEADER: principal.user, ROLES_HEADER: ",".join(sorted(principal.roles))})
        try:
            resp = http.get(path, headers=headers)
        except httpx.HTTPError as exc:
            raise ApiError(503, "CORE_UNAVAILABLE", "Core did not answer in time; try again") from exc
        if resp.status_code >= 500:
            raise ApiError(503, "CORE_UNAVAILABLE", f"Core answered HTTP {resp.status_code}; try again")
        if resp.status_code >= 400:
            raise ApiError(resp.status_code, "CORE_REFUSED", f"Core refused the request (HTTP {resp.status_code})")
        return resp.json()

    return fetch


def build_context(fetch: Fetch, principal: Principal) -> list[dict]:
    """Portfolio summary rows and open breaches, each tagged with a citation id."""
    records: list[dict] = []
    for row in fetch(principal, "/api/v1/monitoring/summary") or []:
        records.append({"ref": f"cp:{row['cpId']}", **row})
    for row in fetch(principal, "/api/v1/reports/open-breaches") or []:
        records.append({"ref": f"breach:{row['appliedCovenantId']}", **row})
    return records


def _trim(records: list[dict]) -> list[dict]:
    out, size = [], 2
    for r in records:
        n = len(json.dumps(r, default=str)) + 1
        if size + n > MAX_CONTEXT:
            break
        out.append(r)
        size += n
    return out


def _relevant(question: str, records: list[dict]) -> list[dict]:
    q = question.upper()
    named = [r for r in records if r.get("cpId") and str(r["cpId"]).upper() in q]
    if named:
        return named
    if re.search(r"breach|escalat|overdue|open", question, re.I):
        return [r for r in records if r["ref"].startswith("breach:")]
    return [r for r in records if r["ref"].startswith("cp:")]


def _describe(r: dict) -> str:
    if r["ref"].startswith("breach:"):
        flag = " (escalate)" if r.get("escalate") else ""
        return (f"{r['cpId']}: {r.get('covenantName')} {r.get('result')} for {r.get('daysOpen')} days, "
                f"status {r.get('status')}{flag}")
    return (f"{r['cpId']}: {r.get('applied')} covenants, {r.get('breached')} breached, "
            f"{r.get('error')} with data errors")


def answer(question: str, principal: Principal, fetch: Fetch, completion: Completion | None) -> dict:
    question = (question or "").strip()
    if not question or not TOPIC.search(question):
        return {"answer": REFUSAL, "citations": [], "method": "RULES", "advisory": ADVISORY}
    records = _trim(build_context(fetch, principal))
    relevant = _relevant(question, records)
    if completion is not None and records:
        try:
            text = completion([
                {"role": "system", "content": "Answer the credit officer's question using only the JSON records "
                                              "given. Cite each record you use by its ref in square brackets. If "
                                              "the records do not answer it, say so. At most five sentences."},
                {"role": "user", "content": json.dumps({"question": question, "records": records}, default=str)},
            ]).strip()[:3000]
            cited = [r["ref"] for r in records if f"[{r['ref']}]" in text]
            return {"answer": text, "citations": cited, "method": "AI", "advisory": ADVISORY}
        except Exception as ex:
            log.warning("Ask AI fell back to rules: %s", type(ex).__name__)
    if not relevant:
        return {"answer": "Nothing in your portfolio matches that question.", "citations": [], "method": "RULES",
                "advisory": ADVISORY}
    lines = [_describe(r) for r in relevant[:20]]
    return {"answer": "; ".join(lines) + ".", "citations": [r["ref"] for r in relevant[:20]], "method": "RULES",
            "advisory": ADVISORY}
