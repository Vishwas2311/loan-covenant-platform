"""Review page for the golden-set labels (decision D13): an analyst checks each agreement's covenants.

Usage:  python golden-set/review.py            then open http://127.0.0.1:8765

The page shows an agreement next to its draft label. The analyst checks, fixes, removes or adds
covenants, then marks the label VERIFIED; the server writes labels/AGR-nnn.json and manifest.csv, so
evaluate.py measures the extractor against the verified labels. With pypdf installed (for example
`dps/.venv/Scripts/python golden-set/review.py`) the page also checks that each quote is on its page.

Local only: it listens on 127.0.0.1, refuses other Host headers, and saves only requests carrying the
X-Review header, which a page on another site cannot send without a CORS preflight this server never
answers.
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import re
from datetime import date
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DOC_ID = re.compile(r"^AGR-\d{3}$")
OPERATORS = {">=", "<=", ">", "<", "=="}
CATEGORIES = {"FINANCIAL", "NON_FINANCIAL"}
FREQUENCIES = {"MONTHLY", "QUARTERLY", "HALF_YEARLY", "YEARLY"}
STATUSES = {"DRAFT_UNVERIFIED", "IN_REVIEW", "VERIFIED"}
MAX_BODY = 2_000_000


class LabelError(ValueError):
    """The submitted label breaks a rule; the message says which."""


def manifest_rows(root: Path) -> list[dict]:
    with (root / "manifest.csv").open(newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def read_label(root: Path, doc_id: str) -> dict:
    return json.loads((root / "labels" / f"{doc_id}.json").read_text(encoding="utf-8"))


def document_list(root: Path) -> list[dict]:
    """The agreements to review (statements are checked in their label files; see ANALYST-GUIDE.md)."""
    out = []
    for row in manifest_rows(root):
        if row.get("type", "LOAN_AGREEMENT") != "LOAN_AGREEMENT":
            continue
        label = read_label(root, row["documentId"])
        out.append({"documentId": row["documentId"], "sourceFile": row["sourceFile"], "pages": int(row["pages"] or 0),
                    "covenants": len(label.get("covenants", [])), "status": label.get("status"),
                    "reviewedBy": label.get("reviewedBy")})
    return out


def _number(value, field: str, index: int):
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise LabelError(f"Covenant {index + 1}: {field} must be a number")
    return value


def validate(label: dict, stored: dict) -> dict:
    """The label to save: rules from golden-set/README.md, with identity fields kept from the stored file."""
    if not isinstance(label, dict) or not isinstance(label.get("covenants"), list):
        raise LabelError("Expected a label with a covenants list")
    status = label.get("status")
    if status not in STATUSES:
        raise LabelError(f"status must be one of {sorted(STATUSES)}")
    pages = int(stored.get("pages") or 0)
    covenants = []
    for i, c in enumerate(label["covenants"]):
        if not isinstance(c, dict):
            raise LabelError(f"Covenant {i + 1} is not an object")
        metric = str(c.get("metric") or "").strip().upper()
        if not re.fullmatch(r"[A-Z][A-Z0-9_]{1,63}", metric):
            raise LabelError(f"Covenant {i + 1}: metric must be a key like DSCR or NEGATIVE_PLEDGE")
        category = c.get("category")
        if category not in CATEGORIES:
            raise LabelError(f"Covenant {i + 1}: category must be FINANCIAL or NON_FINANCIAL")
        operator = c.get("operator") or None
        threshold = _number(c.get("threshold"), "threshold", i)
        if category == "FINANCIAL":
            if operator not in OPERATORS or threshold is None:
                raise LabelError(f"Covenant {i + 1} ({metric}): a financial covenant needs an operator and a threshold")
        elif operator is not None or threshold is not None:
            raise LabelError(f"Covenant {i + 1} ({metric}): a non-financial covenant has no operator or threshold")
        page = c.get("page")
        if isinstance(page, bool) or not isinstance(page, int) or not 1 <= page <= max(pages, 1):
            raise LabelError(f"Covenant {i + 1} ({metric}): page must be between 1 and {pages}")
        frequency = c.get("frequency") or None
        if frequency is not None and frequency not in FREQUENCIES:
            raise LabelError(f"Covenant {i + 1} ({metric}): frequency must be one of {sorted(FREQUENCIES)}")
        quote = str(c.get("quote") or "").strip()
        if status == "VERIFIED" and not quote:
            raise LabelError(f"Covenant {i + 1} ({metric}): a verified covenant needs the quote that proves it")
        covenants.append({"metric": metric, "category": category, "operator": operator, "threshold": threshold,
                          "unit": (str(c["unit"]).strip() or None) if c.get("unit") else None,
                          "frequency": frequency, "page": page, "quote": quote})
    reviewer = str(label.get("reviewedBy") or "").strip()
    if status == "VERIFIED" and not reviewer:
        raise LabelError("Enter your name before marking the label verified")
    out = {k: stored[k] for k in ("documentId", "type", "sourceFile", "sha256", "pages") if k in stored}
    out.update({"status": status, "labelledBy": stored.get("labelledBy"), "reviewedBy": reviewer or None,
                "reviewedAt": date.today().isoformat() if status == "VERIFIED" else stored.get("reviewedAt"),
                "covenants": covenants})
    notes = str(label.get("notes") or "").strip()
    if notes:
        out["notes"] = notes
    return out


def save_label(root: Path, doc_id: str, submitted: dict) -> dict:
    stored = read_label(root, doc_id)
    label = validate(submitted, stored)
    (root / "labels" / f"{doc_id}.json").write_text(json.dumps(label, indent=2, ensure_ascii=False) + "\n",
                                                   encoding="utf-8", newline="\n")
    rows = manifest_rows(root)
    for row in rows:
        if row["documentId"] == doc_id:
            row["covenants"] = str(len(label["covenants"]))
            row["status"] = label["status"]
    buffer = io.StringIO()
    writer = csv.DictWriter(buffer, fieldnames=list(rows[0].keys()), lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)
    (root / "manifest.csv").write_text(buffer.getvalue(), encoding="utf-8", newline="\n")
    return label


def page_texts(pdf: Path) -> list[str] | None:
    """Text of each page, or None when pypdf is not installed."""
    try:
        from pypdf import PdfReader
    except ImportError:
        return None
    return [page.extract_text() or "" for page in PdfReader(str(pdf)).pages]


def _squash(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip().lower()


def quote_checks(root: Path, doc_id: str, label: dict) -> list[bool] | None:
    """For each covenant, whether its quote appears on its page (whitespace-insensitive)."""
    texts = page_texts(root / "documents" / f"{doc_id}.pdf")
    if texts is None:
        return None
    out = []
    for c in label.get("covenants", []):
        page = c.get("page")
        quote = _squash(c.get("quote") or "")
        on_page = isinstance(page, int) and 1 <= page <= len(texts) and bool(quote)
        out.append(on_page and quote in _squash(texts[page - 1]))
    return out


def make_handler(root: Path, port: int):
    allowed_hosts = {f"127.0.0.1:{port}", f"localhost:{port}"}

    class Handler(BaseHTTPRequestHandler):
        server_version = "golden-set-review"

        def log_message(self, fmt, *args):  # quiet: one line per save only
            pass

        def _send(self, status: int, body: bytes, content_type: str) -> None:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; "
                                                        "frame-src 'self'; object-src 'none'")
            self.end_headers()
            self.wfile.write(body)

        def _json(self, status: int, payload) -> None:
            self._send(status, json.dumps(payload).encode(), "application/json")

        def _host_ok(self) -> bool:
            if self.headers.get("Host") not in allowed_hosts:
                self._json(HTTPStatus.FORBIDDEN, {"error": f"Open the page at http://127.0.0.1:{port}"})
                return False
            return True

        def _doc_id(self, prefix: str, suffix: str = "") -> str | None:
            doc_id = self.path[len(prefix):len(self.path) - len(suffix) if suffix else None]
            return doc_id if DOC_ID.match(doc_id) and (root / "labels" / f"{doc_id}.json").exists() else None

        def do_GET(self):  # noqa: N802 - http.server API
            if not self._host_ok():
                return
            path = self.path.split("?")[0]
            if path in ("/", "/index.html"):
                self._send(HTTPStatus.OK, (root / "review.html").read_bytes(), "text/html; charset=utf-8")
            elif path == "/review.js":
                self._send(HTTPStatus.OK, (root / "review.js").read_bytes(), "text/javascript; charset=utf-8")
            elif path == "/api/documents":
                self._json(HTTPStatus.OK, document_list(root))
            elif path.startswith("/api/labels/"):
                self.path = path
                doc_id = self._doc_id("/api/labels/")
                if doc_id is None:
                    self._json(HTTPStatus.NOT_FOUND, {"error": "No such document"})
                    return
                label = read_label(root, doc_id)
                self._json(HTTPStatus.OK, {"label": label, "quoteOnPage": quote_checks(root, doc_id, label)})
            elif path.startswith("/documents/") and path.endswith(".pdf"):
                self.path = path
                doc_id = self._doc_id("/documents/", ".pdf")
                pdf = root / "documents" / f"{doc_id}.pdf" if doc_id else None
                if pdf is None or not pdf.exists():
                    self._json(HTTPStatus.NOT_FOUND, {"error": "No such document"})
                    return
                self._send(HTTPStatus.OK, pdf.read_bytes(), "application/pdf")
            else:
                self._json(HTTPStatus.NOT_FOUND, {"error": "Not found"})

        def _discard_body(self) -> None:
            # Read what the client sent before refusing it. Answering with the body unread closes the
            # socket on unread data, which Windows turns into a reset: the client sees "connection
            # reset" instead of the refusal.
            length = int(self.headers.get("Content-Length") or 0)
            if 0 < length <= MAX_BODY:
                self.rfile.read(length)
            else:
                self.close_connection = True

        def do_PUT(self):  # noqa: N802 - http.server API
            if not self._host_ok():
                self._discard_body()
                return
            if self.headers.get("X-Review") != "1" or not (self.headers.get("Content-Type") or "").startswith(
                    "application/json"):
                self._discard_body()
                self._json(HTTPStatus.FORBIDDEN, {"error": "Save from the review page"})
                return
            doc_id = self._doc_id("/api/labels/") if self.path.startswith("/api/labels/") else None
            if doc_id is None:
                self._discard_body()
                self._json(HTTPStatus.NOT_FOUND, {"error": "No such document"})
                return
            length = int(self.headers.get("Content-Length") or 0)
            if length <= 0 or length > MAX_BODY:
                self._json(HTTPStatus.BAD_REQUEST, {"error": "Missing or oversized body"})
                return
            try:
                label = save_label(root, doc_id, json.loads(self.rfile.read(length)))
            except (LabelError, json.JSONDecodeError) as exc:
                self._json(HTTPStatus.UNPROCESSABLE_ENTITY, {"error": str(exc)})
                return
            print(f"saved {doc_id}: {label['status']}, {len(label['covenants'])} covenants", flush=True)
            self._json(HTTPStatus.OK, {"label": label, "quoteOnPage": quote_checks(root, doc_id, label)})

    return Handler


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(ROOT, args.port))
    checks = "on" if page_texts(next((ROOT / "documents").glob("AGR-*.pdf"))) is not None else "off (install pypdf)"
    print(f"Golden-set review on http://127.0.0.1:{args.port}  (quote checks {checks}; Ctrl+C to stop)")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
