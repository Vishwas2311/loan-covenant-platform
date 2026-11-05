"""Golden-set evaluation for covenant extraction (release gate G6 in 05-prd.md).

Usage:
    python evaluate.py --predictions <dir of {documentId}.json> [--labels labels] [--include-drafts]
                       [--statuses VERIFIED,SYNTHETIC]

Each prediction file has the same shape as a label file: {"documentId": ..., "covenants": [...]}.
Matching is per document by (metric, category). Reported per document and overall:
  - covenant recall    = matched labelled covenants / labelled covenants
  - covenant precision = matched predicted covenants / predicted covenants
  - threshold accuracy = matched financial covenants with the same operator and threshold / matched financial
  - page accuracy      = matched covenants whose page equals the labelled page / matched with a labelled page
Statement labels (type FINANCIAL_STATEMENT) hold {"values": {KEY: amount in INR crore}}; a predicted value
counts as correct within 0.5% (or 0.01). value accuracy = correct values / labelled values.

Which labels count is chosen by status: VERIFIED by default (checked by a credit analyst); SYNTHETIC labels
belong to documents generated from a written specification (make_synthetic.py), so their truth is known by
construction; --include-drafts adds DRAFT_UNVERIFIED labels.

Gate (initial, to be agreed): threshold accuracy >= 0.95 and covenant recall >= 0.85, and value accuracy
>= 0.95 when statement labels count.
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass, field
from pathlib import Path

GATE_THRESHOLD_ACCURACY = 0.95
GATE_RECALL = 0.85
GATE_VALUE_ACCURACY = 0.95


@dataclass
class Score:
    labelled: int = 0
    predicted: int = 0
    matched: int = 0
    financial_matched: int = 0
    threshold_correct: int = 0
    with_page: int = 0
    page_correct: int = 0
    misses: list[str] = field(default_factory=list)
    extras: list[str] = field(default_factory=list)
    wrong_thresholds: list[str] = field(default_factory=list)

    def add(self, other: Score) -> None:
        for name in ("labelled", "predicted", "matched", "financial_matched", "threshold_correct",
                     "with_page", "page_correct"):
            setattr(self, name, getattr(self, name) + getattr(other, name))

    @staticmethod
    def _ratio(a: int, b: int) -> float | None:
        return round(a / b, 4) if b else None

    def metrics(self) -> dict:
        return {
            "recall": self._ratio(self.matched, self.labelled),
            "precision": self._ratio(self.matched, self.predicted),
            "threshold_accuracy": self._ratio(self.threshold_correct, self.financial_matched),
            "page_accuracy": self._ratio(self.page_correct, self.with_page),
        }


def _key(c: dict) -> tuple[str, str]:
    return (str(c.get("metric") or "").upper(), str(c.get("category") or "FINANCIAL").upper())


def _same_threshold(a: dict, b: dict) -> bool:
    if a.get("operator") != b.get("operator"):
        return False
    x, y = a.get("threshold"), b.get("threshold")
    if x is None or y is None:
        return x is y
    return abs(float(x) - float(y)) <= 1e-9


def score_document(label: dict, prediction: dict) -> Score:
    s = Score()
    gold = {_key(c): c for c in label.get("covenants", [])}
    pred = {_key(c): c for c in prediction.get("covenants", [])}
    s.labelled, s.predicted = len(gold), len(pred)
    for k, g in gold.items():
        p = pred.get(k)
        if p is None:
            s.misses.append(k[0])
            continue
        s.matched += 1
        if k[1] == "FINANCIAL":
            s.financial_matched += 1
            if _same_threshold(g, p):
                s.threshold_correct += 1
            else:
                s.wrong_thresholds.append(f"{k[0]}: gold {g.get('operator')} {g.get('threshold')} "
                                          f"vs {p.get('operator')} {p.get('threshold')}")
        if g.get("page") is not None:
            s.with_page += 1
            if g.get("page") == p.get("page"):
                s.page_correct += 1
    s.extras = [k[0] for k in pred if k not in gold]
    return s


def score_statement(label: dict, prediction: dict) -> tuple[int, int, list[str]]:
    """(labelled values, correct values, mismatches) for one statement."""
    gold = label.get("values") or {}
    pred = prediction.get("values") or {}
    wrong = []
    for key, value in gold.items():
        got = pred.get(key)
        if got is None or abs(float(got) - float(value)) > max(0.01, abs(float(value)) * 0.005):
            wrong.append(f"{key}: gold {value} vs {got}")
    return len(gold), len(gold) - len(wrong), wrong


def evaluate(labels_dir: Path, predictions_dir: Path, include_drafts: bool = False,
             statuses: tuple[str, ...] = ("VERIFIED",)) -> dict:
    total = Score()
    per_doc = {}
    values_labelled = values_correct = 0
    counted = set(statuses) | ({"DRAFT_UNVERIFIED"} if include_drafts else set())
    for label_path in sorted(labels_dir.glob("*.json")):
        label = json.loads(label_path.read_text(encoding="utf-8"))
        if label.get("status") not in counted:
            continue
        pred_path = predictions_dir / label_path.name
        prediction = json.loads(pred_path.read_text(encoding="utf-8")) if pred_path.exists() else {"covenants": []}
        if label.get("type") == "FINANCIAL_STATEMENT":
            n, ok, wrong = score_statement(label, prediction)
            values_labelled += n
            values_correct += ok
            per_doc[label["documentId"]] = {"value_accuracy": Score._ratio(ok, n), "wrong_values": wrong}
            continue
        s = score_document(label, prediction)
        total.add(s)
        per_doc[label["documentId"]] = {**s.metrics(), "misses": s.misses, "extras": s.extras,
                                        "wrong_thresholds": s.wrong_thresholds}
    overall = {**total.metrics(), "value_accuracy": Score._ratio(values_correct, values_labelled)}
    passed = (overall["threshold_accuracy"] is not None and overall["recall"] is not None
              and overall["threshold_accuracy"] >= GATE_THRESHOLD_ACCURACY and overall["recall"] >= GATE_RECALL
              and (overall["value_accuracy"] is None or overall["value_accuracy"] >= GATE_VALUE_ACCURACY))
    return {"documents": len(per_doc), "overall": overall, "gate_passed": passed, "per_document": per_doc}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--labels", type=Path, default=Path(__file__).parent / "labels")
    ap.add_argument("--predictions", type=Path, required=True)
    ap.add_argument("--include-drafts", action="store_true")
    ap.add_argument("--statuses", default="VERIFIED", help="comma-separated label statuses to count")
    args = ap.parse_args(argv)
    report = evaluate(args.labels, args.predictions, args.include_drafts,
                      tuple(s.strip() for s in args.statuses.split(",") if s.strip()))
    print(json.dumps(report, indent=2))
    return 0 if report["gate_passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
