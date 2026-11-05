"""AI usage: tokens and cost of every model call, recorded per request (review GAP-05).

The completion notes the tokens of each call it makes in a context variable; the endpoint that asked
for the answer then stores one ``dps_ai_usage`` row with what the call was for, who asked, the CP or
document, the model, the tokens and the cost. A request that made no model call (rules answers) stores
nothing. Cost uses the per-1,000-token rates in settings; with no rates set it is left empty.
"""

from __future__ import annotations

from contextvars import ContextVar
from dataclasses import dataclass
from datetime import timedelta

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from dps.models import AiUsage, utcnow
from dps.settings import Settings

PURPOSES = ("ASK_AI", "AGREEMENT_QA", "SUGGESTIONS", "NARRATIVE")


@dataclass
class Tokens:
    model: str
    prompt: int = 0
    completion: int = 0
    calls: int = 0


_noted: ContextVar[Tokens | None] = ContextVar("dps_ai_tokens", default=None)


def note(model: str, prompt_tokens: int, completion_tokens: int) -> None:
    """Called by the completion after each model call; several calls in one request add up."""
    current = _noted.get()
    if current is None:
        current = Tokens(model)
        _noted.set(current)
    current.prompt += int(prompt_tokens or 0)
    current.completion += int(completion_tokens or 0)
    current.calls += 1


def take() -> Tokens | None:
    """The tokens noted so far in this request, and a fresh start."""
    current = _noted.get()
    _noted.set(None)
    return current


def cost(settings: Settings, tokens: Tokens) -> float | None:
    if settings.ai_prompt_cost_per_1k is None and settings.ai_completion_cost_per_1k is None:
        return None
    return round(tokens.prompt / 1000 * (settings.ai_prompt_cost_per_1k or 0.0)
                 + tokens.completion / 1000 * (settings.ai_completion_cost_per_1k or 0.0), 6)


def record(db: Session, settings: Settings, purpose: str, user: str, *, cp_id: str | None = None,
           document_id: str | None = None, job_id: str | None = None) -> AiUsage | None:
    """Stores the request's model usage, if it made any model call."""
    tokens = take()
    if tokens is None:
        return None
    row = AiUsage(purpose=purpose, user=user, cp_id=cp_id, document_id=document_id, job_id=job_id,
                  model=tokens.model, calls=tokens.calls, prompt_tokens=tokens.prompt,
                  completion_tokens=tokens.completion, cost=cost(settings, tokens))
    db.add(row)
    db.commit()
    return row


def summary(db: Session, days: int) -> dict:
    """Totals per purpose over the last ``days`` days, for support and administrators."""
    since = utcnow() - timedelta(days=days)
    rows = db.execute(select(AiUsage.purpose, func.count(), func.sum(AiUsage.calls), func.sum(AiUsage.prompt_tokens),
                             func.sum(AiUsage.completion_tokens), func.sum(AiUsage.cost))
                      .where(AiUsage.at >= since).group_by(AiUsage.purpose)).all()
    by_purpose = [{"purpose": p, "requests": n, "calls": int(c or 0), "promptTokens": int(pt or 0),
                   "completionTokens": int(ct or 0), "cost": None if cost_sum is None else round(float(cost_sum), 4)}
                  for p, n, c, pt, ct, cost_sum in rows]
    costs = [r["cost"] for r in by_purpose if r["cost"] is not None]
    return {"days": days, "byPurpose": sorted(by_purpose, key=lambda r: r["purpose"]),
            "promptTokens": sum(r["promptTokens"] for r in by_purpose),
            "completionTokens": sum(r["completionTokens"] for r in by_purpose),
            "cost": round(sum(costs), 4) if costs else None}
