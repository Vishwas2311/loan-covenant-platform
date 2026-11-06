"""Flow A Extract step: deterministic covenant extraction from loan agreement pages.

Ported from covenanttrackingphase1.2 ``LoanAgreementExtractionTool`` (numbered-clause scan, metric
aliases, threshold phrases, net-worth amounts, non-financial rules), reworked to be page-aware: every
term carries the page it was found on and a verbatim quote, so the Validate step can check citations.
"""

from __future__ import annotations

import re
from dataclasses import asdict, dataclass, field

from dps.parsing import Page

# Our metric keys → phrases (metric_mappings.json, trimmed to the reliable multi-word forms).
FINANCIAL_ALIASES: dict[str, list[str]] = {
    "DSCR": ["DSCR", "DEBT SERVICE COVERAGE RATIO", "DEBT SERVICE COVERAGE"],
    "ICR": ["ICR", "INTEREST COVERAGE RATIO", "INTEREST COVERAGE", "INTEREST COVER"],
    "DEBT_TO_EBITDA": ["DEBT TO EBITDA", "TOTAL DEBT TO EBITDA", "DEBT/EBITDA", "TOTAL LEVERAGE RATIO"],
    "NET_LEVERAGE_RATIO": ["NET LEVERAGE RATIO", "NET DEBT TO EBITDA", "NET DEBT/EBITDA"],
    "DEBT_TO_NET_WORTH": ["DEBT TO NET WORTH", "TOTAL DEBT TO NET WORTH", "DEBT/NET WORTH"],
    "DEBT_TO_EQUITY": ["DEBT TO EQUITY", "DEBT-TO-EQUITY", "DEBT/EQUITY", "TOTAL DEBT TO EQUITY"],
    "EBITDA_TO_EMI": ["EBITDA TO EMI", "EBITDA/EMI", "EMI COVERAGE"],
    "CURRENT_RATIO": ["CURRENT RATIO", "WORKING CAPITAL RATIO"],
    "QUICK_RATIO": ["QUICK RATIO", "ACID TEST RATIO"],
    "FIXED_CHARGE_COVERAGE_RATIO": ["FIXED CHARGE COVERAGE RATIO", "FIXED CHARGE COVERAGE", "FCCR"],
    "MIN_TANGIBLE_NET_WORTH": ["MINIMUM TANGIBLE NET WORTH", "TANGIBLE NET WORTH"],
    "MIN_NET_WORTH": ["MINIMUM NET WORTH", "NET WORTH"],
    "MINIMUM_LIQUIDITY": ["MINIMUM LIQUIDITY", "MINIMUM CASH BALANCE", "MINIMUM UNRESTRICTED CASH"],
}
# Names used in US syndicated and bilateral credit agreements (benchmark of 50 real agreements, 6 November 2026).
for _metric, _names in {
    "DEBT_TO_EBITDA": ["CONSOLIDATED LEVERAGE RATIO", "CONSOLIDATED TOTAL LEVERAGE RATIO", "LEVERAGE RATIO",
                       "FUNDED DEBT TO EBITDA", "TOTAL FUNDED DEBT TO EBITDA", "SENIOR FUNDED DEBT TO EBITDA",
                       "SENIOR DEBT TO EBITDA", "CASH FLOW LEVERAGE RATIO", "SENIOR SECURED LEVERAGE RATIO",
                       "CONSOLIDATED SENIOR SECURED LEVERAGE RATIO", "MAXIMUM TOTAL LEVERAGE RATIO"],
    "NET_LEVERAGE_RATIO": ["TOTAL NET LEVERAGE RATIO", "FIRST LIEN NET LEVERAGE RATIO", "SECURED NET LEVERAGE RATIO",
                           "CONSOLIDATED NET SENIOR SECURED LEVERAGE RATIO", "SENIOR SECURED NET LEVERAGE RATIO"],
    "ICR": ["CONSOLIDATED INTEREST COVERAGE RATIO"],
    "FIXED_CHARGE_COVERAGE_RATIO": ["CONSOLIDATED FIXED CHARGE COVERAGE RATIO", "BASIC FIXED CHARGE COVERAGE RATIO"],
    "DSCR": ["GLOBAL DEBT SERVICE COVERAGE RATIO", "NET DEBT SERVICE COVERAGE RATIO", "CASH FLOW COVERAGE RATIO",
             "CASH FLOW COVERAGE", "CASH FLOW TO DEBT SERVICE"],
    "DEBT_TO_NET_WORTH": ["DEBT TO TANGIBLE NET WORTH", "INDEBTEDNESS TO TANGIBLE NET WORTH",
                          "FUNDED DEBT TO TANGIBLE NET WORTH", "DEBT-TO-NET WORTH RATIO"],
    "DEBT_TO_EQUITY": ["NET DEBT TO EQUITY RATIO", "NET DEBT TO EQUITY"],
}.items():
    FINANCIAL_ALIASES[_metric] = FINANCIAL_ALIASES[_metric] + [n for n in _names if n not in FINANCIAL_ALIASES[_metric]]

