"""Page finder v3: cheap first, expensive only where the cheap pass leaves a gap.

1. pypdf text for every page; score with v2's headings.
2. pdfplumber re-reads only the pages that score as a statement (reading order on spreads).
3. OCR only for pages without usable text that sit in the financial-statements part of the report: after
   the auditor's report, or among the pages ranked best by amount density. Never the whole report.
"""
from __future__ import annotations

import re

import find_pages
import find_v2  # noqa: F401  (installs the v2 headings)
from find_v2 import usable

AUDIT = re.compile(r"independent\s+auditor'?s?\s+report|report\s+of\s+independent\s+registered", re.I)


def texts_for(path: str, ocr=None, window: int = 12) -> tuple[list[str], dict]:
    import pdfplumber
    from pypdf import PdfReader

    texts = [(p.extract_text() or "") for p in PdfReader(path).pages]
    stats = {"pages": len(texts), "plumber": 0, "ocr": 0}
    candidates = {i for i, t in enumerate(texts) for k in ("BS", "PL", "CF") if find_pages.page_scores(t, k) > 4}
    with pdfplumber.open(path) as pdf:
        for i in sorted(candidates):
            texts[i] = pdf.pages[i].extract_text() or texts[i]
            stats["plumber"] += 1
    if ocr is not None:
        audit = [i for i, t in enumerate(texts) if AUDIT.search(t[:1500])]
        zone = set()
        for a in audit:
            zone.update(range(a, min(len(texts), a + window)))
        for c in candidates:
            zone.update(range(max(0, c - 2), min(len(texts), c + 3)))
        data = open(path, "rb").read()
        for i in sorted(zone):
            if not usable(texts[i]):
                texts[i] = " ".join(b[1] for b in ocr.ocr_boxes(data, i, 150))
                stats["ocr"] += 1
    return texts, stats
