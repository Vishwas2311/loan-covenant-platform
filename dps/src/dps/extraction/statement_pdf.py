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
from dataclasses import dataclass, field

from dps.extraction.statement import LINE_ALIASES, match_key, parse_number

log = logging.getLogger(__name__)

Row = tuple[str, float, int | None, str | None]

_AMOUNT = re.compile(r"^\(?-?(?:₹|\$)?[0-9][0-9,]*(?:\.[0-9]+)?\)?$")
_DASHES = {"-", "–", "—", "nil"}
# Currency marks and units printed next to amounts ("$ 32,494", "1,234.5 Cr", "LKR 1,000").
_UNITS = {"cr", "crore", "crores", "lakh", "lakhs", "rs", "₹", "inr", "mn", "million", "$", "us$", "lkr", "€", "£"}
_YEAR = re.compile(r"(?<!\d)(20\d\d)(?:\s*-\s*(\d\d))?(?!\d)")
_SHORT_DATE = re.compile(r"(?<!\d)\d{1,2}[-/ ](?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*"
                         r"[-/ ](\d\d)(?!\d)", re.I)
_CHANGES = re.compile(r"changes?\s+in\s+(operating\s+)?(assets|working\s+capital)"
                      r"|working\s+capital\s+(changes|adjustments)", re.I)
_BARE_BORROWINGS = re.compile(r"(\(?[ivx]+\)\s*)?(interest[- ]bearing\s+)?(loans\s+(and|&)\s+)?borrowings", re.I)
_SECTIONS = [(re.compile(r"non[- ]current liabilities", re.I), "Long-term borrowings"),
             (re.compile(r"^(\(?[0-9ivxa-z]{1,4}[.)]\s*)?current liabilities", re.I), "Short-term borrowings")]
_BLOCK = re.compile(r"^(\(?[0-9ivxa-z]{1,4}[.)]\s*)?(non[- ]current|current)\s+(assets|liabilities)\s*:?$", re.I)
_GARBLE = re.compile(r"\(cid:\d+\)|[ĨǆƉǀďƋͿ]")
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
    texts: list[str] = field(default_factory=list)  # full text of each page read, for the second reader


# ----------------------------------------------------------------------------------------------------
# Words
# ----------------------------------------------------------------------------------------------------
def _text_words(page) -> list[Word]:
    words: list[Word] = []
    for w in page.extract_words(x_tolerance=1.5):
        prev = words[-1] if words else None
        if (prev is not None and abs(prev.top - w["top"]) < 1 and abs(w["x0"] - prev.x1) < 1
                and prev.text[-1:].isdigit() and w["text"][:1] in "0123456789,"):
            # Some PDFs space the characters of one amount apart: "4" "1,651.24" is 41,651.24.
            prev.text += w["text"]
            prev.x1 = w["x1"]
            continue
        words.append(Word(w["text"], w["x0"], w["x1"], w["top"]))
    return words


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


_MONTH = re.compile(r"^(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[.,]?$", re.I)


def _date_line(line: list[Word]) -> bool:
    """A heading row of dates: "31 March 2025   31 March 2024", "2025 2024 2023", "FY 2024-25"."""
    numbers = [w.text for w in line if _is_amount(w.text)]
    if not numbers:
        return False
    month = any(_MONTH.match(w.text) for w in line)
    return all(re.fullmatch(r"(19|20)\d\d", n) or (month and re.fullmatch(r"\d{1,2},?", n)) for n in numbers)


