"""Flow B Parse step for statement PDFs: read the table, not just the text (03-flows-design.md §8).

The plain-text reader takes the first number on each line. In real statements that is often the note
number ("Total equity  12  2,000  1,850"), a quarter, the previous year or a date, and it was right on
48% of the values in the statement-PDF benchmark (outputs/benchmarks/statement-pdf-readers.md). This
reader keeps each word's position instead:

1. Words with their positions: pdfplumber on pages with a text layer, RapidOCR boxes on scanned pages
   (the page is straightened first, using the slope of the boxes).
2. Lines of words, split into label + amounts. Words straight after an amount mean a sentence, not a
   table row, so narrative such as a directors' report is skipped; a wide gap starts a second table on
   the same line (statements printed side by side).
3. Amounts are grouped into columns by their right edge, and the header words above each column are
   kept. The column used is the current annual, standalone one: a note column, quarter or half-year
   columns, consolidated and previous-year columns are passed over, and the latest year wins.
4. Labels that wrap onto a second line are joined, and a bare "Borrowings" takes its meaning from the
   "Non-current liabilities" / "Current liabilities" heading above it.

The rows then go through the same extract_values() as every other format.
"""

from __future__ import annotations

import io
import logging
import math
import re
import statistics
from collections.abc import Iterable
from dataclasses import dataclass

from dps.extraction.statement import LINE_ALIASES, match_key, parse_number

log = logging.getLogger(__name__)

Row = tuple[str, float, int | None, str | None]

_AMOUNT = re.compile(r"^\(?-?(?:₹)?[0-9][0-9,]*(?:\.[0-9]+)?\)?$")
_DASHES = {"-", "–", "—", "nil"}
_UNITS = {"cr", "crore", "crores", "lakh", "lakhs", "rs", "₹", "inr", "mn", "million"}
_YEAR = re.compile(r"(?<!\d)(20\d\d)(?:\s*-\s*(\d\d))?(?!\d)")
_BARE_BORROWINGS = re.compile(r"(\(?[ivx]+\)\s*)?borrowings", re.I)
_SECTIONS = [(re.compile(r"non[- ]current liabilities", re.I), "Long-term borrowings"),
             (re.compile(r"^current liabilities", re.I), "Short-term borrowings")]
# Text pages need this many word characters; fewer means a scan (same rule as ParserEngine).
MIN_TEXT_CHARS = 40
CHAR_W = 5.0  # points; about one character at statement font sizes


@dataclass
class Word:
    text: str
    x0: float
    x1: float
    top: float
    height: float = 0.0


@dataclass
class PdfStatement:
    rows: list[Row]
    header_text: str
    ocr_pages: list[int]
    ocr_quality: float | None  # mean OCR confidence over scanned pages, None when none were scanned


# ----------------------------------------------------------------------------------------------------
# Words
# ----------------------------------------------------------------------------------------------------
def _text_words(page) -> list[Word]:
    return [Word(w["text"], w["x0"], w["x1"], w["top"]) for w in page.extract_words(x_tolerance=1.5)]


def ocr_words(boxes: Iterable[tuple[list, str, float]], dpi: int) -> list[Word]:
    """RapidOCR boxes (four corner points in pixels, text, confidence) -> words in points, deskewed."""
    boxes = list(boxes)
    slopes = [math.atan2(b[1][1] - b[0][1], b[1][0] - b[0][0]) for b, _, _ in boxes if b[1][0] - b[0][0] > 60]
    angle = -statistics.median(slopes) if slopes else 0.0
    cos, sin = math.cos(angle), math.sin(angle)
    k = 72 / dpi
    words: list[Word] = []
    for box, text, _ in boxes:
        xs = [(x * cos - y * sin) * k for x, y in box]
        ys = [(x * sin + y * cos) * k for x, y in box]
        x0, x1, top, bottom = min(xs), max(xs), min(ys), max(ys)
        parts = text.split()  # one box can hold several words; they share its width
        step = (x1 - x0) / max(1, len(text))
        pos = x0
        for part in parts:
            words.append(Word(part, pos, pos + step * len(part), (top + bottom) / 2, bottom - top))
            pos += step * (len(part) + 1)
    return words


