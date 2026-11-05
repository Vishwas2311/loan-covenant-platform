"""The synthetic golden set: its labels match its documents, and the gate scores statements too."""

from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

from evaluate import evaluate, score_statement
from make_synthetic import AGREEMENTS, STATEMENTS, _pdf, layout, statement_values

HERE = Path(__file__).resolve().parent


def _norm(s: str) -> str:
    return re.sub(r"\s+", " ", s).strip()


def test_every_label_quote_is_on_its_page():
    pypdf = pytest.importorskip("pypdf")
    import io

    for a in AGREEMENTS:
        pages, covenants = layout(a)
        reader = pypdf.PdfReader(io.BytesIO(_pdf(pages)))
        texts = [_norm(p.extract_text()) for p in reader.pages]
        assert len(texts) == len(pages)
        for c in covenants:
            assert _norm(c["quote"]) in texts[c["page"] - 1], (a.doc_id, c["metric"])


def test_committed_labels_match_the_specification():
    for a in AGREEMENTS:
        label = json.loads((HERE / "labels" / f"{a.doc_id}.json").read_text(encoding="utf-8"))
        assert label["status"] == "SYNTHETIC"
        assert label["covenants"] == layout(a)[1]
    for doc_id, _, _, items in STATEMENTS:
        label = json.loads((HERE / "labels" / f"{doc_id}.json").read_text(encoding="utf-8"))
        assert label["values"] == statement_values(items)


def test_statement_values_score_within_half_a_percent(tmp_path):
    assert score_statement({"values": {"EBITDA": 100.0, "CASH": 0.0}}, {"values": {"EBITDA": 100.4, "CASH": 0.009}}) \
        == (2, 2, [])
    n, ok, wrong = score_statement({"values": {"EBITDA": 100.0, "NET_WORTH": 5.0}}, {"values": {"EBITDA": 101.0}})
    assert (n, ok) == (2, 0) and "NET_WORTH: gold 5.0 vs None" in wrong

    labels, preds = tmp_path / "labels", tmp_path / "preds"
    labels.mkdir()
    preds.mkdir()
    (labels / "FS-1.json").write_text(json.dumps({"documentId": "FS-1", "type": "FINANCIAL_STATEMENT",
                                                  "status": "SYNTHETIC", "values": {"EBITDA": 10.0}}))
    (labels / "AGR-1.json").write_text(json.dumps({"documentId": "AGR-1", "status": "SYNTHETIC", "covenants": [
        {"metric": "DSCR", "category": "FINANCIAL", "operator": ">=", "threshold": 1.2}]}))
    (preds / "FS-1.json").write_text(json.dumps({"values": {"EBITDA": 2.0}}))
    (preds / "AGR-1.json").write_text(json.dumps({"covenants": [
        {"metric": "DSCR", "category": "FINANCIAL", "operator": ">=", "threshold": 1.2}]}))
    report = evaluate(labels, preds, statuses=("SYNTHETIC",))
    assert report["overall"]["value_accuracy"] == 0.0 and report["gate_passed"] is False
    assert evaluate(labels, preds)["documents"] == 0  # VERIFIED only by default


def test_predict_runs_the_dps_extractors(tmp_path):
    pytest.importorskip("dps")
    from predict import main

    assert main(["--out", str(tmp_path)]) == 0
    report = evaluate(HERE / "labels", tmp_path, statuses=("SYNTHETIC",))
    assert report["gate_passed"], report["per_document"]
