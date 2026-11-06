"""Held-out set, written after the readers were frozen: styles none of them was tuned on.

H01 Times 9 pt, tight cells, ALL-CAPS labels, '(Audited)' headers
H02 three year columns, current year in the middle (2025 | 2026 | 2024)? no: 2024 | 2025 | 2026, current last
H03 ₹ symbol inside every number cell and 'Previous Year' / 'Current Year' headers (previous first)
H04 half-year results: 'Six months ended' columns before 'Year ended'
H05 long P&L + BS on one dense page, 8 pt, two-line wrapped labels everywhere
H06 scan of H01 at 200 dpi, 1.0° skew (made by scan step below)
"""
import json
import os
import sys

sys.argv = [sys.argv[0]]
import gen_html as g  # noqa: E402  (also rewrites the S corpus identically)

here = os.path.dirname(os.path.abspath(__file__))
CSS2 = g.CSS.replace("font-family:Arial,Helvetica,sans-serif;font-size:10.5pt",
                     "font-family:'Times New Roman',Times,serif;font-size:9pt").replace("padding:3px 6px", "padding:1px 3px")
docs = []


def write(i, html, truth, layout, css=CSS2):
    open(os.path.join(g.OUT, f"H{i:02d}.html"), "w", encoding="utf-8").write(
        f"<!doctype html><html><head><meta charset='utf-8'><style>{css}</style></head><body>{html}</body></html>")
    docs.append({"id": f"H{i:02d}", "truth": truth, "layout": layout})


def simple(rows, cols, values, unit="crore", caps=False, cell=lambda s: s, note=False):
    h = '<table class="rule"><tr><th class="l">PARTICULARS</th>' + ("<th>Note</th>" if note else "")
    h += "".join(f"<th>{c}</th>" for c in cols) + "</tr>"
    truth = {}
    for label, key in rows:
        if key is None:
            h += f'<tr class="sec"><td class="l">{label}</td></tr>'
            continue
        truth[key] = values[0][key]
        lab = label.upper() if caps else label
        h += f'<tr><td class="l">{lab}</td>' + (f'<td class="n">{g.NOTES.get(key, "")}</td>' if note else "")
        h += "".join(f'<td class="n">{cell(g.fmt(v[key], unit))}</td>' for v in values[1:]) + "</tr>"
    return h + "</table>", truth


PLR = [(r[1], r[2]) if r[0] == "tot" else r for r in g.PL]
BSR = [(r[1], None) if r[0] == "sec" else ((r[1], r[2]) if r[0] == "tot" else r) for r in g.BS if r[1] is not None]
BSR = [r for r in BSR if r[0] != "Property, plant and equipment"]

cy, py = g.company(101)
t, tr = simple(PLR, ("31.03.2026 (Audited)", "31.03.2025 (Audited)"), (cy, cy, py), caps=True, note=True)
write(1, g.page("HIMALAYA BEVERAGES LIMITED", "STATEMENT OF PROFIT AND LOSS (₹ IN CRORE)", t), tr, "Times 9 pt, ALL-CAPS labels, (Audited) headers")

cy, p1 = g.company(102)
p2 = {k: v * 0.86 for k, v in p1.items()}
t, tr = simple(BSR, ("FY 2023-24", "FY 2024-25", "FY 2025-26"), (cy, p2, p1, cy))
write(2, g.page("Coromandel Fertilisers Ltd", "Balance sheet, three years (Rs. crore)", t), tr, "Three year columns, current year last")

cy, py = g.company(103)
t, tr = simple(PLR[:1] + PLR[6:] + [r for r in BSR if r[1] in ("NET_WORTH", "LT_DEBT", "ST_DEBT", "CASH")],
               ("Previous Year", "Current Year"), (cy, py, cy), cell=lambda s: "₹ " + s)
write(3, g.page("Aravalli Minerals Ltd", "Figures in ₹ crore", t), tr, "₹ in every cell, 'Previous Year' first, 'Current Year' second")

cy, py = g.company(104)
h1 = {k: v * 0.48 for k, v in cy.items()}
h0 = {k: v * 0.45 for k, v in py.items()}
t, tr = simple(PLR, ("Six months ended 30.09.2025", "Six months ended 30.09.2024", "Year ended 31.03.2026", "Year ended 31.03.2025"),
               (cy, h1, h0, cy, py), unit="lakh")
write(4, g.page("Satpura Tyres Ltd", "Financial results (₹ in lakhs)", t), tr, "Half-year columns before the annual columns")

cy, py = g.company(105)
css8 = CSS2.replace("font-size:9pt", "font-size:8pt")
t1, a = g.table(g.PL, ("2026", "2025"), cy, py, wrap=True)
t2, b = g.table(g.BS, ("2026", "2025"), cy, py, wrap=True)
write(5, g.page("Brahmaputra Tea Estates Ltd", "Abridged statements, ₹ crore", t1 + "<br>" + t2), {**a, **b},
      "Dense 8 pt page, P&L then balance sheet, wrapped labels", css=css8)

docs.append({"id": "H06", "truth": docs[0]["truth"], "layout": "Scan of H01 at 200 dpi, 1.0° skew", "scan_of": "H01"})
json.dump(docs, open(os.path.join(here, "truth_holdout.json"), "w"), indent=1)
print(len(docs), "held-out documents;", sum(len(d["truth"]) for d in docs), "truth values")
