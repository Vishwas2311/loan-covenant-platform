"""Leave-one-out scoring for India / Sri Lanka: a reader is scored only on values that two OTHER voters agree
on, so no voter grades its own answers. Adjudicated values (adjudicated.json, from page images) override."""
import json
import sys

VOTERS = ["NEW", "LAY", "AI", "DOCX"]
KEYS = ["REVENUE", "OTHER_INCOME", "TOTAL_INCOME", "EMPLOYEE_COST", "DEPRECIATION", "INTEREST_PAID", "PBT", "TAX",
        "PAT", "CASH", "INVENTORY", "RECEIVABLES", "CURRENT_ASSETS", "TOTAL_ASSETS", "SHARE_CAPITAL", "NET_WORTH",
        "LT_DEBT", "ST_DEBT", "TRADE_PAYABLES", "CURRENT_LIABILITIES", "CFO", "CFI", "CFF"]


def close(a, b):
    return abs(a - b) <= max(0.01, 0.005 * abs(b))


res = json.load(open(sys.argv[1]))
adj = json.load(open(sys.argv[2])) if len(sys.argv) > 2 else {}
methods = ["OLD", "NEW", "LAY", "AI", "DOCX", "DOCT"]
tot = {m: dict(c=0, w=0, miss=0, n=0, ws=0, s=0.0, docs=0) for m in methods}
for d, ms in res.items():
    if d.startswith("US"):
        continue
    for m in methods:
        r = ms.get(m)
        if not r:
            continue
        got = r.get("values", {})
        flagged = bool(r.get("issues")) or "error" in r
        tot[m]["docs"] += 1
        tot[m]["s"] += r.get("seconds", 0)
        for k in KEYS:
            if k in adj.get(d, {}):
                truth = adj[d][k]
                if truth is None:
                    continue
            else:
                others = [ms[v]["values"][k] for v in VOTERS if v != m and v in ms and k in ms[v].get("values", {})]
                truth = next((v for v in others if sum(1 for w in others if close(w, v)) >= 2), None)
                if truth is None:
                    continue
            x = tot[m]
            x["n"] += 1
            if k not in got:
                x["miss"] += 1
            elif close(got[k], truth):
                x["c"] += 1
            else:
                x["w"] += 1
                x["ws"] += 0 if flagged else 1
print(f"{'method':6} {'values':>7} {'accuracy':>9} {'correct':>8} {'wrong':>6} {'silent wrong':>13} {'missing':>8} {'docs':>5} {'s/doc':>6}")
for m in methods:
    x = tot[m]
    if x["n"]:
        print(f"{m:6} {x['n']:>7} {100 * x['c'] / x['n']:8.1f}% {x['c']:>8} {x['w']:>6} {x['ws']:>13} {x['miss']:>8} "
              f"{x['docs']:>5} {x['s'] / max(1, x['docs']):6.1f}")
