"""For missing covenant inputs, is the suggested line the right one?"""
import json
import sys

from dps.extraction.statement import TO_CRORE, _unit, likely_lines

res = json.load(open(sys.argv[1]))
hand = {d: v for d, v in json.load(open("adjudicated.json")).items() if not d.startswith("_")}
us = {d: v["values"] for d, v in json.load(open("truth_us.json")).items()}


def near(a, b):
    return abs(a - b) <= max(0.01, 0.005 * abs(b))


right = wrong = nosug = shown = 0
for d, ms in sorted(res.items()):
    x = ms.get("NEW")
    if not x or "raw" not in x:
        continue
    rows = [(tuple(r) + (None,) * 4)[:4] for r in x["raw"]]
    sugg = likely_lines(rows, set(x["values"]), TO_CRORE[_unit(x["header"])])
    shown += len(sugg)
    truth = hand.get(d) or us.get(d) or {}
    by = {s["key"]: s for s in sugg}
    for k, t in truth.items():
        if t is None or k in x["values"]:
            continue
        cands = t if isinstance(t, list) else [t]
        s = by.get(k)
        if s is None:
            nosug += 1
            print(d, k, "missing, no suggestion")
        elif any(near(s["value"], c) for c in cands):
            right += 1
            print(d, k, "RIGHT:", s["label"], s["value"])
        else:
            wrong += 1
            print(d, k, "other line:", s["label"], s["value"], "truth", cands[0])
print(f"\nmissing values with truth: suggestion right {right}, other line {wrong}, none {nosug}; "
      f"suggestions shown over all docs: {shown}")
