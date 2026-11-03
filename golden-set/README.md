# Golden Set: Extraction Quality Gate

This is the labelled set used to measure covenant extraction, and the release gate for R1 (PRD goal G6).

| Path | What |
|---|---|
| `documents/` | Source documents (`AGR-nnn.pdf` agreements; `FS-nnn.*` statements to be added) |
| `labels/` | One JSON label per document |
| `manifest.csv` | Index: id, type, source file, pages, covenant count, status |
| `evaluate.py` | Scores predictions against labels; exit code 0 means the gate passed |

## Current state (2026-11-02)

- **10 agreements** (the unique sample agreements from our app; 218 pages).
- All labels are `DRAFT_UNVERIFIED`: they were **produced by our current regex extractor**, not by a person, so they show what we extract today, not the truth.
- Every draft must be reviewed by a credit analyst before it counts. The harness ignores drafts unless `--include-drafts` is passed.
- `AGR-006` has 0 covenants because one of its 2 pages has no text layer (scanned). Label it by hand; it is a useful OCR test case.
- **Missing:** at least 10 more agreements (including scanned and table-heavy ones), and at least 20 financial statements. Owner still open (decision D13).

## Review page (recommended)

A local page shows each agreement next to its draft label, so an analyst can check it quickly:

```bash
dps/.venv/Scripts/python golden-set/review.py
```

Then open http://127.0.0.1:8765. (Plain `python golden-set/review.py` works too; with the DPS virtual environment it also checks that every quote really is on its page.)

1. Type your name at the top right.
2. Pick an agreement on the left. The PDF opens at the first covenant's page; **Show page** jumps to any covenant's page.
3. For each covenant, compare it with the agreement and fix any field. A red *not found on that page* means the page number or the quote is wrong.
4. **Remove** entries that are not covenants; **Add a missing covenant** for any the extractor missed.
5. Tick **Correct** on every covenant, write step-downs, cure periods or testing basis in **Notes**, then **Mark verified**. **Save draft** keeps unfinished work (status *in review*).

The page writes `labels/AGR-nnn.json` and `manifest.csv` and applies the rules in the table below. It listens only on your own computer. Commit the changed labels when a batch is done.

## How to label an agreement (by hand)

1. Open `documents/AGR-nnn.pdf` next to `labels/AGR-nnn.json`.
2. For every covenant in the agreement, check or fix one entry:

| Field | Rule |
|---|---|
| `metric` | Our metric key (see `docs/integration/04-api-contracts.md` §6.1), e.g. `DSCR`, `DEBT_TO_EBITDA`, `NEGATIVE_PLEDGE` |
| `category` | `FINANCIAL` (numeric test) or `NON_FINANCIAL` (obligation) |
| `operator`, `threshold` | As written, e.g. "not less than 1.25x" → `>=`, `1.25`. `null` for non-financial |
| `unit` | `x`, `%`, `AED mn`, `INR cr`… as written; `null` for plain ratios |
| `frequency` | `QUARTERLY`, `YEARLY`, … if the clause says so |
| `page` | 1-based page where the covenant clause is (not a schedule/template page) |
| `quote` | Shortest exact sentence from that page that proves the term |

3. Delete entries that are not real covenants. Add any the extractor missed.
4. Set `"status": "VERIFIED"` and `"reviewedBy": "<name>"`, then update `manifest.csv`.
5. Step-downs (different thresholds by date), cure periods and testing basis (LTM / quarterly): add them in a free-text `"notes"` field for now (needed for FR-29).

## Running the gate

```bash
python evaluate.py --predictions <folder with AGR-nnn.json predictions>
```

Gate (initial, to be agreed): **threshold accuracy ≥ 0.95** and **covenant recall ≥ 0.85**, on VERIFIED labels only.
