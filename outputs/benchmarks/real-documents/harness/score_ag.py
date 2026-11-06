"""Score covenant extraction against truth_ag.json: recall, precision, false covenants, section hits."""
import json
import sys

truth = json.load(open("truth_ag.json"))
cov, excluded = truth["covenants"], truth["excluded"]
res = {}
for f in sys.argv[1:]:
    for d, ms in json.load(open(f)).items():
        res.setdefault(d, {}).update(ms)
methods = sorted({m for d in cov for m in res.get(d, {})})


def match(p, t):
    if p.get("family") != t["family"] or p.get("dir") != t["dir"]:
        return False
    a, b = float(p["threshold"]), float(t["threshold"])
    return abs(a - b) <= max(0.011, 0.01 * abs(b))


rows = []
for m in methods:
    tp = fn = fp = fp_none = docs = sec_hit = sec_tot = 0
    secs = 0.0
    for d, want in cov.items():
        r = res.get(d, {}).get(m)
        if r is None:
            continue
        docs += 1
        got = list(r.get("terms", []))
        used = set()
        for t in want:
            hit = next((i for i, p in enumerate(got) if i not in used and match(p, t)), None)
            if hit is None:
                fn += 1
            else:
                tp += 1
                used.add(hit)
        extra = len(got) - len(used)
        fp += extra
        if not want:
            fp_none += extra
        if "section" in r and want:
            pages = {t["page"] for t in want}
            sec_tot += len(pages)
            sec_hit += sum(1 for p in pages if any(abs(p - s) <= 1 for s in r["section"]))
        secs += r.get("seconds", 0)
    rec = tp / max(1, tp + fn)
    prec = tp / max(1, tp + fp)
    f1 = 2 * rec * prec / max(1e-9, rec + prec)
    sec = f"{100 * sec_hit / sec_tot:.0f}%" if sec_tot else "-"
    rows.append((m, docs, tp, fn, fp, fp_none, rec, prec, f1, sec, secs / max(1, docs)))
print(f"{len(cov)} agreements scored ({sum(len(v) for v in cov.values())} covenants; "
      f"{sum(1 for v in cov.values() if not v)} with none); excluded: {', '.join(excluded)}")
print(f"{'method':7} {'docs':>4} {'found':>6} {'missed':>7} {'false':>6} {'false on none':>14} {'recall':>7} {'precision':>10} "
      f"{'F1':>6} {'section':>8} {'s/doc':>6}")
for m, docs, tp, fn, fp, fpn, rec, prec, f1, sec, s in rows:
    print(f"{m:7} {docs:>4} {tp:>6} {fn:>7} {fp:>6} {fpn:>14} {100 * rec:6.1f}% {100 * prec:9.1f}% {100 * f1:5.1f}% {sec:>8} {s:6.1f}")
