"""Rules learned from 50 real statements and 50 real loan agreements (India, Sri Lanka, SEC filings).

Each case is the shape of a line or page that a reader got wrong in the benchmark
(outputs/benchmarks/real-documents/), cut down to the words that matter.
"""

from __future__ import annotations

import io
import json
from datetime import date

import pytest
from pypdf import PdfWriter
from test_flows import FakeParser, add_document
from test_statement_pdf import amounts, header, line

from dps.db import Database
from dps.extraction.agreement import extract_terms
from dps.extraction.statement import _unit, extract_values, match_key
from dps.extraction.statement_ai import check, disagreements
from dps.extraction.statement_pages import rank, statement_pages, usable
from dps.extraction.statement_pdf import PdfStatement, _text_words, choose_column, rows_from_words
from dps.models import Base
from dps.parsing import Page


@pytest.fixture
def database(tmp_path) -> Database:
    db = Database(f"sqlite:///{tmp_path / 'real.db'}")
    Base.metadata.create_all(db.engine)
    return db


# ----------------------------------------------------------------------------------------------------
# Units
# ----------------------------------------------------------------------------------------------------
@pytest.mark.parametrize(("header_text", "unit"), [
    ("All amounts are in I Lakhs unless otherwise stated", "lakh"),  # the ₹ glyph extracted as "I"
    ("GROUP COMPANY 2025 2024 Rs.’000 Rs.’000", "thousand"),  # typographic apostrophe
    ("Un-Audited Rs 000", "thousand"),
    ("amounts in rupee", "rupee"),
    ("(In millions of dollars, except per share amounts)", "million"),
    ("2025 Rs. 2024 Rs.", "rupee"),  # a Sri Lankan column headed just "Rs."
])
def test_units_as_real_statements_print_them(header_text, unit):
    assert _unit(header_text) == unit


# ----------------------------------------------------------------------------------------------------
# Labels
# ----------------------------------------------------------------------------------------------------
@pytest.mark.parametrize(("label", "key"), [
    ("Total Non Current Assets", None),
    ("Total Non-Current Liabilities", None),
    ("Total Current Assets", "CURRENT_ASSETS"),
    ("Other current assets", None),
    ("Impairment Reversal of Trade Receivables", None),
    ("(Increase)/decrease in trade receivables", None),
    ("change in Accounts receivable", None),
    ("Trade & Other Payables", "TRADE_PAYABLES"),
    ("Stated Capital", "SHARE_CAPITAL"),
    ("Total Shareholder's Fund", "NET_WORTH"),
    ("Equity attributable to Owners of the Company", "NET_WORTH"),
    ("Profit/(Loss) for the year (V-VI)", "PAT"),
    ("Profit before exceptional items and tax", None),
    ("Employee benefits", "EMPLOYEE_COST"),
    ("Total tax expense (VI)", "TAX"),
])
def test_labels_from_real_statements(label, key):
    assert match_key(label) == key


def _values(rows, header_text="Amounts in INR crore"):
    return {v.key: v.value for v in extract_values(rows, header_text).values if not v.derived}


def test_indian_total_revenue_is_total_income_and_the_total_tax_line_wins():
    v = _values([("Revenue from operations", 16571.03, 1, None), ("Other Income", 177.90, 1, None),
                 ("Total revenue", 16748.93, 1, None), ("Current tax", 470.79, 1, None),
                 ("Total tax expense", 538.61, 1, None)])
    assert (v["REVENUE"], v["TOTAL_INCOME"], v["TAX"]) == (16571.03, 16748.93, 538.61)


def test_tax_sign_follows_a_statement_that_brackets_its_expenses():
    """Sri Lankan statements bracket every deduction: "(316,523)" is a tax charge, a bare "8,993" a credit."""
    charged = _values([("Cost of Sales", -5946763, 1, None), ("Administrative Expenses", -347497, 1, None),
                       ("Income Tax Expense", -336697, 1, None)], "Rs. '000")
    credit = _values([("Administrative Expenses", -102418, 1, None), ("Finance Costs", -98559, 1, None),
                      ("Income Tax Expense", 8993, 1, None)], "Rs. '000")
    plain = _values([("Employee benefits expense", 87.39, 1, None), ("Tax expense", 69.44, 1, None)])
    assert charged["TAX"] == pytest.approx(33.6697)
    assert credit["TAX"] == pytest.approx(-0.8993)
    assert plain["TAX"] == 69.44


def test_trade_payables_split_by_micro_and_small_enterprises_are_added():
    v = _values([("Total outstanding dues of micro enterprises and small enterprises", 1074.97, 1, None),
                 ("Total outstanding dues of creditors other than micro enterprises", 18094.50, 1, None)])
    assert v["TRADE_PAYABLES"] == pytest.approx(19169.47)


