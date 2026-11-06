"""Statement-PDF reader benchmark. Run inside the DPS containers so the versions are production's.

  python bench.py <pdf_dir> <out.json> M1 M3 M4 M6      (dps-worker: pypdf, pdfplumber, RapidOCR)
  python bench.py <pdf_dir> <out.json> M2 M5            (dps-worker-heavy: Docling)

Each method returns rows (label, value, page, cell) that go through DPS's own extract_values(), so the
label matching, unit scaling and checks are identical; only how the rows are read differs.
"""
from __future__ import annotations

import io
import json
import os
import re
import sys
import time

from dps.extraction.statement import extract_values, parse_number, rows_from_text
from dps.parsing import ParserEngine, RapidOcrBackend, needs_heavy

NUM = re.compile(r"^\(?-?(?:Rs\.?|₹|\$)?\s?[0-9][0-9,]*(?:\.[0-9]+)?\)?$")
DASH = {"-", "–", "—", "nil", "Nil", "NIL"}
UNITS = {"cr", "cr.", "crore", "crores", "lakh", "lakhs", "rs.", "rs", "₹", "inr", "mn", "million", "$", "us$", "lkr", "€", "£"}
YEAR = re.compile(r"(?<!\d)(20\d\d)(?:\s*-\s*(\d\d))?(?!\d)")


# ----------------------------------------------------------------------------------------------------
# Shared row interpretation: tokens with positions -> (label, value, page, cell) rows
# ----------------------------------------------------------------------------------------------------
def is_num(t: str) -> bool:
    return bool(NUM.match(t.replace("Rs.", "").strip())) or t in DASH


def tok_value(t: str) -> float | None:
    if t in DASH:
        return None
    return parse_number(t.replace("Rs.", "").replace("₹", "").replace("$", "").strip())


