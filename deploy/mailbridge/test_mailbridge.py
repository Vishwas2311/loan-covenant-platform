"""The test mail bridge renders Core's notification contract as an e-mail."""

from mailbridge import addresses, render


def test_rows_become_a_table_and_recipients_split():
    msg = render({"templateCode": "Cov_Escalation", "priority": "HIGH", "emailTo": "a@x.test; b@x.test",
                  "emailCC": "c@x.test", "variables": {"cpMonitoring": [
                      {"CP_ID": "CP-1001", "Covenant": "Minimum DSCR <1.25>", "Days_Open": "36"},
                      {"CP_ID": "CP-1002", "Covenant": "ICR", "Extra": "x"}]}}, "from@x.test")
    assert msg["Subject"] == "[High] Escalation: covenant breaches left unhandled"
    assert msg["To"] == "a@x.test, b@x.test" and msg["Cc"] == "c@x.test"
    html = msg.get_body(("html",)).get_content()
    assert "<th" in html and "Minimum DSCR &lt;1.25&gt;" in html and "Extra" in html
    assert "CP_ID: CP-1001" in msg.get_body(("plain",)).get_content()


def test_unknown_template_and_empty_rows():
    msg = render({"templateCode": "Other", "emailTo": "a@x.test", "variables": {}}, "f@x.test")
    assert msg["Subject"] == "Covenant notification (Other)" and msg["Cc"] is None
    assert "(no rows)" in msg.get_body(("plain",)).get_content()
    assert addresses(None) == [] and addresses(" a@x , ,b@x ") == ["a@x", "b@x"]
