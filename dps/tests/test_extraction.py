from __future__ import annotations

from pathlib import Path

import pytest
from pypdf import PdfWriter

from dps.core_client import CoreMaster
from dps.extraction.agreement import Term, _Doc, _quote, _threshold, extract_terms
from dps.extraction.mapping import normalize
from dps.extraction.validation import FAILED, NEEDS_REVIEW, PUBLISH, check_conflicts, validate
from dps.parsing import Page, ParseError, ParserEngine, needs_heavy, text_quality

GOLDEN = Path(__file__).resolve().parents[2] / "golden-set" / "documents"

AGREEMENT = [
    Page(1, "LOAN AGREEMENT\nArticle 4 Financial Covenants\n\n"
            "4.1 Debt Service Coverage Ratio (DSCR)\n"
            "    The Borrower shall maintain a DSCR of not less than 1.25 times, tested quarterly.\n"
            "        DSCR = EBITDA / (Principal_Paid + Interest_Paid)\n"
            "    Required threshold: >= 1.25x.\n\n"
            "4.2 Debt to Net Worth Ratio\n"
            "    The Borrower shall ensure that its Debt to Net Worth Ratio does not\n", "TEXT", 0.9),
    Page(2, "    exceed 2.50 times.\n\n"
            "4.3 Net Worth\n"
            "    The Borrower shall maintain a minimum Net Worth of INR 5,000 Crores.\n\n"
            "5.1 Negative Pledge\n"
            "    The Borrower shall not create any lien over its assets.\n\n"
            "5.2 Books and Records\n"
            "    Proper books are kept at the registered office.\n\n"
            "5.3 Insurance\n"
            "    The Borrower must hold insurance policies over the assets.\n", "TEXT", 0.9),
    Page(3, "SCHEDULE 3 COMPLIANCE TABLE\nCurrent Ratio Not less than 1.10:1 [Ratio]\n", "TEXT", 0.9),
]


def _by_metric(terms: list[Term]) -> dict[str, Term]:
    return {t.metric: t for t in terms}


def test_extracts_financial_and_non_financial_terms_with_pages_and_quotes():
    terms = _by_metric(extract_terms(AGREEMENT))

    dscr = terms["DSCR"]
    assert (dscr.operator, dscr.threshold, dscr.page, dscr.frequency) == (">=", 1.25, 1, "QUARTERLY")
    assert dscr.confidence == 0.95  # words and symbol agree
    assert dscr.definition == "DSCR = EBITDA / (Principal_Paid + Interest_Paid)"
    assert "not less than 1.25" in dscr.quote

    dnw = terms["DEBT_TO_NET_WORTH"]  # phrase straddles the page break: quoted from page 2
    assert (dnw.operator, dnw.threshold, dnw.page) == ("<=", 2.5, 2)
    assert dnw.confidence == 0.85

    nw = terms["MIN_NET_WORTH"]
    assert (nw.operator, nw.threshold, nw.unit) == (">=", 5000.0, "crores")

    cr = terms["CURRENT_RATIO"]  # outside any numbered clause: fallback scan
    assert (cr.operator, cr.threshold, cr.page, cr.confidence) == (">=", 1.1, 3, 0.7)

    assert terms["NEGATIVE_PLEDGE"].category == "NON_FINANCIAL"
    assert terms["BOOKS_AND_RECORDS"].page == 2  # heading-only rule
    assert "INSURANCE_MAINTENANCE" not in terms  # no maintain/keep obligation


def test_empty_document_yields_nothing():
    assert extract_terms([Page(1, "  ", "NONE", 0.0)]) == []


def test_threshold_rules():
    assert _threshold("of 3 percent", amount=False) is None
    assert _threshold("not less than 500", amount=True) is None  # too small to be an amount floor
    assert _threshold("not exceeding INR 20 lakhs", amount=True)[:3] == ("<=", 20.0, "lakhs")
    assert _threshold("≥ 1.5x", amount=False)[:2] == (">=", 1.5)
    assert _threshold("more than 2", amount=False)[:2] == (">", 2.0)
    assert _threshold("less than 4", amount=False)[:2] == ("<", 4.0)


