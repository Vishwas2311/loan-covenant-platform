"""Safety net on the 45 agreements: does it catch the covenants the rules miss, and how many sentences does it
show a reviewer per agreement (noise)?"""
import json
import statistics
import sys

from dps.extraction.agreement import extract_terms
from dps.extraction.coverage import possible_covenants
from dps.parsing import Page

FAMILY = {"DSCR": "DSCR", "ICR": "ICR", "FIXED_CHARGE_COVERAGE_RATIO": "FCCR", "DEBT_TO_EBITDA": "LEVERAGE",
          "NET_LEVERAGE_RATIO": "LEVERAGE", "DEBT_TO_NET_WORTH": "DEBT_TO_NW", "DEBT_TO_EQUITY": "DEBT_TO_NW",
          "CURRENT_RATIO": "CURRENT_RATIO", "QUICK_RATIO": "QUICK_RATIO", "MIN_TANGIBLE_NET_WORTH": "MIN_NW",
          "MIN_NET_WORTH": "MIN_NW", "MINIMUM_LIQUIDITY": "LIQUIDITY", "EBITDA_TO_EMI": "DSCR",
          "LOAN_TO_VALUE": "LTV", "MIN_EBITDA": "MIN_EBITDA"}

cache = json.load(open("ag_pages.json"))
truth = json.load(open("truth_ag.json"))
cov, excluded = truth["covenants"], set(truth["excluded"])


def match(t, c):
    return (FAMILY.get(t.metric, t.metric) == c["family"] and ("min" if (t.operator or "").startswith(">") else "max")
            == c["dir"] and t.threshold is not None and abs(t.threshold - c["threshold"]) <= max(0.011, 0.01 * c["threshold"]))


caught = missed_total = 0
shown = []
for doc, raw in sorted(cache.items()):
    if doc in excluded or doc not in cov:
        continue
    pages = [Page(*p) for p in raw]
    terms = extract_terms(pages)
    cands, aside = possible_covenants(pages, terms)
    shown.append(len(cands))
    for c in cov[doc]:
        if any(match(t, c) for t in terms):
            continue
        missed_total += 1
        thr = f"{c['threshold']:g}"
        hit = [x for x in cands if thr in x.quote or f"{c['threshold']:.2f}" in x.quote]
        caught += bool(hit)
        print(doc, c["family"], c["threshold"], "-> NET" if hit else "-> not in net", (hit[0].name if hit else ""))
    print("   ", doc, "shown", len(cands), "set aside", aside)
print(f"\nmissed by rules: {missed_total}, caught by the net: {caught}; sentences shown per agreement: "
      f"median {statistics.median(shown)}, max {max(shown)}, total {sum(shown)}")
