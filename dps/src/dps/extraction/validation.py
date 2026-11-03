"""Flow A Validate step: deterministic checks that decide publish / needs_review / failed (03 §2)."""

from __future__ import annotations

import re
from collections.abc import Callable

from dps.extraction.agreement import normalize_ws
from dps.extraction.mapping import Proposal
from dps.parsing import Page

PUBLISH, NEEDS_REVIEW, FAILED = "publish", "needs_review", "failed"


def _numbers(text: str) -> list[float]:
    return [float(n.replace(",", "")) for n in re.findall(r"\d[\d,]*(?:\.\d+)?", text or "")]


def check_quote(p: Proposal, pages_by_no: dict[int, str]) -> list[str]:
    if p.page is None or p.page not in pages_by_no:
        return ["QUOTE_PAGE_MISSING"]
    if not p.quote or normalize_ws(p.quote) not in pages_by_no[p.page]:
        return ["QUOTE_NOT_FOUND"]
    if p.threshold_raw is not None and not any(abs(n - p.threshold_raw) < 1e-9 for n in _numbers(p.quote)):
        return ["THRESHOLD_NOT_IN_QUOTE"]
    return []


def check_conflicts(proposals: list[Proposal]) -> None:
    """Same metric with different thresholds (e.g. step-downs) → both held for a person to decide."""
    seen: dict[str, Proposal] = {}
    for p in proposals:
        other = seen.get(p.metric_raw)
        if other is not None and (other.operator, other.threshold) != (p.operator, p.threshold):
            for x in (other, p):
                if "THRESHOLD_CONFLICT" not in x.issues:
                    x.issues.append("THRESHOLD_CONFLICT")
        seen.setdefault(p.metric_raw, p)


def validate(pages: list[Page], proposals: list[Proposal], formula_errors: Callable[[str], list[str]],
             min_confidence: float) -> str:
    """Annotates ``issues`` on each proposal and returns the route label."""
    if not any(p.text.strip() for p in pages):
        return FAILED
    pages_by_no = {p.no: normalize_ws(p.text) for p in pages}
    for p in proposals:
        p.issues.extend(check_quote(p, pages_by_no))
        if p.expression and p.category != "INFO":
            p.issues.extend(f"FORMULA_INVALID: {e}" for e in formula_errors(p.expression))
        if p.confidence < min_confidence:
            p.issues.append("LOW_CONFIDENCE")
    check_conflicts(proposals)
    if not proposals:
        return NEEDS_REVIEW  # text but nothing found: a person confirms the agreement has no covenants
    return NEEDS_REVIEW if any(p.issues for p in proposals) else PUBLISH
