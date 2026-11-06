"""Re-apply DPS extract_values (current rules) to every reader's saved raw rows, so all get the same rules."""
import json, sys
from dps.extraction.statement import extract_values
r = json.load(open(sys.argv[1]))
for d, ms in r.items():
    for m, x in ms.items():
        if "raw" in x and "header" in x:
            res = extract_values([(tuple(row) + (None,) * 4)[:4] for row in x["raw"]], x["header"])
            x["values"] = {v.key: v.value for v in res.values if not v.derived}
            x["issues"] = res.issues
json.dump(r, open(sys.argv[2], "w"))
