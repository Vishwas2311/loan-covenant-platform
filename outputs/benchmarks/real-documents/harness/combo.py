"""Value of the second reader: NEW publishes; a value where AI differs goes to review.

For each truth value: NEW right / wrong / missing, and whether AI agrees with NEW. Reports what a bank would
see with NEW alone versus NEW + AI cross-check (disagreement -> review)."""
import json
import sys


res = json.load(open(sys.argv[1]))
hand = json.load(open("adjudicated.json"))
us = json.load(open("truth_us.json"))
CHECKED = {"REVENUE", "PBT", "PAT", "INTEREST_PAID", "DEPRECIATION", "TOTAL_ASSETS", "NET_WORTH", "CURRENT_ASSETS",
           "CURRENT_LIABILITIES", "LT_DEBT", "ST_DEBT", "CASH", "CFO", "INVENTORY", "RECEIVABLES"}
EXPENSE = {"INTEREST_PAID", "DEPRECIATION"}


def near(a, b):
    return abs(a - b) <= max(0.01, 0.005 * abs(b))


def run(name, truths):
    n = right = wrong = miss = 0
    pub_right = pub_wrong = review_right = review_wrong = 0
    for d, t in truths.items():
        ms = res.get(d, {})
        if "NEW" not in ms:
            continue
        g, a = ms["NEW"].get("values", {}), ms.get("AI", {}).get("values", {})
        for k, cands in t.items():
            if k not in CHECKED or cands is None:
                continue
            cands = cands if isinstance(cands, list) else [cands]
            n += 1
            if k not in g:
                miss += 1
                continue
            ok = any(near(g[k], c) for c in cands)
            right += ok
            wrong += not ok
            other = a.get(k)
            if other is not None and k in EXPENSE:
                other = abs(other)
            differs = other is not None and not near(g[k], other)
            if differs:
                review_right += ok
                review_wrong += not ok
            else:
                pub_right += ok
                pub_wrong += not ok
    print(f"\n{name}: {n} covenant-input values")
    print(f"  NEW alone      : {right} right ({100*right/n:.1f}%), {wrong} wrong published, {miss} missing")
    print(f"  NEW + AI check : {pub_right} right published, {pub_wrong} wrong published "
          f"({100*pub_wrong/max(1,pub_right+pub_wrong):.1f}% of published), {review_right + review_wrong} sent to "
          f"review ({review_wrong} of them really wrong, {review_right} were right)")


run("India / Sri Lanka, hand-checked", {d: v for d, v in hand.items() if not d.startswith("_")})
run("SEC 10-K, XBRL truth", {d: v["values"] for d, v in us.items()})