def _amount(text: str) -> float | None:
    if text.lower() in _DASHES:
        return None
    return parse_number(text.replace("Rs.", "").replace("₹", "").replace("$", ""))


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
        if prev is None and re.fullmatch(r"\(\d{1,2}\)|\d{1,2}\.", w.text):
            cur.label.append(w)  # "(2) Non-current liabilities": the item number opens the label
        elif _is_amount(w.text):
            cur.amounts.append(w)
        elif low in _UNITS:
            pass  # "Rs. 1,234.5 Cr"
        elif w.text.endswith("%"):
            if not cur.amounts:
                cur.sentence = True  # "grew 12% to 5,400": a percentage in a sentence
            # after the amounts it is a "% Change" column: never an amount, and no sentence either
        else:
            if cur.amounts and low in ("and", "or", "&") and not cur.sentence:
                cur.label.extend(cur.amounts)  # amounts quoted inside the label, not the row's figures
                cur.amounts = []
                cur.label.append(w)
                prev = w
                continue
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


def _heading_parts(line: list[Word]) -> list[list[Word]]:
    """Phrases, except that years and dates stand alone: "In thousands 2025 2024 2023" heads three columns."""
    out: list[list[Word]] = []
    for phrase in _phrases(line):
        run: list[Word] = []
        for w in phrase:
            if _YEAR.search(w.text) or re.fullmatch(r"\d{1,2}[./-]\d{1,2}[./-]\d{2,4}", w.text):
                if run:
                    out.append(run)
                    run = []
                out.append([w])
            else:
                run.append(w)
        if run:
            out.append(run)
    return out


def _column_of(cols: list[tuple[float, float]], x: float) -> int | None:
    for i, (a, b) in enumerate(cols):
        if a - 2 * CHAR_W <= x <= b + 2 * CHAR_W:
            return i
    return None


def _is_note_column(header: str, values: list[float]) -> bool:
    # "Note" / "Notes" / "Note No." heads a short column; "The accompanying notes are an integral part" does not.
    words = header.lower().split()
    named = any(re.fullmatch(r"notes?\.?", w) for w in words)
    small = sum(1 for v in values if v.is_integer() and 0 < v <= 99) >= max(1, 0.6 * len(values))
    # Unnamed: nearly all small whole numbers ("25", "26", ... with a "34.07" sub-note among them).
    mostly = len(values) >= 3 and sum(1 for v in values if v.is_integer() and 0 < v <= 60) >= 0.8 * len(values)
    return (named and (len(words) <= 6 or small)) or mostly


def choose_column(headers: dict[int, str], values: dict[int, list[float]]) -> int | None:
    """The current annual standalone column, from the header words above each column."""
    best: tuple[float, int, int] | None = None
    fullest = max((len(v) for v in values.values()), default=0)
    for col, vals in values.items():
        h = headers.get(col, "").lower()
        if _is_note_column(h, vals):
            continue
        if len(vals) < 0.25 * fullest:
            continue  # a stray number (a membership number under the signatures) is no column of figures
        score = 0.0
        # "Twelve months ended" is the year; three, six or nine months, quarters and halves are not.
        if re.search(r"quarter|qtr|\bq[1-4]\b|half|\b(three|six|nine|3|6|9)\s+months|\bmonth\s+ended", h):
            score -= 50
        if re.search(r"year|\bfy|annual|audited", h):
            score += 5
        if "consolidated" in h and any("standalone" in x.lower() for x in headers.values()):
            score -= 20  # only against a standalone column; a page title "Consolidated statements" is no penalty
        if "group" in h and any("company" in x.lower() for x in headers.values()):
            score -= 20  # Sri Lankan "Group | Company" columns: the company is the standalone entity
        if "previous" in h or "prior" in h:
            score -= 30
        if re.search(r"\bchange\b|variance|increase|decrease", h):
            score -= 50  # "Change + / (-)": the difference between two columns
        years = [int(a) + (1 if b else 0) for a, b in _YEAR.findall(h)]
        years += [2000 + int(y) for y in _SHORT_DATE.findall(h)]  # "30-Sep-25"
        if years:
            score += (max(years) - 2000) * 10
        cand = (score, -col, col)
        if best is None or cand > best:
            best = cand
    return best[2] if best else None


_MARKER = re.compile(r"\b(group|company|consolidated|standalone|quarter|year|months?)\b", re.I)


