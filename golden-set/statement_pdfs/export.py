"""Copy the generated statement PDFs into the golden set as FS-011..FS-034, with labels and manifest rows.

Regenerate the PDFs first (needs Python, Node and the UI's Playwright for Chromium, and DPS's venv for
pypdfium2 and Pillow):

    python gen_html.py                    # html/S01..S18 + truth.json
    python gen_holdout.py                 # html/H01..H05 + truth_holdout.json (written after the reader was frozen)
    node print_pdf.cjs ../../ui/node_modules/playwright
    ../../dps/.venv/Scripts/python scan.py   # S13, S14 and H06: image-only scans
    python export.py

The truth is known by construction: every figure is printed from the same numbers the label holds.
"""
import csv
import hashlib
import json
import shutil
from pathlib import Path

HERE = Path(__file__).resolve().parent
GOLDEN = HERE.parent
LABEL_BY = "generator: statement_pdfs/gen_html.py and gen_holdout.py (truth by construction)"


def main() -> None:
    docs = json.loads((HERE / "truth.json").read_text()) + json.loads((HERE / "truth_holdout.json").read_text())
    manifest = GOLDEN / "manifest.csv"
    with manifest.open(newline="", encoding="utf-8") as f:
        rows = {r["documentId"]: r for r in csv.DictReader(f)}
    for n, d in enumerate(docs, start=11):
        doc_id = f"FS-{n:03d}"
        src = HERE / "pdf" / f"{d['id']}.pdf"
        name = f"{doc_id}.pdf"
        shutil.copyfile(src, GOLDEN / "documents" / name)
        data = src.read_bytes()
        label = {"documentId": doc_id, "type": "FINANCIAL_STATEMENT", "sourceFile": name,
                 "sha256": hashlib.sha256(data).hexdigest(), "unit": "INR crore", "status": "SYNTHETIC",
                 "labelledBy": LABEL_BY, "reviewedBy": None, "layout": d["layout"], "generatorId": d["id"],
                 "values": d["truth"]}
        (GOLDEN / "labels" / f"{doc_id}.json").write_text(json.dumps(label, indent=1, ensure_ascii=False) + "\n",
                                                          encoding="utf-8")
        rows[doc_id] = {"documentId": doc_id, "type": "FINANCIAL_STATEMENT", "sourceFile": name, "pages": "",
                        "covenants": str(len(d["truth"])), "status": "SYNTHETIC"}
    with manifest.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["documentId", "type", "sourceFile", "pages", "covenants", "status"],
                           lineterminator="\n")
        w.writeheader()
        for doc_id in sorted(rows):
            w.writerow(rows[doc_id])
    print(f"exported {len(docs)} statement PDFs")


if __name__ == "__main__":
    main()
