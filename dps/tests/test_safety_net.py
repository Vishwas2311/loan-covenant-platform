"""Phase 1 safety net: nothing covenant-like or wrongly read passes silently (outputs/benchmarks/real-documents §12)."""

from __future__ import annotations

import json
from datetime import date

import pytest
from test_flows import FakeParser, add_document, intake, terms
from test_statement_pdf import amounts, header, line

from dps.db import Database
from dps.extraction.agreement import extract_terms
from dps.extraction.coverage import looks_like_covenant, possible_covenants
from dps.extraction.mapping import normalize
from dps.extraction.statement import identity_issues, likely_lines
from dps.extraction.statement_pdf import PdfStatement, rows_from_words
from dps.models import Base, Document, StatementValue
from dps.parsing import Page


@pytest.fixture
def database(tmp_path) -> Database:
    db = Database(f"sqlite:///{tmp_path / 'net.db'}")
    Base.metadata.create_all(db.engine)
    return db


# ----------------------------------------------------------------------------------------------------
# Accounting identities
# ----------------------------------------------------------------------------------------------------
def test_identities_catch_misreads_and_stay_quiet_on_a_sound_statement():
    sound = {"REVENUE": 551.38, "OTHER_INCOME": 3.07, "TOTAL_INCOME": 554.45, "PBT": 271.55, "TAX": 69.44,
             "PAT": 202.11, "TOTAL_ASSETS": 4286.42, "TOTAL_EQUITY_AND_LIABILITIES": 4286.42,
             "CURRENT_ASSETS": 4182.46, "CASH": 384.62, "RECEIVABLES": 91.18}
    assert identity_issues(sound) == []
    misread = dict(sound, TOTAL_EQUITY_AND_LIABILITIES=4978.50, TAX=-5.54, PAT=423.41, INVENTORY=16205.05)
    codes = [i.split(":")[0] for i in identity_issues(misread)]
    assert codes == ["BALANCE_SHEET_DOES_NOT_BALANCE", "PART_ABOVE_TOTAL", "PROFIT_DOES_NOT_ADD_UP",
                     "CURRENT_ASSETS_TOO_SMALL"]


def test_equity_income_between_pbt_and_pat_is_no_alarm():
    """US filings: PBT 51.6, tax 14.2, net earnings 34.1 (equity-method losses in between): within 15%."""
    assert identity_issues({"PBT": 51.6, "TAX": 14.2, "PAT": 34.1, "REVENUE": 1552.4}) == []


# ----------------------------------------------------------------------------------------------------
# Statement safety net
# ----------------------------------------------------------------------------------------------------
def test_an_unlabelled_total_under_current_assets_is_total_current_assets():
    page = (header(10, ("2025", 440), ("2024", 540))
            + line(30, ("Current Assets", 40))
            + amounts(50, "Inventories", ("1,117,059", 440), ("922,075", 540))
            + amounts(70, "Cash & Bank Balances", ("49,829", 440), ("68,819", 540))
            + amounts(90, "", ("2,919,855", 440), ("3,070,014", 540))
            + amounts(110, "TOTAL ASSETS", ("10,287,333", 440), ("9,424,865", 540))
            + line(130, ("Current Liabilities", 40))
            + amounts(150, "Trade & Other Payables", ("1,236,614", 440), ("1,158,104", 540))
            + amounts(170, "", ("2,221,216", 440), ("2,136,783", 540)))
    rows = rows_from_words([page])
    assert ("Total current assets", 2919855.0, 1, None) in rows
    assert ("Total current liabilities", 2221216.0, 1, None) in rows


def test_a_likely_line_is_offered_for_a_covenant_input_not_found():
    rows = [("Term loans from banks", 3069.6, 2, None),
            ("Other non-current assets", 9.0, 2, None), ("Non current tax assets (net)", 13.5, 2, None)]
    got = likely_lines(rows, found={"REVENUE"}, factor=0.1)
    assert {"key": "LT_DEBT", "label": "Term loans from banks", "value": 306.96, "page": 2,
            "cell": None} in got
    assert not any(g["key"] in ("CURRENT_ASSETS", "TAX") for g in got)


