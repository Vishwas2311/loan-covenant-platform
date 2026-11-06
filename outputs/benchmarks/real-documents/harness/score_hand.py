"""Score readers only on hand-read values (adjudicated.json, from page images)."""
import json, sys
r = json.load(open(sys.argv[1])); a = json.load(open(sys.argv[2])); show = sys.argv[3:] 
def close(x, y): return abs(x - y) <= max(0.01, 0.005 * abs(y))
print(f"{'method':6} {'values':>7} {'accuracy':>9} {'correct':>8} {'wrong':>6} {'missing':>8} {'docs':>5}")
for m in ["OLD", "NEW", "LAY", "AI", "DOCX", "DOCT"]:
    c = w = mi = n = docs = 0
    for d, t in a.items():
        if d.startswith("_") or m not in r.get(d, {}): continue
        docs += 1; g = r[d][m].get("values", {})
        for k, v in t.items():
            if v is None: continue
            n += 1
            if k not in g: mi += 1; (m in show) and print("   miss", d, k, v)
            elif close(g[k], v): c += 1
            else: w += 1; (m in show) and print("   WRONG", d, k, "got", g[k], "truth", v)
    if n: print(f"{m:6} {n:>7} {100*c/n:8.1f}% {c:>8} {w:>6} {mi:>8} {docs:>5}")
