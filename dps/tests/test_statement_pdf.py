"""Table-aware statement PDF reader (extraction/statement_pdf.py) and its use in Flow B."""

from __future__ import annotations

import json
import math
from datetime import date
from pathlib import Path

import pytest
from test_flows import FakeParser, add_document

from dps.db import Database
from dps.extraction.statement import extract_values
from dps.extraction.statement_pdf import (
    PdfStatement,
    Word,
    choose_column,
    ocr_label,
    ocr_words,
    read_pdf_statement,
    rows_from_words,
)
from dps.models import Base

GOLDEN = Path(__file__).resolve().parents[2] / "golden-set"


@pytest.fixture
def database(tmp_path: Path) -> Database:
    db = Database(f"sqlite:///{tmp_path / 'pdf.db'}")
    Base.metadata.create_all(db.engine)
    return db


def line(y: float, *cells: tuple[str, float]) -> list[Word]:
    """Words at x positions; an amount's x is its right edge, as statements right-align numbers."""
    out = []
    for text, x in cells:
        for i, part in enumerate(text.split()):
            x0 = x + i * 40
            out.append(Word(part, x0, x0 + 5 * len(part), y))
    return out


def header(y: float, *cells: tuple[str, float]) -> list[Word]:
    """Column headings, each ending at its column's right edge as statements print them."""
    out = []
    for text, right in cells:
        parts = text.split()
        x = right - 5 * len(text)
        for part in parts:
            out.append(Word(part, x, x + 5 * len(part), y))
            x += 5 * (len(part) + 1)
    return out


def amounts(y: float, label: str, *values: tuple[str, float]) -> list[Word]:
    words = line(y, (label, 40))
    for text, right in values:
        words.append(Word(text, right - 5 * len(text), right, y))
    return words


def values_of(rows):
    return {k.key: k.value for k in extract_values(rows, "Amounts in INR crore").values if not k.derived}


def test_skips_the_note_column_and_takes_the_latest_year():
    page = (line(10, ("Particulars", 40)) + header(10, ("Note", 310), ("31 March 2026", 440), ("31 March 2025", 540))
            + amounts(30, "Inventories", ("8", 310), ("1,042.65", 440), ("1,115.62", 540))
            + amounts(50, "Total equity", ("12", 310), ("4,055.36", 440), ("4,167.58", 540))
            + amounts(70, "Long-term borrowings", ("14", 310), ("2,342.93", 440), ("1,319.77", 540)))
    v = values_of(rows_from_words([page]))
    assert v == {"INVENTORY": 1042.65, "NET_WORTH": 4055.36, "LT_DEBT": 2342.93}


def test_current_year_printed_second_and_annual_column_after_quarters():
    prior_first = (header(10, ("FY 2024-25", 360), ("FY 2025-26", 460))
                   + amounts(30, "EBITDA", ("410.00", 360), ("500.00", 460)))
    assert values_of(rows_from_words([prior_first])) == {"EBITDA": 500.0}
    quarters = (header(10, ("Quarter ended 31.03.2026", 300), ("Year ended 31.03.2026", 520),
                       ("Year ended 31.03.2025", 640))
                + amounts(30, "Revenue from operations", ("933.0", 300), ("3,455.4", 520), ("3,083.5", 640)))
    assert values_of(rows_from_words([quarters])) == {"REVENUE": 3455.4}


def test_choose_column_passes_over_consolidated_previous_and_half_year():
    vals = {0: [1.0], 1: [2.0], 2: [3.0], 3: [4.0]}
    assert choose_column({0: "Standalone 2026", 1: "Standalone 2025", 2: "Consolidated 2026", 3: "Consolidated 2025"},
                         vals) == 0
    assert choose_column({0: "Previous Year", 1: "Current Year"}, {0: [1.0], 1: [2.0]}) == 1
    assert choose_column({0: "Six months ended 30.09.2025", 1: "Year ended 31.03.2026"}, {0: [1.0], 1: [2.0]}) == 1
    assert choose_column({0: "Note"}, {0: [8.0, 9.0, 10.0]}) is None


def test_narrative_ratios_wrapped_labels_and_bare_borrowings():
    page = (line(10, ("Revenue from operations grew 12% to ₹ 5,400 crore against 4,800 last year", 40))
            + amounts(30, "Net cash generated from", ("733.33", 440), ("612.10", 540))
            + line(42, ("operating activities", 40))
            + amounts(60, "EBITDA margin %", ("18.6", 440))
            + line(80, ("Non-current liabilities", 40))
            + amounts(100, "(i) Borrowings", ("2,000.00", 440), ("1,800.00", 540))
            + line(120, ("Current liabilities", 40))
            + amounts(140, "(i) Borrowings", ("600.00", 440), ("550.00", 540)))
    v = values_of(rows_from_words([page]))
    assert v == {"CFO": 733.33, "LT_DEBT": 2000.0, "ST_DEBT": 600.0}


