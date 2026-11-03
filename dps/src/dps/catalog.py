"""Statement line items DPS serves to Core (04-api-contracts.md §6.2)."""

from __future__ import annotations

LINE_ITEMS: dict[str, str] = {
    "REVENUE": "Revenue",
    "EBITDA": "EBITDA",
    "EBIT": "EBIT",
    "DEPRECIATION": "Depreciation and amortisation",
    "INTEREST_PAID": "Interest paid",
    "PRINCIPAL_PAID": "Principal repaid",
    "EMI_AMOUNT": "EMI amount",
    "PAT": "Profit after tax",
    "TOTAL_DEBT": "Total debt",
    "ST_DEBT": "Short-term debt",
    "LT_DEBT": "Long-term debt",
    "NET_DEBT": "Net debt",
    "CASH": "Cash and equivalents",
    "NET_WORTH": "Net worth",
    "TANGIBLE_NET_WORTH": "Tangible net worth",
    "CURRENT_ASSETS": "Current assets",
    "CURRENT_LIABILITIES": "Current liabilities",
    "INVENTORY": "Inventory",
    "RECEIVABLES": "Receivables",
    "CAPEX": "Capital expenditure",
}


def catalog() -> dict:
    return {"columns": [{"key": k, "label": v, "dataType": "NUMBER", "kind": "METRIC"}
                        for k, v in LINE_ITEMS.items()]}
