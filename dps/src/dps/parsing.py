"""ParserEngine (03-flows-design.md §8): pypdf native text first, RapidOCR only for pages without usable
text, Docling as an opt-in heavy fallback. Azure AI Document Intelligence is not used.

Each page comes back with the method that produced it and a quality score, so Flow A/B can route a
document to the heavy worker pool when the fast parse is not good enough.
"""

from __future__ import annotations

import io
import logging
import re
from dataclasses import asdict, dataclass
from typing import Protocol

from pypdf import PdfReader

log = logging.getLogger(__name__)

# A page with fewer printable word characters than this is treated as having no text layer.
MIN_TEXT_CHARS = 40
# A page below this quality is "poor"; more than HEAVY_POOR_SHARE poor pages sends the document to the
# heavy (Docling) pool. A few blank pages (signature, cover) are normal and do not.
HEAVY_THRESHOLD = 0.5
HEAVY_POOR_SHARE = 0.25
# An OCR'd page that yields fewer word characters than this is poor whatever OCR's confidence: a real
# agreement page has hundreds, and a few confident fragments of a blurred scan are not readable text.
MIN_OCR_CHARS = 80
POOR_OCR_QUALITY = 0.3


@dataclass
class Page:
    no: int
    text: str
    method: str  # TEXT | OCR | DOCLING | NONE
    quality: float

    def as_dict(self) -> dict:
        return asdict(self)


class ParseError(Exception):
    """The file is not a readable PDF."""


class OcrBackend(Protocol):
    def ocr_page(self, pdf_bytes: bytes, page_index: int) -> tuple[str, float]:
        """Returns (text, mean confidence 0..1) for one page."""


def text_quality(text: str) -> float:
    """Share of word characters among non-space characters, damped for very short pages (0..1)."""
    stripped = re.sub(r"\s+", "", text or "")
    if not stripped:
        return 0.0
    words = len(re.findall(r"[A-Za-z0-9]", stripped))
    ratio = words / len(stripped)
    length_factor = min(1.0, len(stripped) / 200)
    return round(ratio * (0.5 + 0.5 * length_factor), 3)


class RapidOcrBackend:
    """RapidOCR (ONNX runtime, CPU). Imported lazily so the API process does not load the model."""

    def __init__(self, dpi: int = 200) -> None:
        self._dpi = dpi
        self._engine = None

    def _ensure(self):  # pragma: no cover - exercised only where the optional OCR stack is installed
        if self._engine is None:
            from rapidocr_onnxruntime import RapidOCR

            self._engine = RapidOCR()
        return self._engine

    def ocr_page(self, pdf_bytes: bytes, page_index: int) -> tuple[str, float]:  # pragma: no cover
        import pypdfium2 as pdfium

        pdf = pdfium.PdfDocument(pdf_bytes)
        image = pdf[page_index].render(scale=self._dpi / 72).to_pil()
        result, _ = self._ensure()(image)
        if not result:
            return "", 0.0
        lines = [r[1] for r in result]
        confidence = sum(float(r[2]) for r in result) / len(result)
        return "\n".join(lines), confidence


class ParserEngine:
    """Fast path used by both flows. ``ocr`` may be None (no OCR available): such pages come back empty."""

    def __init__(self, ocr: OcrBackend | None = None) -> None:
        self._ocr = ocr

    def parse(self, pdf_bytes: bytes) -> list[Page]:
        try:
            reader = PdfReader(io.BytesIO(pdf_bytes))
            raw_pages = [p.extract_text() or "" for p in reader.pages]
        except Exception as ex:  # pypdf raises many types for corrupt input
            raise ParseError(f"Unreadable PDF: {type(ex).__name__}") from ex

        pages: list[Page] = []
        for i, text in enumerate(raw_pages):
            if len(re.findall(r"\w", text)) >= MIN_TEXT_CHARS:
                pages.append(Page(i + 1, text, "TEXT", text_quality(text)))
                continue
            pages.append(self._ocr_or_empty(pdf_bytes, i, text))
        return pages

    def _ocr_or_empty(self, pdf_bytes: bytes, index: int, native: str) -> Page:
        if self._ocr is None:
            return Page(index + 1, native, "NONE", text_quality(native))
        try:
            text, confidence = self._ocr.ocr_page(pdf_bytes, index)
        except Exception as ex:
            log.warning("OCR failed on page %d: %s", index + 1, type(ex).__name__)
            return Page(index + 1, native, "NONE", 0.0)
        quality = round(min(confidence, text_quality(text) or confidence), 3)
        if len(re.findall(r"\w", text)) < MIN_OCR_CHARS:
            quality = min(quality, POOR_OCR_QUALITY)
        return Page(index + 1, text, "OCR", quality)


def needs_heavy(pages: list[Page]) -> bool:
    """True when the fast parse left too many pages unusable: route to the Docling pool."""
    if not pages:
        return True
    poor = sum(1 for p in pages if p.quality < HEAVY_THRESHOLD)
    return poor / len(pages) > HEAVY_POOR_SHARE


class DoclingEngine:  # pragma: no cover - heavy optional dependency, run only by dps-worker-heavy
    """Layout-aware fallback. About 45-55x slower than the fast path (benchmark in 03-flows-design §8)."""

    def parse(self, pdf_bytes: bytes) -> list[Page]:
        from docling.datamodel.base_models import DocumentStream
        from docling.document_converter import DocumentConverter

        result = DocumentConverter().convert(DocumentStream(name="doc.pdf", stream=io.BytesIO(pdf_bytes)))
        doc = result.document
        pages: list[Page] = []
        for page_no in sorted(doc.pages):
            text = doc.export_to_markdown(page_no=page_no)
            pages.append(Page(page_no, text, "DOCLING", max(text_quality(text), HEAVY_THRESHOLD)))
        return pages
