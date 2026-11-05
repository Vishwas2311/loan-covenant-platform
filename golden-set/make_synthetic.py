"""Builds the synthetic part of the golden set from a written specification (truth known by construction).

The ten agreements (AGR-011..AGR-020) use the wording Indian loan agreements use and that broke extraction
before ("shall not be less than", "shall not fall below", "shall at no time exceed", amounts in lakh and
crore), with decoy numbers (notice periods, drawdown minimums, collateral values) that are not financial
covenants. The ten statements (FS-001..FS-010) state their line items in lakh, crore or rupees, as CSV and
XLSX, with the labels banks use.

Each label is written from the same specification that writes the document, so it is right by
construction and has status SYNTHETIC. The analyst-labelled real agreements stay the release gate; the
synthetic set is the regression gate that runs in CI.

    python golden-set/make_synthetic.py            # writes documents/, labels/ and updates manifest.csv
"""

from __future__ import annotations

import csv
import hashlib
import io
import json
import textwrap
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent
DOCS, LABELS, MANIFEST = HERE / "documents", HERE / "labels", HERE / "manifest.csv"
LABEL_BY = "generator: make_synthetic.py (truth by construction)"


# ---------- a small multi-page text PDF writer (Helvetica, A4) ----------

LINES_PER_PAGE, WIDTH = 50, 92