def test_quote_outside_document_is_empty():
    doc = _Doc([])
    assert _quote(doc, 0, 1) == ("", None)
    long_doc = _Doc([Page(1, "x" * 400 + "\nvalue not less than 2", "TEXT", 1.0)])
    quote, page = _quote(long_doc, 401, 420)
    assert page == 1 and len(quote) <= 300 and quote.endswith("2")


def test_frequency_mapping():
    pages = [Page(1, "4.1 Current Ratio\nThe Borrower shall keep a Current Ratio not less than 1.2 tested monthly.\n"
                     "4.2 Quick Ratio\nQuick Ratio not less than 1.0 tested annually.\n"
                     "4.3 Debt to Equity\nDebt to Equity not more than 2 tested half-yearly.\n", "TEXT", 1.0)]
    terms = _by_metric(extract_terms(pages))
    assert terms["CURRENT_RATIO"].frequency == "MONTHLY"
    assert terms["QUICK_RATIO"].frequency == "YEARLY"
    assert terms["DEBT_TO_EQUITY"].frequency is None


MASTERS = [
    CoreMaster("m-dscr", "Minimum DSCR", "FINANCIAL", "AUTO", ("DSCR",)),
    CoreMaster("m-cr", "Current Ratio Floor", "FINANCIAL", "AUTO", ("CURRENT_ASSETS", "CURRENT_LIAB")),
    CoreMaster("m-ins", "Insurance Cover Maintained", "NON_FINANCIAL", "MANUAL", ()),
    CoreMaster("m-emi", "EBITDA to EMI Floor", "FINANCIAL", "AUTO", ("DPS_EBITDA", "DPS_EMI_AMOUNT")),
]


def test_normalize_maps_library_custom_and_units():
    terms = extract_terms(AGREEMENT) + [
        Term("INSURANCE_MAINTENANCE", "NON_FINANCIAL", None, None, None, None, 2, "q", None, 0.8),
        Term("MIN_TANGIBLE_NET_WORTH", "FINANCIAL", ">=", 20.0, "lakhs", None, 2, "q", None, 0.85),
        Term("QUICK_RATIO", "FINANCIAL", ">=", 1.0, None, None, 2, "q", None, 0.85),
        Term("INVENTORY_DAYS", "FINANCIAL", "<=", 90.0, None, None, 2, "q", None, 0.85),
    ]
    props = {p.metric_raw: p for p in normalize(terms, MASTERS)}

    assert (props["DSCR"].covenant_master_id, props["DSCR"].expression) == ("m-dscr", "DSCR >= 1.25")
    assert props["CURRENT_RATIO"].expression == "CURRENT_RATIO >= 1.1"  # multi-measure master: mapped measure
    assert props["INSURANCE_MAINTENANCE"].expression is None
    assert props["INSURANCE_MAINTENANCE"].assessment_type == "MANUAL"

    dnw = props["DEBT_TO_NET_WORTH"]
    assert dnw.covenant_master_id is None and dnw.name == "Debt to Net Worth Ceiling"
    assert dnw.expression == "DPS_TOTAL_DEBT / DPS_NET_WORTH <= 2.5"
    emi = normalize([Term("EBITDA_TO_EMI", "FINANCIAL", ">=", 1.2, None, None, 1, "q", None, 0.9)], MASTERS)[0]
    assert (emi.covenant_master_id, emi.expression) == ("m-emi", "DPS_EBITDA / DPS_EMI_AMOUNT >= 1.2")

    nw = props["MIN_NET_WORTH"]
    assert (nw.expression, nw.unit, nw.threshold_raw) == ("DPS_NET_WORTH >= 5000", "INR_CR", 5000.0)
    tnw = props["MIN_TANGIBLE_NET_WORTH"]
    assert (tnw.threshold, tnw.unit) == (0.2, "INR_CR")

    quick = props["QUICK_RATIO"]  # library entry absent in this Core: DPS-measure rule instead
    assert quick.covenant_master_id is None
    assert quick.expression == "(DPS_CURRENT_ASSETS - DPS_INVENTORY) / DPS_CURRENT_LIABILITIES >= 1"
    days = props["INVENTORY_DAYS"]  # no library entry and no DPS rule: the RM writes it
    assert (days.expression, days.assessment_type, days.name) == (None, "MANUAL", "Inventory Days")
    assert props["NEGATIVE_PLEDGE"].name == "Negative Pledge"


