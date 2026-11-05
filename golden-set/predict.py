"""Runs DPS's own parser and extractors over the golden-set documents and writes one prediction per document.

    python golden-set/predict.py --out <dir>      # needs the DPS package (dps/.venv or `pip install -e dps`)
    python golden-set/evaluate.py --predictions <dir> --statuses SYNTHETIC

Agreements: the terms Flow A extracts ({"covenants": [...]} like a label). Statements: the line items Flow B
reads, in INR crore ({"values": {...}}). Only documents with a label are predicted.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent


def predict_agreement(data: bytes) -> dict:
    from dps.extraction.agreement import extract_terms
    from dps.parsing import ParserEngine

    terms = extract_terms(ParserEngine().parse(data))
    return {"covenants": [{"metric": t.metric, "category": t.category, "operator": t.operator,
                           "threshold": t.threshold, "unit": t.unit, "frequency": t.frequency, "page": t.page,
                           "quote": t.quote} for t in terms]}


def predict_statement(name: str, data: bytes) -> dict:
    from dps.extraction import statement as st

    if name.endswith(".csv"):
        rows, header = st.rows_from_csv(data), st.header_from_csv(data)
    elif name.endswith(".xls"):
        rows, header = st.rows_from_xls(data), st.header_from_xls(data)
    else:
        rows, header = st.rows_from_xlsx(data), st.header_from_xlsx(data)
    result = st.extract_values([tuple(r) for r in rows], header)
    return {"values": {v.key: v.value for v in result.values}, "issues": result.issues}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--labels", type=Path, default=HERE / "labels")
    ap.add_argument("--documents", type=Path, default=HERE / "documents")
    args = ap.parse_args(argv)
    args.out.mkdir(parents=True, exist_ok=True)
    for label_path in sorted(args.labels.glob("*.json")):
        label = json.loads(label_path.read_text(encoding="utf-8"))
        source = args.documents / label["sourceFile"]
        if not source.exists():  # the first ten keep their original file name in the label
            source = args.documents / f"{label['documentId']}.pdf"
        if not source.exists():
            continue
        data = source.read_bytes()
        prediction = (predict_statement(source.name, data) if label.get("type") == "FINANCIAL_STATEMENT"
                      else predict_agreement(data))
        (args.out / label_path.name).write_text(json.dumps({"documentId": label["documentId"], **prediction},
                                                           indent=1), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
