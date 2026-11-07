"""Flow A Validate step: deterministic checks that decide publish / needs_review / failed (03 §2)."""

from __future__ import annotations

import re
from collections.abc import Callable

from dps.extraction.agreement import normalize_ws
from dps.extraction.mapping import Proposal, base_metric
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


# Which way each ratio is normally tested. An operator pointing the other way usually means the wording was
# misread ("shall not be less than" taken as "less than"), so a person confirms it before it is applied.
FLOORS = {"DSCR", "ICR", "EBITDA_TO_EMI", "CURRENT_RATIO", "QUICK_RATIO", "FIXED_CHARGE_COVERAGE_RATIO",
          "MIN_TANGIBLE_NET_WORTH", "MIN_NET_WORTH", "MINIMUM_LIQUIDITY", "MIN_EBITDA"}
CEILINGS = {"DEBT_TO_EBITDA", "NET_LEVERAGE_RATIO", "DEBT_TO_NET_WORTH", "DEBT_TO_EQUITY", "LOAN_TO_VALUE"}


def check_direction(p: Proposal) -> list[str]:
    if base_metric(p.metric_raw) in FLOORS and p.operator in ("<", "<="):
        return ["OPERATOR_UNEXPECTED: usually a minimum (>=)"]
    if base_metric(p.metric_raw) in CEILINGS and p.operator in (">", ">="):
        return ["OPERATOR_UNEXPECTED: usually a maximum (<=)"]
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
        p.issues.extend(check_direction(p))
        if p.expression and p.category != "INFO":
            p.issues.extend(f"FORMULA_INVALID: {e}" for e in formula_errors(p.expression))
        if p.confidence < min_confidence:
            p.issues.append("LOW_CONFIDENCE")
    check_conflicts(proposals)
    if not proposals:
        return NEEDS_REVIEW  # text but nothing found: a person confirms the agreement has no covenants
    return NEEDS_REVIEW if any(p.issues for p in proposals) else PUBLISH
