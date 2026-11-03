"""FR-29 / FR-30: agreement facts that are not covenants but matter when reviewing them.

Facility dates (agreement, maturity / final repayment, first test), cure or grace periods, the testing
basis (LTM) and step-down language. Each comes back as a ``Term`` with category ``INFO``; for dates the
ISO date is carried in ``definition`` and later stored as the term's expression. Nothing is invented: a
fact is reported only when the agreement states it, with page and quote.
"""

from __future__ import annotations

import re
from datetime import date, datetime

from dps.extraction.agreement import Term, _Doc, _quote
from dps.parsing import Page

_DATE = r"(\d{1,2})(?:st|nd|rd|th)?\s+([A-Za-z]{3,9})\.?,?\s+(\d{4})"

DATE_FACTS: list[tuple[str, str]] = [
    ("AGREEMENT_DATE", r"\b(?:dated|date of (?:this )?agreement|agreement date)\s*[:\-]?\s*" + _DATE),
    ("EFFECTIVE_DATE", r"\beffective date\b[^.\n]{0,40}?" + _DATE),
    ("MATURITY_DATE", r"\b(?:final maturity date|maturity date|final repayment(?: date)?(?: due)?"
                      r"(?: on or before)?)\b[^.\n]{0,40}?" + _DATE),
    ("FIRST_TEST_DATE", r"\bfirst (?:test(?:ing)? date|testing date|test period ending)\b[^.\n]{0,40}?" + _DATE),
]
CURE = re.compile(r"\b(?:cure|curing|remed(?:y|ied)|grace period)\b[^.]{0,120}?\bwithin\s+(?:[a-z\- ]+\s+)?"
                  r"\(?(\d{1,3})\)?\s+(?:business\s+)?days", re.I)
LTM = re.compile(r"\b(?:last|trailing|preceding)\s+twelve(?:\s*\(12\))?\s+months\b|\bLTM\b|\bTTM\b", re.I)
STEP_DOWN = re.compile(r"\bstep[\s-]?(?:down|up)\b|\bshall\s+(?:reduce|decrease|increase)\s+to\s+[0-9.]+", re.I)


def parse_date(day: str, month: str, year: str) -> date | None:
    for fmt in ("%d %B %Y", "%d %b %Y"):
        try:
            return datetime.strptime(f"{int(day)} {month.title()} {year}", fmt).date()
        except ValueError:
            continue
    return None


def extract_info(pages: list[Page]) -> list[Term]:
    doc = _Doc(pages)
    if not doc.text.strip():
        return []
    flat = doc.text
    out: list[Term] = []
    for metric, pattern in DATE_FACTS:
        m = re.search(pattern, flat, re.I)
        if not m:
            continue
        when = parse_date(*m.groups()[-3:])
        if when is None:
            continue
        quote, page = _quote(doc, m.start(), m.end())
        out.append(Term(metric, "INFO", None, None, "DATE", None, page, quote, when.isoformat(), 0.9))
    m = CURE.search(flat)
    if m:
        quote, page = _quote(doc, m.start(), m.end())
        out.append(Term("CURE_PERIOD_DAYS", "INFO", "=", float(m.group(1)), "DAYS", None, page, quote, None, 0.85))
    m = LTM.search(flat)
    if m:
        quote, page = _quote(doc, m.start(), m.end())
        out.append(Term("TESTING_BASIS", "INFO", None, None, None, None, page, quote, "LTM", 0.8))
    m = STEP_DOWN.search(flat)
    if m:
        quote, page = _quote(doc, m.start(), m.end())
        out.append(Term("STEP_SCHEDULE", "INFO", None, None, None, None, page, quote,
                        "Threshold changes over time; check the schedule", 0.7))
    return out