def test_validate_routes_and_flags_issues():
    props = normalize(extract_terms(AGREEMENT), MASTERS)
    route = validate(AGREEMENT, props, lambda e: ["unknown measure"] if "DPS_" in e else [], 0.8)
    assert route == NEEDS_REVIEW
    by = {p.metric_raw: p for p in props}
    assert by["DSCR"].issues == []
    assert by["DEBT_TO_NET_WORTH"].issues == ["FORMULA_INVALID: unknown measure"]
    assert by["CURRENT_RATIO"].issues == ["LOW_CONFIDENCE"]


def test_validate_publish_failed_and_empty():
    props = normalize([t for t in extract_terms(AGREEMENT) if t.metric == "DSCR"], MASTERS)
    assert validate(AGREEMENT, props, lambda e: [], 0.8) == PUBLISH
    assert validate([Page(1, " ", "NONE", 0.0)], [], lambda e: [], 0.8) == FAILED
    assert validate(AGREEMENT, [], lambda e: [], 0.8) == NEEDS_REVIEW


def test_quote_checks():
    [p] = normalize([Term("DSCR", "FINANCIAL", ">=", 9.0, None, None, 1, "Required threshold: >= 1.25x.", None,
                          0.9)], MASTERS)
    validate(AGREEMENT, [p], lambda e: [], 0.8)
    assert p.issues == ["THRESHOLD_NOT_IN_QUOTE"]
    [q] = normalize([Term("DSCR", "FINANCIAL", ">=", 1.25, None, None, 1, "invented text 1.25", None, 0.9)], MASTERS)
    [r] = normalize([Term("DSCR", "FINANCIAL", ">=", 1.25, None, None, 7, "x", None, 0.9)], MASTERS)
    validate(AGREEMENT, [q, r], lambda e: [], 0.8)
    assert q.issues[0] == "QUOTE_NOT_FOUND"
    assert "QUOTE_PAGE_MISSING" in r.issues


def test_conflicting_thresholds_are_flagged():
    a, b = normalize([Term("DSCR", "FINANCIAL", ">=", 1.25, None, None, 1, "q", None, 0.9),
                      Term("DSCR", "FINANCIAL", ">=", 1.5, None, None, 1, "q", None, 0.9)], MASTERS)
    b.issues.append("THRESHOLD_CONFLICT")
    check_conflicts([a, b])
    assert a.issues == ["THRESHOLD_CONFLICT"] and b.issues == ["THRESHOLD_CONFLICT"]


class _Ocr:
    def __init__(self, fail: bool = False) -> None:
        self.fail = fail

    def ocr_page(self, pdf_bytes: bytes, page_index: int) -> tuple[str, float]:
        if self.fail:
            raise RuntimeError("model missing")
        return self.text, 0.9

    text = ("Scanned covenant text: the Borrower shall maintain a Debt Service Coverage Ratio of not less "
            "than 1.25 times, tested on each Quarter Date.")


def _blank_pdf(pages: int = 1) -> bytes:
    import io

    w = PdfWriter()
    for _ in range(pages):
        w.add_blank_page(width=200, height=200)
    buf = io.BytesIO()
    w.write(buf)
    return buf.getvalue()


