# Statement PDF readers: benchmark and decision (6 November 2026)

**Question.** The design chose pypdf + RapidOCR as the main reader, **pdfplumber for tables in financial
statements** and Docling as the backup for hard files, but pdfplumber was never wired in. Would it help, and
is anything better? A bank needs the figures exactly right, so every engine that can run inside the bank was
measured on the same documents.

**Answer.** Yes. The reader DPS used took the first number on each line of text and got **48% of statement
values right, with 52 wrong values that would have been reported without review**. A reader built on
pdfplumber's word positions (RapidOCR's box positions on scans) with a column picker gets **99.1%** (321 of
324 values; the 3 it misses are on a blurred 100 dpi scan, which is now held for review). It is in DPS as
`dps/src/dps/extraction/statement_pdf.py`.

## The documents

24 statement PDFs in layouts seen in Indian filings and bank MIS packs, each with an answer key known by
construction (every figure is printed from the numbers the key holds). 18 were used while building the reader;
**6 more (H01–H06) were written after it was frozen** and scored without any change, to check it was not just
fitted to the first 18. They are now in the golden set as FS-011..FS-034 and gate every CI build.

| | Layout | What it tests |
|---|---|---|
| S01 | MIS page, dot leaders | the easy case |
| S02 | Balance sheet, Note column, two years, grid | note numbers next to amounts |
| S03 | P&L in lakhs, Indian digit grouping `2,75,131.00` | units, grouping |
| S04 | Cash flow, `(1,234.56)` negatives, wrapped labels | signs, two-line labels |
| S05 | Balance sheet + P&L + cash flow on three pages, ₹ million | multi-page |
| S06 | Previous year printed first | column order |
| S07 | SEBI results: three quarter columns, then the year | quarter vs annual |
| S08 | Standalone and consolidated, four columns | which entity |
| S09 | Ind AS: both debts just "Borrowings" under headings | context from headings |
| S10 | `Rs. 1,234.5 Cr` on every value, no unit header | inline units |
| S11 | Ratios, margins, growth % mixed with amounts; dashes | not amounts |
| S12 | Balance sheet then notes repeating labels | first occurrence |
| S13 | Scan of S02, 150 dpi, slight skew and noise | OCR |
| S14 | Scan of S03, 100 dpi, blur, 1.5° skew | poor OCR |
| S15 | Balance sheet and P&L side by side | two tables on a line |
| S16 | Minus signs, no thousands separators | number formats |
| S17 | Directors' report pages quoting other numbers first | narrative |
| S18 | Merged two-row header | header parsing |
| H01 | Times 9 pt, ALL-CAPS labels, "(Audited)" headers | held out |
| H02 | Three year columns, current last | held out |
| H03 | `₹` in every cell, "Previous Year" / "Current Year" | held out |
| H04 | Six-months columns before the annual ones | held out |
| H05 | Dense 8 pt page, P&L then balance sheet | held out |
| H06 | Scan of H01, 200 dpi, 1° skew | held out |

## Engines

All run locally, on the bank's own machines, and every one feeds the same DPS label matching, unit
conversion and checks (`extract_values`), so only the reading differs. They ran inside the production DPS
containers (pypdf 6.21, pdfplumber 0.11.10, RapidOCR 1.4.4, Docling 2.148), except Camelot, PyMuPDF and
img2table (scratch environment).

