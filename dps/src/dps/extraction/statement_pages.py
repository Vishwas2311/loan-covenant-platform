"""Flow B Parse step for long PDFs: find the statement pages before reading any table.

An annual report or a 10-K runs to 100-400 pages and holds the balance sheet, profit and loss and cash flow on
a handful of them. Reading every page with the table reader is slow and lets a notes page or a five-year
summary win the first-occurrence rule in extract_values. Benchmark on 47 real reports from India, Sri Lanka
and the SEC (outputs/benchmarks/real-documents/): this finder put a true statement page first for 87.6% of
the 137 statements and in its first two for 96.4%, in a median 63 s for a whole report with OCR.

1. pypdf text for every page (fast), scored per statement type: its heading near the top, the line items
   that belong to it, how many amounts it prints. Notes pages and the auditor's report score down; a
   standalone ("Company") page beats a consolidated ("Group") one.
2. pdfplumber re-reads the best six pages per type (better reading order on two-statement spreads).
3. OCR only when a type still has no confident page, and only for text-less or garbled pages in the
   financial statements part (after the auditor's report, or next to the best candidates).
"""

from __future__ import annotations

import io
import re

HEADINGS = {
    "BS": r"balance\s+sheets?|statements?\s+of\s+financial\s+(position|condition)",
    "PL": r"statements?\s+of\s+(standalone\s+|consolidated\s+)?(profit\s+(and|&|or)\s+loss|(\(loss\)\s*)?"
          r"(income|operations|earnings)(\s*\(loss\))?|comprehensive\s+income)|profit\s+(and|&)\s+loss\s+account|"
          r"income\s+statements?|results\s+of\s+operations",
    "CF": r"(statements?\s+of\s+)?cash\s+flows?(\s+statements?)?",
}
LINES = {
    "BS": [r"total\s+assets", r"total\s+equity", r"total\s+liabilities", r"current\s+assets", r"inventor",
           r"property,?\s+plant", r"trade\s+(and\s+other\s+)?receivables|accounts\s+receivable",
           r"share\s+capital|common\s+stock|stated\s+capital", r"retained\s+earnings|other\s+equity|reserves"],
    "PL": [r"revenue|net\s+sales|turnover", r"finance\s+costs?|interest\s+expense", r"depreciation",
           r"profit\s+before\s+tax|income\s+before\s+income\s+taxes|earnings\s+before", r"tax\s+expense|income\s+tax",
           r"profit\s+for\s+the\s+(year|period)|net\s+(income|earnings|profit)", r"earnings\s+per\s+share",
           r"cost\s+of\s+(sales|goods|materials)"],
    "CF": [r"operating\s+activities", r"investing\s+activities", r"financing\s+activities",
           r"purchase\s+of\s+property|capital\s+expenditure",
           r"cash\s+and\s+cash\s+equivalents\s+at\s+(the\s+)?(end|beginning)"],
}
KINDS = ("BS", "PL", "CF")
_AMOUNT = re.compile(r"\(?\d{1,3}(?:,\d{2,3})+(?:\.\d+)?\)?|\b\d+\.\d{1,2}\b")
_NOTES = re.compile(r"notes?\s+(forming\s+part|to\s+the\s+(standalone\s+|consolidated\s+)?financial\s+statements)",
                    re.I)
_AUDIT = re.compile(r"independent\s+auditor|auditor'?s\s+report|key\s+audit\s+matters", re.I)
_AUDIT_START = re.compile(r"independent\s+auditor'?s?\s+report|report\s+of\s+independent\s+registered", re.I)
_GARBLE = re.compile(r"\(cid:\d+\)|[ĨǆƉǀďƋͿ]")
# A page scoring above this is a candidate; at CONFIDENT the type needs no OCR search.
CANDIDATE, CONFIDENT = 4.0, 9.0
# Reports longer than this are searched; shorter files are read whole (a statement pack is 2-10 pages).
LONG_REPORT_PAGES = 12


def usable(text: str) -> bool:
    """Text a reader can use: enough words and no broken font encoding ("(cid:12)")."""
    return len(re.findall(r"[A-Za-z]{3,}", text)) >= 15 and len(_GARBLE.findall(text)) < 5


def page_score(text: str, kind: str) -> float:
    t = text.lower()
    score = 0.0
    if re.search(HEADINGS[kind], t[:700]):
        score += 6
    elif re.search(HEADINGS[kind], t):
        score += 1
    score += sum(1.5 for p in LINES[kind] if re.search(p, t))
    score += min(len(_AMOUNT.findall(text)), 120) / 20
    if _NOTES.search(text[:400]):
        score -= 4
    if _AUDIT.search(text[:900]):
        score -= 5
    if kind == "BS" and re.search(r"profit\s+for\s+the\s+year|operating\s+activities", t):
        score -= 2
    if kind == "PL" and re.search(r"total\s+assets", t):
        score -= 2
    if kind == "CF" and not re.search(r"operating\s+activities", t):
        score -= 6
    return score


def rank(texts: list[str]) -> dict[str, list[int]]:
    """0-based page indexes per statement type, best first (at most two)."""
    out: dict[str, list[int]] = {}
    for kind in KINDS:
        scored = []
        for i, text in enumerate(texts):
            s = page_score(text, kind)
            if s <= CANDIDATE:
                continue
            head = text[:600].lower()
            standalone = "standalone" in head or ("company" in head and "group" not in head)
            consolidated = "consolidated" in head
            scored.append((s + (2 if standalone else 0) - (1 if consolidated and not standalone else 0), i, i))
        scored.sort(reverse=True)
        out[kind] = [i for _, _, i in scored[:2]]
    return out


def page_texts(data: bytes, ocr=None, per_kind: int = 6, window: int = 12) -> list[str]:
    """Text of every page, spending pdfplumber and OCR only where the statements are likely to be."""
    import pdfplumber
    from pypdf import PdfReader

    texts = [(p.extract_text() or "") for p in PdfReader(io.BytesIO(data)).pages]
    best: set[int] = set()
    for kind in KINDS:
        ranked = sorted(((page_score(t, kind), i) for i, t in enumerate(texts)), reverse=True)
        best.update(i for s, i in ranked[:per_kind] if s > CANDIDATE)
    with pdfplumber.open(io.BytesIO(data)) as pdf:
        for i in sorted(best):
            texts[i] = pdf.pages[i].extract_text() or texts[i]
    missing = [k for k in KINDS if max((page_score(t, k) for t in texts), default=0) < CONFIDENT]
    if ocr is not None and missing:
        zone: set[int] = set()
        for a in (i for i, t in enumerate(texts) if _AUDIT_START.search(t[:1500])):
            zone.update(range(a, min(len(texts), a + window)))
        for c in best:
            zone.update(range(max(0, c - 2), min(len(texts), c + 3)))
        for i in sorted(zone):
            if not usable(texts[i]):
                texts[i] = " ".join(b[1] for b in ocr.ocr_boxes(data, i, 150))
    return texts


def statement_pages(data: bytes, ocr=None) -> list[int] | None:
    """0-based pages to read, best balance sheet / P&L / cash flow first, then the runners-up.

    None for a short file (read it whole) or when no page looks like a statement.
    """
    from pypdf import PdfReader

    if len(PdfReader(io.BytesIO(data)).pages) <= LONG_REPORT_PAGES:
        return None
    ranked = rank(page_texts(data, ocr))
    order: list[int] = []
    for position in (0, 1):
        for kind in KINDS:
            pages = ranked[kind]
            if len(pages) > position and pages[position] not in order:
                order.append(pages[position])
    return order or None
