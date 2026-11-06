"""US answer keys from SEC XBRL company facts: the figure each company tagged for the 10-K's fiscal year.

Values are put in DPS's output space: amount in the statement's printed unit (millions or thousands, read
from the balance-sheet heading) times DPS's factor for that unit, so a reader that misreads the unit is
scored wrong. Keys with several acceptable concepts (total equity with or without non-controlling
interests) keep every candidate value.
"""
import json
import re
import sys

from pypdf import PdfReader

TO_CRORE = {"million": 0.1, "thousand": 0.0001}
CONCEPTS = {
    "REVENUE": ["Revenues", "RevenueFromContractWithCustomerExcludingAssessedTax", "SalesRevenueNet",
                "RevenueFromContractWithCustomerIncludingAssessedTax"],
    "PAT": ["NetIncomeLoss", "ProfitLoss"],
    "INTEREST_PAID": ["InterestExpense", "InterestExpenseNonoperating", "InterestExpenseDebt"],
    "PBT": ["IncomeLossFromContinuingOperationsBeforeIncomeTaxesExtraordinaryItemsNoncontrollingInterest",
            "IncomeLossFromContinuingOperationsBeforeIncomeTaxesMinorityInterestAndIncomeLossFromEquityMethodInvestments"],
    "TAX": ["IncomeTaxExpenseBenefit"],
    "CASH": ["CashAndCashEquivalentsAtCarryingValue"],
    "INVENTORY": ["InventoryNet"],
    "RECEIVABLES": ["AccountsReceivableNetCurrent"],
    "CURRENT_ASSETS": ["AssetsCurrent"],
    "TOTAL_ASSETS": ["Assets"],
    "CURRENT_LIABILITIES": ["LiabilitiesCurrent"],
    "NET_WORTH": ["StockholdersEquity", "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest"],
    "LT_DEBT": ["LongTermDebtNoncurrent"],
    "TRADE_PAYABLES": ["AccountsPayableCurrent"],
    "CFO": ["NetCashProvidedByUsedInOperatingActivities"],
    "CFI": ["NetCashProvidedByUsedInInvestingActivities"],
    "CFF": ["NetCashProvidedByUsedInFinancingActivities"],
    "CAPEX": ["PaymentsToAcquirePropertyPlantAndEquipment"],
}
INSTANT = {"CASH", "INVENTORY", "RECEIVABLES", "CURRENT_ASSETS", "TOTAL_ASSETS", "CURRENT_LIABILITIES", "NET_WORTH",
           "LT_DEBT", "TRADE_PAYABLES"}


def main(us_dir: str, stmt_dir: str, out: str) -> None:
    index = json.load(open(f"{us_dir}/index.json"))
    pages = json.load(open("truth_pages.json"))
    truth = {}
    for doc, meta in index.items():
        if doc not in pages:
            continue
        head = (PdfReader(f"{stmt_dir}/{doc}.pdf").pages[pages[doc]["BS"] - 1].extract_text() or "")[:700].lower()
        unit = "thousand" if re.search(r"in\s+thousands|\(thousands|thousands\s+of", head) else "million"
        facts = json.load(open(f"{us_dir}/{doc}.facts.json"))["facts"].get("us-gaap", {})
        acc, end = meta["accession"], meta["report_date"]
        vals = {}
        for key, names in CONCEPTS.items():
            cands = []
            for name in names:
                for u in facts.get(name, {}).get("units", {}).get("USD", []):
                    if u.get("accn") != acc or u.get("end") != end:
                        continue
                    if key not in INSTANT and u.get("start") and int(end[:4]) - int(u["start"][:4]) > 1:
                        continue
                    if key not in INSTANT and u.get("fp") not in ("FY", None):
                        continue
                    v = u["val"] / (1e6 if unit == "million" else 1e3) * TO_CRORE[unit]
                    if key == "CAPEX":
                        v = abs(v)
                    cands.append(round(v, 6))
            if cands:
                vals[key] = sorted(set(cands))
        truth[doc] = {"unit": unit, "values": vals}
        print(doc, unit, len(vals), "keys")
    json.dump(truth, open(out, "w"), indent=1)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3])
