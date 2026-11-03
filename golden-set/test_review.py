import json
import shutil
import threading
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

import pytest

import review

HERE = Path(__file__).resolve().parent
DSCR = {"metric": "dscr", "category": "FINANCIAL", "operator": ">=", "threshold": 1.25, "unit": None,
        "frequency": "QUARTERLY", "page": 3, "quote": "not less than 1.25 times"}
PLEDGE = {"metric": "NEGATIVE_PLEDGE", "category": "NON_FINANCIAL", "operator": None, "threshold": None,
          "page": 4, "quote": "shall not create any security"}


@pytest.fixture
def root(tmp_path: Path) -> Path:
    """A copy of the golden set, so tests never touch the real labels."""
    for name in ("labels", "manifest.csv", "review.html", "review.js"):
        src = HERE / name
        (shutil.copytree if src.is_dir() else shutil.copy)(src, tmp_path / name)
    (tmp_path / "documents").mkdir()
    shutil.copy(HERE / "documents" / "AGR-001.pdf", tmp_path / "documents" / "AGR-001.pdf")
    return tmp_path


def submitted(status="VERIFIED", covenants=(DSCR, PLEDGE), reviewer="Priya Shah"):
    return {"status": status, "reviewedBy": reviewer, "covenants": list(covenants), "notes": "Step-down in 2027"}


def test_verified_label_is_saved_and_the_manifest_follows(root):
    label = review.save_label(root, "AGR-001", {**submitted(), "documentId": "AGR-999", "sha256": "forged"})

    stored = json.loads((root / "labels" / "AGR-001.json").read_text(encoding="utf-8"))
    assert stored == label
    assert stored["documentId"] == "AGR-001" and stored["sha256"] != "forged"  # identity kept from the file
    assert stored["status"] == "VERIFIED" and stored["reviewedBy"] == "Priya Shah" and stored["reviewedAt"]
    assert stored["covenants"][0]["metric"] == "DSCR" and stored["notes"] == "Step-down in 2027"
    row = next(r for r in review.manifest_rows(root) if r["documentId"] == "AGR-001")
    assert row["status"] == "VERIFIED" and row["covenants"] == "2"
    assert next(d for d in review.document_list(root) if d["documentId"] == "AGR-001")["reviewedBy"] == "Priya Shah"


def test_draft_can_be_saved_without_a_name_or_quotes(root):
    label = review.save_label(root, "AGR-001", submitted("IN_REVIEW", [{**DSCR, "quote": ""}], reviewer=""))
    assert label["status"] == "IN_REVIEW" and label["reviewedBy"] is None


@pytest.mark.parametrize("change, message", [
    ({"reviewedBy": ""}, "Enter your name"),
    ({"status": "DONE"}, "status must be"),
    ({"covenants": [{**DSCR, "quote": " "}]}, "needs the quote"),
    ({"covenants": [{**DSCR, "threshold": None}]}, "needs an operator and a threshold"),
    ({"covenants": [{**DSCR, "operator": "=>"}]}, "needs an operator and a threshold"),
    ({"covenants": [{**PLEDGE, "threshold": 1}]}, "has no operator or threshold"),
    ({"covenants": [{**DSCR, "page": 99}]}, "page must be between 1 and"),
    ({"covenants": [{**DSCR, "page": True}]}, "page must be between 1 and"),
    ({"covenants": [{**DSCR, "threshold": "1.25"}]}, "threshold must be a number"),
    ({"covenants": [{**DSCR, "metric": "bad metric!"}]}, "metric must be a key"),
    ({"covenants": [{**DSCR, "category": "OTHER"}]}, "category must be"),
    ({"covenants": [{**DSCR, "frequency": "WEEKLY"}]}, "frequency must be one of"),
    ({"covenants": ["DSCR"]}, "is not an object"),
    ({"covenants": None}, "covenants list"),
])
def test_rules_from_the_labelling_guide(root, change, message):
    with pytest.raises(review.LabelError, match=message):
        review.save_label(root, "AGR-001", {**submitted(), **change})


def test_quote_check_finds_quotes_on_their_page(root):
    texts = review.page_texts(root / "documents" / "AGR-001.pdf")
    if texts is None:
        pytest.skip("pypdf not installed")
    sentence = " ".join(texts[2].split()[:12])
    checks = review.quote_checks(root, "AGR-001", {"covenants": [
        {"page": 3, "quote": sentence}, {"page": 1, "quote": sentence}, {"page": 3, "quote": ""}]})
    assert checks == [True, False, False]


@pytest.fixture
def server(root):
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), review.make_handler(root, 0))
    port = httpd.server_address[1]
    httpd.RequestHandlerClass = review.make_handler(root, port)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{port}"
    httpd.shutdown()


def call(url, method="GET", body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, resp.read(), resp.headers
    except urllib.error.HTTPError as err:
        return err.code, err.read(), err.headers


def test_http_reads_and_saves(server):
    status, body, headers = call(server + "/")
    assert status == 200 and b"Golden-set review" in body and "nosniff" in headers["X-Content-Type-Options"]
    status, body, headers = call(server + "/review.js")
    assert status == 200 and b"openDoc" in body and headers["Content-Type"].startswith("text/javascript")
    status, body, _ = call(server + "/api/documents")
    assert status == 200 and any(d["documentId"] == "AGR-001" for d in json.loads(body))
    status, body, _ = call(server + "/api/labels/AGR-001")
    assert status == 200 and json.loads(body)["label"]["documentId"] == "AGR-001"
    status, body, headers = call(server + "/documents/AGR-001.pdf")
    assert status == 200 and body.startswith(b"%PDF") and headers["Content-Type"] == "application/pdf"

    ok = {"Content-Type": "application/json", "X-Review": "1"}
    status, body, _ = call(server + "/api/labels/AGR-001", "PUT", submitted(), ok)
    assert status == 200 and json.loads(body)["label"]["status"] == "VERIFIED"
    status, body, _ = call(server + "/api/labels/AGR-001", "PUT", submitted(reviewer=""), ok)
    assert status == 422 and b"Enter your name" in body


def test_http_refuses_cross_site_writes_bad_hosts_and_paths(server):
    port = server.rsplit(":", 1)[1]
    assert call(server + "/api/labels/AGR-001", "PUT", submitted(), {"Content-Type": "application/json"})[0] == 403
    assert call(server + "/api/labels/AGR-001", "PUT", submitted(), {"Content-Type": "text/plain", "X-Review": "1"})[0] \
        == 403
    assert call(server + "/api/documents", headers={"Host": f"evil.example:{port}"})[0] == 403
    for path in ("/api/labels/../manifest", "/api/labels/AGR-999", "/documents/..%2Freview.py.pdf",
                 "/documents/AGR-002.pdf", "/review.py"):
        assert call(server + path)[0] == 404, path
    assert call(server + "/api/labels/AGR-999", "PUT", submitted(),
                {"Content-Type": "application/json", "X-Review": "1"})[0] == 404
    req = urllib.request.Request(server + "/api/labels/AGR-001", data=b"{", method="PUT",
                                 headers={"Content-Type": "application/json", "X-Review": "1"})
    try:
        urllib.request.urlopen(req, timeout=10)
    except urllib.error.HTTPError as err:
        assert err.code == 422