def test_two_tables_side_by_side_on_one_line():
    page = (header(10, ("2026", 240), ("2025", 310), ("2026", 570), ("2025", 650))
            + [Word("Inventories", 40, 95, 30), Word("845.19", 210, 240, 30), Word("800.00", 280, 310, 30),
               Word("EBITDA", 380, 410, 30), Word("610.00", 540, 570, 30), Word("580.00", 620, 650, 30)])
    assert values_of(rows_from_words([page])) == {"INVENTORY": 845.19, "EBITDA": 610.0}


def test_ocr_words_straighten_a_skewed_page_and_labels_without_spaces():
    a = math.radians(1.5)
    def rot(x, y):
        return [x * math.cos(a) - y * math.sin(a), x * math.sin(a) + y * math.cos(a)]

    def box(x0, y0, x1, y1):
        return [rot(x0, y0), rot(x1, y0), rot(x1, y1), rot(x0, y1)]
    boxes = [(box(80, 100, 400, 130), "Totalcurrentassets", 0.98), (box(1100, 100, 1300, 130), "2,615.02", 0.99)]
    words = ocr_words(boxes, dpi=200)
    assert abs(words[0].top - words[1].top) < 1.0  # same line once straightened
    rows = rows_from_words([words])
    assert [ocr_label(r[0]) for r in rows] == ["total current assets"]
    assert ocr_label("Revenue from operations") == "Revenue from operations"
    assert ocr_label("Zzzz") == "Zzzz"


@pytest.mark.parametrize("doc_id", ["FS-012", "FS-017", "FS-027", "FS-030"])
def test_golden_set_text_pdfs_read_exactly(doc_id):
    label = json.loads((GOLDEN / "labels" / f"{doc_id}.json").read_text(encoding="utf-8"))
    table = read_pdf_statement((GOLDEN / "documents" / label["sourceFile"]).read_bytes())
    got = {v.key: v.value for v in extract_values(table.rows, table.header_text).values if not v.derived}
    assert table.ocr_pages == [] and table.ocr_quality is None
    for key, want in label["values"].items():
        assert got[key] == pytest.approx(want, rel=0.005), key


def test_golden_set_scan_reads_through_rapidocr():
    pytest.importorskip("rapidocr_onnxruntime")
    from dps.parsing import RapidOcrBackend

    label = json.loads((GOLDEN / "labels" / "FS-023.json").read_text(encoding="utf-8"))
    table = read_pdf_statement((GOLDEN / "documents" / "FS-023.pdf").read_bytes(), RapidOcrBackend())
    got = {v.key: v.value for v in extract_values(table.rows, table.header_text).values if not v.derived}
    assert table.ocr_pages == [1] and table.ocr_quality > 0.97
    for key, want in label["values"].items():
        assert got[key] == pytest.approx(want, rel=0.005), key


def test_scanned_page_without_ocr_gives_no_rows():
    table = read_pdf_statement((GOLDEN / "documents" / "FS-023.pdf").read_bytes(), ocr=None)
    assert table.rows == [] and table.ocr_pages == []


def test_outflows_are_stored_as_positive_amounts():
    rows = [("Purchase of property, plant and equipment", -135.88, 1, None), ("Dividends paid", -20.0, 1, None)]
    assert values_of(rows) == {"CAPEX": 135.88, "DIVIDENDS_PAID": 20.0}


# ---------- Flow B ----------
def flow_with_reader(database, reader, name="fs.pdf", pages=None):
    from dps.flows.statement import StatementDeps, StatementProcessingFlow

    doc_id = add_document(database, "FINANCIAL_STATEMENT", name, date(2026, 3, 31), ("CP-1",))
    deps = StatementDeps(database.session_factory, lambda d: b"%PDF", FakeParser(pages or []), "ds-dps", False, reader)
    flow = StatementProcessingFlow(deps)
    flow.kickoff(inputs={"job_id": "j1", "document_id": doc_id})
    return flow


ROWS = [("EBITDA", 50.5, 1, None), ("Total debt", 120.0, 1, None), ("Net worth", 300.0, 1, None)]


def test_flow_b_uses_the_table_reader_and_holds_poor_scans(database):
    good = flow_with_reader(database, lambda d: PdfStatement(ROWS, "Rs. in crore", [], None))
    assert good.state.outcome == "REPORTED" and good.state.route == "report"

    poor = flow_with_reader(database, lambda d: PdfStatement(ROWS, "Rs. in crore", [1], 0.95))
    assert poor.state.outcome == "NEEDS_REVIEW"
    assert poor.state.issues == ["LOW_OCR_QUALITY: scanned pages read at 95% confidence"]


def test_flow_b_falls_back_to_text_lines_when_the_table_reader_fails(database):
    from dps.parsing import Page

    def broken(data):
        raise ValueError("odd pdf")

    pages = [Page(1, "Balance sheet (Rs. in crores)\nEBITDA 50.5\nTotal debt 120\nNet worth 300\n", "TEXT", 0.9)]
    flow = flow_with_reader(database, broken, pages=pages)
    assert flow.state.outcome == "REPORTED"
    empty = flow_with_reader(database, lambda d: PdfStatement([], "", [1], None), pages=[Page(1, "", "NONE", 0.0)])
    assert empty.state.outcome == "HEAVY"  # nothing read: the scan still goes to the heavy pool