# ----------------------------------------------------------------------------------------------------
# Lines, segments, columns
# ----------------------------------------------------------------------------------------------------
def _is_amount(text: str) -> bool:
    return bool(_AMOUNT.match(text.replace("Rs.", ""))) or text.lower() in _DASHES


def _amount(text: str) -> float | None:
    if text.lower() in _DASHES:
        return None
    return parse_number(text.replace("Rs.", "").replace("₹", ""))


def _lines(words: list[Word]) -> list[list[Word]]:
    heights = sorted(w.height for w in words if w.height)
    tol = max(3.0, 0.45 * heights[len(heights) // 2]) if heights else 3.0
    lines: list[list[Word]] = []
    for w in sorted(words, key=lambda w: (w.top, w.x0)):
        if lines and abs(lines[-1][0].top - w.top) <= tol:
            lines[-1].append(w)
        else:
            lines.append([w])
    return [sorted(line, key=lambda w: w.x0) for line in lines]


@dataclass
class _Segment:
    label: list[Word]
    amounts: list[Word]
    sentence: bool = False


def _segments(line: list[Word]) -> list[_Segment]:
    segs = [_Segment([], [])]
    prev: Word | None = None
    for w in line:
        cur = segs[-1]
        low = w.text.lower().strip(".,")
        if _is_amount(w.text):
            cur.amounts.append(w)
        elif low in _UNITS:
            pass  # "Rs. 1,234.5 Cr"
        elif w.text.endswith("%"):
            cur.sentence = True  # a percentage is never an amount
        else:
            if cur.amounts:
                if prev is not None and w.x0 - prev.x1 > 4 * CHAR_W:
                    segs.append(_Segment([w], []))  # a second table on the same line
                    prev = w
                    continue
                cur.sentence = True  # words straight after an amount: narrative
            cur.label.append(w)
        prev = w
    return segs


def _columns(rights: list[float]) -> list[tuple[float, float]]:
    spans: list[list[float]] = []
    for x in sorted(rights):
        if spans and x - spans[-1][1] <= 2.5 * CHAR_W:
            spans[-1][1] = x
        else:
            spans.append([x, x])
    return [(a, b) for a, b in spans]


def _phrases(line: list[Word]) -> list[list[Word]]:
    """Words close together; a gap wider than two characters starts a new phrase."""
    out: list[list[Word]] = []
    for w in line:
        if out and w.x0 - out[-1][-1].x1 <= 2 * CHAR_W:
            out[-1].append(w)
        else:
            out.append([w])
    return out


def _column_of(cols: list[tuple[float, float]], x: float) -> int | None:
    for i, (a, b) in enumerate(cols):
        if a - 2 * CHAR_W <= x <= b + 2 * CHAR_W:
            return i
    return None


def _is_note_column(header: str, values: list[float]) -> bool:
    return "note" in header.lower() or (len(values) >= 3 and all(v.is_integer() and 0 < v <= 60 for v in values))


def choose_column(headers: dict[int, str], values: dict[int, list[float]]) -> int | None:
    """The current annual standalone column, from the header words above each column."""
    best: tuple[float, int, int] | None = None
    for col, vals in values.items():
        h = headers.get(col, "").lower()
        if _is_note_column(h, vals):
            continue
        score = 0.0
        if re.search(r"quarter|qtr|\bq[1-4]\b|half|six months|nine months|month", h):
            score -= 50
        if re.search(r"year|\bfy|annual|audited", h):
            score += 5
        if "consolidated" in h:
            score -= 20
        if "previous" in h or "prior" in h:
            score -= 30
        years = [int(a) + (1 if b else 0) for a, b in _YEAR.findall(h)]
        if years:
            score += (max(years) - 2000) * 10
        cand = (score, -col, col)
        if best is None or cand > best:
            best = cand
    return best[2] if best else None


def rows_from_words(pages: list[list[Word]]) -> list[Row]:
    out: list[Row] = []
    for page_no, words in enumerate(pages, start=1):
        lines = _lines(words)
        segs = [_segments(line) for line in lines]
        cols = _columns([a.x1 for ss in segs for s in ss if not s.sentence for a in s.amounts])


        headers: dict[int, str] = {}
        reach = {i: (a - 14 * CHAR_W, b + CHAR_W) for i, (a, b) in enumerate(cols)}
        parsed: list[dict] = []
        section = ""
        for line, ss in zip(lines, segs, strict=True):
            if not any(s.amounts and not s.sentence for s in ss):
                text = " ".join(w.text for w in line).strip()
                if not any(_is_amount(w.text) for w in line):  # a sentence quoting amounts is no header
                    for phrase in _phrases(line):  # a heading goes to the column it overlaps most
                        x0, x1 = phrase[0].x0, phrase[-1].x1
                        overlap = {i: min(x1, b) - max(x0, a) for i, (a, b) in reach.items()}
                        best_col = max(overlap, key=overlap.get, default=None)
                        if best_col is not None and overlap[best_col] > 0:
                            words_text = " ".join(w.text for w in phrase)
                            headers[best_col] = (headers.get(best_col, "") + " " + words_text).strip()
                if parsed and parsed[-1]["open"] and text[:1].islower():
                    parsed[-1]["label"] += " " + text  # a label wrapped onto the next line
                    parsed[-1]["open"] = False
                elif any(rx.search(text) for rx, _ in _SECTIONS):
                    section = text
                continue
            for s in ss:
                if s.sentence or not s.amounts:
                    continue
                vals: dict[int, float | None] = {}
                for a in s.amounts:
                    c = _column_of(cols, a.x1)
                    if c is not None and c not in vals:
                        vals[c] = _amount(a.text)
                label = " ".join(w.text for w in s.label).strip(" .:-")
                parsed.append({"label": label, "vals": vals, "section": section, "open": True})
        by_col: dict[int, list[float]] = {}
        for r in parsed:
            for c, v in r["vals"].items():
                if v is not None:
                    by_col.setdefault(c, []).append(v)
        best = choose_column(headers, by_col)
        for r in parsed:
            label = r["label"]
            if _BARE_BORROWINGS.fullmatch(label) and r["section"]:
                label = next(name for rx, name in _SECTIONS if rx.search(r["section"]))
            value = r["vals"].get(best) if best is not None else None
            if value is None:
                # A row outside the chosen column (side-by-side tables): its own first non-note amount.
                own = [c for c in sorted(r["vals"]) if r["vals"][c] is not None
                       and not _is_note_column(headers.get(c, ""), by_col.get(c, []))]
                value = r["vals"][own[0]] if own else None
            if label and value is not None:
                out.append((label, value, page_no, None))
    return out


def _compact(s: str) -> str:
    return re.sub(r"[^a-z]", "", s.lower())


def ocr_label(label: str) -> str:
    """OCR often drops spaces ("Totalcurrentassets"); match the catalog phrases without spaces."""
    if match_key(label):
        return label
    c = _compact(label)
    best = ""
    for phrases in LINE_ALIASES.values():
        for phrase in phrases:
            p = _compact(phrase)
            if len(p) >= 4 and c.startswith(p) and len(p) > len(_compact(best)) and len(p) >= 0.6 * len(c):
                best = phrase
    return best or label


# ----------------------------------------------------------------------------------------------------
# Entry point
# ----------------------------------------------------------------------------------------------------
def read_pdf_statement(data: bytes, ocr=None, dpi: int = 200) -> PdfStatement:
    """Rows from a statement PDF. ``ocr`` is a RapidOcrBackend (or None: scanned pages give no rows)."""
    import pdfplumber

    pages: list[list[Word]] = []
    header, ocr_pages, confidences = "", [], []
    with pdfplumber.open(io.BytesIO(data)) as pdf:
        for index, page in enumerate(pdf.pages):
            text = page.extract_text() or ""
            if len(re.findall(r"\w", text)) >= MIN_TEXT_CHARS:
                words = _text_words(page)
            elif ocr is not None:
                boxes = ocr.ocr_boxes(data, index, dpi)
                words = ocr_words(boxes, dpi)
                text = " ".join(w.text for w in words)
                ocr_pages.append(index + 1)
                if boxes:
                    confidences.append(sum(float(b[2]) for b in boxes) / len(boxes))
            else:
                words = []
            pages.append(words)
            if index < 2:
                header += text[:600] + " "
    rows = rows_from_words(pages)
    if ocr_pages:
        rows = [(ocr_label(r[0]) if r[2] in ocr_pages else r[0], *r[1:]) for r in rows]
    quality = round(sum(confidences) / len(confidences), 3) if confidences else None
    return PdfStatement(rows, header, ocr_pages, quality)
