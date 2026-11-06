# Real documents: statement and loan-agreement readers, benchmark and decision (6 November 2026)

**Question.** The synthetic benchmark (`../statement-pdf-readers/`) chose pdfplumber word positions for
statement PDFs. Does that hold on real documents downloaded from the internet: Indian and Sri Lankan annual
reports, US 10-K filings and real loan agreements? Which engine should DPS use for each kind of document, how
should it route a file, and how accurate is the result?

**Answer, in one paragraph.** On real statements the DPS table reader (pdfplumber words, RapidOCR boxes on
scans, a column picker and the catalog rules), after the fixes in this report, reads **91.9% of
hand-checked Indian and Sri Lankan values right with 1 wrong**, and **81.2% of the SEC
XBRL values** of US filings. Every other engine scored lower on India / Sri Lanka; the bank's Azure OpenAI
model scored higher on US filings (**93.0%**). Their mistakes fall on different lines, so the best
arrangement is the table reader as the source of every value, with the model as an optional second reader
whose disagreements go to review: on the US filings that cuts wrong values that would be published from
9 to 4, at the cost of reviewing about 10 values in 100. Long
reports are searched for their statement pages first (right page first for 87.6% of statements, in the first
two for 96.4%). For loan agreements the rules engine finds **56.7%** of real maintenance covenants
at **68% precision**; the model finds more (70%) but invents more (64% precision), so it can
only suggest covenants for a person to confirm. Every proposed covenant is approved by a person in
Core before it is applied.

## 1. The documents

108 files were downloaded with an honest user agent from public sources (list with URLs:
`sources.tsv`); the PDFs themselves are not committed (third-party copyright, 300 MB).

| Set | Files used | Pages (median, range) | Where from | What makes them hard |
|---|---|---|---|---|
| Indian annual reports | 23 | 182 (1-372) | company filings via tijorifinance.com | 100-370 pages, statements on 3-6 of them; standalone and consolidated; lakhs, crores, ₹ in every cell; note columns; split digits |
| Sri Lankan annual / interim reports | 10 | 121 | CSE-listed companies' own sites | Group and Company side by side, Rs. '000 or full rupees, quarters next to the year, % change columns, expenses in brackets |
| US 10-K filings | 14 | 120 | SEC EDGAR | thousands or millions of dollars, 3 years of columns, cash-flow working-capital lines named like balance-sheet lines |
| US credit agreements | 44 | 34 (4-301) | SEC EDGAR exhibits (EX-10) | covenants written as "will not permit ... to exceed", pricing grids, incurrence tests |
| Indian debenture trust deed | 1 | | public issue document | financial covenants inside a trust deed |
| Sri Lankan trust deeds and prospectuses | 5 | | issuers' sites | scanned pages (AG48: 20 of 21 scanned) |

34 of the 47 statement files and 26 of the 50 agreement files have at least one page without a text layer.
Two statement downloads were dropped (one not a statement, one duplicate: IN28 = IN07). Five agreements were
excluded from scoring because they hold no maintenance covenant a person could label with confidence.

## 2. How accuracy was measured

A value is right within 0.5% (rounding between "1,234.5" and "1,234.47"). Every reader's raw rows were
passed through the same DPS rules (`extract_values`) with the final code (`harness/rescore.py`), so only the
reading differs.

| Truth | What it is | Values | Strength |
|---|---|---|---|
| **Hand-checked** (`truth/adjudicated.json`) | 12 Indian / Sri Lankan documents: the statement pages rendered as images and every figure read by eye (standalone / Company column; lakhs and Rs. '000 converted to crore) | 172 | strongest; chosen where readers disagreed, so it is the hard part of the set |
| **SEC XBRL** (`truth/truth_us.json`) | the company's own tagged figures from the SEC companyfacts API, for the same fiscal year | 229 on 14 filings | independent of every reader |
| **Leave-one-out consensus** | for the other India / Sri Lanka documents: a value counts as truth when two of the *other* readers agree on it, so no reader marks its own work; hand values override | about 420-540 per reader | broad but weaker: two readers can agree on the same wrong column or unit |
| **Covenants** (`truth/truth_ag.json`) | 60 maintenance covenants hand-labelled in 45 agreements (family, minimum / maximum, threshold, page); pricing grids, incurrence tests, cash sweeps and release conditions excluded | 60 | strongest |
| **Statement pages** (`truth/truth_pages.json`) | the true balance sheet, P&L and cash-flow page of each report, checked on page images | 137 | strongest |

The consensus score flattered the table reader before hand-checking: on 7 documents chosen because readers
disagreed it read only 32% right (unit and column mistakes on IN21, IN22, LK10, LK13). Those are the
documents the fixes in section 5 came from. The hand-checked numbers below are after the fixes.

## 3. Engines tested

Every engine runs on the bank's own machines except Azure OpenAI (the bank's existing deployment, already
used by DPS for advisory answers). Azure AI Document Intelligence was not tested, as agreed: documents must
not go to a new cloud service.

