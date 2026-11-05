"""Flow B Extract/Calculate steps: financial statement line items (04-api-contracts.md §6.2).

Reads label/value rows from PDF text, XLSX or CSV, maps labels to the 20 catalog keys, converts amounts to
INR crore, then derives missing inputs from the ones present (e.g. TOTAL_DEBT = ST_DEBT + LT_DEBT).
"""

from __future__ import annotations

import csv
import io
import re
from dataclasses import asdict, dataclass, field

from dps.parsing import ParseError

# Catalog key → label phrases (lower case, matched on whole words; longest phrase wins).
LINE_ALIASES: dict[str, list[str]] = {
    "REVENUE": ["revenue from operations", "total revenue", "net sales", "revenue", "turnover"],
    "EBITDA": ["ebitda", "operating profit before depreciation"],
    "EBIT": ["ebit", "operating profit"],
    "DEPRECIATION": ["depreciation and amortisation", "depreciation and amortization", "depreciation"],
    "INTEREST_PAID": ["interest paid", "finance costs", "finance cost", "interest expense"],
    "PRINCIPAL_PAID": ["principal repaid", "principal paid", "repayment of borrowings", "principal repayment"],
    "EMI_AMOUNT": ["emi amount", "emi", "instalment amount", "installment amount"],
    "PAT": ["profit after tax", "net profit", "pat"],
    "TOTAL_DEBT": ["total debt", "total borrowings"],
    "ST_DEBT": ["short-term borrowings", "short term borrowings", "short-term debt", "short term debt"],
    "LT_DEBT": ["long-term borrowings", "long term borrowings", "long-term debt", "long term debt"],
    "NET_DEBT": ["net debt"],
    "CASH": ["cash and cash equivalents", "cash and bank balances", "cash and equivalents", "cash"],
    "NET_WORTH": ["total equity", "net worth", "shareholders funds", "shareholders' funds"],
    "TANGIBLE_NET_WORTH": ["tangible net worth"],
    "CURRENT_ASSETS": ["total current assets", "current assets"],
    "CURRENT_LIABILITIES": ["total current liabilities", "current liabilities"],
    "INVENTORY": ["inventories", "inventory"],
    "RECEIVABLES": ["trade receivables", "receivables", "debtors"],
    "CAPEX": ["capital expenditure", "capex", "purchase of property, plant and equipment"],
    # C6: the rest of a full spread (balance sheet, profit and loss, cash flow).
    "OTHER_INCOME": ["other income"],
    "TOTAL_INCOME": ["total income"],
    "MATERIAL_COST": ["cost of materials consumed", "cost of goods sold", "cost of sales", "raw materials consumed"],
    "EMPLOYEE_COST": ["employee benefits expense", "employee benefit expense", "employee costs", "staff costs"],
    "OTHER_EXPENSES": ["other expenses"],
    "TOTAL_EXPENSES": ["total expenses"],
    "PBT": ["profit before tax", "profit before income tax", "pbt"],
    "TAX": ["total tax expense", "tax expense", "income tax expense", "provision for tax"],
    "FIXED_ASSETS": ["property, plant and equipment", "property plant and equipment", "net fixed assets",
                     "fixed assets"],
    "CWIP": ["capital work-in-progress", "capital work in progress", "cwip"],
    "INTANGIBLES": ["intangible assets", "goodwill and intangible assets"],
    "INVESTMENTS": ["non-current investments", "investments"],
    "TOTAL_ASSETS": ["total assets"],
    "SHARE_CAPITAL": ["equity share capital", "share capital"],
    "RESERVES": ["other equity", "reserves and surplus"],
    "TRADE_PAYABLES": ["trade payables", "creditors"],
    "PROVISIONS": ["provisions"],
    "LEASE_LIABILITIES": ["lease liabilities"],
    "TOTAL_EQUITY_AND_LIABILITIES": ["total equity and liabilities", "total liabilities and equity"],
    "CFO": ["net cash from operating activities", "net cash generated from operating activities",
            "net cash flow from operating activities", "cash generated from operations"],
    "CFI": ["net cash used in investing activities", "net cash from investing activities",
            "net cash flow from investing activities"],
    "CFF": ["net cash used in financing activities", "net cash from financing activities",
            "net cash flow from financing activities"],
    "DIVIDENDS_PAID": ["dividends paid", "dividend paid"],
}

