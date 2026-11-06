"""Benchmark corpus: 18 financial statements in layouts seen in real Indian filings and bank MIS packs.

Writes html/<id>.html (printed to PDF by print_pdf.cjs) and truth.json: for each document, the catalog
keys it prints and their correct value in INR crore (current period, standalone, annual).
"""
import json
import os
import random

OUT = os.path.join(os.path.dirname(__file__), "html")
os.makedirs(OUT, exist_ok=True)

CSS = """
body{font-family:Arial,Helvetica,sans-serif;font-size:10.5pt;margin:28px 34px;color:#111}
h1{font-size:14pt;margin:0 0 2px} h2{font-size:11.5pt;margin:14px 0 4px} .sub{font-size:9pt;color:#333;margin-bottom:8px}
table{border-collapse:collapse;width:100%} td,th{padding:3px 6px;vertical-align:top}
th{font-weight:bold;text-align:right} th.l,td.l{text-align:left} td.n{text-align:right;white-space:nowrap}
.grid td,.grid th{border:1px solid #444} .rule th{border-bottom:1px solid #000} .tot td{font-weight:bold;border-top:1px solid #000}
.sec td{font-weight:bold;padding-top:8px} .ind{padding-left:22px} .ind2{padding-left:40px}
.lead{display:flex;justify-content:space-between;border-bottom:1px dotted #888;margin:3px 0}
.page{page-break-after:always} p{margin:4px 0;line-height:1.35}
"""


def company(seed):
    """A consistent set of annual figures in INR crore (current year CY and previous year PY)."""
    r = random.Random(seed)
    rev = r.uniform(800, 9000)
    def year(f):
        revenue = rev * f
        other_inc = revenue * r.uniform(0.005, 0.02)
        material = revenue * r.uniform(0.45, 0.6)
        employee = revenue * r.uniform(0.05, 0.1)
        other_exp = revenue * r.uniform(0.08, 0.14)
        ebitda = revenue - material - employee - other_exp
        dep = revenue * r.uniform(0.03, 0.06)
        ebit = ebitda - dep
        interest = revenue * r.uniform(0.015, 0.04)
        pbt = ebit + other_inc - interest
        tax = pbt * 0.252
        pat = pbt - tax
        st = revenue * r.uniform(0.05, 0.12)
        lt = revenue * r.uniform(0.15, 0.35)
        cash = revenue * r.uniform(0.02, 0.08)
        inv = revenue * r.uniform(0.08, 0.15)
        recv = revenue * r.uniform(0.08, 0.16)
        ca = inv + recv + cash + revenue * r.uniform(0.02, 0.05)
        payables = revenue * r.uniform(0.07, 0.12)
        cl = st + payables + revenue * r.uniform(0.02, 0.05)
        share_cap = round(r.choice([50, 100, 250, 500]) * 1.0, 2)
        reserves = revenue * r.uniform(0.25, 0.6)
        nw = share_cap + reserves
        ta = nw + lt + cl + revenue * r.uniform(0.01, 0.03)
        cfo = pat + dep + revenue * r.uniform(-0.02, 0.03)
        capex = revenue * r.uniform(0.04, 0.09)
        cfi = -(capex + revenue * r.uniform(0.0, 0.01))
        cff = -(interest + revenue * r.uniform(0.0, 0.03))
        v = dict(REVENUE=revenue, OTHER_INCOME=other_inc, TOTAL_INCOME=revenue + other_inc, MATERIAL_COST=material,
                 EMPLOYEE_COST=employee, OTHER_EXPENSES=other_exp, EBITDA=ebitda, DEPRECIATION=dep, EBIT=ebit,
                 INTEREST_PAID=interest, PBT=pbt, TAX=tax, PAT=pat, ST_DEBT=st, LT_DEBT=lt, TOTAL_DEBT=st + lt,
                 CASH=cash, INVENTORY=inv, RECEIVABLES=recv, CURRENT_ASSETS=ca, TRADE_PAYABLES=payables,
                 CURRENT_LIABILITIES=cl, SHARE_CAPITAL=share_cap, RESERVES=reserves, NET_WORTH=nw, TOTAL_ASSETS=ta,
                 CFO=cfo, CAPEX=capex, CFI=cfi, CFF=cff)
        return {k: round(x, 2) for k, x in v.items()}
    return year(1.0), year(r.uniform(0.82, 0.95))


