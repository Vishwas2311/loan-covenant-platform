"""Flow A safety net: every sentence that reads like a financial covenant is either explained or shown to a person.

The covenant rules (agreement.py) know a list of covenants and of ways to write them. An agreement written
tomorrow may use a covenant or a wording they do not know ("Debt Yield of not less than 9%"). This pass does
not try to understand the sentence. It only recognises the shape of a covenant, an obligation, a comparison,
a financial word and a number, and checks that each such sentence is accounted for:

- part of a proposed covenant (its quote overlaps the sentence), or
- set aside for a known reason: a pricing grid, an incurrence test, a cash sweep, a definition;
- otherwise proposed as a POSSIBLE_COVENANT for a person: "not understood by DPS, please look".

So a covenant DPS cannot read is never dropped without a trace.
"""

from __future__ import annotations

import re

from dps.extraction.agreement import _NOT_A_COVENANT, Term, normalize_ws
from dps.parsing import Page

# "permit"/"maintain" too: a list form ("the Borrower will not: ... 6.01 Permit the Leverage Ratio to exceed")
# leaves the "will not" in an earlier sentence.
_OBLIGATION = re.compile(r"\b(shall|will|must|agrees?\s+to|undertakes?|covenants?\s+(?:that|to)|is\s+required\s+to|"
                         r"permit|maintain)\b", re.I)
_COMPARISON = re.compile(r"not\s+(?:be\s+)?(?:less|lower|more|greater|higher)\s+than|at\s+least|\bminimum\b|"
                         r"\bmaximum\b|\bexceed|\bbelow\b|\babove\b|or\s+(?:less|more|greater)\b|no\s+(?:less|more|greater)\s+than|"
                         r"in\s+excess\s+of|fall\s+below|\bmaintain", re.I)
_FINANCIAL = re.compile(r"\bratio\b|coverage|\bcover\b|leverage|net\s+worth|tangible|\bebitda\b|liquidity|"
                        r"\bcash\b|\bdebt\b|\bequity\b|\bcapital\b|\bvalue\b|\byield\b|gearing|borrowings|"
                        r"indebtedness|\bassets\b|\bincome\b|\bearnings\b|\bprofit\b", re.I)
_NUMBER = re.compile(r"\d+(?:\.\d+)?\s*(?:%|per\s*cent|percent|x\b|times\b|:\s*1(?:\.0+)?\b|to\s+1(?:\.0+)?\b)|"
                     r"(?:\$|INR|Rs\.?|₹|LKR|USD)\s*\d|\d[\d,]*(?:\.\d+)?\s*(?:crores?|lakhs?|million|billion)\b", re.I)
_DEFINITION = re.compile(r"^[\W\d]*[\"“][^\"”]{2,80}[\"”]\s+(?:means|shall\s+mean|has\s+the\s+meaning)", re.I)
_NAME = re.compile(r"((?:[A-Z][\w\-]*\s+){0,4}(?:Ratio|Coverage|Cover|Leverage|Net\s+Worth|EBITDA|Liquidity|Yield|"
                   r"Gearing|Value|Capital|Margin))\b")
# Permissions with a cap, not a test the borrower must pass: "Investments ... not to exceed the greater of $73.0
# million and 45.0% of LTM EBITDA", "Liens securing Indebtedness ...", "as if such Indebtedness had been incurred".
_BASKET = re.compile(r"\b(?:investments?|liens?|dispositions?|restricted\s+payments?|judgments?)\b[^.]{0,200}"
                     r"(?:aggregate|greater\s+of|fair\s+market\s+value)|had\s+been\s+incurred|such\s+incurrence|"
                     r"release\s+price|partial\s+release|qualify\s+for|in\s+tranches", re.I)
# More than this is noise for a reviewer; the rest are counted in the summary issue.
MAX_SHOWN = 12


def _sentences(text: str) -> list[str]:
    parts = re.split(r"(?<=[.;])\s+(?=[A-Z(\"“])|\n\s*\n", text)
    return [normalize_ws(p) for p in parts if len(p) > 30]