| | Engine | Licence | How it reads |
|---|---|---|---|
| M1 | **DPS before** (pypdf text, RapidOCR lines on scans) | BSD / Apache-2.0 | first number on each line |
| M2 | **DPS heavy pool before** (Docling page text) | MIT | first number on each line |
| M3 | pdfplumber `extract_tables` | MIT | ruled tables only, first number |
| M4 | **pdfplumber words + RapidOCR boxes + column picker (chosen)** | MIT / Apache-2.0 | table from word positions |
| M5 | Docling tables (TableFormer) + column picker | MIT | AI table structure |
| M6 | pypdf layout mode + column picker | BSD | text positions; no scans |
| M7 | Camelot (stream) + column picker | MIT | text positions |
| M8 | PyMuPDF `find_tables` + column picker | **AGPL-3.0** (needs a commercial licence) | ruled and text tables |
| M9 | img2table + column picker | MIT | image-based table detection |
| M10 | Azure OpenAI (the bank's existing deployment) on the page text | service | model reads the figures as JSON |

## Results

A value is correct within 0.5%. **Silent wrong** = a wrong value on a document DPS would have reported
without review; flagged wrong = wrong but the document is held anyway. Seconds are for the 18 documents on a
loaded laptop (Docling would be faster on a server, but not by 20 times).

| Engine | Main set (241 values) | Held-out (83 values) | Silent wrong (main / held-out) | Seconds (main) |
|---|---|---|---|---|
| **M4 pdfplumber + RapidOCR boxes** | **97.5%** | **100%** | 1 / 0 (capex sign, now fixed) | 25.6 |
| M5 Docling tables | 90.9% | 90.4% | 1 / 0 | 517 |
| M10 Azure OpenAI | 89.6% | 94.0% | 1 / 0 | 130 |
| M6 pypdf layout | 88.8% | 85.5% | 1 / 0 | 0.5 |
| M7 Camelot | 80.5% | 85.5% | 1 / 0 | 8.7 |
| M9 img2table | 68.0% | 41.0% | 11 / 1 | 30 |
| M2 DPS heavy pool before | 50.2% | 36.1% | 52 / 11 | 639 |
| M8 PyMuPDF | 49.4% | 62.7% | 0 / 11 | 13 |
| **M1 DPS before** | **48.1%** | **32.5%** | **52 / 11** | 17 |
| M3 pdfplumber `extract_tables` | 9.5% | — | 0 | 1.9 |

With the capex sign fixed (outflows stored as positive amounts), the chosen reader in DPS scores **321 of 324
values (99.1%)** across all 24 documents in the golden-set gate, and **every value right on 23 of 24
documents**. The only misses are 3 lines on S14 (blurred 100 dpi scan), which are missing rather than wrong,
and that scan is held for review because its OCR confidence (95%) is under the 97% bar.

Per-document scores: `results/score-main.txt` and `results/score-holdout.txt`.

## What each engine got wrong

- **DPS before (M1) and its heavy pool (M2):** both take the first number on a line. That is the note number
  (`Total equity 12 2,000`), a quarter (`Quarter ended`), the previous year when it is printed first, a date
  (`31 March 2026 5,400`), or a number from a sentence in the directors' report. On scans RapidOCR returns
  each label and amount as separate lines, so nothing was read at all.
- **Docling tables (M5):** good structure, but 20 times slower, missed values where headings were
  separate text, and read nothing from the poor scan (S14) or the inline-unit summary (S10).
- **Azure OpenAI (M10):** strong on the poor scan (11 of 12, best of all), but usually leaves out one line
  per document. It also returned nothing for S01, every time, because the title said "quarterly MIS" and
  it was told to skip quarters. Its output depends on the wording of the prompt and the page, and each call
  sends the statement to the cloud and costs tokens.
- **Camelot, img2table, PyMuPDF:** they split tables into cells less reliably than word positions,
  img2table produced 12 silent wrong values, and PyMuPDF's AGPL licence would need a commercial licence for
  the bank.

## Not tested

Commercial cloud services were not tested: **Azure AI Document Intelligence** (the design rules it out so
that documents never leave the bank), AWS Textract, Google Document AI and ABBYY. They need an account, and
every page would leave the bank. On these documents the chosen reader leaves at most 3 values in 324 to
gain, all on a deliberately poor scan that a person already checks, so a paid service could only help with
bad scans. The way to find out is to run this harness (`alt.py` shows how to add an engine) on the bank's real
scanned statements before deciding.

## How DPS routes a statement PDF now

1. **Each page:** a text layer (40 or more word characters) is read with **pdfplumber** word positions;
   otherwise the page is rendered at 200 dpi and read with **RapidOCR**, using its box positions,
   straightened by the boxes' own slope.
2. **Rows:** label + amounts per line; the **current annual standalone column** is chosen from the headings.
   Note, quarter, half-year, consolidated and previous-year columns are skipped, narrative is ignored,
   wrapped labels are joined, and "Borrowings" takes its meaning from the heading above it.
3. **Checks:** the usual data-quality rules, plus `LOW_OCR_QUALITY` when scanned pages were read under 97%
   mean OCR confidence. Any issue holds the statement for a person to review.
4. **Fallback:** if the table reader finds no rows (or fails on an odd PDF), the earlier path runs: pypdf
   text with RapidOCR lines; if most pages are still unreadable, the document goes to the **Docling** heavy
   pool.

Loan agreements (Flow A) are unchanged: pypdf text first, RapidOCR for pages with no text layer, Docling
when more than a quarter of the pages are still poor.

## Re-running

The documents: `golden-set/statement_pdfs/` (`export.py` lists the steps). The harness: `bench.py`
(M1–M6, run inside the DPS containers), `alt.py` (M7–M10) and `score.py`. Raw results are in `results/`.