def test_total_equity_replaces_the_share_attributable_to_owners():
    v = _values([("Equity attributable to owners of the company", 6485.07, 1, None),
                 ("Non-controlling interest", 10107.08, 1, None), ("Total equity", 16592.15, 1, None)])
    assert v["NET_WORTH"] == 16592.15


# ----------------------------------------------------------------------------------------------------
# Columns
# ----------------------------------------------------------------------------------------------------
def test_company_column_under_a_group_company_heading():
    page = (header(5, ("GROUP", 400), ("COMPANY", 600))
            + header(10, ("2025", 340), ("2024", 440), ("2025", 540), ("2024", 640))
            + amounts(30, "Revenue", ("88,888,780", 340), ("75,895,448", 440), ("134,396", 540), ("132,732", 640))
            + amounts(50, "Profit for the Year", ("2,541,129", 340), ("60,831", 440), ("646,690", 540),
                      ("682,802", 640)))
    rows = rows_from_words([page])
    assert {r[0]: r[1] for r in rows} == {"Revenue": 134396.0, "Profit for the Year": 646690.0}


def test_year_column_under_a_quarter_and_year_heading_and_change_columns_skipped():
    page = (header(5, ("Quarter ended", 380), ("For the Year ended", 680))
            + header(10, ("31/03/2025", 300), ("31/03/2024", 400), ("Change", 480), ("31/03/2025", 600),
                     ("31/03/2024", 700), ("Change", 780))
            + amounts(30, "Revenue", ("1,652,963", 300), ("1,793,518", 400), ("(140,555)", 480),
                      ("7,285,916", 600), ("7,214,937", 700), ("70,979", 780)))
    assert rows_from_words([page]) == [("Revenue", 7285916.0, 1, None)]


def test_percent_change_column_does_not_hide_the_row_or_head_a_column():
    page = (header(5, ("GROUP", 400), ("COMPANY", 700))
            + header(10, ("2025", 340), ("2024", 440), ("% Change", 520), ("2025", 640), ("2024", 740))
            + amounts(30, "Profit Before Tax", ("349,412", 340), ("91,036", 440), ("284%", 520), ("316,376", 640),
                      ("66,151", 740)))
    assert rows_from_words([page]) == [("Profit Before Tax", 316376.0, 1, None)]


def test_signature_dates_below_the_statement_head_no_column():
    page = (header(10, ("For the year ended 31st March 2025", 440), ("For the year ended 31st March 2024", 600))
            + amounts(30, "Revenue from operations", ("16,571.03", 440), ("13,079.84", 600))
            + amounts(50, "Finance costs", ("218.27", 440), ("277.57", 600))
            + line(80, ("Date: 29th Day of May, 2025", 520)))
    assert [r[1] for r in rows_from_words([page])] == [16571.03, 218.27]


def test_unnamed_note_column_with_a_sub_note_is_still_a_note_column():
    vals = {0: [25.0, 26.0, 29.0, 30.0, 31.0, 34.07], 1: [16363.10, 123.67, 1383.22, 781.46, 273.65, 29.15]}
    assert choose_column({0: "2025", 1: "31st March 2025"}, vals) == 1


def test_short_dates_give_the_year():
    vals = {0: [1.0, 2.0], 1: [3.0, 4.0]}
    assert choose_column({0: "Company 31-Mar-24 Audited", 1: "Company 30-Sep-25 Un-Audited"}, vals) == 1


def test_digits_spaced_apart_in_the_pdf_are_one_amount():
    class FakePage:
        def extract_words(self, x_tolerance):
            return [{"text": "Other", "x0": 40.0, "x1": 60.0, "top": 127.0},
                    {"text": "1", "x0": 401.6, "x1": 404.7, "top": 127.0},
                    {"text": "2,225.05", "x0": 404.66, "x1": 427.1, "top": 127.0},
                    {"text": "10,822.75", "x0": 476.3, "x1": 501.7, "top": 127.0}]

    assert [w.text for w in _text_words(FakePage())] == ["Other", "12,225.05", "10,822.75"]


def test_cash_flow_working_capital_lines_are_movements_not_balances():
    page = (header(10, ("2025", 440), ("2024", 540))
            + amounts(30, "Accounts receivable, net", ("1,092.0", 440), ("950.0", 540))
            + line(50, ("Changes in operating assets and liabilities:", 40))
            + amounts(70, "Accounts receivable", ("(1,532.9)", 440), ("(10.0)", 540))
            + amounts(90, "Net cash provided by operating activities", ("2,000.0", 440), ("1,800.0", 540)))
    v = {x.key: x.value for x in extract_values(rows_from_words([page]), "In millions").values if not x.derived}
    assert v["RECEIVABLES"] == pytest.approx(109.2)
    assert v["CFO"] == pytest.approx(200.0)