| Code | Engine | Licence | How it reads a statement |
|---|---|---|---|
| OLD | DPS before: pypdf text, RapidOCR lines on scans | BSD / Apache-2.0 | first number on each line |
| **NEW** | **DPS now: pdfplumber word positions, RapidOCR boxes on scans, column picker, catalog rules** | MIT / Apache-2.0 | table from word positions |
| LAY | pypdf layout mode + column picker | BSD | text positions, no scans |
| AI | Azure OpenAI (the bank's deployment) on the page text | service | model returns the figures as JSON |
| DOCX | Docling (TableFormer) tables + column picker | MIT | AI table structure |
| DOCT | Docling page text + first number (the old heavy pool) | MIT | first number on each line |

Camelot, PyMuPDF and img2table were measured on the synthetic set only (section 8): they scored below the
table reader there and PyMuPDF is AGPL, so they were not run on the real set.

## 4. Results: statements

### 4.1 Finding the statement pages in a long report

| Finder | Right page first | In first two | Median time per report | Slowest | Pages sent to OCR |
|---|---|---|---|---|---|
| v1: pypdf text, headings and line items | 81.8% | 91.2% | 12 s | | 0 |
| v2: + pdfplumber on candidates, OCR every text-less page | 86.9% | 96.4% | 115 s | 1,613 s | 360 |
| v3: OCR only in the statements part | 85.4% | 94.9% | | | |
| **v4: v3 with a budget (chosen)** | **87.6%** | **96.4%** | **63 s** | **124 s** | **8** |

137 true statement pages. v4 misses the first page on IN43 (cash flow), LK05, US05, US12 and US14 (P&L);
reading the first two candidates of each statement covers all but five.

### 4.2 Reading the figures

**Hand-checked India / Sri Lanka (172 values, 12 documents):**

| Engine | Right | Wrong | Missing | Accuracy |
|---|---|---|---|---|
| **NEW: DPS table reader** | **158** | **1** | 13 | **91.9%** |
| AI: Azure OpenAI | 145 | 17 | 10 | 84.3% |
| DOCX: Docling tables | 56 | 67 | 49 | 32.6% |
| LAY: pypdf layout | 43 | 95 | 34 | 25.0% |
| OLD: DPS before | 22 | 108 | 42 | 12.8% |
| DOCT: Docling text | 19 | 107 | 46 | 11.0% |

Most of the model's 17 wrong values are scale and sign: it gave LK01 and LK04 in full rupees as if they were
crore-sized, read the previous year on LK09, and gave tax credits and charges the wrong sign.

**All 32 India / Sri Lanka documents, leave-one-out consensus (hand values override):**

| Engine | Values scored | Accuracy | Wrong | Wrong on a document with no issue raised | Missing | Seconds per document |
|---|---|---|---|---|---|---|
| **NEW** | 422 | **90.3%** | 17 | 15 | 24 | 2.9-4.3 |
| AI | 454 | 75.3% | 67 | 2 | 45 | 14.4 |
| DOCX | 479 | 47.0% | 153 | 5 | 101 | 193.5 |
| LAY | 522 | 38.5% | 207 | 25 | 114 | 0.8 |
| OLD | 538 | 27.9% | 308 | 16 | 80 | 1.5 |
| DOCT | 538 | 22.3% | 310 | 15 | 108 | 180.5 |

Each reader is scored on the values the others agree on, so the counts differ by reader.

**US 10-K filings against the SEC XBRL (229 values, 14 documents):**

| Engine | Accuracy | Right | Wrong | Wrong with no issue raised | Missing | Seconds per document |
|---|---|---|---|---|---|---|
| AI | **93.0%** | 213 | 9 | 5 | 7 | 13.5 |
| **NEW** | 81.2% | 186 | 9 | 0 | 34 | 2.5-5.0 |
| OLD | 76.4% | 175 | 24 | 1 | 30 | 0.4 |
| DOCX | 76.0% | 174 | 23 | 0 | 32 | 121.8 |
| LAY | 67.2% | 154 | 42 | 1 | 33 | 0.5 |
| DOCT | 59.8% | 137 | 23 | 2 | 69 | 103.9 |

The table reader's US gap is mostly missing values (34), not wrong ones: cash-flow lines on pages the
finder ranked second, and US line names still outside the catalog. Before this work the same reader scored
13.5% on these filings (it did not know "$" or "millions of dollars").

Seconds are per document on a loaded 8-core laptop with three benchmark jobs at once; Docling runs one
document per process because it ran out of memory on long reports.

### 4.3 The table reader with the model as a second reader

The model reads the same pages; a covenant input where the two disagree is held for a person, and the table
reader's value is the one stored.

| | Covenant-input values | Right and stored | Wrong and stored | Sent to review |
|---|---|---|---|---|
| India / Sri Lanka, table reader alone | 126 | 118 | 1 | 0 |
| India / Sri Lanka, with second reader | 126 | 105 | **0** | 14 (1 really wrong, 13 right) |
| US 10-K, table reader alone | 167 | 143 | 9 | 0 |
| US 10-K, with second reader | 167 | 132 | **4** | 16 (5 really wrong, 11 right) |

Covenant inputs: revenue, PBT, PAT, interest, depreciation, total assets, net worth, current assets and
liabilities, long- and short-term debt, cash, CFO, inventory, receivables. The table reader already reports
an issue for some wrong values (negative debt, debt that does not add up), so "wrong and stored" counts only
values on a statement that would have been published.

### 4.4 End to end on whole reports

The tables above read the statement pages cut out by hand. This is DPS as it runs in production: the
whole downloaded file (up to 372 pages) goes in, the page finder picks the pages, the reader reads them.

| Truth | Values | Cut pages | Whole report | Whole report, wrong values stored | With second reader, wrong stored / sent to review |
|---|---|---|---|---|---|
| India / Sri Lanka, hand-checked | 172 | 91.9% | **91.9%** | 1 of 126 covenant inputs | 0 / 14 |
| India / Sri Lanka, leave-one-out | 422 | 90.3% | **90.3%** | | |
| US 10-K, XBRL | 229 | 81.2% | **78.6%** | 16 of 167 covenant inputs | 4 / 23 |

Time per whole report: median 21 s, slowest 76 s (OCR included). On the US filings the whole-report run gains on
US01 and US03 and loses on US05, US07, US10 and US12 (US05 and US12 are two of the five statements where the
finder's first page is not the true one); net 6 values. The second reader catches 12 of the 16 wrong values.

Two problems the whole-report run exposed and fixed before these figures: a runner-up page in another unit
(a ten-year summary in Rs '000 behind a statement in rupees) set the unit for the whole statement, and an
interim report's quarter statement came before the six-month one. Runner-up pages in a different unit are
now skipped, ten-year summaries and quarter statements score down, and short files are read in the
finder's order.

## 5. What was corrected in DPS, and why

Each rule below came from a real page that was read wrong; `dps/tests/test_real_documents.py` holds the
shape of each page, cut down to the words that matter.

**Units** (`extraction/statement.py`)
- "All amounts are in I Lakhs": pdfplumber extracts the ₹ glyph as "I". Accepted before the unit word.
- "Rs.’000" (typographic apostrophe) and "Rs 000" (no apostrophe) are thousands. Before: read as rupees,
  every value 1,000 times too small (LK09, LK10).
- "amounts in rupee" (singular), "LKR", "$", "millions of dollars" are units.

**Columns** (`extraction/statement_pdf.py`)
- Digits spaced apart in the PDF ("4" "1,651.24") are one amount (IN22: other income read as 1.0).
- A heading over a pair of columns names both: "GROUP" over 2025 / 2024, "Quarter ended" over two dates.
  Before, only the column nearest the word got it, so the Group 2025 column won over Company 2025 (LK10) and
  the quarter over the year (LK13).
- "% Change" columns: a percentage after the amounts no longer turns the row into a sentence (LK09's whole
  P&L was dropped), and "% Change" heads no column.
- "Change + / (-)" columns score down; "30-Sep-25" gives the year 2025.
- Signatures below the statement ("Date: 29th May, 2025" under the prior-year column) head no column
  (IN27, IN46 read the previous year).
- A stray number's column (a membership or DIN number) takes no heading and is never the chosen column.
- A note column without the word "Note" is found by its values: nearly all small whole numbers, even with a
  "34.07" sub-note among them (IN21: revenue read as 25, the note number).
- "(2) Non-current liabilities": the item number opens the label, so "Borrowings" below it is long-term.
- Inside "Changes in operating assets and liabilities" a line is a movement, not a balance (US 10-Ks:
  receivables read as -1,532.9).

**Labels and values** (`extraction/statement.py`)
- "Total Non Current Assets" (no hyphen) is not total current assets (LK01).
- "Impairment reversal of trade receivables", "(Increase)/decrease in ...", "change in ..." are not balances.
- "&" is "and"; "Profit/(Loss) for the year" is the profit; "Stated capital" is share capital;
  "Total shareholder's fund" and "Equity attributable to owners of the company" are net worth, and a later
  "Total equity" replaces the attributable share when there are non-controlling interests.
- Indian "Total revenue" after "Other income" is total income, not revenue (IN27).
- "Total tax expense" replaces "Current tax" (IN21).
- A statement that brackets its expenses ("Figures in brackets indicate deductions") brackets a tax charge
  too: the tax sign follows the expense lines, so the catalog keeps a charge positive (LK04, LK09, LK10, LK13).
- Trade payables split into micro and small enterprises and the rest are added (IN21, IN22, IN44).
- Earlier in this work: US and Sri Lankan line names (net income, total stockholders' equity, trade and other
  payables...), "other ..." lines are parts not totals, profit before exceptional items is not PBT, expenses
  and outflows stored positive.

**Routing** (`extraction/statement_pages.py`, `statement_pdf.read_pdf_statement`)
- A PDF over 12 pages is searched for its statement pages first (v4 above) and only those are read, the best
  balance sheet, P&L and cash flow before the runners-up; a runner-up printed in another unit is skipped.
  A shorter file is read in the finder's order, so an interim report's period statement comes before its
  quarter statement. Before, every page of a 300-page report was read
  and a five-year summary or a notes page could win the first-occurrence rule.
- A page whose text is a broken font encoding ("(cid:12)") is read from its image with OCR.

**Second reader** (`extraction/statement_ai.py`, setting `DPS_STATEMENT_AI_CHECK`, off by default)
- With a model configured, the model reads the same pages; any covenant input it reads differently adds
  `SECOND_READER_DIFFERS` and the statement waits for a person. Its numbers are never stored. A model outage
  adds nothing, so statements are never blocked by it.

**Agreements** (`extraction/agreement.py`)
- US covenant names (consolidated / senior / first-lien leverage ratio, funded debt to EBITDA, fixed charge
  coverage, cash-flow coverage, debt to tangible net worth...).
- "will not permit X to be less than 3.00" is a floor and "to exceed 3.50" a ceiling, also when the
  negation comes before the covenant name; "equals or exceeds", "never exceed".
- Not covenants: pro forma / incurrence tests, pricing grids ("Applicable Rate", "Pricing Level"),
  excess-cash-flow percentages, incremental facilities, release and extension conditions, and ratios above
  25 (a "90 days" read as a ratio).

## 6. Results: loan agreements

45 agreements, 60 hand-labelled maintenance covenants (13 agreements have none):

| Method | Found | Missed | False proposals | False on an agreement with none | Recall | Precision | F1 | Seconds per agreement |
|---|---|---|---|---|---|---|---|---|
| **W_NEW: DPS rules now, whole document** | **34** | 26 | 16 | 2 | **56.7%** | 68.0% | 61.8% | 37 |
| S_NEW: DPS rules now, covenant section only | 32 | 28 | 6 | 1 | 53.3% | **84.2%** | 65.3% | 37 |
| S_DOC: Docling text of the section + DPS rules | 32 | 28 | 6 | 1 | 53.3% | 84.2% | 65.3% | 92 |
| S_AI: Azure OpenAI on the section | **42** | 18 | 24 | 5 | **70.0%** | 63.6% | 66.7% | 78 |
| W_OLD: DPS rules before, whole document | 23 | 37 | 17 | 4 | 38.3% | 57.5% | 46.0% | 35 |
| S_OLD: DPS rules before, section only | 22 | 38 | 4 | 0 | 36.7% | 84.6% | 51.2% | 36 |
| W_PLB: pdfplumber text, whole document (stopped after 5: 390 s each) | 3 of 13 | | 3 | 0 | 23.1% | 50.0% | | 390 |

Docling gives exactly the rules engine's result on the section (the text is the same where it matters) at
2.5 times the time, so it adds nothing for agreements with a text layer. What the rules still miss (26 covenants, W_NEW): 4 loan-to-value tests (no catalog metric);
12 where nothing of that family was proposed (DSCR, interest cover, fixed-charge cover, minimum EBITDA and
two leverage tests written in wording the rules do not know yet); 10 where a covenant of the right family
was proposed with another number or direction (a step-down schedule read at another level, two tests of one
family where DPS keeps one per metric, or a nearby number). The model finds 8 more covenants but proposes 24 that
are not covenants (pricing levels, incurrence tests, insurance amounts), so it can only be a suggestion.

Recall = labelled covenants found with the right family, direction and threshold; precision = share of the
covenants a method proposed that are real. S_ = only the pages the covenant-section finder chose (it found
the right section in about 80% of agreements); W_ = the whole document.

## 7. Decision: what DPS uses

| Document | Route | Why |
|---|---|---|
| Statement PDF, up to 12 pages | pdfplumber words on text pages, RapidOCR boxes on scans and garbled pages, column picker, catalog rules | best on every Indian / Sri Lankan measure; 2.9 s per document; runs in the bank |
| Statement PDF, longer (annual report, 10-K) | page finder v4 first, then the same reader on the chosen pages | 96.4% of statements are in its first two pages; a whole report read in about a minute |
| Statement, any | optional second reader (Azure OpenAI) on the same pages; disagreement goes to review | catches most of what the table reader gets wrong on US filings; never publishes a model number |
| Scan read under 97% OCR confidence | held for review (unchanged) | the poor scans are where every reader loses values |
| Table reader finds nothing | pypdf text lines, then the Docling heavy pool (unchanged) | Docling is 60 times slower and less accurate on real statements, so it is only the last resort |
| Loan agreement | rules engine on the whole document (section finder not used to cut pages) | finds more covenants than on the section alone; a missed section costs recall |
| Loan agreement, second opinion | the model may suggest covenants, always for a person to confirm | it finds more but a third of what it proposes is not a covenant |

Not chosen: **Docling** for statements (46.7% on India / Sri Lanka, 76% on US, 190 s per document) and
**pypdf layout** (38.7%, no scans). Both stay in DPS for what they already do (Docling for unreadable files,
pypdf for agreements' text).

## 8. Earlier synthetic benchmark (24 documents, answer keys known by construction)

| Engine | Main set (241 values) | Held-out (83 values) |
|---|---|---|
| pdfplumber words + RapidOCR boxes (DPS now) | 97.5% (99.1% with the capex fix) | 100% |
| Docling tables | 90.9% | 90.4% |
| Azure OpenAI | 89.6% | 94.0% |
| pypdf layout | 88.8% | 85.5% |
| Camelot (stream) | 80.5% | 85.5% |
| img2table | 68.0% | 41.0% |
| Docling text, first number | 50.2% | 36.1% |
| PyMuPDF find_tables (AGPL) | 49.4% | 62.7% |
| DPS before (pypdf, first number) | 48.1% | 32.5% |
| pdfplumber extract_tables | 9.5% | |

The real documents confirm the ranking at the top and show what synthetic pages could not: units printed
in odd ways, Group / Company spreads, signatures, quarters beside the year and 300-page reports.

## 9. Limits

- Hand-checked truth covers 12 of the 33 Indian / Sri Lankan documents (172 values); the rest is consensus.
- One covenant per metric: an agreement with two leverage tests (total and senior) yields one.
- No loan-to-value metric in the catalog yet.
- The US truth is the company's XBRL, which sometimes tags a slightly different line (for example interest
  paid in cash rather than interest expense); a few "wrong" values are such differences.
- Lines printed without a label (an unlabelled total under a column) cannot be read by any rules reader.

## 10. Re-running

`harness/` holds every script: `fetch.py`, `edgar_10k.py`, `edgar_credit.py` download the sources;
`find_v4.py` and `score_finder.py` the page finder; `real_bench.py` (run inside the DPS worker and heavy
containers) the statement readers; `rescore.py` re-applies the current rules; `score_hand.py`,
`score_loo.py`, `score_real.py` and `combo.py` score; `ag_bench.py` and `score_ag.py` the agreements.
Results: `results/`.