def _pdf(pages: list[list[str]]) -> bytes:
    def esc(s: str) -> str:
        return s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")

    objs: list[str] = []
    kids = []
    for lines in pages:
        content = "BT /F1 10 Tf 50 800 Td 14 TL " + " ".join(f"({esc(ln)}) '" for ln in lines) + " ET"
        objs.append(f"<< /Length {len(content.encode('latin-1'))} >>\nstream\n{content}\nendstream")
        objs.append("PAGE")
        kids.append(len(objs))
    n_fixed = 3  # catalog, pages, font come first
    body = ["<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [" + " ".join(f"{k + n_fixed} 0 R" for k in kids) + f"] /Count {len(kids)} >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
    for i, o in enumerate(objs, start=1):
        if o == "PAGE":
            body.append(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents {i - 1 + n_fixed} 0 R "
                        "/Resources << /Font << /F1 3 0 R >> >> >>")
        else:
            body.append(o)
    out, offsets = b"%PDF-1.4\n", []
    for i, o in enumerate(body, 1):
        offsets.append(len(out))
        out += f"{i} 0 obj\n{o}\nendobj\n".encode("latin-1")
    xref = len(out)
    out += f"xref\n0 {len(body) + 1}\n0000000000 65535 f \n".encode()
    out += b"".join(f"{o:010d} 00000 n \n".encode() for o in offsets)
    out += f"trailer\n<< /Size {len(body) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    return out


@dataclass
class Clause:
    text: str
    metric: str | None = None  # None: not a covenant (decoy or boilerplate)
    category: str = "FINANCIAL"
    operator: str | None = None
    threshold: float | None = None
    unit: str | None = None
    frequency: str | None = None


@dataclass
class Agreement:
    doc_id: str
    borrower: str
    sections: list[tuple[str, list[Clause]]] = field(default_factory=list)


def layout(a: Agreement) -> tuple[list[list[str]], list[dict]]:
    """Pages of text lines, and the label entries with the page each covenant landed on."""
    pages: list[list[str]] = [[]]
    covenants: list[dict] = []

    def room(n: int) -> None:
        if len(pages[-1]) + n > LINES_PER_PAGE:
            pages.append([])

    for heading, clauses in a.sections:
        room(3)
        pages[-1] += ["", heading.upper()]
        for c in clauses:
            lines = textwrap.wrap(c.text, WIDTH)
            room(len(lines) + 1)
            pages[-1] += lines + [""]
            if c.metric:
                covenants.append({"metric": c.metric, "category": c.category, "operator": c.operator,
                                  "threshold": c.threshold, "unit": c.unit, "frequency": c.frequency,
                                  "page": len(pages), "quote": c.text})
    return pages, covenants


def F(text, metric, op, value, unit=None, freq="QUARTERLY"):  # noqa: N802 - reads like a table row
    return Clause(text, metric, "FINANCIAL", op, value, unit, freq)


def N(text, metric):  # noqa: N802
    return Clause(text, metric, "NON_FINANCIAL")


def D(text):  # noqa: N802 - decoy: numbers that are not covenants
    return Clause(text)


def boilerplate(borrower: str, amount: str) -> list[tuple[str, list[Clause]]]:
    return [
        ("Article 1 Definitions and Interpretation", [
            D(f"This Facility Agreement is made between {borrower} (the Borrower) and Bharat Commercial Bank "
              f"Limited (the Lender) for a term loan facility of {amount}."),
            D("Business Day means a day on which banks are open for general business in Mumbai. Financial Year "
              "means the period of twelve months ending on 31 March of each year."),
            D("EBITDA means profit before tax, interest, depreciation and amortisation, as shown in the audited "
              "consolidated financial statements of the Borrower."),
        ]),
        ("Article 2 The Facility", [
            D("The Borrower may request a drawdown by giving the Lender not less than 5 (five) Business Days "
              "notice, and each drawdown shall not be less than INR 25 crore."),
            D("The Borrower may prepay the facility after giving not less than 30 (thirty) days prior written "
              "notice to the Lender."),
        ]),
    ]


def default_events() -> tuple[str, list[Clause]]:
    return ("Article 9 Events of Default", [
        D("Any amount unpaid for more than 7 (seven) days after its due date is an Event of Default."),
        D("A cure period of 30 (thirty) days applies to any breach of the financial covenants that is capable "
          "of remedy."),
    ])


AGREEMENTS = [
    Agreement("AGR-011", "Konkan Cements Limited", [
        *boilerplate("Konkan Cements Limited", "INR 850 crore"),
        ("Article 7 Financial Covenants", [
            F("7.1 The Borrower shall ensure that the Debt Service Coverage Ratio shall not be less than 1.35 "
              "times, tested quarterly on a trailing twelve month basis.", "DSCR", ">=", 1.35),
            F("7.2 Total Debt to EBITDA shall not be more than 3.50 times on each test date.",
              "DEBT_TO_EBITDA", "<=", 3.5),
            F("7.3 The Interest Coverage Ratio shall at no time be lower than 2.25 times.", "ICR", ">=", 2.25),
            F("7.4 The Borrower shall maintain a Tangible Net Worth of not less than INR 600 Crores at all times.",
              "MIN_TANGIBLE_NET_WORTH", ">=", 600, "crores"),
        ]),
        ("Article 8 Undertakings", [
            N("8.1 The Borrower shall not create any lien, charge or encumbrance over its assets without the "
              "prior written consent of the Lender.", "NEGATIVE_PLEDGE"),
            N("8.2 The Borrower shall keep its plant and stocks insured against all risks and shall maintain "
              "the insurance policies in force throughout the tenor.", "INSURANCE_MAINTENANCE"),
        ]),
        default_events(),
    ]),
    Agreement("AGR-012", "Deccan Agro Foods Private Limited", [
        *boilerplate("Deccan Agro Foods Private Limited", "INR 120 crore"),
        ("Article 6 Financial Covenants", [
            F("6.1 The DSCR of the Borrower shall not fall below 1.20 times for any Financial Year.", "DSCR",
              ">=", 1.2, freq="YEARLY"),
            F("6.2 The Current Ratio shall not be less than 1.10 times.", "CURRENT_RATIO", ">=", 1.1),
            F("6.3 Total Debt to Net Worth shall not exceed 2.00 times.", "DEBT_TO_NET_WORTH", "<=", 2.0),
        ]),
        ("Article 7 Undertakings", [
            N("7.1 The Borrower shall maintain proper books of account at its registered office.",
              "BOOKS_AND_RECORDS"),
        ]),
        default_events(),
    ]),
    Agreement("AGR-013", "Sahyadri Power Transmission Limited", [
        *boilerplate("Sahyadri Power Transmission Limited", "INR 2,400 crore"),
        ("Article 10 Financial Covenants", [
            F("10.1 The Borrower shall ensure that its Debt Service Coverage Ratio is at least 1.30 times.",
              "DSCR", ">=", 1.3),
            F("10.2 The Net Debt to EBITDA ratio shall at no time exceed 4.75 times.", "NET_LEVERAGE_RATIO",
              "<=", 4.75),
            F("10.3 The Fixed Charge Coverage Ratio shall not be lower than 1.15 times.",
              "FIXED_CHARGE_COVERAGE_RATIO", ">=", 1.15),
        ]),
        ("Article 11 Security", [
            D("The value of the Collateral shall be at least 133% of the outstanding facility, as certified by an "
              "approved valuer every year."),
        ]),
        default_events(),
    ]),
    Agreement("AGR-014", "Malwa Textiles Limited", [
        *boilerplate("Malwa Textiles Limited", "INR 75 crore"),
        ("Article 5 Financial Covenants", [
            F("5.1 The Borrower shall maintain a Net Worth of not less than INR 45,000 lakhs.", "MIN_NET_WORTH",
              ">=", 45000, "lakhs"),
            F("5.2 Debt to Equity shall not be more than 1.75 times.", "DEBT_TO_EQUITY", "<=", 1.75),
            F("5.3 The Interest Coverage Ratio shall not be less than 3.00 times.", "ICR", ">=", 3.0),
        ]),
        ("Article 8 Undertakings", [
            N("8.1 The Borrower shall not, without the prior written consent of the Lender, create any lien over "
              "its assets.", "NEGATIVE_PLEDGE"),
        ]),
        default_events(),
    ]),
    Agreement("AGR-015", "Coromandel Logistics Limited", [
        *boilerplate("Coromandel Logistics Limited", "INR 310 crore"),
        ("Article 7 Financial Covenants", [
            F("7.1 Total Debt to EBITDA shall not be greater than 3.00:1 on each Quarter Date.",
              "DEBT_TO_EBITDA", "<=", 3.0),
            F("7.2 The Debt Service Coverage Ratio must be no less than 1.25 times.", "DSCR", ">=", 1.25),
            F("7.3 The Quick Ratio shall not be less than 0.90 times.", "QUICK_RATIO", ">=", 0.9),
        ]),
        default_events(),
    ]),
    Agreement("AGR-016", "Narmada Chemicals Limited", [
        *boilerplate("Narmada Chemicals Limited", "INR 540 crore"),
        ("Article 9 Financial Covenants", [
            F("9.1 The Borrower shall ensure that the Interest Coverage Ratio does not fall below 2.50 times.",
              "ICR", ">=", 2.5),
            F("9.2 The Borrower shall maintain Minimum Liquidity of not less than INR 40 crore in unencumbered "
              "cash at all times.", "MINIMUM_LIQUIDITY", ">=", 40, "crore"),
            F("9.3 The ratio of Total Debt to Net Worth shall not be more than 1.50 times.", "DEBT_TO_NET_WORTH",
              "<=", 1.5),
        ]),
        ("Article 10 Undertakings", [
            N("10.1 The Borrower shall maintain insurance over its plants with reputable insurers and keep the "
              "policies in force.", "INSURANCE_MAINTENANCE"),
        ]),
        default_events(),
    ]),
    Agreement("AGR-017", "Vindhya Steel Works Limited", [
        *boilerplate("Vindhya Steel Works Limited", "INR 1,150 crore"),
        ("Article 7 Financial Covenants", [
            F("7.1 The Net Leverage Ratio shall not exceed 4.00 times until 31 March 2027 and shall not exceed "
              "3.50 times thereafter.", "NET_LEVERAGE_RATIO", "<=", 4.0),
            F("7.2 The DSCR shall at no time be less than 1.40 times.", "DSCR", ">=", 1.4),
        ]),
        default_events(),
    ]),
    Agreement("AGR-018", "Godavari Healthcare Limited", [
        *boilerplate("Godavari Healthcare Limited", "INR 260 crore"),
        ("Article 6 Financial Covenants", [
            F("6.1 EBITDA to EMI shall not be less than 1.50 times in any quarter.", "EBITDA_TO_EMI", ">=", 1.5),
            F("6.2 The Current Ratio of the Borrower shall be at least 1.33 times.", "CURRENT_RATIO", ">=", 1.33),
            F("6.3 Total Debt to EBITDA shall not be more than 2.75 times.", "DEBT_TO_EBITDA", "<=", 2.75),
        ]),
        ("Article 7 Undertakings", [
            N("7.1 The Borrower shall maintain proper books of account and records in accordance with Indian "
              "Accounting Standards.", "BOOKS_AND_RECORDS"),
        ]),
        default_events(),
    ]),
    Agreement("AGR-019", "Aravali Infrastructure Limited", [
        *boilerplate("Aravali Infrastructure Limited", "INR 3,000 crore"),
        ("Article 12 Financial Covenants", [
            F("12.1 The Borrower shall ensure that the Debt Service Coverage Ratio shall not be less than 1.15 "
              "times during construction and shall not be less than 1.30 times after commercial operation.",
              "DSCR", ">=", 1.15),
            F("12.2 Debt to Equity shall at no time exceed 3.00 times.", "DEBT_TO_EQUITY", "<=", 3.0),
            F("12.3 The Borrower shall maintain a Tangible Net Worth of not less than INR 1,200 crore.",
              "MIN_TANGIBLE_NET_WORTH", ">=", 1200, "crore"),
        ]),
        default_events(),
    ]),
    Agreement("AGR-020", "Brahmaputra Tea Estates Limited", [
        *boilerplate("Brahmaputra Tea Estates Limited", "INR 60 crore"),
        ("Article 5 Financial Covenants", [
            F("5.1 The Interest Coverage Ratio shall not be lower than 1.75 times for any Financial Year.",
              "ICR", ">=", 1.75, freq="YEARLY"),
            F("5.2 Total Debt to EBITDA shall not be more than 4.00 times.", "DEBT_TO_EBITDA", "<=", 4.0),
            F("5.3 The Borrower shall maintain a Net Worth of not less than INR 9,500 lakhs.", "MIN_NET_WORTH",
              ">=", 9500, "lakhs"),
        ]),
        ("Article 6 Undertakings", [
            N("6.1 The Borrower shall not create any lien over its tea estates without the consent of the Lender.",
              "NEGATIVE_PLEDGE"),
        ]),
        default_events(),
    ]),
]


# ---------- statements: line items with a known value in INR crore ----------

UNITS = {"lakhs": ("Amounts in INR lakhs", 100.0), "crore": ("Amounts in INR crore", 1.0),
         "rupees": ("Amounts in INR", 1e7)}

STATEMENTS = [
    # id, unit, format, {label as written: (key, value in crore)}
    ("FS-001", "lakhs", "csv", {"Revenue from operations": ("REVENUE", 412.5), "EBITDA": ("EBITDA", 61.2),
                                "Finance costs": ("INTEREST_PAID", 9.8), "Short-term borrowings": ("ST_DEBT", 22.0),
                                "Long-term borrowings": ("LT_DEBT", 118.0), "Total equity": ("NET_WORTH", 240.0),
                                "Cash and cash equivalents": ("CASH", 14.5)}),
    ("FS-002", "crore", "csv", {"Revenue from operations": ("REVENUE", 1250.0), "EBITDA": ("EBITDA", 210.0),
                                "Finance costs": ("INTEREST_PAID", 38.0), "Long-term borrowings": ("LT_DEBT", 640.0),
                                "Short-term borrowings": ("ST_DEBT", 75.0), "Total equity": ("NET_WORTH", 905.0)}),
    ("FS-003", "rupees", "csv", {"Revenue from operations": ("REVENUE", 88.4), "EBITDA": ("EBITDA", 11.3),
                                 "Finance costs": ("INTEREST_PAID", 2.1), "Long-term borrowings": ("LT_DEBT", 31.0),
                                 "Total equity": ("NET_WORTH", 47.5), "Cash and cash equivalents": ("CASH", 3.2)}),
    ("FS-004", "lakhs", "xlsx", {"Revenue from operations": ("REVENUE", 560.0), "EBITDA": ("EBITDA", 70.0),
                                 "Finance costs": ("INTEREST_PAID", 12.5), "Short-term borrowings": ("ST_DEBT", 40.0),
                                 "Long-term borrowings": ("LT_DEBT", 160.0), "Total equity": ("NET_WORTH", 300.0)}),
    ("FS-005", "crore", "xlsx", {"Revenue from operations": ("REVENUE", 3120.0), "EBITDA": ("EBITDA", 455.0),
                                 "Finance costs": ("INTEREST_PAID", 96.0), "Long-term borrowings": ("LT_DEBT", 1840.0),
                                 "Short-term borrowings": ("ST_DEBT", 210.0), "Total equity": ("NET_WORTH", 2210.0),
                                 "Cash and cash equivalents": ("CASH", 145.0)}),
    ("FS-006", "lakhs", "csv", {"Revenue from operations": ("REVENUE", 95.0), "EBITDA": ("EBITDA", 9.5),
                                "Finance costs": ("INTEREST_PAID", 3.4), "Long-term borrowings": ("LT_DEBT", 41.0),
                                "Total equity": ("NET_WORTH", 26.0)}),
    ("FS-007", "crore", "csv", {"Revenue from operations": ("REVENUE", 740.0), "EBITDA": ("EBITDA", 98.0),
                                "Finance costs": ("INTEREST_PAID", 21.0), "Short-term borrowings": ("ST_DEBT", 60.0),
                                "Long-term borrowings": ("LT_DEBT", 300.0), "Total equity": ("NET_WORTH", 410.0),
                                "Cash and cash equivalents": ("CASH", 37.0)}),
    ("FS-008", "rupees", "xlsx", {"Revenue from operations": ("REVENUE", 22.6), "EBITDA": ("EBITDA", 2.9),
                                  "Finance costs": ("INTEREST_PAID", 0.8), "Long-term borrowings": ("LT_DEBT", 9.4),
                                  "Total equity": ("NET_WORTH", 12.1)}),
    ("FS-009", "lakhs", "xlsx", {"Revenue from operations": ("REVENUE", 1830.0), "EBITDA": ("EBITDA", 240.0),
                                 "Finance costs": ("INTEREST_PAID", 55.0), "Short-term borrowings": ("ST_DEBT", 150.0),
                                 "Long-term borrowings": ("LT_DEBT", 720.0), "Total equity": ("NET_WORTH", 980.0)}),
    ("FS-010", "crore", "csv", {"Revenue from operations": ("REVENUE", 205.0), "EBITDA": ("EBITDA", 26.5),
                                "Finance costs": ("INTEREST_PAID", 5.5), "Long-term borrowings": ("LT_DEBT", 88.0),
                                "Short-term borrowings": ("ST_DEBT", 12.0), "Total equity": ("NET_WORTH", 130.0),
                                "Cash and cash equivalents": ("CASH", 9.0)}),
]


def statement_rows(unit: str, items: dict) -> list[list]:
    header, per_crore = UNITS[unit]
    return [[header, ""]] + [[label, round(v * per_crore, 2)] for label, (_, v) in items.items()]


def statement_values(items: dict) -> dict:
    values = {key: v for key, v in items.values()}
    if "LT_DEBT" in values or "ST_DEBT" in values:  # a derived line every statement reader works out
        values["TOTAL_DEBT"] = round(values.get("LT_DEBT", 0.0) + values.get("ST_DEBT", 0.0), 4)
    return values


def statement_bytes(fmt: str, rows: list[list]) -> bytes:
    if fmt == "csv":
        buf = io.StringIO()
        csv.writer(buf, lineterminator="\n").writerows(rows)
        return buf.getvalue().encode()
    from openpyxl import Workbook

    wb = Workbook()
    ws = wb.active
    ws.title = "Statement"
    for row in rows:
        ws.append(row)
    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


def write_label(doc_id: str, payload: dict) -> None:
    (LABELS / f"{doc_id}.json").write_text(json.dumps(payload, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")


def main() -> None:
    DOCS.mkdir(exist_ok=True)
    LABELS.mkdir(exist_ok=True)
    rows = {}
    if MANIFEST.exists():
        with MANIFEST.open(newline="", encoding="utf-8") as f:
            for r in csv.DictReader(f):
                rows[r["documentId"]] = r
    for a in AGREEMENTS:
        pages, covenants = layout(a)
        data = _pdf(pages)
        name = f"{a.doc_id}.pdf"
        (DOCS / name).write_bytes(data)
        write_label(a.doc_id, {"documentId": a.doc_id, "type": "LOAN_AGREEMENT", "sourceFile": name,
                               "sha256": hashlib.sha256(data).hexdigest(), "pages": len(pages),
                               "status": "SYNTHETIC", "labelledBy": LABEL_BY, "reviewedBy": None,
                               "covenants": covenants})
        rows[a.doc_id] = {"documentId": a.doc_id, "type": "LOAN_AGREEMENT", "sourceFile": name,
                          "pages": str(len(pages)), "covenants": str(len(covenants)), "status": "SYNTHETIC"}
    for doc_id, unit, fmt, items in STATEMENTS:
        data = statement_bytes(fmt, statement_rows(unit, items))
        name = f"{doc_id}.{fmt}"
        (DOCS / name).write_bytes(data)
        values = statement_values(items)
        write_label(doc_id, {"documentId": doc_id, "type": "FINANCIAL_STATEMENT", "sourceFile": name,
                             "sha256": hashlib.sha256(data).hexdigest(), "unit": "INR crore", "status": "SYNTHETIC",
                             "labelledBy": LABEL_BY, "reviewedBy": None, "values": values})
        rows[doc_id] = {"documentId": doc_id, "type": "FINANCIAL_STATEMENT", "sourceFile": name, "pages": "",
                        "covenants": str(len(values)), "status": "SYNTHETIC"}
    with MANIFEST.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["documentId", "type", "sourceFile", "pages", "covenants", "status"],
                           lineterminator="\n")
        w.writeheader()
        for doc_id in sorted(rows):
            w.writerow(rows[doc_id])
    print(f"wrote {len(AGREEMENTS)} agreements and {len(STATEMENTS)} statements")


if __name__ == "__main__":
    main()
