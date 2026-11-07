"""Cache ParserEngine pages (pypdf + RapidOCR) per agreement, as DPS Flow A reads them."""
import json, os, sys
from dps.parsing import ParserEngine, RapidOcrBackend
eng = ParserEngine(RapidOcrBackend())
out = json.load(open("ag_pages.json")) if os.path.exists("ag_pages.json") else {}
for name in sorted(os.listdir("agreements")):
    if not name.endswith(".pdf") or name[:-4] in out:
        continue
    pages = eng.parse(open(f"agreements/{name}", "rb").read())
    out[name[:-4]] = [[p.no, p.text, p.method, p.quality] for p in pages]
    json.dump(out, open("ag_pages.json", "w"))
    print(name, len(pages), flush=True)