SCALE = {"crore": 1, "lakh": 100, "million": 10}  # multiply crore by this to print


def fmt(x, unit="crore", indian=False, brackets=True, dec=2, prefix="", suffix=""):
    v = x * SCALE[unit]
    neg = v < 0
    s = f"{abs(v):,.{dec}f}"
    if indian:
        whole, _, frac = s.replace(",", "").partition(".")
        head, tail = whole[:-3], whole[-3:]
        parts = []
        while len(head) > 2:
            parts.insert(0, head[-2:]); head = head[:-2]
        if head:
            parts.insert(0, head)
        s = ",".join(parts + [tail]) + ("." + frac if frac else "")
        if not parts:
            s = tail + ("." + frac if frac else "")
    s = prefix + s + suffix
    if neg:
        s = f"({s})" if brackets else "-" + s
    return s


BS = [("sec", "ASSETS"), ("sec", "Non-current assets"), ("Property, plant and equipment", None),
      ("sec", "Current assets"), ("Inventories", "INVENTORY"), ("Trade receivables", "RECEIVABLES"),
      ("Cash and cash equivalents", "CASH"), ("tot", "Total current assets", "CURRENT_ASSETS"),
      ("tot", "Total assets", "TOTAL_ASSETS"), ("sec", "EQUITY AND LIABILITIES"),
      ("Equity share capital", "SHARE_CAPITAL"), ("Other equity", "RESERVES"), ("tot", "Total equity", "NET_WORTH"),
      ("sec", "Non-current liabilities"), ("Long-term borrowings", "LT_DEBT"), ("sec", "Current liabilities"),
      ("Short-term borrowings", "ST_DEBT"), ("Trade payables", "TRADE_PAYABLES"),
      ("tot", "Total current liabilities", "CURRENT_LIABILITIES")]
PL = [("Revenue from operations", "REVENUE"), ("Other income", "OTHER_INCOME"), ("tot", "Total income", "TOTAL_INCOME"),
      ("Cost of materials consumed", "MATERIAL_COST"), ("Employee benefits expense", "EMPLOYEE_COST"),
      ("Other expenses", "OTHER_EXPENSES"), ("tot", "EBITDA", "EBITDA"), ("Depreciation and amortisation expense", "DEPRECIATION"),
      ("Finance costs", "INTEREST_PAID"), ("tot", "Profit before tax", "PBT"), ("Tax expense", "TAX"),
      ("tot", "Profit after tax", "PAT")]
CF = [("Net cash generated from operating activities", "CFO"),
      ("Purchase of property, plant and equipment", "CAPEX_NEG"),
      ("Net cash used in investing activities", "CFI"), ("Net cash used in financing activities", "CFF")]
NOTES = {"INVENTORY": 8, "RECEIVABLES": 9, "CASH": 10, "SHARE_CAPITAL": 12, "RESERVES": 13, "LT_DEBT": 14,
         "ST_DEBT": 17, "TRADE_PAYABLES": 18, "REVENUE": 21, "OTHER_INCOME": 22, "MATERIAL_COST": 23,
         "EMPLOYEE_COST": 24, "OTHER_EXPENSES": 27, "DEPRECIATION": 26, "INTEREST_PAID": 25, "TAX": 28}


def val(v, key):
    if key == "CAPEX_NEG":
        return -v["CAPEX"]
    return v.get(key)