# C6: which statement each standard line belongs to, in the order a spread shows them.
SECTIONS: dict[str, list[str]] = {
    "PROFIT_AND_LOSS": ["REVENUE", "OTHER_INCOME", "TOTAL_INCOME", "MATERIAL_COST", "EMPLOYEE_COST",
                        "OTHER_EXPENSES", "TOTAL_EXPENSES", "EBITDA", "DEPRECIATION", "EBIT", "INTEREST_PAID", "PBT",
                        "TAX", "PAT"],
    "BALANCE_SHEET": ["FIXED_ASSETS", "CWIP", "INTANGIBLES", "INVESTMENTS", "INVENTORY", "RECEIVABLES", "CASH",
                      "CURRENT_ASSETS", "TOTAL_ASSETS", "SHARE_CAPITAL", "RESERVES", "NET_WORTH",
                      "TANGIBLE_NET_WORTH", "LT_DEBT", "ST_DEBT", "TOTAL_DEBT", "NET_DEBT", "LEASE_LIABILITIES",
                      "TRADE_PAYABLES", "PROVISIONS", "CURRENT_LIABILITIES", "TOTAL_EQUITY_AND_LIABILITIES"],
    "CASH_FLOW": ["CFO", "CAPEX", "CFI", "PRINCIPAL_PAID", "EMI_AMOUNT", "DIVIDENDS_PAID", "CFF"],
}
# Lines that contain an alias but mean something else.
_EXCLUDE = re.compile(r"\b(ratio|margin|%|per\s+share|growth|cover)\b", re.I)

TO_CRORE = {"crore": 1.0, "lakh": 0.01, "million": 0.1, "billion": 100.0, "thousand": 0.0001, "rupee": 1e-7}
_UNIT_HINT = re.compile(r"(?:in|amounts?\s+in|₹|rs\.?|inr)\s*(?:in\s+)?(crores?|cr\b|lakhs?|lacs?|millions?|mn\b|"
                        r"billions?|bn\b|thousands?|'000|rupees)", re.I)
# "Amounts in INR" / "in rupees" with no scale word after it: plain rupees (golden set: such a statement
# was read as crore, every amount 10^7 too large).
_RUPEE_HINT = re.compile(r"(?:amounts?\s+in|figures?\s+in|in)\s+(?:inr|rs\.?|₹|rupees)\b(?!\s*(?:in\s+)?(?:crores?|"
                         r"cr\b|lakhs?|lacs?|millions?|mn\b|billions?|bn\b|thousands?|'000))", re.I)
_NUMBER = re.compile(r"\(?-?[0-9][0-9,]*(?:\.[0-9]+)?\)?")
REQUIRED = ("EBITDA", "TOTAL_DEBT", "NET_WORTH")


@dataclass
class LineValue:
    key: str
    value: float  # INR crore
    unit: str
    derived: bool = False
    page: int | None = None
    cell: str | None = None
    label: str = ""

    def as_dict(self) -> dict:
        return asdict(self)


@dataclass
class StatementResult:
    values: list[LineValue] = field(default_factory=list)
    unit_scale: str = "crore"
    issues: list[str] = field(default_factory=list)


def _unit(text: str) -> str:
    """The scale the statement states; "crore" when it states none (see :func:`unit_stated`)."""
    m = _UNIT_HINT.search(text or "")
    if not m:
        return "rupee" if _RUPEE_HINT.search(text or "") else "crore"
    word = m.group(1).lower()
    if word.startswith(("cr",)):
        return "crore"
    if word.startswith(("lakh", "lac")):
        return "lakh"
    if word.startswith(("mill", "mn")):
        return "million"
    if word.startswith(("bill", "bn")):
        return "billion"
    if word.startswith(("thous", "'000")):
        return "thousand"
    return "rupee"


def unit_stated(text: str) -> bool:
    return bool(_UNIT_HINT.search(text or "") or _RUPEE_HINT.search(text or ""))


