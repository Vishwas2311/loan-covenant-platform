"""Agreement benchmark: covenant extraction on whole documents and on the covenant section.

  W_OLD   DPS Flow A today: ParserEngine (pypdf, RapidOCR) on every page -> extract_terms
  W_PLB   pdfplumber text (RapidOCR for pages without text) -> extract_terms
  S_OLD   section pages only (section finder) -> ParserEngine -> extract_terms
  S_AI    section pages -> Azure OpenAI (maintenance covenants as JSON)
  S_DOC   section pages -> Docling text -> extract_terms                   (heavy container)

  python ag_bench.py <pdf_dir> <out.json> METHOD...
"""
import io
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dps.extraction.agreement import extract_terms  # noqa: E402
from dps.parsing import Page, ParserEngine, RapidOcrBackend, text_quality  # noqa: E402

FAMILY = {"DSCR": "DSCR", "ICR": "ICR", "FIXED_CHARGE_COVERAGE_RATIO": "FCCR", "DEBT_TO_EBITDA": "LEVERAGE",
          "NET_LEVERAGE_RATIO": "LEVERAGE", "DEBT_TO_NET_WORTH": "DEBT_TO_NW", "DEBT_TO_EQUITY": "DEBT_TO_NW",
          "CURRENT_RATIO": "CURRENT_RATIO", "QUICK_RATIO": "QUICK_RATIO", "MIN_TANGIBLE_NET_WORTH": "MIN_NW",
          "MIN_NET_WORTH": "MIN_NW", "MINIMUM_LIQUIDITY": "LIQUIDITY", "EBITDA_TO_EMI": "DSCR"}
_ocr = RapidOcrBackend()

HEAD = re.compile(r"financial\s+(maintenance\s+)?covenants?|financial\s+condition\s+covenants?|financial\s+ratios|"
                  r"financial\s+undertakings", re.I)
KEEP = re.compile(r"(shall|will)\s+not\s+permit[^.]{0,160}(ratio|net\s+worth|liquidity)|"
                  r"(shall|will)\s+(at\s+all\s+times\s+)?maintain[^.]{0,120}(ratio|net\s+worth|liquidity|coverage)|"
                  r"(not|never)\s+(be\s+)?(less|greater|more)\s+than\s+[\d.]+\s*(to|:)\s*1", re.I)
NOISE = re.compile(r"applicable\s+rate|pricing\s+level|pro\s+forma\s+basis|excess\s+cash\s+flow|incremental|"
                   r"asset\s+sale\s+percentage|ecf\s+percentage", re.I)


def find_section(texts: list[str], k: int = 3) -> list[int]:
    scored = []
    for i, t in enumerate(texts):
        s = (5 if HEAD.search(t) else 0) + 3 * len(KEEP.findall(t)) - 2 * len(NOISE.findall(t))
        if s > 0:
            scored.append((s, i + 1))
    scored.sort(reverse=True)
    return sorted(p for _, p in scored[:k])


def page_texts(data: bytes) -> tuple[list[str], list[int]]:
    from pypdf import PdfReader
    texts = [(p.extract_text() or "") for p in PdfReader(io.BytesIO(data)).pages]
    ocr_pages = []
    for i, t in enumerate(texts):
        if len(re.findall(r"\w", t)) < 40:
            texts[i] = "\n".join(b[1] for b in _ocr.ocr_boxes(data, i, 200))
            ocr_pages.append(i + 1)
    return texts, ocr_pages


def terms_from_pages(pages: list[Page]) -> list[dict]:
    out = []
    for t in extract_terms(pages):
        if t.category != "FINANCIAL" or t.threshold is None:
            continue
        out.append({"family": FAMILY.get(t.metric, t.metric), "dir": "min" if (t.operator or "").startswith(">") else "max",
                    "threshold": t.threshold, "page": t.page, "metric": t.metric})
    return out


def w_old(data, _texts):
    pages = ParserEngine(_ocr).parse(data)
    return terms_from_pages(pages), {}