def test_a_figure_twenty_times_off_last_period_is_held_for_a_unit_check(database):
    from dps.flows.statement import StatementDeps, StatementProcessingFlow

    with database.session_factory() as s:
        s.add(StatementValue(cp_id="CP-1", key="REVENUE", value=900.36, unit="INR_CR", currency="INR",
                             as_of_date=date(2024, 3, 31), derived=False, source_type="DOCUMENT"))
        s.commit()
    rows = [("Revenue", 9003641728.0, 1, None), ("EBITDA", 50.5, 1, None), ("Total debt", 120.0, 1, None),
            ("Net worth", 300.0, 1, None), ("Term loans from banks", 30.0, 1, None)]
    doc_id = add_document(database, "FINANCIAL_STATEMENT", "fs.pdf", date(2025, 3, 31), ("CP-1",))
    deps = StatementDeps(database.session_factory, lambda d: b"%PDF", FakeParser([]), "ds-dps", False,
                         lambda d: PdfStatement(rows, "Rs. in crore", [], None, ["p"]))
    flow = StatementProcessingFlow(deps)
    flow.kickoff(inputs={"job_id": "j1", "document_id": doc_id})
    assert flow.state.outcome == "NEEDS_REVIEW"
    assert flow.state.issues == ["SCALE_JUMP: REVENUE 9003641728.0 against 900.36 for 2024-03-31"]
    with database.session_factory() as s:
        review = json.loads(s.get(Document, doc_id).review_json)
    assert {"key": "LT_DEBT", "label": "Term loans from banks", "value": 30.0, "page": 1,
            "cell": None} in review["likely"]


# ----------------------------------------------------------------------------------------------------
# Agreement safety net
# ----------------------------------------------------------------------------------------------------
UNKNOWN = "6.3 Debt Yield. The Borrower shall maintain a Debt Yield of not less than 9.0%, tested quarterly."


def test_a_covenant_dps_does_not_know_is_shown_not_dropped():
    pages = [Page(1, UNKNOWN, "TEXT", 1.0)]
    known = extract_terms(pages)
    assert [t for t in known if t.category == "FINANCIAL"] == []
    found, aside = possible_covenants(pages, known)
    [c] = found
    assert (c.category, c.name, c.page, c.issues) == ("CANDIDATE", "Debt Yield", 1,
                                                      ["POSSIBLE_COVENANT_NOT_UNDERSTOOD"])
    assert "9.0%" in c.quote and aside == {"pricing, incurrence or other condition": 0, "definition": 0}


def test_explained_sentences_baskets_and_definitions_are_not_shown():
    text = ("4.1 Debt Service Coverage Ratio. The Borrower shall maintain a DSCR of not less than 1.25 times.\n\n"
            "Investments in joint ventures shall not exceed in aggregate the greater of $73.0 million and 45.0% of "
            "LTM EBITDA.")
    pages = [Page(1, text, "TEXT", 1.0)]
    found, aside = possible_covenants(pages, extract_terms(pages))
    assert found == []
    assert aside == {"pricing, incurrence or other condition": 1, "definition": 0}
    definition = [Page(1, "“Leverage Ratio” means the ratio of Total Debt to EBITDA, which shall not exceed "
                          "3.0 to 1.0.", "TEXT", 1.0)]
    assert possible_covenants(definition, []) == ([], {"pricing, incurrence or other condition": 0, "definition": 1})


def test_a_proposal_with_another_number_does_not_explain_the_sentence():
    pages = [Page(1, "The Borrower shall maintain a minimum Debt Service Coverage Ratio of 1.25:1. Gross Debt "
                     "Service Coverage Ratio shall be maintained at a level not less than 1.50 to 1.00 annually.",
                  "TEXT", 1.0)]
    proposals = [t for t in extract_terms(pages) if t.category == "FINANCIAL"]
    found, _ = possible_covenants(pages, proposals[:1])
    assert any("1.50" in c.quote for c in found)


def test_covenant_shape_needs_a_limit_next_to_a_covenant_word():
    assert looks_like_covenant("The Borrower shall maintain a Debt Yield of not less than 9%.")
    assert not looks_like_covenant("The Borrower shall deliver financial statements within 90 days, and cash "
                                   "collateral of at least $5,000,000 for letters of credit.")


def test_possible_covenants_reach_the_review_and_core_only_when_approved(database):
    flow = intake(database, [Page(1, UNKNOWN, "TEXT", 1.0)])
    doc_id = add_document(database)
    flow.kickoff(inputs={"job_id": "j1", "document_id": doc_id})
    [cand] = [t for t in terms(database, doc_id).values() if t.category == "CANDIDATE"]
    assert cand.status == "NEEDS_REVIEW" and cand.name == "Debt Yield"
    assert json.loads(cand.issues_json) == ["POSSIBLE_COVENANT_NOT_UNDERSTOOD"]
    assert cand.metric_raw.startswith("POSSIBLE_COVENANT:1:")
    pages = [Page(1, UNKNOWN, "TEXT", 1.0)]
    [p] = normalize(extract_terms(pages) + possible_covenants(pages, [])[0], [])
    assert p.metric_raw == cand.metric_raw  # stable across runs, so a decision survives reprocessing
