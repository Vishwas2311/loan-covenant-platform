import json

from evaluate import evaluate, main, score_document


def label(covs, status="VERIFIED", doc="AGR-T"):
    return {"documentId": doc, "status": status, "covenants": covs}


DSCR = {"metric": "DSCR", "category": "FINANCIAL", "operator": ">=", "threshold": 1.25, "page": 5}
ICR = {"metric": "ICR", "category": "FINANCIAL", "operator": ">=", "threshold": 2.0, "page": 6}
PLEDGE = {"metric": "NEGATIVE_PLEDGE", "category": "NON_FINANCIAL", "operator": None, "threshold": None, "page": 4}


def test_perfect_match():
    s = score_document(label([DSCR, ICR, PLEDGE]), {"covenants": [DSCR, ICR, PLEDGE]})
    assert s.metrics() == {"recall": 1.0, "precision": 1.0, "threshold_accuracy": 1.0, "page_accuracy": 1.0}


def test_miss_extra_wrong_threshold_and_page():
    pred = [{**DSCR, "threshold": 1.5, "page": 9}, {"metric": "CURRENT_RATIO", "category": "FINANCIAL"}]
    s = score_document(label([DSCR, ICR]), {"covenants": pred})
    m = s.metrics()
    assert m["recall"] == 0.5 and m["precision"] == 0.5
    assert m["threshold_accuracy"] == 0.0 and m["page_accuracy"] == 0.0
    assert s.misses == ["ICR"] and s.extras == ["CURRENT_RATIO"] and "gold >= 1.25" in s.wrong_thresholds[0]


def test_operator_and_none_thresholds():
    a = {"metric": "X", "category": "FINANCIAL", "operator": "<=", "threshold": None}
    assert score_document(label([a]), {"covenants": [a]}).threshold_correct == 1
    assert score_document(label([a]), {"covenants": [{**a, "operator": ">="}]}).threshold_correct == 0
    assert score_document(label([a]), {"covenants": [{**a, "threshold": 1.0}]}).threshold_correct == 0


def test_evaluate_skips_drafts_and_gate(tmp_path, capsys):
    labels, preds = tmp_path / "labels", tmp_path / "preds"
    labels.mkdir()
    preds.mkdir()
    (labels / "AGR-1.json").write_text(json.dumps(label([DSCR, ICR], doc="AGR-1")))
    (labels / "AGR-2.json").write_text(json.dumps(label([DSCR], status="DRAFT_UNVERIFIED", doc="AGR-2")))
    (preds / "AGR-1.json").write_text(json.dumps({"covenants": [DSCR, ICR]}))
    report = evaluate(labels, preds)
    assert report["documents"] == 1 and report["gate_passed"] is True
    with_drafts = evaluate(labels, preds, include_drafts=True)
    assert with_drafts["documents"] == 2 and with_drafts["overall"]["recall"] == 0.6667
    assert with_drafts["gate_passed"] is False
    assert main(["--labels", str(labels), "--predictions", str(preds)]) == 0
    assert main(["--labels", str(labels), "--predictions", str(preds), "--include-drafts"]) == 1
    assert '"gate_passed"' in capsys.readouterr().out


def test_empty_set_does_not_pass(tmp_path):
    (tmp_path / "l").mkdir()
    (tmp_path / "p").mkdir()
    assert evaluate(tmp_path / "l", tmp_path / "p")["gate_passed"] is False
