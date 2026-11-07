"""How well the accounting identities catch wrong readings: per document, do they fire on documents with a
wrong value, and stay quiet on documents that were read right?"""
import json
import sys

from dps.extraction.statement import identity_issues

res = json.load(open(sys.argv[1]))
method = sys.argv[2] if len(sys.argv) > 2 else "NEW"
hand = {d: v for d, v in json.load(open("adjudicated.json")).items() if not d.startswith("_")}
us = {d: {k: c for k, c in v["values"].items()} for d, v in json.load(open("truth_us.json")).items()}


def near(a, b):
    return abs(a - b) <= max(0.01, 0.005 * abs(b))


rows = []
for d, ms in sorted(res.items()):
    x = ms.get(method)
    if not x:
        continue
    v = x.get("values", {})
    issues = identity_issues(v)
    truth = hand.get(d) or us.get(d)
    wrong = None
    if truth:
        wrong = [k for k, t in truth.items() if t is not None and k in v
                 and not any(near(v[k], c) for c in (t if isinstance(t, list) else [t]))]
    rows.append((d, issues, wrong))
    print(d, "WRONG:" + ",".join(wrong) if wrong else ("ok" if wrong is not None else "?"), "|",
          "; ".join(i.split(":")[0] for i in issues))
known = [r for r in rows if r[2] is not None]
bad = [r for r in known if r[2]]
good = [r for r in known if not r[2]]
print(f"\nchecked docs with truth: {len(known)}; with a wrong value: {len(bad)}, flagged {sum(1 for r in bad if r[1])}; "
      f"read right: {len(good)}, flagged anyway {sum(1 for r in good if r[1])}")
print(f"all docs flagged: {sum(1 for r in rows if r[1])} of {len(rows)}")