def _spread_markers(lines: list[list[Word]], segs: list[list[_Segment]], reach: dict[int, tuple[float, float]],
                    headers: dict[int, str]) -> None:
    """A heading over a pair of columns names both: "GROUP" over "2025 2024", "Quarter ended" over two dates.

    Only heading lines above the first row of figures, so a signature ("Company Secretary") names nothing.
    On a line with several such headings each column takes the nearest; a lone one covers what it overlaps.
    """
    for line, ss in zip(lines, segs, strict=True):
        if any(s.amounts and not s.sentence for s in ss) and not _date_line(line):
            return
        if any(s.sentence for s in ss) or len(line) > 12:
            continue  # narrative ("... against 4,800 last year") heads no column
        marks = [p for p in _phrases(line) if len(p) <= 5 and _MARKER.search(" ".join(w.text for w in p))]
        if not marks or not reach:
            continue
        for col, (a, b) in reach.items():
            centre = (a + b) / 2
            if len(marks) > 1:
                p = min(marks, key=lambda p: abs((p[0].x0 + p[-1].x1) / 2 - centre))
            else:
                p = marks[0]
                if min(p[-1].x1, b) - max(p[0].x0, a) <= 0:
                    continue
            words = " ".join(m.group(0) for m in _MARKER.finditer(" ".join(w.text for w in p)))
            if words.lower() not in headers.get(col, "").lower():
                headers[col] = (headers.get(col, "") + " " + words).strip()


