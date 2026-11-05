"""Test-only stand-in for the bank's notification service: turns Core's notification calls into real e-mail.

Core posts ``POST /api/v1/notifications`` with ``{moduleCode, templateCode, emailTo, emailCC, variables:
{cpMonitoring: [ {...}, ... ]}, priority}``. This service renders that as an HTML e-mail (a short heading for
the template and the rows as a table) and hands it to an SMTP server, normally the local Mailpit inbox. It
is for local and test environments only: the bank's own service owns real templates and delivery.

Standard library only. Settings come from the environment:
  SMTP_HOST (default mailpit), SMTP_PORT (1025), MAIL_FROM (covenant-monitoring@covenant.local),
  BRIDGE_PORT (8090).
"""

from __future__ import annotations

import html
import json
import os
import smtplib
from email.message import EmailMessage
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SUBJECTS = {
    "Cov_Template": "Covenant monitoring summary",
    "Cov_Escalation": "Escalation: covenant breaches left unhandled",
    "Cov_Borrower_Certificate_Reminder": "Reminder: compliance certificate due",
    "Cov_Saved_Report": "Your scheduled covenant report",
}


def addresses(value: str | None) -> list[str]:
    return [a.strip() for a in (value or "").replace(";", ",").split(",") if a.strip()]


def render(body: dict, sender: str) -> EmailMessage:
    """The e-mail for one notification call."""
    template = str(body.get("templateCode") or "")
    rows = (body.get("variables") or {}).get("cpMonitoring") or []
    subject = SUBJECTS.get(template, f"Covenant notification ({template or 'no template'})")
    if str(body.get("priority") or "").upper() == "HIGH":
        subject = "[High] " + subject
    columns: list[str] = []
    for row in rows:
        for key in row:
            if key not in columns:
                columns.append(key)
    head = "".join(f"<th style='text-align:left;padding:4px 8px;border-bottom:1px solid #ccc'>{html.escape(c)}</th>"
                   for c in columns)
    lines = "".join("<tr>" + "".join(f"<td style='padding:4px 8px'>{html.escape(str(row.get(c, '')))}</td>"
                                     for c in columns) + "</tr>" for row in rows)
    text = "\n".join(", ".join(f"{c}: {row.get(c, '')}" for c in columns) for row in rows) or "(no rows)"
    msg = EmailMessage()
    msg["Subject"] = subject
    msg["From"] = sender
    msg["To"] = ", ".join(addresses(body.get("emailTo")))
    if addresses(body.get("emailCC")):
        msg["Cc"] = ", ".join(addresses(body.get("emailCC")))
    msg["X-Covenant-Template"] = template
    msg.set_content(f"{subject}\n\n{text}\n")
    msg.add_alternative(f"<h3 style='font-family:sans-serif'>{html.escape(subject)}</h3>"
                        f"<table style='border-collapse:collapse;font-family:sans-serif;font-size:13px'>"
                        f"<thead><tr>{head}</tr></thead><tbody>{lines}</tbody></table>", subtype="html")
    return msg


class Handler(BaseHTTPRequestHandler):
    def _answer(self, status: int, payload: dict) -> None:
        data = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self) -> None:  # noqa: N802 - http.server naming
        self._answer(200 if self.path == "/health" else 404, {"status": "UP"} if self.path == "/health" else {})

    def do_POST(self) -> None:  # noqa: N802 - http.server naming
        if self.path != "/api/v1/notifications":
            self._answer(404, {"error": "not found"})
            return
        try:
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")
        except ValueError:
            self._answer(400, {"error": "body is not JSON"})
            return
        if not addresses(body.get("emailTo")):
            self._answer(400, {"error": "emailTo is required"})
            return
        msg = render(body, os.environ.get("MAIL_FROM", "covenant-monitoring@covenant.local"))
        try:
            with smtplib.SMTP(os.environ.get("SMTP_HOST", "mailpit"), int(os.environ.get("SMTP_PORT", "1025")),
                              timeout=10) as smtp:
                smtp.send_message(msg)
        except OSError as exc:
            self._answer(502, {"error": f"SMTP delivery failed: {type(exc).__name__}"})
            return
        self._answer(200, {"status": "SENT", "to": msg["To"]})

    def log_message(self, fmt: str, *args) -> None:  # one short line per call, no bodies
        print(f"{self.address_string()} {fmt % args}", flush=True)


def main() -> None:
    port = int(os.environ.get("BRIDGE_PORT", "8090"))
    # Listens on all interfaces inside its container; the compose file publishes no port for it.
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()  # nosec B104


if __name__ == "__main__":
    main()
