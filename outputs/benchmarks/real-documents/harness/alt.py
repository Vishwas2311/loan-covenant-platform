"""More statement-PDF readers, scored the same way as bench.py (rows -> DPS extract_values).

  M7 Camelot (stream)     M8 PyMuPDF find_tables     M9 img2table      (host scratch venv)
  M10 Azure OpenAI: the page text from pdfplumber (RapidOCR for scans) and a JSON answer (dps-api container)
"""
from __future__ import annotations

import json
import os
import re
import sys
import time

sys.path.insert(0, os.environ.get("DPS_SRC", "/app/src"))
import bench  # noqa: E402
from dps.extraction.statement import LINE_ALIASES, extract_values  # noqa: E402


def rows_from_cells(table: list[list[str]], page: int, header_names: list[str] | None = None) -> list[tuple]:
    """A table as rows of cell strings -> (label, value, page, None), with the shared column picker."""
    def clean(c):
        return "" if c is None or (isinstance(c, float) and c != c) or str(c) in ("nan", "None") else str(c)
    table = [[clean(c).replace("\n", " ").strip() for c in r] for r in table]
    width = max((len(r) for r in table), default=0)
    headers = {i: (clean(header_names[i]) if header_names and i < len(header_names) else "") for i in range(width)}
    first_num = next((i for i, r in enumerate(table) if any(bench.is_num(c) and c not in bench.DASH for c in r[1:])), len(table))
    for r in table[:first_num]:
        for i, c in enumerate(r):
            headers[i] = (headers[i] + " " + c).strip()
    parsed, values_by_col, section = [], {}, ""
    for r in table[first_num:]:
        label = next((c for c in r if c and not bench.is_num(c)), "")
        vals = {i: bench.tok_value(c) for i, c in enumerate(r) if c and bench.is_num(c)}
        for i, v in vals.items():
            if v is not None:
                values_by_col.setdefault(i, []).append(v)
        parsed.append((label, vals))
    best = bench.pick_column(headers, values_by_col)
    out = []
    for label, vals in parsed:
        if not vals:
            if any(rx.search(label) for rx, _ in bench.SECTION_DEBT):
                section = label
            continue
        if re.fullmatch(r"(\(?[ivx]+\)?\s*)?borrowings", label.lower()) and section:
            for rx, name in bench.SECTION_DEBT:
                if rx.search(section):
                    label = name
        v = vals.get(best) if best is not None else None
        if v is None:
            usable = [c for c in sorted(vals) if vals[c] is not None and not bench._is_note_col(headers, values_by_col, c)]
            v = vals[usable[0]] if usable else None
        if label and v is not None:
            out.append((label, v, page, None))
    return out


def m7_camelot(path):
    import camelot
    rows, header = [], ""
    for t in camelot.read_pdf(path, pages="all", flavor="stream"):
        rows += rows_from_cells(t.df.values.tolist(), t.page)
        header += " ".join(t.df.values[:3].ravel().tolist()) + " "
    return rows, header


def m8_pymupdf(path):
    import fitz
    rows, header = [], ""
    with fitz.open(path) as doc:
        for i, page in enumerate(doc, start=1):
            if i <= 2:
                header += page.get_text()[:600]
            for t in page.find_tables(strategy="text").tables:
                rows += rows_from_cells(t.extract(), i, list(t.header.names))
    return rows, header


def m9_img2table(path):
    from img2table.document import PDF
    rows, header = [], ""
    doc = PDF(path)
    for page_no, tables in doc.extract_tables(borderless_tables=True).items():
        for t in tables:
            rows += rows_from_cells(t.df.values.tolist(), page_no + 1,
                                    [str(c) for c in t.df.columns])
    import fitz
    with fitz.open(path) as d:
        header = " ".join(p.get_text()[:600] for p in list(d)[:2])
    return rows, header


PROMPT = """You read a company's financial statement for a bank's covenant monitoring.
Return JSON only: {{"unit": "crore|lakh|million|thousand|rupee", "values": {{KEY: number}}}}.
Use the CURRENT annual period (the latest year ended). When both standalone (company) and consolidated (group)
statements are shown use the standalone ones, otherwise use the statements given. Never a quarter, half-year or
previous year, never a ratio or percentage. Negative numbers in brackets are negative. Give each number
exactly as printed in the stated unit (do not convert). Leave out any key the statement does not print.
Keys and what they mean: {keys}

Statement text:
{text}"""


def m10_azure_openai(data: bytes):
    import pdfplumber
    from crewai import LLM
    import io
    pages = []
    with pdfplumber.open(io.BytesIO(data)) as pdf:
        for i, p in enumerate(pdf.pages):
            t = p.extract_text(layout=True) or ""
            if len(re.findall(r"\w", t)) < 40:
                t = "\n".join(" ".join(x["text"] for x in ln) for ln in bench.lines_from_tokens(bench.ocr_tokens(data, i)))
            pages.append(t)
    keys = "; ".join(f"{k}: {v[0]}" for k, v in LINE_ALIASES.items())
    llm = LLM(model=os.environ["DPS_AI_MODEL"], timeout=90)
    answer = llm.call([{"role": "user", "content": PROMPT.format(keys=keys, text="\n\n".join(pages)[:60000])}])
    j = json.loads(answer[answer.find("{"): answer.rfind("}") + 1])
    unit = j.get("unit", "crore")
    rows = [(LINE_ALIASES[k][0], float(v), None, None) for k, v in j.get("values", {}).items() if k in LINE_ALIASES and v is not None]
    tok = llm.get_token_usage_summary()
    return rows, f"amounts in {unit}", {"tokens": tok.prompt_tokens + tok.completion_tokens}


if __name__ == "__main__":
    pdf_dir, out_path, methods = sys.argv[1], sys.argv[2], sys.argv[3:]
    results = json.load(open(out_path)) if os.path.exists(out_path) else {}
    for name in sorted(os.listdir(pdf_dir)):
        path = os.path.join(pdf_dir, name)
        doc_id = name[:-4]
        for m in methods:
            t0 = time.perf_counter()
            try:
                if m == "M10":
                    rows, header, extra = m10_azure_openai(open(path, "rb").read())
                else:
                    rows, header = {"M7": m7_camelot, "M8": m8_pymupdf, "M9": m9_img2table}[m](path)
                    extra = {}
                res = extract_values(rows, header)
                rec = {"values": {v.key: v.value for v in res.values if not v.derived}, "issues": res.issues,
                       "rows": len(rows), "route": extra}
            except Exception as ex:  # noqa: BLE001
                rec = {"error": f"{type(ex).__name__}: {ex}"[:300]}
            rec["seconds"] = round(time.perf_counter() - t0, 2)
            results.setdefault(doc_id, {})[m] = rec
            print(doc_id, m, rec["seconds"], rec.get("error", ""), flush=True)
        json.dump(results, open(out_path, "w"), indent=1)