def table(rows, cols, cy, py=None, unit="crore", note=False, cls="rule", indian=False, brackets=True,
          extra_cols=None, order="cy_first", wrap=False):
    """cols: header cells after Particulars (and Note). extra_cols: callables giving more values per key."""
    h = '<table class="%s"><tr><th class="l">Particulars</th>' % cls
    if note:
        h += "<th>Note</th>"
    h += "".join(f"<th>{c}</th>" for c in cols) + "</tr>"
    truth = {}
    for row in rows:
        if row[0] == "sec":
            h += f'<tr class="sec"><td class="l" colspan="{2 + len(cols)}">{row[1]}</td></tr>'
            continue
        tot = row[0] == "tot"
        label, key = (row[1], row[2]) if tot else row
        cells = []
        if key is None:
            vals = [cy["REVENUE"] * 0.42, (py or cy)["REVENUE"] * 0.40]
        else:
            vals = [val(cy, key), val(py, key) if py else None]
            if key != "CAPEX_NEG":
                truth[key] = cy[key]
            else:
                truth["CAPEX"] = cy["CAPEX"]
        if extra_cols:
            vals = [f(key) for f in extra_cols]
        elif order == "py_first":
            vals = vals[::-1]
        for x in vals[:len(cols)]:
            cells.append("" if x is None else fmt(x, unit, indian, brackets))
        shown = label
        if wrap and len(label) > 30:
            cut = label.rfind(" ", 0, 28)
            shown = label[:cut] + "<br>" + label[cut + 1:]
        h += f'<tr class="{"tot" if tot else ""}"><td class="l{"" if tot else " ind"}">{shown}</td>'
        if note:
            h += f'<td class="n">{NOTES.get(key, "")}</td>'
        h += "".join(f'<td class="n">{c}</td>' for c in cells) + "</tr>"
    return h + "</table>", truth


def page(title, sub, body):
    return f'<div class="page"><h1>{title}</h1><div class="sub">{sub}</div>{body}</div>'


def doc(i, html, truth, note):
    open(os.path.join(OUT, f"S{i:02d}.html"), "w", encoding="utf-8").write(
        f"<!doctype html><html><head><meta charset='utf-8'><style>{CSS}</style></head><body>{html}</body></html>")
    return {"id": f"S{i:02d}", "truth": truth, "layout": note}


docs = []
YR = ("31 March 2026", "31 March 2025")

# S01 MIS one page, dot leaders, crore
cy, py = company(1)
keys = ["REVENUE", "EBITDA", "DEPRECIATION", "INTEREST_PAID", "PAT", "ST_DEBT", "LT_DEBT", "CASH", "NET_WORTH",
        "CURRENT_ASSETS", "CURRENT_LIABILITIES"]
lab = {"REVENUE": "Revenue", "EBITDA": "EBITDA", "DEPRECIATION": "Depreciation", "INTEREST_PAID": "Interest expense",
       "PAT": "Net profit", "ST_DEBT": "Short term debt", "LT_DEBT": "Long term debt", "CASH": "Cash and bank balances",
       "NET_WORTH": "Net worth", "CURRENT_ASSETS": "Current assets", "CURRENT_LIABILITIES": "Current liabilities"}
body = "".join(f'<div class="lead"><span>{lab[k]}</span><span>{fmt(cy[k])}</span></div>' for k in keys)
docs.append(doc(1, page("Shree Polymers Ltd: quarterly MIS", "FY 2025-26 key figures. Amounts in ₹ crore", body),
                {k: cy[k] for k in keys}, "MIS one page, dot leaders, one value per line"))

# S02 Balance sheet, Schedule III, note column, grid, crore
cy, py = company(2)
t, tr = table(BS, YR, cy, py, note=True, cls="grid")
docs.append(doc(2, page("Bharat Castings Ltd", "Standalone Balance Sheet as at 31 March 2026 (₹ in crore)", t), tr,
                "Balance sheet, note column, two years, grid"))

# S03 P&L lakhs, Indian grouping, note column
cy, py = company(3)
t, tr = table(PL, ("Year ended 31 March 2026", "Year ended 31 March 2025"), cy, py, unit="lakh", note=True, indian=True)
docs.append(doc(3, page("Kaveri Agro Foods Ltd", "Statement of Profit and Loss for the year ended 31 March 2026 (₹ in lakhs)", t),
                tr, "P&L in lakhs, Indian digit grouping, note column"))

