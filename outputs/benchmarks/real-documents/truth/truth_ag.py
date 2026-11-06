"""Hand-labelled maintenance covenants (family, direction, threshold, page) for the 50 agreements.

Only covenants the borrower must keep meeting count: pricing grids, incurrence tests, excess-cash-flow
sweeps, release or extension conditions and insurance amounts are not covenants. Families: LEVERAGE (any
debt / EBITDA, gross, net, senior, first lien), DSCR, ICR, FCCR, DEBT_TO_NW (debt or liabilities to net
worth / tangible net worth / equity), CURRENT_RATIO, MIN_NW (net worth or tangible net worth floor),
LIQUIDITY, MIN_EBITDA, LTV. Direction: min or max. Pages are where the clause is.
"""
import json

L = {
    "AG01": [("LEVERAGE", "max", 2.0, 11), ("FCCR", "min", 1.15, 12), ("MIN_EBITDA", "min", 9500000, 12)],
    "AG02": [("DEBT_TO_NW", "max", 3.50, 60), ("FCCR", "min", 1.50, 61)],
    "AG03": [("DSCR", "min", 1.20, 22), ("LEVERAGE", "max", 3.0, 22), ("LEVERAGE", "max", 5.0, 22)],
    "AG04": [("DSCR", "min", 1.25, 76)],
    "AG05": [("ICR", "min", 1.25, 2), ("DSCR", "min", 1.20, 2), ("DEBT_TO_NW", "max", 1.50, 2), ("LIQUIDITY", "min", 7500000, 2)],
    "AG06": [], "AG08": [], "AG10": [], "AG17": [], "AG21": [], "AG29": [], "AG30": [], "AG39": [],
    "AG47": [], "AG48": [], "AG50": [], "AG52": [], "AG54": [],
    "AG07": [("DSCR", "min", 1.25, 10), ("LTV", "max", 75, 10)],
    "AG09": [("LTV", "max", 65, 26), ("DSCR", "min", 1.30, 28)],
    "AG11": [("DSCR", "min", 1.40, 11), ("MIN_NW", "min", 25000000, 12)],
    "AG12": [("DSCR", "min", 1.25, 15), ("DEBT_TO_NW", "max", 3.0, 15)],
    "AG13": [("LEVERAGE", "max", 3.00, 14), ("DSCR", "min", 2.00, 14)],
    "AG14": [("DSCR", "min", 1.35, 5)],
    "AG15": [("DSCR", "min", 1.25, 18)],
    "AG16": [("DEBT_TO_NW", "max", 9.0, 24), ("DSCR", "min", 1.20, 25), ("CURRENT_RATIO", "min", 1.0, 25)],
    "AG18": [("LEVERAGE", "max", 3.00, 7), ("DSCR", "min", 1.15, 7)],
    "AG19": [("DSCR", "min", 1.35, 19)],
    "AG20": [("LTV", "max", 70, 7), ("DEBT_TO_NW", "max", 9.1, 13), ("DSCR", "min", 1.2, 13)],
    "AG22": [("DSCR", "min", 2.00, 12)],
    "AG23": [("DSCR", "min", 1.25, 5)],
    "AG24": [("LTV", "max", 67.70, 7)],
    "AG25": [("DEBT_TO_NW", "max", 9.0, 15), ("DSCR", "min", 1.25, 15), ("CURRENT_RATIO", "min", 1.0, 15)],
    "AG26": [("LEVERAGE", "max", 5.00, 133)],
    "AG27": [("LEVERAGE", "max", 4.75, 106)],
    "AG28": [("LEVERAGE", "max", 3.50, 113)],
    "AG31": [("LEVERAGE", "max", 3.75, 90), ("ICR", "min", 3.00, 91)],
    "AG32": [("MIN_NW", "min", 10000000, 2), ("ICR", "min", 1.5, 3)],
    "AG33": [("DSCR", "min", 1.20, 3)],
    "AG35": [("MIN_NW", "min", 85000000, 7), ("DSCR", "min", 1.25, 7)],
    "AG36": [("MIN_NW", "min", 105000000, 37)],
    "AG38": [("DEBT_TO_NW", "max", 3.75, 41), ("ICR", "min", 2.25, 41), ("DSCR", "min", 1.05, 41)],
    "AG41": [("LEVERAGE", "max", 5.50, 172), ("LEVERAGE", "max", 3.50, 172), ("ICR", "min", 2.00, 172)],
    "AG42": [("LEVERAGE", "max", 7.3, 160)],
    "AG44": [("LEVERAGE", "max", 9.25, 84), ("LEVERAGE", "max", 7.25, 84)],
}
EXCLUDED = {"AG34": "scanned; covenant thresholds not legible enough to label", "AG37": "covenant clauses not located",
            "AG40": "thresholds inside amendment mark-up", "AG43": "threshold inside amendment mark-up",
            "AG45": "security cover level is in a schedule not in the PDF"}
json.dump({"covenants": {k: [dict(family=f, dir=d, threshold=t, page=p) for f, d, t, p in v] for k, v in L.items()},
           "excluded": EXCLUDED}, open("truth_ag.json", "w"), indent=1)
print(len(L), "labelled agreements,", sum(len(v) for v in L.values()), "covenants,", len(EXCLUDED), "excluded")