AMOUNT_METRICS = {"MIN_NET_WORTH", "MIN_TANGIBLE_NET_WORTH", "MINIMUM_LIQUIDITY"}
# A ratio covenant above this is a misread number ("90 days"), not a threshold.
MAX_RATIO = 25.0
# Text around a ratio that makes it something other than a maintenance covenant: a pricing grid, an
# incurrence test for new debt or payments, a cash sweep, or a condition for releasing funds or extending.
_NOT_A_COVENANT = re.compile(
    r"pro\s+forma|after\s+giving\s+effect|applicable\s+(rate|margin|percentage)|pricing\s+level|asset\s+sale\s+"
    r"percentage|ecf\s+percentage|excess\s+cash\s+flow|incremental|until\s+such\s+time|evidence\s+satisfactory|"
    r"extension\s+(option|period)|extended\s+maturity|release\s+of\s+(the\s+)?(funds|letter)", re.I)

_INTENT = re.compile(r"\b(shall|must|required\s+to|undertakes?\s+to|agree(?:s|d)?\s+to|will\s+not)\b", re.I)

NON_FINANCIAL_RULES: list[tuple[str, list[str], list[str]]] = [
    # (metric, body patterns, heading-only patterns)
    ("COMPLIANCE_CERTIFICATE",
     [r"\bcompliance\s+certificate\b", r"\bofficer\s+certificate\b", r"\bcertificate\s+of\s+compliance\b",
      r"\bcertificate\s+signed\s+by\b", r"\bchief\s+financial\s+officer\b"],
     [r"\bcompliance\s+certificate\b", r"\bofficer\s+certificate\b", r"\bcertificate\s+of\s+compliance\b"]),
    ("REPORTING_COVENANT",
     [r"\binformation\s+covenant\b", r"\bfinancial\s+statements?\s+and\s+information\b",
      r"\bsubmit\s+(?:audited\s+)?financial\s+statements?\b",
      r"\bdeliver\s+(?:monthly|quarterly|annual|yearly)\s+(?:financial\s+)?reports?\b",
      r"\bprovide\s+(?:monthly|quarterly|annual|yearly)\s+(?:financial\s+)?statements?\b"],
     [r"\bfinancial\s+statements?\s+and\s+information\b", r"\bfinancial\s+information\b"]),
    ("NEGATIVE_PLEDGE",
     # "shall not, without the prior written consent of the Lender, create any lien" puts words in between.
     [r"\bnegative\s+pledge\b", r"\bshall\s+not\b[^.]{0,80}?\bcreate\s+(?:any\s+)?(?:lien|charge|encumbrance)\b",
      r"\bno\s+encumbrance\b", r"\bwithout\s+prior\s+written\s+consent\b"], []),
    ("DEBT_INCURRANCE_RESTRICTION",
     [r"\bshall\s+not\s+incur\s+additional\s+indebtedness\b", r"\bno\s+further\s+borrowing\b",
      r"\badditional\s+debt\s+requires\s+(?:prior\s+)?consent\b"], []),
    ("ASSET_DISPOSAL_RESTRICTION",
     [r"\bshall\s+not\s+dispose\s+of\s+assets\b", r"\bno\s+sale\s+or\s+transfer\s+of\s+(?:material\s+)?assets\b",
      r"\boutside\s+ordinary\s+course\s+of\s+business\b"], []),
    ("CHANGE_OF_CONTROL",
     [r"\bchange\s+of\s+control\b", r"\bpromoter\s+shareholding\b", r"\bmanagement\s+control\b"], []),
    ("INSURANCE_MAINTENANCE",
     [r"\bmaintain\s+adequate\s+insurance\b", r"\bkeep\s+assets\s+insured\b", r"\binsurance\s+policy\s+assignment\b",
      r"\binsurance\s+(?:shall\s+be\s+)?maintained\b", r"\bmaintain\s+.*\binsurance\b",
      r"\bkeep\s+.*\binsurance\b"], []),
    ("BOOKS_AND_RECORDS",
     [r"\bbooks?\s+and\s+records?\b", r"\bmaintain\s+proper\s+books?\s+of\s+account\b"],
     [r"\bbooks?\s+and\s+records?\b"]),
    ("REGULATORY_AND_TAX_COMPLIANCE",
     [r"\bcomply\s+with\s+all\s+applicable\s+laws\b", r"\btimely\s+payment\s+of\s+tax(?:es)?\b",
      r"\bstatutory\s+dues\b", r"\bmaintain\s+licen[cs]es?\s+and\s+permits?\b"], []),
]