# S04 Cash flow, brackets, wrapped labels
cy, py = company(4)
t, tr = table(CF + [("Cash and cash equivalents at the end of the year", "CASH")], YR, cy, py, wrap=True)
docs.append(doc(4, page("Deccan Textiles Ltd", "Cash Flow Statement for the year ended 31 March 2026 (Amounts in ₹ crore)", t),
                tr, "Cash flow, negatives in brackets, labels wrapped over two lines"))

# S05 Full annual pack 3 pages, million, note column
cy, py = company(5)
t1, a = table(BS, YR, cy, py, unit="million", note=True)
t2, b = table(PL, YR, cy, py, unit="million", note=True)
t3, c = table(CF, YR, cy, py, unit="million")
html = (page("Orion Pharma Ltd", "Balance Sheet as at 31 March 2026 (₹ in million)", t1)
        + page("Orion Pharma Ltd", "Statement of Profit and Loss (₹ in million)", t2)
        + page("Orion Pharma Ltd", "Statement of Cash Flows (₹ in million)", t3))
docs.append(doc(5, html, {**a, **b, **c}, "Three statements on three pages, ₹ million, note column"))

# S06 Previous year printed first
cy, py = company(6)
t, tr = table(PL + [("sec", "Balance sheet extract")] + BS[10:], ("FY 2024-25", "FY 2025-26"), cy, py, order="py_first")
docs.append(doc(6, page("Malabar Spices Ltd: lender pack", "Figures in ₹ crore. Current year in the second column", t), tr,
                "Previous year in the first column, current year second"))

# S07 SEBI quarterly results: 3 quarters + 2 years, lakhs; truth = year ended 31.03.2026
cy, py = company(7)
q = lambda f: (lambda k: None if k is None else (val(cy, k) * f))
cols = ("Quarter ended 31.03.2026", "Quarter ended 31.12.2025", "Quarter ended 31.03.2025", "Year ended 31.03.2026",
        "Year ended 31.03.2025")
t, tr = table(PL, cols, cy, py, unit="lakh", extra_cols=[q(0.27), q(0.25), q(0.24), q(1.0), lambda k: None if k is None else val(py, k)])
docs.append(doc(7, page("Narmada Cement Ltd", "Audited financial results for the quarter and year ended 31 March 2026 (₹ in lakhs)", t),
                tr, "SEBI results: three quarter columns before the annual column"))

# S08 Standalone and consolidated side by side; truth = standalone current
cy, py = company(8)
cons = {k: v * 1.18 for k, v in cy.items()}
consp = {k: v * 1.15 for k, v in py.items()}
cols = ("Standalone 2026", "Standalone 2025", "Consolidated 2026", "Consolidated 2025")
t, tr = table(PL[:1] + PL[6:] + BS[10:13] + BS[14:], cols, cy, py,
              extra_cols=[lambda k: val(cy, k), lambda k: val(py, k), lambda k: val(cons, k), lambda k: val(consp, k)])
docs.append(doc(8, page("Garuda Logistics Ltd", "Key financials (₹ crore)", t), tr,
                "Standalone and consolidated, four number columns"))

# S09 Ind AS: 'Borrowings' under non-current / current headings, no 'long-term' word
cy, py = company(9)
rows = [("sec", "Non-current liabilities"), ("sec", "Financial liabilities"), ("(i) Borrowings", "LT_DEBT"),
        ("sec", "Current liabilities"), ("sec", "Financial liabilities"), ("(i) Borrowings", "ST_DEBT"),
        ("(ii) Trade payables", "TRADE_PAYABLES"), ("tot", "Total current liabilities", "CURRENT_LIABILITIES"),
        ("sec", "Equity"), ("Equity share capital", "SHARE_CAPITAL"), ("Other equity", "RESERVES"),
        ("tot", "Total equity", "NET_WORTH"), ("Cash and cash equivalents", "CASH")]
t, tr = table(rows, YR, cy, py, note=True)
docs.append(doc(9, page("Sahyadri Power Ltd", "Balance Sheet extract (₹ in crore), Ind AS Schedule III", t), tr,
                "Ind AS: both debts labelled just 'Borrowings' under section headings"))

