"""C3 and O8: advisory AI. It never decides compliance: Core does. Output is labelled advisory everywhere.

Without a model configured (``DPS_AI_MODEL`` empty) the advisor gives deterministic suggestions that just
mirror the assessed result, so the feature degrades to "no AI" rather than failing. With a model (e.g.
``azure/<deployment>``; credentials from AZURE_API_KEY / AZURE_API_BASE / AZURE_API_VERSION) it asks the
LLM through CrewAI's ``LLM`` wrapper and falls back to the deterministic answer on any error.
"""

from __future__ import annotations

import json
import logging
import re
from collections.abc import Callable

log = logging.getLogger(__name__)

STATUSES = {"COMPLIED", "BREACHED", "WAIVER", "EXTENSION"}
ADVISORY = "Advisory only (AI); the covenant status is decided in Core."

SYSTEM_PROMPT = (
    "You review loan covenant results for a credit officer. For each covenant, suggest one status: "
    "COMPLIED, BREACHED, WAIVER or EXTENSION, a one-sentence rationale grounded only in the data given, "
    "and a confidence 0-100. Suggest WAIVER or EXTENSION only for a breach the data shows is small or "
    "temporary. Never invent numbers. Reply with a JSON array of objects with keys appliedCovenantId, "
    "recommendedStatus, rationale, confidence and nothing else."
)

Completion = Callable[[list[dict]], str]


def deterministic(covenants: list[dict]) -> list[dict]:
    out = []
    for c in covenants:
        result = (c.get("latestResult") or "").upper()
        if result == "COMPLIANT":
            status, why = "COMPLIED", "Latest assessment is compliant."
        elif result == "BREACHED":
            value = c.get("computedValue")
            status = "BREACHED"
            why = f"Latest assessment breached ({c.get('conditionExpression')}, actual {value})." if value \
                else "Latest assessment breached."
        else:
            continue  # ERROR / pending: nothing sound to suggest
        out.append({"appliedCovenantId": c["appliedCovenantId"], "recommendedStatus": status,
                    "rationale": f"{why} {ADVISORY}", "confidence": None})
    return out


def _parse(text: str, allowed_ids: set[str]) -> list[dict]:
    match = re.search(r"\[.*\]", text or "", re.S)
    if not match:
        raise ValueError("no JSON array in model output")
    rows = json.loads(match.group(0))
    out = []
    for r in rows:
        if not isinstance(r, dict) or r.get("appliedCovenantId") not in allowed_ids:
            continue
        status = str(r.get("recommendedStatus", "")).upper()
        if status not in STATUSES:
            continue
        conf = r.get("confidence")
        conf = max(0.0, min(100.0, float(conf))) if isinstance(conf, (int, float)) else None
        rationale = str(r.get("rationale") or "")[:1000]
        out.append({"appliedCovenantId": r["appliedCovenantId"], "recommendedStatus": status,
                    "rationale": f"{rationale} {ADVISORY}".strip(), "confidence": conf})
    return out


def crewai_completion(model: str, temperature: float | None = None) -> Completion:  # pragma: no cover - live model
    from crewai import LLM

    from dps import usage

    def call(messages: list[dict]) -> str:
        # One LLM per call: its token counters then belong to this call alone, even under concurrency.
        # None keeps the model's default: newer Azure OpenAI models refuse any other temperature.
        llm = LLM(model=model) if temperature is None else LLM(model=model, temperature=temperature)
        text = llm.call(messages)
        spent = llm.get_token_usage_summary()
        usage.note(model, spent.prompt_tokens, spent.completion_tokens)
        return text

    return call


class Advisor:
    def __init__(self, completion: Completion | None = None) -> None:
        self._completion = completion

    @property
    def completion(self) -> Completion | None:
        return self._completion

    @property
    def ai_enabled(self) -> bool:
        return self._completion is not None

    def suggest(self, cp_id: str, covenants: list[dict]) -> list[dict]:
        """C3: one suggestion per covenant it can say something about."""
        covenants = [c for c in covenants if isinstance(c, dict) and c.get("appliedCovenantId")]
        if not covenants:
            return []
        if self._completion is None:
            return deterministic(covenants)
        facts = [{k: c.get(k) for k in ("appliedCovenantId", "covenantName", "conditionExpression",
                                         "computedValue", "latestResult")} for c in covenants]
        messages = [{"role": "system", "content": SYSTEM_PROMPT},
                    {"role": "user", "content": json.dumps({"cpId": cp_id, "covenants": facts})}]
        try:
            return _parse(self._completion(messages), {c["appliedCovenantId"] for c in covenants})
        except Exception as ex:  # model down, rate-limited or off-format: fall back, never fail Core
            log.warning("AI suggestions fell back to deterministic: %s", type(ex).__name__)
            return deterministic(covenants)

    def narrative(self, values: list[dict]) -> dict:
        """O8: a short reading of one statement's line items, for the reviewer (advisory)."""
        by_key = {v["key"]: v["value"] for v in values}
        lines = []
        if "EBITDA" in by_key and by_key.get("INTEREST_PAID"):
            lines.append(f"EBITDA covers interest {by_key['EBITDA'] / by_key['INTEREST_PAID']:.2f} times.")
        if "TOTAL_DEBT" in by_key and by_key.get("EBITDA"):
            lines.append(f"Debt is {by_key['TOTAL_DEBT'] / by_key['EBITDA']:.2f} times EBITDA.")
        if "TOTAL_DEBT" in by_key and by_key.get("NET_WORTH"):
            lines.append(f"Debt to net worth is {by_key['TOTAL_DEBT'] / by_key['NET_WORTH']:.2f}.")
        if "CURRENT_ASSETS" in by_key and by_key.get("CURRENT_LIABILITIES"):
            lines.append(f"Current ratio is {by_key['CURRENT_ASSETS'] / by_key['CURRENT_LIABILITIES']:.2f}.")
        text = " ".join(lines) or "Not enough line items to comment on."
        method = "RULES"
        if self._completion is not None and values:
            try:
                text = self._completion([
                    {"role": "system", "content": "Summarise these financial statement figures (INR crore) "
                                                  "for a credit officer in at most four sentences. Use only "
                                                  "the numbers given."},
                    {"role": "user", "content": json.dumps(by_key)},
                ]).strip()[:2000]
                method = "AI"
            except Exception as ex:
                log.warning("Narrative fell back to rules: %s", type(ex).__name__)
        return {"text": text, "method": method, "advisory": ADVISORY}