def rows_from_words(pages: list[list[Word]], numbers: list[int] | None = None) -> list[Row]:
    """Rows from each page's words; ``numbers`` are the pages' 1-based numbers in the file (default 1, 2, ...)."""
    out: list[Row] = []
    for index, words in enumerate(pages):
        page_no = numbers[index] if numbers else index + 1
        lines = _lines(words)
        segs = [_segments(line) for line in lines]
        rights = [a.x1 for line, ss in zip(lines, segs, strict=True) if not _date_line(line)
                  for s in ss if not s.sentence for a in s.amounts]
        cols = _columns(rights)
        sizes = [sum(1 for x in rights if a <= x <= b) for a, b in cols]
        # Below the statement's last line come the signatures ("Date: 29th May, 2025" under the prior-year
        # column); they head no column.
        last = max((i for i, ss in enumerate(segs) for s in ss
                    if s.amounts and not s.sentence and match_key(" ".join(w.text for w in s.label))), default=-1)
        headers: dict[int, str] = {}
        # Headings go to columns of figures, not to a stray number's column ("31 March 2025" over one amount).
        reach = {i: (a - 14 * CHAR_W, b + CHAR_W) for i, (a, b) in enumerate(cols)
                 if sizes[i] >= 0.25 * max(sizes)}
        parsed: list[dict] = []
        section = ""
        changes = False  # inside "Changes in operating assets and liabilities" of a cash flow statement
        total_for = ""  # the total an unlabelled figure stands for, after a "Current assets" heading
        for index, (line, ss) in enumerate(zip(lines, segs, strict=True)):
            if _date_line(line) or not any(s.amounts and not s.sentence for s in ss):
                text = " ".join(w.text for w in line).strip()
                # a row of dates is a heading; a sentence quoting amounts is not
                if (_date_line(line) or not any(_is_amount(w.text) for w in line)) and (index < last or last < 0):
                    for phrase in _heading_parts(line):  # a heading goes to the column it overlaps most
                        if any("%" in w.text for w in phrase):
                            continue  # "% Change" heads percentages, which are never read as amounts
                        x0, x1 = phrase[0].x0, phrase[-1].x1
                        overlap = {i: min(x1, b) - max(x0, a) for i, (a, b) in reach.items()}
                        best_col = max(overlap, key=overlap.get, default=None)
                        if len(phrase) == 1 and _YEAR.search(phrase[0].text) and reach:
                            # A year sits right-aligned or centred over its own column: nearest edge or centre.
                            mid = (x0 + x1) / 2
                            best_col = min(reach, key=lambda i: min(abs(x1 - cols[i][1]),
                                                                               abs(mid - sum(cols[i]) / 2)))
                            overlap[best_col] = max(overlap.get(best_col, 0), 1)
                        over = [i for i, (a, b) in enumerate(cols) if min(x1, b) > max(x0, a - 2 * CHAR_W)]
                        if len(phrase) > 1 and len(over) > 1:
                            continue  # a title across several columns ("Years ended December 31, 2025, 2024")
                        if best_col is not None and overlap[best_col] > 0:
                            words_text = " ".join(w.text for w in phrase)
                            headers[best_col] = (headers.get(best_col, "") + " " + words_text).strip()
                if parsed and parsed[-1]["open"] and text[:1].islower():
                    parsed[-1]["label"] += " " + text  # a label wrapped onto the next line
                    parsed[-1]["open"] = False
                elif any(rx.search(text) for rx, _ in _SECTIONS):
                    section = text
                block = _BLOCK.match(text)
                if block:
                    total_for = f"Total {block.group(2).lower()} {block.group(3).lower()}"
                if _CHANGES.search(text):
                    changes = True
                elif re.search(r"investing|financing", text, re.I):
                    changes = False
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
                if not label and total_for:
                    # Sri Lankan and many Indian statements print the block's total with no words beside it:
                    # the first unlabelled figure under "Current Assets" is total current assets.
                    label, total_for = total_for, ""
                elif label.lower().startswith("total"):
                    total_for = ""  # the block printed its own total
                if re.match(r"(net\s+)?cash\s+(generated|provided|used|from|flows?)|net\s+cash", label, re.I):
                    changes = False
                elif changes:
                    label = "change in " + label  # "Accounts receivable (1,532.9)" there is a movement, not a balance
                parsed.append({"label": label, "vals": vals, "section": section, "open": True})
        by_col: dict[int, list[float]] = {}
        for r in parsed:
            for c, v in r["vals"].items():
                if v is not None:
                    by_col.setdefault(c, []).append(v)
        _spread_markers(lines, segs, reach, headers)
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
    """Rows from a statement PDF. ``ocr`` is a RapidOcrBackend (or None: scanned pages give no rows).

    A long report (an annual report, a 10-K) is searched first and only its statement pages are read, the
    best balance sheet / P&L / cash flow page before the runners-up (extraction.statement_pages).
    """
    import pdfplumber

    from dps.extraction.statement import _unit, unit_stated
    from dps.extraction.statement_pages import statement_pages

    selection = statement_pages(data, ocr)
    chosen, main = selection if selection is not None else (None, 0)
    main_unit = None
    pages: list[list[Word]] = []
    numbers: list[int] = []
    texts: list[str] = []
    header, ocr_pages, confidences = "", [], []
    with pdfplumber.open(io.BytesIO(data)) as pdf:
        for position, index in enumerate(chosen if chosen is not None else range(len(pdf.pages))):
            page = pdf.pages[index]
            text = page.extract_text() or ""
            if chosen is not None and position == main:
                main_unit = _unit(header)
            if main_unit and unit_stated(text[:1500]) and _unit(text[:1500]) != main_unit:
                continue  # a runner-up in another unit ("Rs 000'" in a ten-year summary): not these statements
            # A broken font encoding ("(cid:12)") gives text no reader can use: read the page image instead.
            if len(re.findall(r"\w", text)) >= MIN_TEXT_CHARS and len(_GARBLE.findall(text)) < 5:
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
            numbers.append(index + 1)
            texts.append(text)
            # The unit is stated under each statement's heading, sometimes well down the page.
            header += text[:1500] + " "
    rows = rows_from_words(pages, numbers)
    if ocr_pages:
        rows = [(ocr_label(r[0]) if r[2] in ocr_pages else r[0], *r[1:]) for r in rows]
    quality = round(sum(confidences) / len(confidences), 3) if confidences else None
    return PdfStatement(rows, header, ocr_pages, quality, texts)