# S10 Inline currency and unit on every value
cy, py = company(10)
keys = ["REVENUE", "EBITDA", "INTEREST_PAID", "PAT", "TOTAL_DEBT", "CASH", "NET_WORTH"]
lab = {"REVENUE": "Total revenue", "EBITDA": "EBITDA", "INTEREST_PAID": "Finance cost", "PAT": "Profit after tax",
       "TOTAL_DEBT": "Total borrowings", "CASH": "Cash and cash equivalents", "NET_WORTH": "Net worth"}
body = "".join(f'<div class="lead"><span>{lab[k]}</span><span>Rs. {fmt(cy[k], dec=1)} Cr</span></div>' for k in keys)
docs.append(doc(10, page("Vindhya Paper Mills Ltd", "Summary for FY 2025-26", body), {k: cy[k] for k in keys},
                "'Rs. 1,234.5 Cr' written on every value, no unit header"))

# S11 Ratio and growth lines that must be ignored; dash for nil
cy, py = company(11)
rows = [("Revenue from operations", "REVENUE"), ("Revenue growth %", None), ("EBITDA", "EBITDA"), ("EBITDA margin %", None),
        ("Finance costs", "INTEREST_PAID"), ("Interest cover ratio", None), ("Profit after tax", "PAT"),
        ("Long-term borrowings", "LT_DEBT"), ("Short-term borrowings", "ST_DEBT"), ("Debt to equity ratio", None),
        ("Total equity", "NET_WORTH")]
h = '<table class="rule"><tr><th class="l">Particulars</th><th>FY26</th><th>FY25</th></tr>'
tr = {}
for label, key in rows:
    if key:
        tr[key] = cy[key]
        a, b = fmt(cy[key]), fmt(py[key])
    elif "growth" in label:
        a, b = "12.4", "9.8"
    elif "margin" in label:
        a, b = "18.6", "17.9"
    elif "cover" in label:
        a, b = "4.1", "3.8"
    else:
        a, b = "0.62", "0.71"
    h += f'<tr><td class="l">{label}</td><td class="n">{a}</td><td class="n">{b}</td></tr>'
h += '<tr><td class="l">Preference share capital</td><td class="n">-</td><td class="n">-</td></tr></table>'
docs.append(doc(11, page("Konkan Shipping Ltd", "Financial highlights (₹ crore)", h), tr,
                "Ratios, margins and growth % mixed with amounts; dashes for nil"))

# S12 Statement followed by notes with sub-lines
cy, py = company(12)
t, tr = table(BS, YR, cy, py, note=True)
notes = (f'<h2>Note 10: Cash and cash equivalents</h2><table class="rule"><tr><th class="l">Particulars</th><th>{YR[0]}</th><th>{YR[1]}</th></tr>'
         f'<tr><td class="l">Cash on hand</td><td class="n">{fmt(0.42)}</td><td class="n">{fmt(0.38)}</td></tr>'
         f'<tr><td class="l">Balances with banks</td><td class="n">{fmt(cy["CASH"] - 0.42)}</td><td class="n">{fmt(py["CASH"] - 0.38)}</td></tr></table>'
         f'<h2>Note 17: Short-term borrowings</h2><table class="rule"><tr><th class="l">Particulars</th><th>{YR[0]}</th><th>{YR[1]}</th></tr>'
         f'<tr><td class="l">Working capital loans from banks</td><td class="n">{fmt(cy["ST_DEBT"] * 0.7)}</td><td class="n">{fmt(py["ST_DEBT"] * 0.7)}</td></tr>'
         f'<tr><td class="l">Current maturities of long-term borrowings</td><td class="n">{fmt(cy["ST_DEBT"] * 0.3)}</td><td class="n">{fmt(py["ST_DEBT"] * 0.3)}</td></tr></table>')
docs.append(doc(12, page("Tapi Steel Ltd", "Balance Sheet as at 31 March 2026 (₹ in crore)", t) + page("Tapi Steel Ltd", "Notes to the financial statements (₹ in crore)", notes),
                tr, "Balance sheet followed by notes that repeat the labels with other numbers"))

