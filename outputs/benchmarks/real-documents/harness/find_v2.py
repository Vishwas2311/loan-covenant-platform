"""Page finder v2: pypdf text first (fast); pdfplumber text for pages that look like statements; RapidOCR for
pages with no usable text (no text layer, or a broken font encoding). Same scoring as v1 plus US / Sri
Lankan headings and two-statement spreads."""
from __future__ import annotations

import re

import find_pages

find_pages.HEADINGS["PL"] = (r"statements?\s+of\s+(standalone\s+|consolidated\s+)?(profit\s+(and|&|or)\s+loss|(\(loss\)\s*)?"
                             r"(income|operations|earnings)(\s*\(loss\))?|comprehensive\s+income)|profit\s+(and|&)\s+loss\s+account|"
                             r"income\s+statements?|results\s+of\s+operations")
GARBLE = re.compile(r"\(cid:\d+\)|[ĨǆƉǀďƋͿ]")


def usable(text: str) -> bool:
    words = len(re.findall(r"[A-Za-z]{3,}", text))
    return words >= 15 and len(GARBLE.findall(text)) < 5


def texts_for(path: str, ocr=None) -> tuple[list[str], dict]:
    import pdfplumber
    from pypdf import PdfReader

    raw = [(p.extract_text() or "") for p in PdfReader(path).pages]
    stats = {"pages": len(raw), "plumber": 0, "ocr": 0}
    texts = list(raw)
    data = open(path, "rb").read()
    with pdfplumber.open(path) as pdf:
        for i, t in enumerate(raw):
            if not usable(t):
                if ocr is not None:
                    boxes = ocr.ocr_boxes(data, i, 150)
                    texts[i] = " ".join(b[1] for b in boxes)
                    stats["ocr"] += 1
                continue
            if any(find_pages.page_scores(t, k) > 4 for k in ("BS", "PL", "CF")):
                texts[i] = pdf.pages[i].extract_text() or t  # better reading order for headings on spreads
                stats["plumber"] += 1
    return texts, stats