def lines_from_tokens(tokens: list[dict], tol: float = 3.0) -> list[list[dict]]:
    """tokens: {text, x0, x1, top[, h]}. Group by baseline (OCR boxes: by centre, within half a box height)."""
    hs = sorted(t["h"] for t in tokens if t.get("h"))
    if hs:
        tol = max(tol, 0.45 * hs[len(hs) // 2])
    tokens = sorted(tokens, key=lambda t: (t["top"], t["x0"]))
    lines: list[list[dict]] = []
    for t in tokens:
        if lines and abs(lines[-1][0]["top"] - t["top"]) <= tol:
            lines[-1].append(t)
        else:
            lines.append([t])
    return [sorted(line, key=lambda t: t["x0"]) for line in lines]


def segments(line: list[dict], gap: float) -> list[dict]:
    """Split a line into label+numbers segments. A wide gap before text after numbers starts a new segment
    (side-by-side tables); a narrow one is a sentence carrying on (narrative) and spoils the segment."""
    segs, cur = [], {"label": [], "nums": [], "bad": False, "x0": None}
    prev = None
    for t in line:
        txt = t["text"]
        low = txt.lower().strip(".,")
        if is_num(txt):
            cur["nums"].append(t)
        elif low in UNITS and cur["nums"]:
            pass  # "1,234.5 Cr"
        elif low in UNITS and not cur["nums"]:
            pass  # "Rs. 1,234"
        elif txt.endswith("%") or low in ("%",):
            cur["bad"] = True
        else:
            if cur["nums"]:
                if prev is not None and t["x0"] - prev["x1"] > gap:
                    segs.append(cur)
                    cur = {"label": [], "nums": [], "bad": False, "x0": None}
                else:
                    cur["bad"] = True  # words straight after an amount: a sentence, not a table row
            cur["label"].append(t)
            if cur["x0"] is None:
                cur["x0"] = t["x0"]
        prev = t
    segs.append(cur)
    return segs


def cluster(xs: list[float], gap: float) -> list[tuple[float, float]]:
    xs = sorted(xs)
    spans: list[list[float]] = []
    for x in xs:
        if spans and x - spans[-1][1] <= gap:
            spans[-1][1] = x
        else:
            spans.append([x, x])
    return [(a, b) for a, b in spans]


def pick_column(headers: dict[int, str], values_by_col: dict[int, list[float]]) -> int | None:
    """Current annual standalone column. headers: col -> header text above it."""
    cands = []
    for col, vals in values_by_col.items():
        h = headers.get(col, "").lower()
        if "note" in h or (vals and all(float(v).is_integer() and 0 < v <= 60 for v in vals) and len(vals) >= 3):
            continue
        score = 0.0
        if re.search(r"quarter|qtr|q[1-4]\b|half|month|9m|6m", h):
            score -= 50
        if re.search(r"year|fy|annual|audited", h):
            score += 5
        if "consolidated" in h:
            score -= 20
        if "previous" in h or "prior" in h:
            score -= 30
        years = [int(a) + (1 if b else 0) for a, b in YEAR.findall(h)]
        if years:
            score += (max(years) - 2000) * 10
        cands.append((score, -col, col))
    if not cands:
        return None
    cands.sort(reverse=True)
    return cands[0][2]


SECTION_DEBT = [(re.compile(r"non[- ]current liabilities", re.I), "Long-term borrowings"),
                (re.compile(r"^current liabilities", re.I), "Short-term borrowings")]


def rows_from_grid(pages_tokens: list[list[dict]], char_w: float = 5.0) -> list[tuple]:
    out = []
    for page_no, tokens in enumerate(pages_tokens, start=1):
        lines = lines_from_tokens(tokens)
        gap = 4 * char_w
        segs_by_line = [segments(ln, gap) for ln in lines]
        rights = [n["x1"] for segs in segs_by_line for s in segs if not s["bad"] for n in s["nums"]]
        cols = [c for c in cluster(rights, 2.5 * char_w)]
        def col_of(x):
            for i, (a, b) in enumerate(cols):
                if a - 2 * char_w <= x <= b + 2 * char_w:
                    return i
            return None
        # Header text per column: text tokens above the first numeric line, sitting over a column.
        headers: dict[int, str] = {}
        widths = {i: (a - 14 * char_w, b + 1) for i, (a, b) in enumerate(cols)}
        pending_label, section = None, ""
        rows_tmp = []
        for ln, segs in zip(lines, segs_by_line):
            has_nums = any(s["nums"] and not s["bad"] for s in segs)
            if not has_nums:
                text = " ".join(t["text"] for t in ln).strip()
                for t in ln:
                    cx = (t["x0"] + t["x1"]) / 2
                    for i, (a, b) in widths.items():
                        if a <= cx <= b + char_w:
                            headers[i] = (headers.get(i, "") + " " + t["text"]).strip()
                # wrapped label continuation or a section heading
                if rows_tmp and rows_tmp[-1]["wrap_ok"] and text and text[:1].islower():
                    rows_tmp[-1]["label"] += " " + text
                    rows_tmp[-1]["wrap_ok"] = False
                elif text:
                    for rx, _ in SECTION_DEBT:
                        if rx.search(text):
                            section = text
                    if not any(rx.search(text) for rx, _ in SECTION_DEBT) and len(text) < 40:
                        pending_label = text
                continue
            for s in segs:
                if s["bad"] or not s["nums"]:
                    continue
                label = " ".join(t["text"] for t in s["label"]).strip(" .:-")
                vals = {}
                for n in s["nums"]:
                    c = col_of(n["x1"])
                    if c is not None and c not in vals:
                        vals[c] = tok_value(n["text"])
                rows_tmp.append({"label": label, "vals": vals, "section": section, "wrap_ok": True})
        values_by_col: dict[int, list[float]] = {}
        for r in rows_tmp:
            for c, v in r["vals"].items():
                if v is not None:
                    values_by_col.setdefault(c, []).append(v)
        # Side-by-side tables: choose per column group (columns left/right of a label) — group by label x.
        best = pick_column(headers, values_by_col)
        for r in rows_tmp:
            label = r["label"]
            if re.fullmatch(r"(\(?[ivx]+\)?\s*)?borrowings", label.lower()) and r["section"]:
                for rx, name in SECTION_DEBT:
                    if rx.search(r["section"]):
                        label = name
            # nearest usable column at or right of the best one within this row
            chosen = None
            if best is not None and best in r["vals"]:
                chosen = r["vals"][best]
            elif r["vals"]:
                usable = [c for c in sorted(r["vals"]) if c in values_by_col and not _is_note_col(headers, values_by_col, c)]
                # side-by-side: this row's own columns; take its first non-note column
                chosen = r["vals"][usable[0]] if usable else None
            if label and chosen is not None:
                out.append((label, chosen, page_no, None))
    return out


def _is_note_col(headers, values_by_col, c):
    h = headers.get(c, "").lower()
    vals = values_by_col.get(c, [])
    return "note" in h or (len(vals) >= 3 and all(float(v).is_integer() and 0 < v <= 60 for v in vals))


# ----------------------------------------------------------------------------------------------------
# Methods
# ----------------------------------------------------------------------------------------------------
_ocr = RapidOcrBackend()


def m1_current(data: bytes):
    pages = ParserEngine(_ocr).parse(data)
    route = {"methods": [p.method for p in pages], "quality": [p.quality for p in pages], "heavy": needs_heavy(pages)}
    rows = rows_from_text([(p.no, p.text) for p in pages])
    header = " ".join(p.text[:600] for p in pages[:2])
    return rows, header, route


def m2_docling_text(data: bytes):
    from dps.parsing import DoclingEngine
    pages = DoclingEngine().parse(data)
    rows = rows_from_text([(p.no, p.text) for p in pages])
    return rows, " ".join(p.text[:600] for p in pages[:2]), {}


def m3_plumber_tables(data: bytes):
    import pdfplumber
    rows, header = [], ""
    with pdfplumber.open(io.BytesIO(data)) as pdf:
        for i, page in enumerate(pdf.pages, start=1):
            header += (page.extract_text() or "")[:600] if i <= 2 else ""
            for tbl in page.extract_tables():
                for r in tbl:
                    cells = [c for c in r if c]
                    if not cells:
                        continue
                    label = cells[0].replace("\n", " ")
                    for c in cells[1:]:
                        v = tok_value(c.split("\n")[0].strip())
                        if v is not None:
                            rows.append((label, v, i, None))
                            break
    return rows, header, {}


def ocr_tokens(data: bytes, index: int, dpi: int = 200) -> list[dict]:
    import math
    import statistics

    import pypdfium2 as pdfium
    img = pdfium.PdfDocument(data)[index].render(scale=dpi / 72).to_pil()
    result, _ = _ocr._ensure()(img)
    if not result:
        return []
    # Straighten: the median slope of the boxes' top edges is the page skew.
    angles = [math.atan2(b[1][1] - b[0][1], b[1][0] - b[0][0]) for b, _, _ in result if b[1][0] - b[0][0] > 60]
    a = -statistics.median(angles) if angles else 0.0
    ca, sa = math.cos(a), math.sin(a)
    rot = lambda p: (p[0] * ca - p[1] * sa, p[0] * sa + p[1] * ca)
    toks = []
    k = 72 / dpi
    for box, text, conf in result:
        pts = [rot(p) for p in box]
        xs, ys = [p[0] * k for p in pts], [p[1] * k for p in pts]
        words = text.split()  # an OCR box can hold several words; share its width
        x0, x1 = min(xs), max(xs)
        w = (x1 - x0) / max(1, len(text))
        pos = x0
        for wd in words:
            toks.append({"text": wd, "x0": pos, "x1": pos + w * len(wd), "top": (min(ys) + max(ys)) / 2,
                         "h": max(ys) - min(ys)})
            pos += w * (len(wd) + 1)
    return toks


def _compact(s: str) -> str:
    return re.sub(r"[^a-z]", "", s.lower())


def ocr_label(label: str) -> str:
    """OCR often drops spaces ('Totalcurrentassets'): match aliases with spaces and punctuation removed."""
    from dps.extraction.statement import LINE_ALIASES, match_key
    if match_key(label):
        return label
    c = _compact(label)
    best = ""
    for phrases in LINE_ALIASES.values():
        for ph in phrases:
            pc = _compact(ph)
            if len(pc) >= 4 and c.startswith(pc) and len(pc) > len(_compact(best)) and len(pc) >= 0.6 * len(c):
                best = ph
    return best or label


def m4_plumber_grid(data: bytes):
    import pdfplumber
    pages_tokens, header, kinds = [], "", []
    with pdfplumber.open(io.BytesIO(data)) as pdf:
        for i, page in enumerate(pdf.pages):
            words = page.extract_words(keep_blank_chars=False, use_text_flow=False, x_tolerance=1.5)
            text = page.extract_text() or ""
            if len(re.findall(r"\w", text)) >= 40:
                pages_tokens.append([{"text": w["text"], "x0": w["x0"], "x1": w["x1"], "top": w["top"]} for w in words])
                kinds.append("TEXT")
            else:
                toks = ocr_tokens(data, i)
                pages_tokens.append(toks)
                text = " ".join(t["text"] for t in toks)
                kinds.append("OCR")
            if i < 2:
                header += text[:600] + " "
    rows = rows_from_grid(pages_tokens)
    rows = [(ocr_label(r[0]) if kinds[r[2] - 1] == "OCR" else r[0],) + tuple(r[1:]) for r in rows]
    return rows, header, {"methods": kinds}


def m5_docling_tables(data: bytes):
    from docling.datamodel.base_models import DocumentStream
    from docling.document_converter import DocumentConverter
    doc = DocumentConverter().convert(DocumentStream(name="d.pdf", stream=io.BytesIO(data))).document
    rows, header = [], doc.export_to_markdown()[:1200]
    for tbl in doc.tables:
        df = tbl.export_to_dataframe()
        page = tbl.prov[0].page_no if tbl.prov else None
        headers = {i: str(c) for i, c in enumerate(df.columns)}
        values_by_col: dict[int, list[float]] = {}
        parsed = []
        for _, r in df.iterrows():
            cells = [str(x) for x in r.tolist()]
            label = next((c for c in cells if c and not is_num(c.strip())), "")
            vals = {i: tok_value(c.strip()) for i, c in enumerate(cells) if c and is_num(c.strip())}
            for i, v in vals.items():
                if v is not None:
                    values_by_col.setdefault(i, []).append(v)
            parsed.append((label, vals))
        best = pick_column(headers, values_by_col)
        section = ""
        for label, vals in parsed:
            if not vals:
                if any(rx.search(label) for rx, _ in SECTION_DEBT):
                    section = label
                continue
            if re.fullmatch(r"(\(?[ivx]+\)?\s*)?borrowings", label.lower().strip()) and section:
                for rx, name in SECTION_DEBT:
                    if rx.search(section):
                        label = name
            v = vals.get(best) if best is not None else None
            if v is None:
                usable = [c for c in sorted(vals) if not _is_note_col(headers, values_by_col, c) and vals[c] is not None]
                v = vals[usable[0]] if usable else None
            if label and v is not None:
                rows.append((label, v, page, None))
    return rows, header, {}


def m6_pypdf_layout(data: bytes):
    from pypdf import PdfReader
    reader = PdfReader(io.BytesIO(data))
    pages_tokens, header = [], ""
    for i, p in enumerate(reader.pages):
        text = p.extract_text(extraction_mode="layout") or ""
        if i < 2:
            header += text[:600] + " "
        toks = []
        for li, line in enumerate(text.splitlines()):
            for m in re.finditer(r"\S+", line):
                toks.append({"text": m.group(0), "x0": m.start() * 5.0, "x1": m.end() * 5.0, "top": li * 12.0})
        pages_tokens.append(toks)
    return rows_from_grid(pages_tokens, char_w=5.0), header, {}


METHODS = {"M1": m1_current, "M2": m2_docling_text, "M3": m3_plumber_tables, "M4": m4_plumber_grid,
           "M5": m5_docling_tables, "M6": m6_pypdf_layout}

if __name__ == "__main__":
    pdf_dir, out_path, methods = sys.argv[1], sys.argv[2], sys.argv[3:]
    results = json.load(open(out_path)) if os.path.exists(out_path) else {}
    for name in sorted(os.listdir(pdf_dir)):
        data = open(os.path.join(pdf_dir, name), "rb").read()
        doc_id = name[:-4]
        for m in methods:
            t0 = time.perf_counter()
            try:
                rows, header, route = METHODS[m](data)
                res = extract_values(rows, header)
                rec = {"values": {v.key: v.value for v in res.values if not v.derived}, "issues": res.issues,
                       "unit": res.unit_scale, "route": route, "rows": len(rows)}
            except Exception as ex:  # noqa: BLE001
                rec = {"error": f"{type(ex).__name__}: {ex}"[:300]}
            rec["seconds"] = round(time.perf_counter() - t0, 2)
            results.setdefault(doc_id, {})[m] = rec
            print(doc_id, m, rec.get("seconds"), rec.get("error", ""), flush=True)
        json.dump(results, open(out_path, "w"), indent=1)
