"""Loan agreements: credit agreements filed as EX-10 exhibits on EDGAR, with financial covenants.

EDGAR full-text search (efts.sec.gov), one exhibit per company, filed 2019 or later, described as a credit
or loan agreement (not an amendment). Writes agreements/<id>.htm (or .pdf) and agreements/index.json.
"""
import json
import os
import sys
import time
import urllib.parse
import urllib.request

UA = "Covenexa-benchmark research@covenexa.local"
QUERIES = ['"financial covenants" "consolidated leverage ratio" "credit agreement"',
           '"interest coverage ratio" "credit agreement" "financial covenants"',
           '"fixed charge coverage ratio" "loan agreement" "financial covenants"',
           '"debt service coverage ratio" "loan agreement" "financial covenants"',
           '"net leverage ratio" "term loan" "credit agreement" "financial covenant"',
           '"maximum total leverage ratio" "credit agreement"',
           '"minimum fixed charge coverage ratio" "credit agreement"',
           '"tangible net worth" "loan agreement" "financial covenants"',
           '"senior secured leverage ratio" "credit agreement" "financial covenant"',
           '"debt to ebitda" "credit agreement" "financial covenants"']


def get(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Encoding": "identity"})
    with urllib.request.urlopen(req, timeout=60) as r:
        data = r.read()
    time.sleep(0.35)
    return data


def main(out: str, want: int) -> None:
    os.makedirs(out, exist_ok=True)
    idx = os.path.join(out, "index.json")
    chosen = json.load(open(idx)) if os.path.exists(idx) else {}
    seen = {v["url"].split("/data/")[1].split("/")[0] for v in chosen.values()}
    for q in QUERIES:
        for start in range(0, 400, 100):
            if len(chosen) >= want:
                break
            url = ("https://efts.sec.gov/LATEST/search-index?" + urllib.parse.urlencode(
                {"q": q, "dateRange": "custom", "startdt": "2019-01-01", "enddt": "2026-10-31", "from": start}))
            hits = json.loads(get(url))["hits"]["hits"]
            for h in hits:
                s = h["_source"]
                ftype, desc = s.get("file_type", ""), (s.get("file_description") or "").lower()
                adsh, fname = h["_id"].split(":")
                cik = s["ciks"][0].lstrip("0")
                if not ftype.startswith("EX-10") or cik in seen:
                    continue
                if "amend" in desc or "amend" in fname.lower() or not ("credit" in desc or "loan" in desc or "credit" in fname.lower() or "loan" in fname.lower()):
                    continue
                seen.add(cik)
                doc_id = f"AG{len(chosen) + 1:02d}"
                link = f"https://www.sec.gov/Archives/edgar/data/{cik}/{adsh.replace('-', '')}/{fname}"
                try:
                    data = get(link)
                except Exception as ex:  # noqa: BLE001
                    print("skip", link, type(ex).__name__)
                    continue
                ext = ".pdf" if data.startswith(b"%PDF") else ".htm"
                open(os.path.join(out, doc_id + ext), "wb").write(data)
                chosen[doc_id] = {"company": s["display_names"][0], "file_date": s["file_date"], "url": link,
                                  "file_type": ftype, "description": s.get("file_description"), "bytes": len(data)}
                print(doc_id, s["file_date"], s["display_names"][0][:50], ftype, len(data), flush=True)
                if len(chosen) >= want:
                    break
    json.dump(chosen, open(os.path.join(out, "index.json"), "w"), indent=1)


if __name__ == "__main__":
    main(sys.argv[1], int(sys.argv[2]))
