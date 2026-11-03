"""Golden-set evaluation for covenant extraction (release gate G6 in 05-prd.md).

Usage:
    python evaluate.py --predictions <dir of {documentId}.json> [--labels labels] [--include-drafts]

Each prediction file has the same shape as a label file: {"documentId": ..., "covenants": [...]}.
Matching is per document by (metric, category). Reported per document and overall:
  - covenant recall    = matched labelled covenants / labelled covenants
  - covenant precision = matched predicted covenants / predicted covenants
  - threshold accuracy = matched financial covenants with the same operator and threshold / matched financial
  - page accuracy      = matched covenants whose page equals the labelled page / matched with a labelled page
Only labels with status VERIFIED count unless --include-drafts is given.

Gate (initial, to be agreed): threshold accuracy >= 0.95 and covenant recall >= 0.85.
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass, field
from pathlib import Path

GATE_THRESHOLD_ACCURACY = 0.95
GATE_RECALL = 0.85


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


def evaluate(labels_dir: Path, predictions_dir: Path, include_drafts: bool = False) -> dict:
    total = Score()
    per_doc = {}
    for label_path in sorted(labels_dir.glob("*.json")):
        label = json.loads(label_path.read_text(encoding="utf-8"))
        if label.get("status") != "VERIFIED" and not include_drafts:
            continue
        pred_path = predictions_dir / label_path.name
        prediction = json.loads(pred_path.read_text(encoding="utf-8")) if pred_path.exists() else {"covenants": []}
        s = score_document(label, prediction)
        total.add(s)
        per_doc[label["documentId"]] = {**s.metrics(), "misses": s.misses, "extras": s.extras,
                                        "wrong_thresholds": s.wrong_thresholds}
    overall = total.metrics()
    passed = (overall["threshold_accuracy"] is not None and overall["recall"] is not None
              and overall["threshold_accuracy"] >= GATE_THRESHOLD_ACCURACY and overall["recall"] >= GATE_RECALL)
    return {"documents": len(per_doc), "overall": overall, "gate_passed": passed, "per_document": per_doc}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--labels", type=Path, default=Path(__file__).parent / "labels")
    ap.add_argument("--predictions", type=Path, required=True)
    ap.add_argument("--include-drafts", action="store_true")
    args = ap.parse_args(argv)
    report = evaluate(args.labels, args.predictions, args.include_drafts)
    print(json.dumps(report, indent=2))
    return 0 if report["gate_passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
