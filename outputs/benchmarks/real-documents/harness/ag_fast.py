"""Agreement rules re-scored on cached pages (ag_pages.json from ag_cache.py): W_NEW and S_NEW in seconds."""
import json
import re
import sys
import time

from dps.extraction.agreement import extract_terms
from dps.parsing import Page

FAMILY = {"DSCR": "DSCR", "ICR": "ICR", "FIXED_CHARGE_COVERAGE_RATIO": "FCCR", "DEBT_TO_EBITDA": "LEVERAGE",
          "NET_LEVERAGE_RATIO": "LEVERAGE", "DEBT_TO_NET_WORTH": "DEBT_TO_NW", "DEBT_TO_EQUITY": "DEBT_TO_NW",
          "CURRENT_RATIO": "CURRENT_RATIO", "QUICK_RATIO": "QUICK_RATIO", "MIN_TANGIBLE_NET_WORTH": "MIN_NW",
          "MIN_NET_WORTH": "MIN_NW", "MINIMUM_LIQUIDITY": "LIQUIDITY", "EBITDA_TO_EMI": "DSCR",
          "LOAN_TO_VALUE": "LTV", "MIN_EBITDA": "MIN_EBITDA"}
HEAD = re.compile(r"financial\s+(maintenance\s+)?covenants?|financial\s+condition\s+covenants?|financial\s+ratios|"
                  r"financial\s+undertakings", re.I)
KEEP = re.compile(r"(shall|will)\s+not\s+permit[^.]{0,160}(ratio|net\s+worth|liquidity)|"
                  r"(shall|will)\s+(at\s+all\s+times\s+)?maintain[^.]{0,120}(ratio|net\s+worth|liquidity|coverage)|"
                  r"(not|never)\s+(be\s+)?(less|greater|more)\s+than\s+[\d.]+\s*(to|:)\s*1", re.I)
NOISE = re.compile(r"applicable\s+rate|pricing\s+level|pro\s+forma\s+basis|excess\s+cash\s+flow|incremental|"
                   r"asset\s+sale\s+percentage|ecf\s+percentage", re.I)


def section(texts, k=3):
    scored = sorted(((5 if HEAD.search(t) else 0) + 3 * len(KEEP.findall(t)) - 2 * len(NOISE.findall(t)), i + 1)
                    for i, t in enumerate(texts))
    return sorted(p for s, p in scored[::-1][:k] if s > 0)


def terms(pages):
    out = []
    for t in extract_terms(pages):
        if t.category != "FINANCIAL" or t.threshold is None:
            continue
        out.append({"family": FAMILY.get(t.metric, t.metric), "dir": "min" if (t.operator or "").startswith(">")
                    else "max", "threshold": t.threshold, "page": t.page, "metric": t.metric, "name": t.name})
    return out


cache = json.load(open(sys.argv[1]))
res = {}
for doc, raw in sorted(cache.items()):
    pages = [Page(*p) for p in raw]
    t0 = time.perf_counter()
    w = terms(pages)
    sec = section([p.text for p in pages])
    s = terms([p for p in pages if p.no in sec])
    dt = round(time.perf_counter() - t0, 2)
    res[doc] = {"W_NEW": {"terms": w, "seconds": dt}, "S_NEW": {"terms": s, "section": sec, "seconds": dt}}
json.dump(res, open(sys.argv[2], "w"), indent=1)
print(len(res))