def w_plb(data, _texts):
    import pdfplumber
    pages = []
    with pdfplumber.open(io.BytesIO(data)) as pdf:
        for i, p in enumerate(pdf.pages):
            t = p.extract_text() or ""
            method = "TEXT"
            if len(re.findall(r"\w", t)) < 40:
                t, method = "\n".join(b[1] for b in _ocr.ocr_boxes(data, i, 200)), "OCR"
            pages.append(Page(i + 1, t, method, text_quality(t)))
    return terms_from_pages(pages), {}


def section(texts):
    return find_section(texts)


def s_old(data, texts):
    sec = section(texts)
    pages = [Page(p, texts[p - 1], "TEXT", text_quality(texts[p - 1])) for p in sec]
    return terms_from_pages(pages), {"section": sec}


def s_doc(data, texts):
    from pypdf import PdfReader, PdfWriter

    from dps.parsing import DoclingEngine
    sec = section(texts)
    if not sec:
        return [], {"section": sec}
    r, w = PdfReader(io.BytesIO(data)), PdfWriter()
    for p in sec:
        w.add_page(r.pages[p - 1])
    buf = io.BytesIO()
    w.write(buf)
    pages = DoclingEngine().parse(buf.getvalue())
    pages = [Page(sec[i], pg.text, pg.method, pg.quality) for i, pg in enumerate(pages) if i < len(sec)]
    return terms_from_pages(pages), {"section": sec}


PROMPT = """You read part of a loan or credit agreement for a bank's covenant monitoring.
List the FINANCIAL MAINTENANCE COVENANTS only: ratios or amounts the borrower must keep meeting while the loan
is outstanding. Do NOT list pricing grids, incurrence tests for new debt or payments ("on a pro forma basis"),
excess cash flow percentages, conditions for releasing funds or extending the loan, or insurance amounts. When a
covenant steps down over time, give the first level.
Return JSON only: {{"covenants": [{{"family": one of LEVERAGE, DSCR, ICR, FCCR, DEBT_TO_NW, CURRENT_RATIO, MIN_NW,
LIQUIDITY, MIN_EBITDA, LTV, OTHER, "dir": "min" or "max", "threshold": number (ratio as 3.5; amounts in full;
LTV as a percent), "page": page number}}]}}

{text}"""


def s_ai(data, texts):
    from crewai import LLM
    sec = section(texts)
    if not sec:
        return [], {"section": sec}
    text = "\n\n".join(f"[page {p}]\n{texts[p - 1]}" for p in sec)[:60000]
    llm = LLM(model=os.environ["DPS_AI_MODEL"], timeout=120)
    answer = llm.call([{"role": "user", "content": PROMPT.format(text=text)}])
    j = json.loads(answer[answer.find("{"): answer.rfind("}") + 1])
    out = [c for c in j.get("covenants", []) if isinstance(c.get("threshold"), (int, float))]
    tok = llm.get_token_usage_summary()
    return out, {"section": sec, "tokens": tok.prompt_tokens + tok.completion_tokens}


METHODS = {"W_OLD": w_old, "W_PLB": w_plb, "S_OLD": s_old, "S_DOC": s_doc, "S_AI": s_ai}

if __name__ == "__main__":
    pdf_dir, out_path, methods = sys.argv[1], sys.argv[2], sys.argv[3:]
    results = json.load(open(out_path)) if os.path.exists(out_path) else {}
    for name in sorted(os.listdir(pdf_dir)):
        if not name.endswith(".pdf"):
            continue
        doc = name[:-4]
        data = open(os.path.join(pdf_dir, name), "rb").read()
        t0 = time.perf_counter()
        texts, ocr_pages = page_texts(data)
        read_s = time.perf_counter() - t0
        for m in methods:
            if m in results.get(doc, {}):
                continue
            t1 = time.perf_counter()
            try:
                terms, extra = METHODS[m](data, texts)
                rec = {"terms": terms, **extra}
            except Exception as ex:  # noqa: BLE001
                rec = {"error": f"{type(ex).__name__}: {ex}"[:200]}
            rec["seconds"] = round(time.perf_counter() - t1 + (read_s if m.startswith("S_") else 0), 2)
            rec["ocr_pages"] = len(ocr_pages)
            results.setdefault(doc, {})[m] = rec
            print(doc, m, len(rec.get("terms", [])), rec["seconds"], rec.get("error", ""), flush=True)
            json.dump(results, open(out_path, "w"), indent=1)