def match_key(label: str) -> str | None:
    text = " " + re.sub(r"\s+", " ", (label or "").lower()).strip() + " "
    if _EXCLUDE.search(text):
        return None
    best, best_len = None, 0
    for key, phrases in LINE_ALIASES.items():
        for phrase in phrases:
            if re.search(r"(?<![a-z])" + re.escape(phrase) + r"(?![a-z])", text) and len(phrase) > best_len:
                best, best_len = key, len(phrase)
    return best


def parse_number(raw: str) -> float | None:
    s = (raw or "").strip()
    if not s or s in ("-", "—"):
        return None
    negative = s.startswith("(") and s.endswith(")") or s.startswith("-")
    digits = s.strip("()").lstrip("-").replace(",", "")
    try:
        value = float(digits)
    except ValueError:
        return None
    return -value if negative else value


def rows_from_text(pages: list[tuple[int, str]]) -> list[tuple[str, float, int | None, str | None]]:
    """(label, first number on the line, page, None) for each 'label .... number' line."""
    out = []
    for page_no, text in pages:
        for line in (text or "").splitlines():
            first = _NUMBER.search(line)
            if not first or first.start() == 0:
                continue
            label = line[:first.start()].strip(" .:-\t")
            if re.search(r"\d", label[-1:] or ""):
                continue
            value = parse_number(first.group(0))
            if label and value is not None:
                out.append((label, value, page_no, None))
    return out


def rows_from_xlsx(data: bytes) -> list[tuple[str, float, int | None, str | None]]:
    from openpyxl import load_workbook

    wb = load_workbook(io.BytesIO(data), read_only=True, data_only=True)
    out = []
    for ws in wb.worksheets:
        for row in ws.iter_rows():
            label = None
            for cell in row:
                v = cell.value
                if isinstance(v, str) and v.strip() and label is None:
                    label = v.strip()
                elif label and isinstance(v, (int, float)) and not isinstance(v, bool):
                    out.append((label, float(v), None, f"{ws.title}!{cell.coordinate}"))
                    break
                elif label and isinstance(v, str) and parse_number(v) is not None:
                    out.append((label, parse_number(v), None, f"{ws.title}!{cell.coordinate}"))
                    break
    return out


def _column(index: int) -> str:
    """0 -> A, 25 -> Z, 26 -> AA (spreadsheet column letters for cell references)."""
    letters = ""
    index += 1
    while index:
        index, rest = divmod(index - 1, 26)
        letters = chr(65 + rest) + letters
    return letters


def _xls_book(data: bytes):
    import xlrd

    try:
        return xlrd.open_workbook(file_contents=data)
    except xlrd.XLRDError as ex:  # not a real .xls (or an encrypted one): a clear reason for the reviewer
        raise ParseError(f"Unreadable .xls statement: {ex}") from ex


def rows_from_xls(data: bytes) -> list[tuple[str, float, int | None, str | None]]:
    """FR-01: the older Excel format (.xls), read like .xlsx: first text cell is the label, first number its value."""
    book = _xls_book(data)
    out = []
    for sheet in book.sheets():
        for r in range(sheet.nrows):
            label = None
            for c, value in enumerate(sheet.row_values(r)):
                if isinstance(value, str) and value.strip() and label is None:
                    label = value.strip()
                elif label and isinstance(value, float):
                    out.append((label, value, None, f"{sheet.name}!{_column(c)}{r + 1}"))
                    break
                elif label and isinstance(value, str) and parse_number(value) is not None:
                    out.append((label, parse_number(value), None, f"{sheet.name}!{_column(c)}{r + 1}"))
                    break
    return out


def header_from_xls(data: bytes, max_rows: int = 10) -> str:
    book = _xls_book(data)
    return " ".join(str(v) for s in book.sheets() for r in range(min(max_rows, s.nrows))
                    for v in s.row_values(r) if isinstance(v, str) and v.strip())


def header_from_xlsx(data: bytes, max_rows: int = 10) -> str:
    """Text of the first rows of each sheet, where a unit note ("Amounts in INR lakhs") usually sits."""
    from openpyxl import load_workbook

    wb = load_workbook(io.BytesIO(data), read_only=True, data_only=True)
    parts = []
    for ws in wb.worksheets:
        for row in ws.iter_rows(max_row=max_rows, values_only=True):
            parts.extend(str(v) for v in row if isinstance(v, str))
    return " ".join(parts)


