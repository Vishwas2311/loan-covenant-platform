"""Download public filings listed in a TSV (id, url) into a folder; record size, pages and the source.

One request per file with an honest User-Agent; no retries against a refusal; PDFs only; 120 MB cap.
"""
import csv
import hashlib
import os
import sys
import time
import urllib.request

UA = "Covenexa-benchmark research@covenexa.local"
CAP = 120 * 1024 * 1024


def fetch(url: str, path: str) -> tuple[int, str]:
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/pdf,*/*"})
    with urllib.request.urlopen(req, timeout=90) as r:
        data = r.read(CAP + 1)
    if len(data) > CAP:
        return 0, "too large"
    if not data.startswith(b"%PDF"):
        return 0, "not a PDF"
    open(path, "wb").write(data)
    return len(data), hashlib.sha256(data).hexdigest()[:16]


def main(list_path: str, out_dir: str) -> None:
    os.makedirs(out_dir, exist_ok=True)
    manifest = os.path.join(out_dir, "manifest.tsv")
    done = set()
    if os.path.exists(manifest):
        done = {r[0] for r in csv.reader(open(manifest, encoding="utf-8"), delimiter="\t")}
    with open(manifest, "a", newline="", encoding="utf-8") as m:
        w = csv.writer(m, delimiter="\t")
        for row in csv.reader(open(list_path, encoding="utf-8"), delimiter="\t"):
            if not row or row[0].startswith("#") or row[0] in done:
                continue
            doc_id, url = row[0], row[1]
            path = os.path.join(out_dir, doc_id + ".pdf")
            try:
                size, info = fetch(url, path)
            except Exception as ex:  # noqa: BLE001
                size, info = 0, f"{type(ex).__name__}: {str(ex)[:80]}"
            w.writerow([doc_id, url, size, info])
            m.flush()
            print(doc_id, size, info, flush=True)
            time.sleep(1.0)  # polite


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
