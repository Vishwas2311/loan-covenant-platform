"""US statements: latest 10-K per company from EDGAR, plus the XBRL facts that become its answer key.

Uses data.sec.gov (submissions and companyfacts) with the User-Agent SEC asks for; 0.3 s between calls.
Writes us/<id>.html (the 10-K main document) and us/<id>.facts.json.
"""
import json
import os
import sys
import time
import urllib.request

UA = "Covenexa-benchmark research@covenexa.local"
CIKS = {"US01": 18230, "US02": 315189, "US03": 73309, "US04": 106640, "US05": 46080, "US06": 63276,
        "US07": 793952, "US08": 48465, "US09": 21076, "US10": 89800, "US11": 9389, "US12": 1370946,
        "US13": 931015, "US14": 91440, "US15": 55067}


def get(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Encoding": "identity"})
    with urllib.request.urlopen(req, timeout=60) as r:
        data = r.read()
    time.sleep(0.3)
    return data


def main(out: str) -> None:
    os.makedirs(out, exist_ok=True)
    index = {}
    for doc_id, cik in CIKS.items():
        try:
            sub = json.loads(get(f"https://data.sec.gov/submissions/CIK{cik:010d}.json"))
            recent = sub["filings"]["recent"]
            i = recent["form"].index("10-K")
            acc, prim = recent["accessionNumber"][i].replace("-", ""), recent["primaryDocument"][i]
            url = f"https://www.sec.gov/Archives/edgar/data/{cik}/{acc}/{prim}"
            open(os.path.join(out, doc_id + ".html"), "wb").write(get(url))
            facts = get(f"https://data.sec.gov/api/xbrl/companyfacts/CIK{cik:010d}.json")
            open(os.path.join(out, doc_id + ".facts.json"), "wb").write(facts)
            index[doc_id] = {"cik": cik, "name": sub["name"], "url": url, "accession": recent["accessionNumber"][i],
                             "report_date": recent["reportDate"][i]}
            print(doc_id, sub["name"], recent["reportDate"][i], flush=True)
        except Exception as ex:  # noqa: BLE001
            print(doc_id, "failed", type(ex).__name__, str(ex)[:80], flush=True)
    json.dump(index, open(os.path.join(out, "index.json"), "w"), indent=1)


if __name__ == "__main__":
    main(sys.argv[1])