_NUM = r"([0-9][0-9,]*(?:\.[0-9]+)?)"
_UNIT = r"\s*(crores?|cr\b|lakhs?|lacs?|million|billion)?"
# "shall not (be|fall|go|drop) less than / below": a verb may sit between the negation and the comparison.
# Without it the bare "less than" rule below matches and the floor is read as a ceiling.
_NOT = r"(?:not|never|at\s+no\s+time)\s+(?:(?:be|fall|go|drop|rise)\s+)?"
_CUR = r"(?:INR|Rs\.?|₹|\$|LKR)?"
# "will not permit the Interest Coverage Ratio ... to be less than 3.00": a floor, though it says "less than".
_PERMIT = r"(?:shall|will)\s+not\s+permit\b[^.;]{0,220}?"
_WORD_THRESHOLDS: list[tuple[str, str]] = [
    (_PERMIT + r"(?:to\s+)?(?:be\s+|fall\s+)?(?:less|lower)\s+than\s*" + _CUR + r"\s*" + _NUM + _UNIT, ">="),
    (_PERMIT + r"(?:to\s+)?(?:exceed|be\s+(?:greater|more|higher)\s+than)\s*" + _CUR + r"\s*" + _NUM + _UNIT, "<="),
    (r"(?:equals?\s+or\s+exceeds?|equal\s+to\s+or\s+greater\s+than|in\s+excess\s+of)\s*" + _CUR + r"\s*" + _NUM
     + _UNIT, ">="),
    (r"(?:never\s+exceed|shall\s+not\s+exceed|equal\s+to\s+or\s+less\s+than)\s*" + _CUR + r"\s*" + _NUM + _UNIT,
     "<="),
    (r"(?:" + _NOT + r"(?:less|lower)\s+than|" + _NOT + r"below|at\s+least|minimum(?:\s+of)?|no\s+less\s+than"
     r"|no\s+lower\s+than)\s*" + _CUR + r"\s*" + _NUM + _UNIT, ">="),
    (r"(?:does\s+not\s+exceed|not\s+(?:to\s+)?exceed(?:ing)?|at\s+no\s+time\s+exceed|at\s+most"
     r"|maximum(?:\s+of)?|" + _NOT + r"(?:more|greater|higher)\s+than|" + _NOT + r"above"
     r"|no\s+more\s+than|no\s+greater\s+than)\s*" + _CUR + r"\s*" + _NUM + _UNIT, "<="),
    (r"(?:greater\s+than|more\s+than)\s*(?:INR|Rs\.?|₹)?\s*" + _NUM + _UNIT, ">"),
    (r"(?:less\s+than)\s*(?:INR|Rs\.?|₹)?\s*" + _NUM + _UNIT, "<"),
]
_SYMBOL_THRESHOLD = re.compile(r"(>=|<=|≥|≤|>|<)\s*([0-9]+(?:\.[0-9]+)?)\s*(?:x\b|times?\b|:1\b)?", re.I)
_FREQUENCY = re.compile(r"\b(monthly|quarterly|semi[-\s]?annual(?:ly)?|half[-\s]?yearly|annual(?:ly)?|yearly)\b", re.I)
_CLAUSE = re.compile(r"(?m)^[ \t]*(\d+(?:\.\d+)+)\.?[ \t]+([^\n]*)$")


@dataclass
class Term:
    metric: str
    category: str  # FINANCIAL | NON_FINANCIAL
    operator: str | None
    threshold: float | None
    unit: str | None
    frequency: str | None  # Core enum: MONTHLY | QUARTERLY | YEARLY | None
    page: int | None
    quote: str
    definition: str | None = None
    confidence: float = 0.0
    method: str = "REGEX"
    issues: list[str] = field(default_factory=list)

    def as_dict(self) -> dict:
        return asdict(self)