def test_borrowings_take_the_numbered_current_liabilities_heading():
    page = (header(10, ("2025", 440), ("2024", 540))
            + line(30, ("(2) Non-current liabilities", 40))
            + amounts(50, "(i) Borrowings", ("15,167.50", 440), ("13,030.22", 540))
            + line(70, ("(3) Current liabilities", 40))
            + amounts(90, "(i) Borrowings", ("6,797.99", 440), ("17,203.08", 540))
            + line(110, ("Current Liabilities", 40))
            + amounts(130, "Interest Bearing Loans & Borrowings", ("654,626", 440), ("716,295", 540)))
    assert [r[0] for r in rows_from_words([page])] == ["Long-term borrowings", "Short-term borrowings",
                                                       "Short-term borrowings"]


# ----------------------------------------------------------------------------------------------------
# Page finder
# ----------------------------------------------------------------------------------------------------
BALANCE_SHEET = ("STANDALONE BALANCE SHEET AS AT 31 MARCH 2025\nAssets\nProperty, plant and equipment 14.51 12.75\n"
                 "Inventories 1,040.72 723.61\nTrade receivables 6,213.80 6,031.38\nTotal current assets 21,729.20\n"
                 "Total assets 24,292.93 21,046.86\nEquity share capital 174.63\nOther equity 3,336.20\n"
                 "Total equity 3,510.84 2,892.75\nTotal liabilities 20,782.08\n")
NOTES = ("Notes to the financial statements\n5. Property, plant and equipment\nGross block 1,234.56 1,111.11\n"
         "Total assets held for sale 12.00\n")
AUDIT = "INDEPENDENT AUDITOR'S REPORT\nTo the members. We have audited the balance sheet and total assets."


def test_the_statement_page_outranks_notes_and_the_auditors_report():
    assert rank([AUDIT, NOTES, BALANCE_SHEET, NOTES])["BS"][0] == 2


PROFIT = ("STATEMENT OF PROFIT OR LOSS\n{when}\nRevenue 35,861 27,725\nFinance Cost (11,260) (439)\n"
          "Profit Before Tax 349,412 91,036\nIncome Tax Expense (28,917) (27,651)\nProfit for the period 320,495\n"
          "Earnings per share 12.59\n")


def test_period_statement_outranks_the_quarter_and_ten_year_summaries_score_down():
    quarter, period = PROFIT.format(when="For the Quarter Ended 30th September"), PROFIT.format(
        when="For the Period Ended 30th September")
    assert rank([quarter, period])["PL"][0] == 1
    assert rank(["DECADE AT A GLANCE\n" + period, period])["PL"][0] == 1


def test_short_files_are_read_whole_and_garbled_text_is_not_usable():
    w = PdfWriter()
    for _ in range(3):
        w.add_blank_page(612, 792)
    buf = io.BytesIO()
    w.write(buf)
    assert statement_pages(buf.getvalue()) is None
    assert not usable("(cid:12)(cid:13)(cid:14)(cid:15)(cid:16) Total assets " * 3)
    assert usable(BALANCE_SHEET)


# ----------------------------------------------------------------------------------------------------
# Agreements
# ----------------------------------------------------------------------------------------------------
@pytest.mark.parametrize(("text", "metric", "op", "value"), [
    ("The Borrower will not permit the Consolidated Interest Coverage Ratio as of the end of any fiscal quarter "
     "to be less than 3.00 to 1.00.", "ICR", ">=", 3.0),
    ("The Borrower will not permit the Consolidated Leverage Ratio as of the end of any fiscal quarter to exceed "
     "3.50 to 1.00.", "DEBT_TO_EBITDA", "<=", 3.5),
    ("The Fixed Charge Coverage Ratio shall equal or exceed 1.25 to 1.00.", "FIXED_CHARGE_COVERAGE_RATIO", ">=",
     1.25),
    ("Funded Debt to EBITDA shall never exceed 4.00 to 1.00.", "DEBT_TO_EBITDA", "<=", 4.0),
])
def test_us_credit_agreement_covenants(text, metric, op, value):
    [term] = [t for t in extract_terms([Page(1, text, "TEXT", 1.0)]) if t.category == "FINANCIAL"]
    assert (term.metric, term.operator, term.threshold) == (metric, op, value)


