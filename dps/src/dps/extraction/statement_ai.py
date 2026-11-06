"""Flow B Validate step, optional: a second reader for the statement figures (Azure OpenAI).

The table reader (statement_pdf) is the source of every value. When ``DPS_STATEMENT_AI_CHECK`` is on and a
model is configured, the model reads the same statement pages and any covenant input where the two readers
differ is held for a person (NEEDS_REVIEW). The model's numbers are never stored: a disagreement costs a
review, it never publishes a wrong figure.

Why both: on 47 real reports (outputs/benchmarks/real-documents/) the table reader was right on 91% of the
hand-checked Indian / Sri Lankan values and 80% of the SEC XBRL values; the model on 84% and 93%. Their
mistakes fall on different lines, so a value both readers agree on is far safer than either alone.
"""

from __future__ import annotations

import json
import logging
from collections.abc import Callable

from dps.extraction.statement import _EXPENSES, LINE_ALIASES, TO_CRORE, LineValue

log = logging.getLogger(__name__)

# The inputs of the covenant formulas; other lines (provisions, CWIP...) do not change a ratio.
CHECKED = ("REVENUE", "PBT", "PAT", "INTEREST_PAID", "DEPRECIATION", "TOTAL_ASSETS", "NET_WORTH", "CURRENT_ASSETS",
           "CURRENT_LIABILITIES", "LT_DEBT", "ST_DEBT", "CASH", "CFO", "INVENTORY", "RECEIVABLES")
TOLERANCE = 0.005  # half a percent: rounding between "1,234.5" and "1,234.47"
MAX_CHARS = 60000

PROMPT = """You read a company's financial statement for a bank's covenant monitoring.
Return JSON only: {{"unit": "crore|lakh|million|thousand|rupee", "values": {{KEY: number}}}}.
Use the CURRENT annual period (the latest year ended). When both standalone (company) and consolidated (group)
statements are shown use the standalone ones, otherwise use the statements given. Never a quarter, half-year or
previous year, never a ratio or percentage. Negative numbers in brackets are negative. Give each number
exactly as printed in the stated unit (do not convert). Leave out any key the statement does not print.
Keys and what they mean: {keys}

Statement text:
{text}"""

Completion = Callable[[list[dict]], str]


def ai_read(text: str, completion: Completion) -> dict[str, float]:
    """The model's reading of the checked keys, in crore like the table reader's values."""
    keys = "; ".join(f"{k}: {LINE_ALIASES[k][0]}" for k in CHECKED)
    answer = completion([{"role": "user", "content": PROMPT.format(keys=keys, text=text[:MAX_CHARS])}])
    data = json.loads(answer[answer.find("{"): answer.rfind("}") + 1])
    factor = TO_CRORE.get(str(data.get("unit", "crore")).lower(), 1.0)
    out = {}
    for key, value in (data.get("values") or {}).items():
        if key in CHECKED and isinstance(value, (int, float)):
            out[key] = round(float(value) * factor, 6)
    return out


def disagreements(values: list[LineValue], theirs: dict[str, float]) -> list[str]:
    """One issue per covenant input the two readers read differently (a line only one of them found is not one)."""
    issues = []
    for v in values:
        if v.derived or v.key not in CHECKED or v.key not in theirs:
            continue
        other = theirs[v.key]
        if v.key in _EXPENSES:
            other = abs(other)  # expenses are stored positive; the model may keep the bracket
        if abs(v.value - other) > max(0.01, TOLERANCE * abs(v.value)):
            issues.append(f"SECOND_READER_DIFFERS: {v.key} read {v.value}, second reader {other}")
    return issues


def check(text: str, values: list[LineValue], completion: Completion) -> list[str]:
    """Issues from the second reader; none when it fails (it is a check, not a step the flow depends on)."""
    try:
        return disagreements(values, ai_read(text, completion))
    except Exception as ex:  # noqa: BLE001 - a model outage must not stop statements from being read
        log.warning("Second reader unavailable: %s", type(ex).__name__)
        return []
