"""Flow A Normalize step: extracted terms → Core proposals (04-api-contracts.md §6.1).

A term maps to a Core library covenant by name; the expression is written in Core's DSL against the
master's own measure. A financial term with no library match becomes a custom candidate whose rule
uses DPS statement measures (``DPS_`` codes, §6.2). Non-financial terms map to MANUAL masters by name
or become MANUAL custom candidates.
"""

from __future__ import annotations

import re
from dataclasses import asdict, dataclass, field

from dps.core_client import CoreMaster
from dps.extraction.agreement import Term

# metric → (Core master names to try, Core measure used when the master does not name exactly one)
LIBRARY: dict[str, tuple[tuple[str, ...], str | None]] = {
    "DSCR": (("Minimum DSCR",), "DSCR"),
    "ICR": (("Minimum Interest Coverage",), "INTEREST_COVER"),
    "NET_LEVERAGE_RATIO": (("Maximum Net Leverage",), "NET_LEVERAGE"),
    "MIN_TANGIBLE_NET_WORTH": (("Minimum Tangible Net Worth",), "TANGIBLE_NET_WORTH"),
    "CURRENT_RATIO": (("Current Ratio Floor",), "CURRENT_RATIO"),
    "QUICK_RATIO": (("Quick Ratio Floor",), "QUICK_RATIO"),
    "DEBT_TO_EQUITY": (("Debt to Equity Ceiling",), "DEBT_EQUITY"),
    "DEBT_TO_EBITDA": (("Total Debt to EBITDA Ceiling",), "DEBT_EBITDA"),
    "DEBT_TO_NET_WORTH": (("Debt to Net Worth Ceiling",), None),  # Core V14, DPS measures
    "EBITDA_TO_EMI": (("EBITDA to EMI Floor",), None),  # Core V14, DPS measures
    "INSURANCE_MAINTENANCE": (("Insurance Cover Maintained",), None),
    "CHANGE_OF_CONTROL": (("No Change of Control",), None),
}

# Custom-candidate rules over DPS statement measures, for metrics the library does not hold yet.
DPS_FORMULAS: dict[str, str] = {
    "DSCR": "DPS_EBITDA / (DPS_PRINCIPAL_PAID + DPS_INTEREST_PAID)",
    "ICR": "DPS_EBITDA / DPS_INTEREST_PAID",
    "DEBT_TO_EBITDA": "DPS_TOTAL_DEBT / DPS_EBITDA",
    "NET_LEVERAGE_RATIO": "DPS_NET_DEBT / DPS_EBITDA",
    "CURRENT_RATIO": "DPS_CURRENT_ASSETS / DPS_CURRENT_LIABILITIES",
    "QUICK_RATIO": "(DPS_CURRENT_ASSETS - DPS_INVENTORY) / DPS_CURRENT_LIABILITIES",
    "DEBT_TO_EQUITY": "DPS_TOTAL_DEBT / DPS_NET_WORTH",
    "MIN_TANGIBLE_NET_WORTH": "DPS_TANGIBLE_NET_WORTH",
    "DEBT_TO_NET_WORTH": "DPS_TOTAL_DEBT / DPS_NET_WORTH",
    "EBITDA_TO_EMI": "DPS_EBITDA / DPS_EMI_AMOUNT",
    "MIN_NET_WORTH": "DPS_NET_WORTH",
    "FIXED_CHARGE_COVERAGE_RATIO": "DPS_EBITDA / (DPS_INTEREST_PAID + DPS_PRINCIPAL_PAID)",
    "MINIMUM_LIQUIDITY": "DPS_CASH",
    "MIN_EBITDA": "DPS_EBITDA",
}

DISPLAY_NAMES: dict[str, str] = {
    "DEBT_TO_NET_WORTH": "Debt to Net Worth Ceiling",
    "EBITDA_TO_EMI": "EBITDA to EMI Floor",
    "MIN_NET_WORTH": "Minimum Net Worth",
    "NEGATIVE_PLEDGE": "Negative Pledge",
    "REPORTING_COVENANT": "Financial Reporting",
    "COMPLIANCE_CERTIFICATE": "Compliance Certificate",
    "BOOKS_AND_RECORDS": "Books and Records",
    "REGULATORY_AND_TAX_COMPLIANCE": "Regulatory and Tax Compliance",
    "SECURITY_COVER": "Minimum Security Cover",
    "MIN_COLLATERAL_VALUE": "Minimum Collateral Value",
    "ASSET_DISPOSAL_CAP": "Asset Disposal Limit",
    "LOAN_TO_VALUE": "Maximum Loan to Value",
    "MIN_EBITDA": "Minimum EBITDA",
}

# Amount metrics are held in INR crore (the measure unit); convert the agreement's unit.
TO_CRORE = {None: 1.0, "crore": 1.0, "crores": 1.0, "cr": 1.0, "lakh": 0.01, "lakhs": 0.01, "lac": 0.01,
            "lacs": 0.01, "million": 0.1, "billion": 100.0}


@dataclass
class Proposal:
    metric_raw: str
    category: str
    covenant_master_id: str | None
    name: str
    expression: str | None
    assessment_type: str  # AUTO | MANUAL
    operator: str | None
    threshold: float | None
    unit: str | None
    frequency: str | None
    page: int | None
    quote: str
    definition: str | None
    confidence: float
    method: str = "REGEX"
    issues: list[str] = field(default_factory=list)
    threshold_raw: float | None = None  # as written in the agreement, before unit conversion

    def as_dict(self) -> dict:
        return asdict(self)