def _numbers(text: str) -> set[float]:
    return {float(n.replace(",", "")) for n in re.findall(r"\d[\d,]*(?:\.\d+)?", text)}


def _covered(sentence: str, proposals: list[tuple[str, float | None]]) -> bool:
    """A proposal explains the sentence when it quotes it and its own number is in it: a proposal with another
    number (the covenant read at a different level, or a different covenant nearby) leaves it unexplained."""
    words = set(re.findall(r"[a-z0-9.]+", sentence.lower()))
    numbers = _numbers(sentence)
    for q, threshold in proposals:
        if threshold is not None and not any(abs(n - threshold) < 1e-9 for n in numbers):
            continue
        qn = normalize_ws(q)
        if qn and (qn in sentence or sentence in qn):
            return True
        qwords = set(re.findall(r"[a-z0-9.]+", qn.lower()))
        if qwords and len(words & qwords) >= 0.6 * min(len(words), len(qwords)):
            return True
    return False


_METRIC = re.compile(r"\bratio\b|coverage|\bcover\b|leverage|net\s+worth|liquidity|\byield\b|gearing|\bebitda\b|"
                     r"loan[- ]to[- ]value|\bltv\b", re.I)


def looks_like_covenant(sentence: str) -> bool:
    """An obligation, and a limit next to a covenant word: "... Debt Yield ... not less than 9%".

    The limit must sit close to both a comparison and a covenant word (within about 160 characters before it), so
    the long legal sentences about baskets, prepayments and eligible investments, which also hold "shall", a
    number and a financial word somewhere, are not shown.
    """
    if not _OBLIGATION.search(sentence):
        return False
    for m in _NUMBER.finditer(sentence):
        near = sentence[max(0, m.start() - 160):m.end() + 30]
        if _COMPARISON.search(near) and _METRIC.search(near):
            return True
    return False


def possible_covenants(pages: list[Page], terms: list[Term]) -> tuple[list[Term], dict[str, int]]:
    """Covenant-shaped sentences no proposal explains, and how many were set aside for each known reason."""
    quotes_by_page: dict[int | None, list[tuple[str, float | None]]] = {}
    for t in terms:
        if t.category == "FINANCIAL" and t.quote:
            quotes_by_page.setdefault(t.page, []).append((t.quote, t.threshold))
    all_quotes = [q for qs in quotes_by_page.values() for q in qs]
    set_aside = {"pricing, incurrence or other condition": 0, "definition": 0}
    seen: set[str] = set()
    found: list[Term] = []
    for page in pages:
        for sentence in _sentences(page.text):
            if not looks_like_covenant(sentence):
                continue
            key = re.sub(r"\W+", " ", sentence.lower())[:200]
            if key in seen:
                continue
            seen.add(key)
            same_page = quotes_by_page.get(page.no, [])
            numbers = _numbers(sentence)
            if any(t is not None and any(abs(n - t) < 1e-9 for n in numbers) for _, t in same_page):
                continue  # a covenant on this page with this number (quoted from its "Required threshold" line)
            if _covered(sentence, same_page or all_quotes):
                continue
            if _DEFINITION.search(sentence):
                set_aside["definition"] += 1
                continue
            if _NOT_A_COVENANT.search(sentence) or _BASKET.search(sentence):
                set_aside["pricing, incurrence or other condition"] += 1
                continue
            name = _NAME.search(sentence)
            found.append(Term("POSSIBLE_COVENANT", "CANDIDATE", None, None, None, None, page.no, sentence[:480],
                              None, 0.5, issues=["POSSIBLE_COVENANT_NOT_UNDERSTOOD"],
                              name=normalize_ws(name.group(1)) if name else "Possible covenant"))
    if len(found) > MAX_SHOWN:
        extra = len(found) - MAX_SHOWN
        found = found[:MAX_SHOWN]
        found[-1].issues.append(f"MORE_POSSIBLE_COVENANTS: {extra} more covenant-like sentences not shown")
    return found, set_aside