@pytest.mark.parametrize("text", [
    "Incremental term loans may be made if, on a pro forma basis, the Total Net Leverage Ratio would not exceed 4.00.",
    "Applicable Rate: Pricing Level I, where the Consolidated Leverage Ratio is less than 1.50 to 1.00.",
    "The ECF Percentage is 50% if the First Lien Net Leverage Ratio is greater than 3.25.",
    "The Leverage Ratio shall not exceed 90 days.",
])
def test_pricing_grids_incurrence_tests_and_sweeps_are_not_covenants(text):
    assert [t for t in extract_terms([Page(1, text, "TEXT", 1.0)]) if t.category == "FINANCIAL"] == []


# ----------------------------------------------------------------------------------------------------
# Second reader (optional AI cross-check)
# ----------------------------------------------------------------------------------------------------
def _answer(values: dict, unit: str = "lakh"):
    def completion(messages):
        assert "CURRENT annual period" in messages[0]["content"]
        return "Here you are: " + json.dumps({"unit": unit, "values": values})
    return completion


def test_second_reader_flags_only_covenant_inputs_it_reads_differently():
    ours = extract_values([("Revenue from operations", 16363.10, 1, None), ("Finance costs", 781.47, 1, None),
                           ("Profit before tax", -965.62, 1, None), ("Provisions", 55.0, 1, None)],
                          "All amounts in Rs. lakhs").values
    agree = _answer({"REVENUE": 16363.1006, "INTEREST_PAID": -781.4659, "PBT": -965.6177, "PROVISIONS": 1.0})
    assert check("text", ours, agree) == []
    differ = _answer({"REVENUE": 17245.00, "PBT": 965.62})
    assert check("text", ours, differ) == [
        "SECOND_READER_DIFFERS: REVENUE read 163.631, second reader 172.45",
        "SECOND_READER_DIFFERS: PBT read -9.6562, second reader 9.6562"]


def test_second_reader_outage_is_no_issue():
    def down(messages):
        raise TimeoutError("model down")
    assert check("text", extract_values([("Revenue", 10.0, 1, None)], "Rs. in crore").values, down) == []


def test_flow_b_holds_a_statement_the_second_reader_disagrees_with(database):
    from dps.flows.statement import StatementDeps, StatementProcessingFlow

    rows = [("Revenue", 100.0, 1, None), ("EBITDA", 50.5, 1, None), ("Total debt", 120.0, 1, None),
            ("Net worth", 300.0, 1, None)]
    seen = []

    def second(text, values):
        seen.append(text)
        return disagreements(values, {"REVENUE": 110.0})

    doc_id = add_document(database, "FINANCIAL_STATEMENT", "fs.pdf", date(2026, 3, 31), ("CP-1",))
    deps = StatementDeps(database.session_factory, lambda d: b"%PDF", FakeParser([]), "ds-dps", False,
                         lambda d: PdfStatement(rows, "Rs. in crore", [], None, ["page one", "page two"]), second)
    flow = StatementProcessingFlow(deps)
    flow.kickoff(inputs={"job_id": "j1", "document_id": doc_id})
    assert seen == ["page one\n\npage two"]
    assert flow.state.outcome == "NEEDS_REVIEW"
    assert flow.state.issues == ["SECOND_READER_DIFFERS: REVENUE read 100.0, second reader 110.0"]


def test_a_long_report_is_read_only_on_its_statement_pages():
    """A golden statement inside a 16-page file: the same values, from its own pages, and OCR only near them."""
    from pathlib import Path

    from pypdf import PdfReader

    from dps.extraction.statement_pdf import read_pdf_statement

    golden = Path(__file__).resolve().parents[2] / "golden-set"
    label = json.loads((golden / "labels" / "FS-012.json").read_text(encoding="utf-8"))
    source = (golden / "documents" / label["sourceFile"]).read_bytes()
    w = PdfWriter()
    for _ in range(13):
        w.add_blank_page(612, 792)
    for page in PdfReader(io.BytesIO(source)).pages:
        w.add_page(page)
    buf = io.BytesIO()
    w.write(buf)

    asked = []

    class FakeOcr:
        def ocr_boxes(self, data, index, dpi):
            asked.append(index)
            return []

    chosen, main = statement_pages(buf.getvalue(), FakeOcr())
    assert min(chosen) >= 13 and 1 <= main <= len(chosen)
    assert all(i >= 11 for i in asked)  # only pages next to the candidates, never the whole report
    table = read_pdf_statement(buf.getvalue())
    got = {v.key: v.value for v in extract_values(table.rows, table.header_text).values if not v.derived}
    for key, want in label["values"].items():
        assert got[key] == pytest.approx(want, rel=0.005), key
    assert {r[2] for r in table.rows} <= {i + 1 for i in chosen}