def _key(name: str) -> str:
    return re.sub(r"[^a-z0-9]", "", (name or "").lower())


def _fmt(value: float) -> str:
    return f"{value:g}" if abs(value) < 1e15 else str(value)


INFO_NAMES: dict[str, str] = {
    "AGREEMENT_DATE": "Agreement date", "EFFECTIVE_DATE": "Effective date", "MATURITY_DATE": "Maturity date",
    "FIRST_TEST_DATE": "First test date", "CURE_PERIOD_DAYS": "Cure period", "TESTING_BASIS": "Testing basis",
    "STEP_SCHEDULE": "Step-down schedule",
}


def _info_proposal(term: Term) -> Proposal:
    """FR-29/FR-30: review information, never a covenant; the value travels in ``expression``."""
    value = term.definition
    if value is None and term.threshold is not None:
        value = f"{_fmt(term.threshold)} {(term.unit or '').lower()}".strip()
    return Proposal(metric_raw=term.metric, category="INFO", covenant_master_id=None,
                    name=INFO_NAMES.get(term.metric, term.metric.title()), expression=value, assessment_type="MANUAL",
                    operator=None, threshold=term.threshold, unit=term.unit, frequency=None, page=term.page,
                    quote=term.quote, definition=None, confidence=term.confidence, method=term.method,
                    issues=list(term.issues), threshold_raw=term.threshold)


def to_proposal(term: Term, masters_by_name: dict[str, CoreMaster]) -> Proposal:
    if term.category == "INFO":
        return _info_proposal(term)
    names, measure = LIBRARY.get(term.metric, ((), None))
    master = next((masters_by_name[_key(n)] for n in names if _key(n) in masters_by_name), None)

    threshold, unit = term.threshold, term.unit
    issues = list(term.issues)
    amount = term.metric in ("MIN_NET_WORTH", "MIN_TANGIBLE_NET_WORTH", "MINIMUM_LIQUIDITY", "MIN_COLLATERAL_VALUE",
                             "ASSET_DISPOSAL_CAP", "MIN_EBITDA")
    if amount and threshold is not None and (unit == "usd" or "$" in (term.quote or "")):
        # Statement measures are INR crore: a dollar amount cannot be compared until a person converts it.
        unit = "USD"
        issues.append("AMOUNT_NOT_IN_INR: convert the threshold to INR crore before applying")
    elif amount and threshold is not None:
        threshold = round(threshold * TO_CRORE.get(unit, 1.0), 6)
        unit = "INR_CR"
    if term.metric == "LOAN_TO_VALUE":
        unit = "PERCENT"  # loan / collateral value: no statement measure, a person enters it each period

    base = dict(metric_raw=term.metric, category=term.category, operator=term.operator, threshold=threshold,
                unit=unit, frequency=term.frequency, page=term.page, quote=term.quote,
                definition=term.definition, confidence=term.confidence, method=term.method,
                issues=issues, threshold_raw=term.threshold)
    rule = f"{term.operator} {_fmt(threshold)}" if term.operator and threshold is not None else None
    if unit == "USD":
        rule = None  # held as a manual covenant until the amount is in INR crore

    if master is not None:
        manual = master.assessment_type == "MANUAL"
        lhs = master.referenced_measures[0] if len(master.referenced_measures) == 1 else measure
        lhs = lhs or DPS_FORMULAS.get(term.metric)
        expression = None if manual or rule is None or lhs is None else f"{lhs} {rule}"
        return Proposal(covenant_master_id=master.id, name=master.name, expression=expression,
                        assessment_type="MANUAL" if manual else "AUTO", **base)

    lhs = DPS_FORMULAS.get(term.metric)
    expression = f"{lhs} {rule}" if lhs and rule else None
    name = DISPLAY_NAMES.get(term.metric, term.metric.replace("_", " ").title())
    return Proposal(covenant_master_id=None, name=name, expression=expression,
                    assessment_type="AUTO" if expression else "MANUAL", **base)


def base_metric(metric_raw: str) -> str:
    """"DEBT_TO_EBITDA:SENIOR_SECURED_LEVERAGE_RATIO" -> "DEBT_TO_EBITDA" (the family of a second covenant)."""
    return metric_raw.split(":", 1)[0]


def normalize(terms: list[Term], masters: list[CoreMaster]) -> list[Proposal]:
    by_name = {_key(m.name): m for m in masters}
    out, seen = [], set()
    for t in terms:
        p = to_proposal(t, by_name)
        if t.category == "FINANCIAL" and t.metric in seen and t.name:
            # A second covenant of one family (senior secured leverage beside total leverage) cannot use the same
            # library covenant on a CP: it becomes a custom covenant under its own name, and a person checks
            # its formula, which is the family's (for example total debt, not senior debt, over EBITDA).
            p.metric_raw = f"{t.metric}:{re.sub(r'[^A-Z0-9]+', '_', t.name.upper()).strip('_')}"
            p.covenant_master_id = None
            lhs = DPS_FORMULAS.get(t.metric)
            p.expression = f"{lhs} {p.operator} {_fmt(p.threshold)}" if lhs and p.expression else None
            p.assessment_type = "AUTO" if p.expression else "MANUAL"
            p.name = t.name.title() if t.name.islower() else t.name
            p.issues.append("SECOND_COVENANT_OF_FAMILY: check the formula measures this covenant")
        seen.add(t.metric)
        out.append(p)
    return out