# S13 / S14 are scans of S02 / S03 (made by scan.py)
docs.append({"id": "S13", "truth": docs[1]["truth"], "layout": "Scan of S02 at 150 dpi, slight skew and noise", "scan_of": "S02"})
docs.append({"id": "S14", "truth": docs[2]["truth"], "layout": "Poor scan of S03 at 100 dpi, blur, skew 1.5°", "scan_of": "S03"})

# S15 Two statements side by side on a landscape page
cy, py = company(15)
t1, a = table(BS[3:13], ("2026", "2025"), cy, py)
t2, b = table(PL, ("2026", "2025"), cy, py)
html = (f'<div class="page"><h1>Ganga Ceramics Ltd</h1><div class="sub">Abridged financial statements, ₹ in crore</div>'
        f'<div style="display:flex;gap:28px"><div style="flex:1"><h2>Balance sheet</h2>{t1}</div>'
        f'<div style="flex:1"><h2>Profit and loss</h2>{t2}</div></div></div>')
docs.append(doc(15, html, {**a, **b}, "Balance sheet and P&L side by side on one landscape page"))

# S16 Minus signs, no thousands separators, Rs. in header
cy, py = company(16)
rows = CF + [("Profit after tax", "PAT"), ("Total equity", "NET_WORTH"), ("Total borrowings", "TOTAL_DEBT")]
h = '<table class="rule"><tr><th class="l">Particulars</th><th>FY 2025-26</th><th>FY 2024-25</th></tr>'
tr = {}
for label, key in rows:
    a, b = val(cy, key), val(py, key)
    tr["CAPEX" if key == "CAPEX_NEG" else key] = cy["CAPEX"] if key == "CAPEX_NEG" else cy[key]
    h += f'<tr><td class="l">{label}</td><td class="n">{a:.2f}</td><td class="n">{b:.2f}</td></tr>'
docs.append(doc(16, page("Ajanta Auto Components Ltd", "Rs. in crore", h + "</table>"), tr,
                "Minus signs, no thousands separators"))

# S17 Directors' report narrative before the statements (first-occurrence trap)
cy, py = company(17)
narr = (f"<p>Dear Members, your Directors present the annual report. During the year revenue from operations grew 12% to "
        f"₹ {fmt(cy['REVENUE'], dec=0)} crore against ₹ {fmt(py['REVENUE'], dec=0)} crore last year. EBITDA was 14.2% of revenue. "
        f"Finance costs rose by 8%. Total equity stood at ₹ {fmt(cy['NET_WORTH'] * 1.01, dec=0)} crore after the bonus issue.</p>" * 3)
t1, a = table(BS, YR, cy, py, note=True)
t2, b = table(PL, YR, cy, py, note=True)
html = (page("Sutlej Chemicals Ltd: Annual Report 2025-26", "Directors' Report", narr)
        + page("Sutlej Chemicals Ltd", "Balance Sheet as at 31 March 2026 (₹ in crore)", t1)
        + page("Sutlej Chemicals Ltd", "Statement of Profit and Loss (₹ in crore)", t2))
docs.append(doc(17, html, {**a, **b}, "Narrative pages before the statements quote other numbers"))

# S18 Spreadsheet-style export: merged header 'Year ended 31 March' over the years
cy, py = company(18)
h = ('<table class="grid"><tr><th class="l" rowspan="2">Particulars</th><th colspan="2" style="text-align:center">'
     'Year ended 31 March (₹ crore)</th></tr><tr><th>2026</th><th>2025</th></tr>')
tr = {}
for row in PL + BS[10:]:
    if row[0] == "sec":
        continue
    label, key = (row[1], row[2]) if row[0] == "tot" else row
    tr[key] = cy[key]
    h += f'<tr><td class="l">{label}</td><td class="n">{fmt(cy[key])}</td><td class="n">{fmt(py[key])}</td></tr>'
docs.append(doc(18, page("Indus Retail Ltd", "Extract from the lender spreadsheet", h + "</table>"), tr,
                "Merged two-row header, years under 'Year ended 31 March'"))

json.dump(docs, open(os.path.join(os.path.dirname(__file__), "truth.json"), "w"), indent=1)
print(len(docs), "documents;", sum(len(d["truth"]) for d in docs), "truth values")