def header_from_csv(data: bytes, max_lines: int = 10) -> str:
    return " ".join(data.decode("utf-8-sig", errors="replace").splitlines()[:max_lines])


def rows_from_csv(data: bytes) -> list[tuple[str, float, int | None, str | None]]:
    text = data.decode("utf-8-sig", errors="replace")
    out = []
    for i, row in enumerate(csv.reader(io.StringIO(text)), start=1):
        if len(row) < 2:
            continue
        for j, cell in enumerate(row[1:], start=2):
            value = parse_number(cell)
            if value is not None:
                out.append((row[0].strip(), value, None, f"R{i}C{j}"))
                break
    return out


def extract_values(rows: list[tuple[str, float, int | None, str | None]], header_text: str) -> StatementResult:
    scale = _unit(header_text)
    factor = TO_CRORE[scale]
    found: dict[str, LineValue] = {}
    for label, value, page, cell in rows:
        key = match_key(label)
        if key and key not in found:  # first occurrence = current period / primary statement
            found[key] = LineValue(key, round(value * factor, 6), "INR_CR", False, page, cell, label[:120])
    result = StatementResult(list(found.values()), scale)
    derive(result)
    check(result)
    if found and not unit_stated(header_text):
        # Assuming crore when nothing says so must not pass silently: a person confirms the scale.
        result.issues.append("UNIT_NOT_STATED: amounts read as INR crore")
    return result


def _put(result: StatementResult, key: str, value: float, *inputs: str) -> None:
    result.values.append(LineValue(key, round(value, 6), "INR_CR", True, label="derived from " + " + ".join(inputs)))


def derive(result: StatementResult) -> None:
    """Calculate step: fill missing inputs from the ones reported."""
    v = {x.key: x.value for x in result.values}
    parts = [k for k in ("ST_DEBT", "LT_DEBT") if k in v]
    if "TOTAL_DEBT" not in v and parts:  # a statement with no short-term line has none to add
        v["TOTAL_DEBT"] = sum(v[k] for k in parts)
        _put(result, "TOTAL_DEBT", v["TOTAL_DEBT"], *parts)
    if "EBITDA" not in v and "EBIT" in v and "DEPRECIATION" in v:
        v["EBITDA"] = v["EBIT"] + abs(v["DEPRECIATION"])
        _put(result, "EBITDA", v["EBITDA"], "EBIT", "DEPRECIATION")
    if "NET_DEBT" not in v and "TOTAL_DEBT" in v and "CASH" in v:
        _put(result, "NET_DEBT", v["TOTAL_DEBT"] - v["CASH"], "TOTAL_DEBT", "-CASH")
    if "EMI_AMOUNT" not in v and "INTEREST_PAID" in v and "PRINCIPAL_PAID" in v:
        _put(result, "EMI_AMOUNT", abs(v["INTEREST_PAID"]) + abs(v["PRINCIPAL_PAID"]), "INTEREST_PAID",
             "PRINCIPAL_PAID")


def check(result: StatementResult) -> None:
    """Validate step data-quality rules (03 §3)."""
    v = {x.key: x.value for x in result.values}
    missing = [k for k in REQUIRED if k not in v]
    if missing:
        result.issues.append("MISSING_LINE_ITEMS: " + ", ".join(missing))
    for key in ("TOTAL_DEBT", "ST_DEBT", "LT_DEBT", "CASH", "CURRENT_ASSETS", "CURRENT_LIABILITIES", "INVENTORY",
                "RECEIVABLES"):
        if v.get(key, 0) < 0:
            result.issues.append(f"NEGATIVE_VALUE: {key}")
    if {"TOTAL_DEBT", "ST_DEBT", "LT_DEBT"} <= v.keys():
        parts = v["ST_DEBT"] + v["LT_DEBT"]
        if abs(parts - v["TOTAL_DEBT"]) > max(0.01, 0.01 * abs(v["TOTAL_DEBT"])):
            result.issues.append(f"DEBT_DOES_NOT_ADD_UP: {v['TOTAL_DEBT']} vs {round(parts, 6)}")
    if "EBITDA" in v and "EBIT" in v and v["EBIT"] > v["EBITDA"] + 1e-9:
        result.issues.append("EBIT_ABOVE_EBITDA")