@dataclass
class _Clause:
    number: str
    heading: str
    body: str
    start: int  # offset in the joined text


class _Doc:
    """Pages joined into one text, with offset → page lookup."""

    def __init__(self, pages: list[Page]) -> None:
        parts, self._starts, self._pages = [], [], []
        pos = 0
        for p in pages:
            self._starts.append(pos)
            self._pages.append(p)
            parts.append(p.text.replace("\r\n", "\n"))
            pos += len(parts[-1]) + 1
        self.text = "\n".join(parts)

    def page_at(self, offset: int) -> Page | None:
        found = self.page_span(offset)
        return found[0] if found else None

    def page_span(self, offset: int) -> tuple[Page, int] | None:
        """The page holding ``offset`` and that page's start offset."""
        found = None
        for start, page in zip(self._starts, self._pages, strict=True):
            if start <= offset:
                found = (page, start)
        return found


def compact(text: str) -> str:
    return re.sub(r"[^A-Z0-9]", "", (text or "").upper())


def normalize_ws(text: str) -> str:
    return " ".join((text or "").split())


def _best_metric(text: str) -> str | None:
    key = compact(text)
    best, best_len = None, 0
    for metric, phrases in FINANCIAL_ALIASES.items():
        for phrase in phrases:
            c = compact(phrase)
            if len(c) > best_len and c in key:
                best, best_len = metric, len(c)
    return best


def _metric_for_clause(heading: str, body: str) -> str | None:
    metric = _best_metric(heading)
    if metric:
        return metric
    first_sentence = body.split(".")[0]
    return _best_metric(first_sentence)


def _frequency(text: str) -> str | None:
    m = _FREQUENCY.search(text or "")
    if not m:
        return None
    word = m.group(1).lower()
    if word.startswith("month"):
        return "MONTHLY"
    if word.startswith("quarter"):
        return "QUARTERLY"
    if word.startswith(("annual", "year")):
        return "YEARLY"
    return None  # semi-annual / half-yearly: no Core schedule; the RM sets a custom cron


def _number(raw: str) -> float:
    return float(raw.replace(",", ""))


def _threshold(text: str, amount: bool) -> tuple[str, float, str | None, int, int, bool] | None:
    """(operator, value, unit, match start, match end, corroborated) from clause text, or None."""
    words = []
    for pattern, op in _WORD_THRESHOLDS:
        m = re.search(pattern, text, re.I)
        if m:
            words.append((m.start(), op, _number(m.group(1)), (m.group(2) or "").strip().lower() or None, m))
    words.sort(key=lambda w: w[0])
    symbol = None if amount else _SYMBOL_THRESHOLD.search(text)
    if symbol:
        op = {"≥": ">=", "≤": "<="}.get(symbol.group(1), symbol.group(1))
        value = _number(symbol.group(2))
        agree = any(w[1] == op and abs(w[2] - value) < 1e-9 for w in words)
        anchor = words[0][4] if words else symbol
        return op, value, None, anchor.start(), anchor.end(), agree
    if words:
        _, op, value, unit, m = words[0]
        if amount and not unit and value < 1000:
            return None  # a ratio-sized number is not a net-worth floor
        return op, value, unit, m.start(), m.end(), False
    if amount:  # "a minimum Net Worth of INR 5,000 Crores": a currency amount alone is a floor
        m = re.search(r"(?:INR|Rs\.?|₹|\$|LKR)\s*" + _NUM + _UNIT, text, re.I)
        if m:
            return ">=", _number(m.group(1)), (m.group(2) or "").strip().lower() or None, m.start(), m.end(), False
    return None


def _quote(doc: _Doc, start: int, end: int) -> tuple[str, int | None]:
    """The line before and the line(s) of [start, end), clipped to the page holding ``end`` and 300 chars.

    Clipping keeps the quote verbatim on one page (the citation check) while still containing the number.
    """
    span = doc.page_span(max(start, end - 1))
    if span is None:
        return "", None
    page, page_start = span
    line_start = max(page_start, doc.text.rfind("\n", 0, start) + 1)
    prev = max(page_start, doc.text.rfind("\n", 0, max(0, line_start - 1)) + 1)
    line_end = doc.text.find("\n", end)
    line_end = len(doc.text) if line_end == -1 else line_end
    quote = normalize_ws(doc.text[prev:line_end])
    if len(quote) > 300:
        quote = normalize_ws(doc.text[line_start:line_end])[-300:].strip()
    return quote, page.no


