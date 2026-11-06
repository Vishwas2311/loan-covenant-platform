"""Find the primary statement pages (balance sheet, P&L, cash flow) in a long annual report or 10-K.

Score per page and statement type: heading near the top, statement-specific line items, amount density.
Standalone beats consolidated when both exist (Indian / Sri Lankan 'Company' columns); notes pages and
the auditor's report are pushed down. Returns the best page per type and the runner-up.
"""
from __future__ import annotations

import re

HEADINGS = {
    "BS": r"balance\s+sheets?|statements?\s+of\s+financial\s+(position|condition)",
    "PL": r"statements?\s+of\s+(standalone\s+)?(profit\s+(and|&|or)\s+loss|(consolidated\s+)?(income|operations|earnings|comprehensive\s+income))|profit\s+(and|&)\s+loss\s+account|income\s+statements?",
    "CF": r"(statements?\s+of\s+)?cash\s+flows?(\s+statements?)?",
}
LINES = {
    "BS": [r"total\s+assets", r"total\s+equity", r"total\s+liabilities", r"current\s+assets", r"inventor", r"property,?\s+plant",
           r"trade\s+(and\s+other\s+)?receivables|accounts\s+receivable", r"share\s+capital|common\s+stock|stated\s+capital", r"retained\s+earnings|other\s+equity|reserves"],
    "PL": [r"revenue|net\s+sales|turnover", r"finance\s+costs?|interest\s+expense", r"depreciation", r"profit\s+before\s+tax|income\s+before\s+income\s+taxes|earnings\s+before",
           r"tax\s+expense|income\s+tax", r"profit\s+for\s+the\s+(year|period)|net\s+(income|earnings|profit)", r"earnings\s+per\s+share", r"cost\s+of\s+(sales|goods|materials)"],
    "CF": [r"operating\s+activities", r"investing\s+activities", r"financing\s+activities", r"purchase\s+of\s+property|capital\s+expenditure",
           r"cash\s+and\s+cash\s+equivalents\s+at\s+(the\s+)?(end|beginning)"],
}
AMOUNT = re.compile(r"\(?\d{1,3}(?:,\d{2,3})+(?:\.\d+)?\)?|\b\d+\.\d{1,2}\b")
NOTES = re.compile(r"notes?\s+(forming\s+part|to\s+the\s+(standalone\s+|consolidated\s+)?financial\s+statements)", re.I)
AUDIT = re.compile(r"independent\s+auditor|auditor'?s\s+report|key\s+audit\s+matters", re.I)


def page_scores(text: str, kind: str) -> float:
    t = text.lower()
    top = t[:700]
    score = 0.0
    if re.search(HEADINGS[kind], top):
        score += 6
    elif re.search(HEADINGS[kind], t):
        score += 1
    score += sum(1.5 for p in LINES[kind] if re.search(p, t))
    n = len(AMOUNT.findall(text))
    score += min(n, 120) / 20
    if NOTES.search(text[:400]):
        score -= 4
    if AUDIT.search(text[:900]):
        score -= 5
    if kind == "BS" and re.search(r"profit\s+for\s+the\s+year|operating\s+activities", t):
        score -= 2
    if kind == "PL" and re.search(r"total\s+assets", t):
        score -= 2
    if kind == "CF" and not re.search(r"operating\s+activities", t):
        score -= 6
    return score


def find(texts: list[str]) -> dict:
    out = {}
    for kind in ("BS", "PL", "CF"):
        scored = []
        for i, text in enumerate(texts):
            s = page_scores(text, kind)
            if s <= 4:
                continue
            head = text[:600].lower()
            standalone = "standalone" in head or ("company" in head and "group" not in head)
            consolidated = "consolidated" in head
            scored.append((s + (2 if standalone else 0) - (1 if consolidated and not standalone else 0), i + 1, consolidated))
        scored.sort(reverse=True)
        out[kind] = [p for _, p, _ in scored[:2]]
    return out