def test_parser_uses_ocr_only_for_pages_without_text():
    pdf = _blank_pdf(2)
    assert [p.method for p in ParserEngine().parse(pdf)] == ["NONE", "NONE"]
    ocr = ParserEngine(_Ocr()).parse(pdf)
    assert [p.method for p in ocr] == ["OCR", "OCR"] and ocr[0].quality > 0.5
    failed = ParserEngine(_Ocr(fail=True)).parse(pdf)
    assert [p.quality for p in failed] == [0.0, 0.0]
    assert needs_heavy(failed) and needs_heavy([]) and not needs_heavy(ocr)
    # A blurred scan: OCR is confident about a few fragments, which is not a readable page.
    garbled = _Ocr()
    garbled.text = "TEHULEANPASLIRYLOALPUENE un.mmooom TRGmY:"
    poor = ParserEngine(garbled).parse(pdf)
    assert [p.quality for p in poor] == [0.3, 0.3] and needs_heavy(poor)


def test_parser_rejects_non_pdf():
    with pytest.raises(ParseError):
        ParserEngine().parse(b"not a pdf")


def test_text_quality():
    assert text_quality("") == 0.0
    assert text_quality("@@@@") == 0.0
    assert 0.9 < text_quality("word " * 100) <= 1.0


@pytest.mark.skipif(not GOLDEN.exists(), reason="golden set not present")
def test_golden_agreement_parses_to_publishable_proposals():
    pages = ParserEngine().parse((GOLDEN / "AGR-001.pdf").read_bytes())
    assert all(p.method == "TEXT" for p in pages[:-1])
    props = normalize(extract_terms(pages), MASTERS)
    assert validate(pages, props, lambda e: [], 0.8) == PUBLISH
    assert {p.metric_raw for p in props} >= {"DSCR", "ICR", "DEBT_TO_EBITDA", "EBITDA_TO_EMI", "NEGATIVE_PLEDGE"}


def test_info_facts_dates_cure_ltm_and_steps():
    from dps.extraction.terms_info import extract_info, parse_date

    pages = [Page(1, "LOAN AGREEMENT\nDated: 1st May 2025\nEffective Date of this facility: 15 Jun 2025\n"
                     "The final maturity date shall be 30 October 2028.\n"
                     "The first test date is 30 September 2025.\n", "TEXT", 1.0),
             Page(2, "Ratios are tested on the last twelve months basis.\nThe DSCR floor shall step down to 1.1 "
                     "from 2027.\nAny breach not remedied within thirty (30) days is an Event of Default.\n"
                     "Dated: 31 Foo 2025\n", "TEXT", 1.0)]
    facts = {t.metric: t for t in extract_info(pages)}
    assert facts["AGREEMENT_DATE"].definition == "2025-05-01"
    assert facts["EFFECTIVE_DATE"].definition == "2025-06-15"
    assert facts["MATURITY_DATE"].definition == "2028-10-30"
    assert facts["FIRST_TEST_DATE"].definition == "2025-09-30" and facts["FIRST_TEST_DATE"].page == 1
    assert (facts["CURE_PERIOD_DAYS"].threshold, facts["CURE_PERIOD_DAYS"].page) == (30.0, 2)
    assert facts["TESTING_BASIS"].definition == "LTM"
    assert "step down" in facts["STEP_SCHEDULE"].quote
    assert extract_info([Page(1, " ", "NONE", 0.0)]) == []
    assert parse_date("31", "Foo", "2025") is None
    assert extract_info([Page(1, "Dated: 31 February 2025", "TEXT", 1.0)]) == []

    props = {p.metric_raw: p for p in normalize(list(facts.values()), MASTERS)}
    assert props["MATURITY_DATE"].expression == "2028-10-30" and props["MATURITY_DATE"].name == "Maturity date"
    assert props["CURE_PERIOD_DAYS"].expression == "30 days"
    assert validate(pages, list(props.values()), lambda e: ["never called for INFO"], 0.5) == PUBLISH