def _definition(body: str, metric: str) -> str | None:
    for line in body.splitlines():
        if "=" in line and not re.search(r"[<>]=?", line) and re.search(r"[/*+-]", line):
            lhs = compact(line.split("=")[0])
            if lhs and any(compact(a) in lhs or lhs in compact(a) for a in FINANCIAL_ALIASES.get(metric, [metric])
                           + [metric]):
                return normalize_ws(line)
    return None


def _clauses(doc: _Doc) -> list[_Clause]:
    marks = list(_CLAUSE.finditer(doc.text))
    out = []
    for i, m in enumerate(marks):
        end = marks[i + 1].start() if i + 1 < len(marks) else len(doc.text)
        body = doc.text[m.end():end]
        body = re.split(r"\n[ \t=]*(?:ARTICLE|SCHEDULE)\s+\w+", body, maxsplit=1, flags=re.I)[0]
        out.append(_Clause(m.group(1), m.group(2).strip(), body, m.start()))
    return out


def _financial(doc: _Doc, clauses: list[_Clause]) -> list[Term]:
    terms: dict[str, Term] = {}
    for c in clauses:
        metric = _metric_for_clause(c.heading, c.body)
        if not metric or metric in terms:
            continue
        combined = f"{c.heading}\n{c.body}"
        found = _threshold(combined, metric in AMOUNT_METRICS)
        if not found or not _plausible(metric, found[1], combined[max(0, found[3] - 300):found[4] + 200]):
            continue
        op, value, unit, s, e, corroborated = found
        body_offset = c.start + (doc.text[c.start:].find(c.heading) if c.heading else 0)
        quote, page = _quote(doc, body_offset + s, body_offset + e)
        terms[metric] = Term(metric, "FINANCIAL", op, value, unit, _frequency(combined), page, quote,
                             _definition(c.body, metric), 0.95 if corroborated else 0.85)
    _scan_unnumbered(doc, terms)
    return list(terms.values())


def _plausible(metric: str, value: float, context: str) -> bool:
    """A maintenance covenant: a sensible number, not a pricing, incurrence or release condition."""
    if metric not in AMOUNT_METRICS and value > MAX_RATIO:
        return False
    return not _NOT_A_COVENANT.search(context)


def _alias_pattern(phrase: str) -> str:
    return r"\b" + r"[\s_\-]*".join(re.escape(w) for w in phrase.split()) + r"\b"


def _scan_unnumbered(doc: _Doc, terms: dict[str, Term]) -> None:
    """Fallback for covenants written outside numbered clauses (tables, schedules, net-worth floors)."""
    # Every alias match in the text, so a match inside a longer alias of another metric ("Debt to EBITDA" in
    # "Net Debt to EBITDA") is not taken for a covenant of its own.
    spans = [(m.start(), m.end(), other) for other, phrases in FINANCIAL_ALIASES.items() for phrase in phrases
             for m in re.finditer(_alias_pattern(phrase), doc.text, re.I)]
    for metric, phrases in FINANCIAL_ALIASES.items():
        if metric in terms:
            continue
        for phrase in sorted(phrases, key=len, reverse=True):
            for m in re.finditer(_alias_pattern(phrase), doc.text, re.I):
                if any(o != metric and s <= m.start() and e >= m.end() and e - s > m.end() - m.start()
                       for s, e, o in spans):
                    continue
                before = doc.text[max(0, m.start() - 20):m.start()].upper()
                if metric in ("MIN_NET_WORTH", "MIN_TANGIBLE_NET_WORTH") and ("DEBT" in before or "TANGIBLE" in before
                                                                            and metric == "MIN_NET_WORTH"):
                    continue
                window = doc.text[m.end():m.end() + 160]
                window = re.split(r"\n\s*\n", window, maxsplit=1)[0]
                # "will not permit the Leverage Ratio ... to exceed 3.50": the negation sits before the name.
                lead = doc.text[max(0, m.start() - 120):m.start()]
                prefix = "will not permit " if re.search(r"(?:shall|will)\s+not\s+permit\b[^.;]*$", lead, re.I) else ""
                found = _threshold(prefix + window, metric in AMOUNT_METRICS)
                context = doc.text[max(0, m.start() - 300):m.end() + 200]
                if not found or not _plausible(metric, found[1], context):
                    continue
                op, value, unit, s, e, _ = found
                e -= len(prefix)
                quote, page = _quote(doc, m.start(), m.end() + e)
                terms[metric] = Term(metric, "FINANCIAL", op, value, unit, _frequency(window), page, quote,
                                     None, 0.7)
                break
            if metric in terms:
                break


