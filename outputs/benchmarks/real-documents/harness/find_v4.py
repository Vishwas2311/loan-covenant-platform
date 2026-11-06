"""Page finder v4: v3 with a budget. pdfplumber re-reads the 6 best candidates per statement type; OCR runs
only when a statement type still has no confident page, and only on text-less pages in the statements part."""
from __future__ import annotations

import find_pages
import find_v2  # noqa: F401  (v2 headings)
from find_v2 import usable
from find_v3 import AUDIT

CONFIDENT = 9.0


def texts_for(path: str, ocr=None, per_kind: int = 6, window: int = 12) -> tuple[list[str], dict]:
    import pdfplumber
    from pypdf import PdfReader

    texts = [(p.extract_text() or "") for p in PdfReader(path).pages]
    stats = {"pages": len(texts), "plumber": 0, "ocr": 0}
    best = set()
    for k in ("BS", "PL", "CF"):
        ranked = sorted(((find_pages.page_scores(t, k), i) for i, t in enumerate(texts)), reverse=True)
        best.update(i for s, i in ranked[:per_kind] if s > 4)
    with pdfplumber.open(path) as pdf:
        for i in sorted(best):
            texts[i] = pdf.pages[i].extract_text() or texts[i]
            stats["plumber"] += 1
    missing = [k for k in ("BS", "PL", "CF") if max(find_pages.page_scores(t, k) for t in texts) < CONFIDENT]
    if ocr is not None and missing:
        zone = set()
        for a in (i for i, t in enumerate(texts) if AUDIT.search(t[:1500])):
            zone.update(range(a, min(len(texts), a + window)))
        for c in best:
            zone.update(range(max(0, c - 2), min(len(texts), c + 3)))
        data = open(path, "rb").read()
        for i in sorted(zone):
            if not usable(texts[i]):
                texts[i] = " ".join(b[1] for b in ocr.ocr_boxes(data, i, 150))
                stats["ocr"] += 1
    return texts, stats
