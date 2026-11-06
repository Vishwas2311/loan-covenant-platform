"""Score results.json against truth.json. Correct = within 0.5% (or 0.01 crore).

Wrong values are split by whether DPS would hold the document for review (issues present) or report it
automatically: a silent wrong figure reaches the covenant check.
"""
import json
import os
import sys

here = os.path.dirname(os.path.abspath(__file__))
truth = {d["id"]: d for d in json.load(open(os.path.join(here, os.environ.get("TRUTH", "truth.json"))))}
results = {}
for f in sys.argv[1:]:
    for doc, ms in json.load(open(f)).items():
        results.setdefault(doc, {}).update(ms)
methods = sorted({m for ms in results.values() for m in ms})


def ok(a, b):
    return abs(a - b) <= max(0.01, 0.005 * abs(b))


tot = {m: {"correct": 0, "wrong_silent": 0, "wrong_flagged": 0, "missing": 0, "secs": 0.0} for m in methods}
per_doc = {}
for doc_id, d in sorted(truth.items()):
    per_doc[doc_id] = {}
    for m in methods:
        rec = results.get(doc_id, {}).get(m)
        if rec is None:
            continue
        got = rec.get("values", {})
        flagged = bool(rec.get("issues")) or "error" in rec
        c = w = miss = 0
        for k, v in d["truth"].items():
            if k not in got:
                miss += 1
            elif ok(got[k], v):
                c += 1
            else:
                w += 1
        t = tot[m]
        t["correct"] += c
        t["missing"] += miss
        t["wrong_flagged" if flagged else "wrong_silent"] += w
        t["secs"] += rec.get("seconds", 0)
        per_doc[doc_id][m] = f"{c}/{len(d['truth'])}" + (f" w{w}" if w else "") + (" F" if flagged else "")

n = sum(len(d["truth"]) for d in truth.values())
print(f"{'doc':5} {'layout':72} " + " ".join(f"{m:>11}" for m in methods))
for doc_id, row in per_doc.items():
    print(f"{doc_id:5} {truth[doc_id]['layout'][:72]:72} " + " ".join(f"{row.get(m, '-'):>11}" for m in methods))
print()
print(f"{'method':6} {'accuracy':>9} {'correct':>8} {'silent wrong':>13} {'flagged wrong':>14} {'missing':>8} {'seconds':>8}")
for m in methods:
    t = tot[m]
    print(f"{m:6} {100 * t['correct'] / n:8.1f}% {t['correct']:>8} {t['wrong_silent']:>13} {t['wrong_flagged']:>14} "
          f"{t['missing']:>8} {t['secs']:>8.1f}")
print(f"\n{n} truth values over {len(truth)} documents. Per-doc cell: correct/total, wN = N wrong, F = DPS would hold it for review")