# Covenants on the security and on asset sales, which no ratio alias names (review RISK-01): "the value of the
# Collateral is not less than INR 10,000 Crores", "security cover of at least 1.25 times", "the value of the
# substitute collateral shall be at least 120% of ...", "assets having a value not exceeding INR 50 Crores in any
# Financial Year". Each is held for a person (confidence below the publish floor): whether such a clause is an
# ongoing covenant or a one-off condition is a credit judgement.
_AMOUNT = r"(?:INR|Rs\.?|₹)\s*" + _NUM + r"\s*(crores?|cr\b|lakhs?|lacs?)"
_FLOOR = r"(?:not\s+(?:be\s+)?less\s+than|at\s+least|minimum\s+of|no\s+less\s+than)"
_SECURITY_RULES: list[tuple[str, str, str, re.Pattern[str]]] = [
    # metric, operator, unit kind ("amount", "percent", "times"), pattern with the number in group 1
    ("SECURITY_COVER", ">=", "percent", re.compile(
        r"\b(?:value\s+of\s+the\s+(?:substitute\s+)?(?:collateral|security)|(?:security|asset|collateral)\s+cover)"
        r"[^.]{0,80}?" + _FLOOR + r"\s*" + _NUM + r"\s*(?:%|per\s*cent)", re.I)),
    ("SECURITY_COVER", ">=", "times", re.compile(
        r"\b(?:security|asset|collateral)\s+cover[^.]{0,80}?" + _FLOOR + r"\s*" + _NUM + r"\s*(?:x\b|times)", re.I)),
    ("MIN_COLLATERAL_VALUE", ">=", "amount", re.compile(
        r"\bvalue\s+of\s+the\s+(?:collateral|security)[^.%]{0,60}?" + _FLOOR + r"\s*" + _AMOUNT, re.I)),
    ("ASSET_DISPOSAL_CAP", "<=", "amount", re.compile(
        r"\b(?:dispos\w*|sell|sale|transfer)\b[^.]{0,220}?(?:not\s+exceeding|up\s+to|not\s+more\s+than)\s*"
        + _AMOUNT, re.I)),
]


def _security_terms(doc: _Doc, found: set[str]) -> list[Term]:
    out: list[Term] = []
    for metric, op, kind, pattern in _SECURITY_RULES:
        if metric in found:
            continue
        m = pattern.search(doc.text)
        if not m:
            continue
        unit = {"percent": "%", "times": "x"}.get(kind) or (m.group(2) or "").strip().lower()
        quote, page = _quote(doc, m.start(), m.end())
        out.append(Term(metric, "FINANCIAL", op, _number(m.group(1)), unit, _frequency(m.group(0)), page, quote,
                        None, 0.75))
        found.add(metric)
    return out


def _non_financial(doc: _Doc, clauses: list[_Clause]) -> list[Term]:
    out: dict[str, Term] = {}
    for c in clauses:
        heading = normalize_ws(c.heading)
        context = normalize_ws(f"{c.heading} {c.body}")
        for metric, patterns, heading_patterns in NON_FINANCIAL_RULES:
            if metric in out:
                continue
            heading_hit = any(re.search(p, heading, re.I) for p in heading_patterns)
            if not heading_hit and not _INTENT.search(context):
                continue
            if not any(re.search(p, context, re.I) for p in patterns):
                continue
            if metric == "INSURANCE_MAINTENANCE" and not re.search(
                    r"\b(?:maintain|maintained|keep|kept|renew|renewed|continue|continued)\b", context, re.I):
                continue
            quote, page = _quote(doc, c.start, c.start + len(c.heading) + 1)
            out[metric] = Term(metric, "NON_FINANCIAL", None, None, None, _frequency(context), page, quote,
                               context[:320], 0.8)
    return list(out.values())


def extract_terms(pages: list[Page]) -> list[Term]:
    """All covenant terms in the agreement: financial (with thresholds) then non-financial."""
    doc = _Doc(pages)
    if not doc.text.strip():
        return []
    clauses = _clauses(doc)
    financial = _financial(doc, clauses)
    return financial + _security_terms(doc, {t.metric for t in financial}) + _non_financial(doc, clauses)
