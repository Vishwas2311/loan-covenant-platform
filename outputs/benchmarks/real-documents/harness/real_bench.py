"""Readers on real statement pages. Run in the DPS containers (bench.py and alt.py next to this file).

  OLD  DPS before: ParserEngine (pypdf, RapidOCR lines) + first number on each line
  NEW  DPS now: extraction.statement_pdf.read_pdf_statement (pdfplumber words / RapidOCR boxes)
  LAY  pypdf layout mode + column picker
  AI   Azure OpenAI on the page text
  DOCT Docling page text + first number (the old heavy pool)      (heavy container)
  DOCX Docling tables + column picker                              (heavy container)
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bench  # noqa: E402
from dps.extraction.statement import extract_values  # noqa: E402

METHODS = {}


def method(name):
    def wrap(f):
        METHODS[name] = f
        return f
    return wrap


@method("OLD")
def old(data):
    rows, header, _ = bench.m1_current(data)
    return rows, header


@method("NEW")
def new(data):
    from dps.extraction.statement_pdf import read_pdf_statement
    from dps.parsing import RapidOcrBackend
    t = read_pdf_statement(data, RapidOcrBackend())
    return t.rows, t.header_text


@method("LAY")
def lay(data):
    rows, header, _ = bench.m6_pypdf_layout(data)
    return rows, header


@method("AI")
def ai(data):
    import alt
    rows, header, _ = alt.m10_azure_openai(data)
    return rows, header


@method("DOCT")
def doct(data):
    rows, header, _ = bench.m2_docling_text(data)
    return rows, header


@method("DOCX")
def docx(data):
    rows, header, _ = bench.m5_docling_tables(data)
    return rows, header


if __name__ == "__main__":
    pdf_dir, out_path, methods = sys.argv[1], sys.argv[2], sys.argv[3:]
    results = json.load(open(out_path)) if os.path.exists(out_path) else {}
    for name in sorted(os.listdir(pdf_dir)):
        if not name.endswith(".pdf"):
            continue
        doc = name[:-4]
        data = open(os.path.join(pdf_dir, name), "rb").read()
        for m in methods:
            if m in results.get(doc, {}):
                continue
            t0 = time.perf_counter()
            try:
                rows, header = METHODS[m](data)
                res = extract_values(rows, header)
                rec = {"values": {v.key: v.value for v in res.values if not v.derived}, "issues": res.issues,
                       "unit": res.unit_scale, "rows": len(rows), "header": header[:3000],
                       "raw": [[r[0][:160], r[1], r[2]] for r in rows]}
            except Exception as ex:  # noqa: BLE001
                rec = {"error": f"{type(ex).__name__}: {ex}"[:200]}
            rec["seconds"] = round(time.perf_counter() - t0, 2)
            results.setdefault(doc, {})[m] = rec
            print(doc, m, rec["seconds"], rec.get("error", ""), flush=True)
            json.dump(results, open(out_path, "w"), indent=1)
