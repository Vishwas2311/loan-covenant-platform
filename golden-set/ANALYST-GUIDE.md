# Golden set: guide for credit analysts

The golden set is how we prove that Covenexa reads loan agreements and financial statements correctly. Before the
app can go to full production, credit analysts must confirm the correct answer for **at least 20 agreements and
20 statements** (PRD goal G6). This page is all you need to do that.

## What is in the set today

| Documents | How the labels were made | What you do |
|---|---|---|
| AGR-001 to AGR-010: real sample agreements | Drafted by the extractor itself (`DRAFT_UNVERIFIED`) | **Check and correct each one.** These count towards the 20. |
| AGR-011 to AGR-020: agreements written for testing | Written from the same text that made the PDF (`SYNTHETIC`) | Optional spot check. They test tricky wording; they do not count towards the 20. |
| FS-001 to FS-010: statements written for testing | Written from the same figures that made the file (`SYNTHETIC`) | Optional spot check. |
| Your bank's own agreements and statements | Not added yet | **Add at least 10 agreements and 20 statements**, including scanned and table-heavy ones. |

Use real documents with personal data removed, or documents the bank can share for testing. Do not add documents
that must not leave the bank's systems.

## Checking an agreement (about 15 minutes each)

1. Start the review page on your computer:

   ```bash
   dps/.venv/Scripts/python golden-set/review.py
   ```

   Then open http://127.0.0.1:8765. Nothing leaves your computer.
2. Type your name at the top right, then pick an agreement on the left.
3. For **each covenant listed**, open its page (**Show page**) and check:
   - **Metric**: is it the right covenant (for example DSCR, not ICR)?
   - **Operator**: a floor is `>=` ("not less than", "shall not fall below", "at least"); a ceiling is `<=`
     ("not more than", "shall not exceed"). This is the most important check.
   - **Threshold**: the number exactly as written (1.35, not 1.3).
   - **Page and quote**: a red *not found on that page* means one of them is wrong.
4. **Remove** anything that is not a covenant: notice periods, drawdown minimums, collateral cover in a security
   clause, cure periods.
5. **Add a missing covenant** for every covenant the list does not have. Read the whole agreement, including the
   compliance-certificate schedule, which sometimes holds the only statement of a limit.
6. Write step-downs ("4.00x until March 2027, then 3.50x"), cure periods and testing basis (LTM, quarterly) in
   **Notes**.
7. Tick **Correct** on every covenant, then **Mark verified**. **Save draft** keeps unfinished work.

Known item to fix while reviewing: AGR-007's draft lacks Total Debt to EBITDA ≤ 3.5:1, stated only in its
compliance-certificate schedule on page 55.

## Adding a new agreement

1. Copy the PDF into `golden-set/documents/` as the next free number, for example `AGR-021.pdf`.
2. Ask the development team to draft its label (they run the extractor), or create
   `golden-set/labels/AGR-021.json` by hand in the same shape as the others, with `"status": "DRAFT_UNVERIFIED"`.
3. Add a row to `golden-set/manifest.csv`, then check it on the review page as above.

## Checking a statement

Statements are checked in their label file, `golden-set/labels/FS-nnn.json`, which lists each line item in
**INR crore**:

```json
"values": { "REVENUE": 412.5, "EBITDA": 61.2, "INTEREST_PAID": 9.8, "LT_DEBT": 118.0, "ST_DEBT": 22.0,
            "TOTAL_DEBT": 140.0, "NET_WORTH": 240.0, "CASH": 14.5 }
```

1. Open the statement and note the unit it states ("Amounts in INR lakhs": divide by 100 for crore; "Amounts in
   INR": divide by 1,00,00,000).
2. Fill in the values for the latest period. `TOTAL_DEBT` is short-term plus long-term borrowings; `NET_WORTH`
   is total equity.
3. Set `"status": "VERIFIED"` and `"reviewedBy": "<your name>"`, and update the row in `manifest.csv`.

## When you are done

Send the changed files in `golden-set/labels/` and `golden-set/manifest.csv` to the development team. They run:

```bash
python golden-set/predict.py --out predictions
python golden-set/evaluate.py --predictions predictions --statuses VERIFIED
```

The release gate passes when, on your verified labels, at least 95% of thresholds are exact, at least 85% of
covenants are found, and at least 95% of statement values are correct.
