# Covenexa user guide

Everything the application does, page by page and step by step, from signing in to the jobs that run by
themselves at night. Written for anyone who uses or checks the app: relationship managers, approvers,
covenant authors, administrators, auditors, support staff and borrowers.

- **To install and run it on one computer**, follow [`local-real-testing.md`](local-real-testing.md) first.
  Every address below (`http://localhost:8080` and so on) is the local one from that guide.
- **To operate it in production**, see [`runbook.md`](runbook.md).
- **To learn the ideas from zero**, the Covenexa beginner's guide teaches them as lessons.

## Contents

1. [What the app does](#1-what-the-app-does)
2. [Signing in, roles and the screen layout](#2-signing-in-roles-and-the-screen-layout)
3. [What every status and colour means](#3-what-every-status-and-colour-means)
4. [Portfolio](#4-portfolio)
5. [Documents: uploading and checking files](#5-documents-uploading-and-checking-files)
6. [Onboarding: choosing what to monitor](#6-onboarding-choosing-what-to-monitor)
7. [CP workspace: one loan in detail](#7-cp-workspace-one-loan-in-detail)
8. [One covenant in detail](#8-one-covenant-in-detail)
9. [Waivers and extensions (maker-checker)](#9-waivers-and-extensions-maker-checker)
10. [Certificates, borrower reminders and the borrower portal](#10-certificates-borrower-reminders-and-the-borrower-portal)
11. [My tasks and the alert bell](#11-my-tasks-and-the-alert-bell)
12. [Reports and Ask AI](#12-reports-and-ask-ai)
13. [Covenant library](#13-covenant-library)
14. [Measures](#14-measures)
15. [Data sources](#15-data-sources)
16. [Settings](#16-settings)
17. [Document jobs](#17-document-jobs)
18. [Support tools](#18-support-tools)
19. [What the app does by itself (the timetable)](#19-what-the-app-does-by-itself-the-timetable)
20. [Everything you can start by hand](#20-everything-you-can-start-by-hand)
21. [The e-mails the app sends](#21-the-e-mails-the-app-sends)
22. [When something goes wrong](#22-when-something-goes-wrong)
23. [Words used in the app](#23-words-used-in-the-app)

---

## 1. What the app does

A bank lends money under a **loan agreement**. The agreement contains **covenants**: promises the
borrower must keep, such as "the debt service coverage ratio (DSCR) shall not be less than 1.25" or
"the borrower shall send audited accounts within 180 days". Covenexa watches those promises for every
loan, tells the right people when one is broken, and keeps a record of every decision.

The life of one loan in the app:

```
 Upload the loan agreement ──► The app reads it and proposes covenants (with page and quote)
          │                                   │
          │                     A person checks and approves them (Documents)
          ▼                                   ▼
 Onboarding: pick which covenants to monitor for this CP, apply them
          │
          ▼
 Figures arrive: the borrower's financial statements (uploaded, or sent through the borrower portal)
 and the bank's own systems  ──►  the app checks each covenant against its rule
          │
          ▼
 Result: kept, breached, could not be checked, or waiting for a person
          │
          ├── Breached ──► waiver or extension (RM asks, approver decides), letters, escalation
          ├── Tasks and alerts for the RM and approver
          └── Every morning at 06:00: a check of everything due, and one summary e-mail per RM and approver
```

Two services do the work behind the screens:

- **Core** keeps the covenants, checks them, schedules them, holds the results and sends the e-mails.
- **DPS** (Document Processing Service) reads uploaded agreements and statements, proposes covenants,
  extracts the figures from statements, and answers AI questions.

A **CP** (credit proposal) is one loan facility. Every page works per CP.

## 2. Signing in, roles and the screen layout

### Signing in

1. Open the app (locally **http://localhost:8080**).
2. You are sent to the bank's sign-in page (locally Keycloak; in production Microsoft Entra ID).
3. Type your user name and password and press **Sign in**.
4. You land on the first page your role can use (for most people the **Portfolio**).
5. To leave, press **Sign out** in the top right. It ends the sign-in session too.

Locally every test user has the same password, the `KC_DEMO_PASSWORD` value in `deploy/.env`.

### Roles

| Role | Local test user | What the role is for |
|---|---|---|
| RM (relationship manager) | `rm-anil`, `rm-priya` | Looks after their own CPs: uploads documents, onboards covenants, records results, asks for waivers |
| Approver (WRM) | `wrm-meera`, `wrm-vikram` | Same CPs as their RM; approves or rejects waivers and extensions; enters figures by hand |
| Author | `author-1` | Writes and changes the covenant library and the measures |
| Admin | `admin-1` | Everything, plus data sources and settings |
| Auditor | `auditor-1` | Sees everything, changes nothing ("Read-only on loans") |
| Support | `support-1` | Sees everything, read-only on loans; restarts document jobs and uses the support tools |
| Borrower | `borrower-reliance` | Only the borrower portal, for the CPs an RM has shared |

An RM sees **only their own CPs**. Opening another RM's CP (for example by typing its address) shows
"not found": the app does not even confirm it exists.

### Which pages each role sees

The menu on the left shows only the pages your role uses, in four groups:

| Menu group | Page | RM | Approver | Author | Admin | Auditor | Support |
|---|---|---|---|---|---|---|---|
| Monitor | Portfolio | ✓ | ✓ | | ✓ | ✓ | ✓ |
| Monitor | My tasks | ✓ | ✓ | | ✓ | | |
| Monitor | Reports | ✓ | ✓ | | ✓ | ✓ | |
| Credit proposals | CP workspace | ✓ | ✓ | | ✓ | ✓ | ✓ |
| Credit proposals | Onboarding | ✓ | ✓ | | ✓ | | |
| Credit proposals | Documents | ✓ | ✓ | | ✓ | ✓ | ✓ |
| Setup | Covenant library | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Setup | Measures | | | ✓ | ✓ | | |
| Setup | Data sources | | | | ✓ | | |
| Setup | Settings | | | | ✓ | | |
| Operations | Document jobs | | | | ✓ | | ✓ |
| Operations | Support tools | | | | ✓ | | ✓ |

If you open a page that is not for your role, the app says which page it is, who uses it, and links
back to your start page. Core and DPS check every request themselves as well, so hiding a page is not
the only protection.

### The top bar

- **Covenexa** logo on the left; on a phone, the ☰ button opens the menu.
- **Read-only on loans** badge: shown to auditors and support staff; you can look at every CP but not
  change covenants, results or documents.
- **Alert bell** 🔔: the things that need you now, with a red count (see section 11).
- **Light / dark** switch.
- Your **user name and roles**, and **Sign out**.

## 3. What every status and colour means

### Result of a check

| Shown as | Meaning |
|---|---|
| **Kept** / Compliant (green) | The figure meets the rule |
| **Breached** (red) | The figure breaks the rule |
| **At risk** (orange) | Kept, but the headroom is smaller than the warning set in Settings (10% by default): e.g. DSCR 1.60 against a floor of 1.50 |
| **Could not be checked** / Error (grey) | A figure was missing, too old, or the source failed; the reason is shown. Missing data never counts as a breach |
| **Awaiting review** / Record result (yellow) | A manual covenant (for example "insurance renewed"): a person must look and record the result |
| **Not applicable** | The covenant does not apply for this period |
| **Not assessed** | No check has run yet |

### Status of a covenant after a breach

| Shown as | Meaning |
|---|---|
| Breached | Open breach, nothing decided yet |
| Waiver requested / Extension requested, **Awaiting approver** | The RM asked; an approver must decide |
| Waived | The bank agreed to ignore this breach |
| Extended (with a date) | The borrower has until that date to fix it |
| Rejected / Withdrawn | The request was turned down, or the RM took it back; the breach stays open |

### Timing

| Shown as | Meaning |
|---|---|
| Next due / Next check | When the covenant is checked next |
| Due soon | Within the "due soon" window (7 days by default) |
| Overdue | Its check date has passed without a result |
| Not started / Ended | Before the covenant's start date / after its end date |
| Paused | Monitoring stopped by hand (section 8) |

### Documents

| Shown as | Meaning |
|---|---|
| Queued / Being read | Waiting for, or inside, a document reader |
| Heavy | A scanned or hard PDF moved to the slower, stronger reader (OCR); this is normal |
| **Waiting for review** | Something read from the file needs a person: low confidence, or a check found an issue |
| Done / Published / Reported | Read; covenants published or figures sent to Core |
| Failed (with a reason) | Could not be read; the reason says what to do (e.g. "This PDF is password-protected") |
| Removed | Taken out of use; kept for the audit trail |

A proposed covenant below 80% confidence, or with any issue (for example the quote was not found on the
page), always waits for a person.

## 4. Portfolio

**Menu → Monitor → Portfolio.** Your home page: the covenant position of every CP you look after.

1. The **numbers at the top**: Covenants monitored, Breached, Could not be checked, Awaiting review.
   Click **Breached** (or another count with a problem) to show only the CPs it counts.
2. **Covenant health by CP**: a bar per CP, coloured by result.
3. **Your next tasks**: the first few tasks waiting on you. **Open My tasks** shows all of them.
4. **Overdue and due in the next 30 days**: checks coming up or missed.
5. **All credit proposals** table: Borrower, Segment, and for each CP how many covenants are Applied,
   Kept, Breached, in Error, Awaiting review and Waived / extended.
   - Type in **Filter by CP id or borrower** to narrow the list.
   - Use **Show** and **Sort by** to choose which CPs and in what order.
   - Click a CP to open it in the CP workspace.
6. **Review the breaches** (shown when something is breached) lists the breached CPs first.

An approver sees the same CPs as their RM. Admin, auditor and support see every CP in the bank.

## 5. Documents: uploading and checking files

**Menu → Credit proposals → Documents.** Loan agreements, financial statements and evidence for a CP.

### What the page shows

- Counts at the top: **Documents in use**, **Loan agreements**, **Financial statements**, **Evidence**,
  **Waiting for review**.
- **Waiting for your review**: files whose results need you. Open one to check and approve it.
- **By credit proposal**: per CP, its agreements, statements, latest period, last upload, and what it
  **needs** (for example "Upload the agreement" or "Up to date").

### Upload a file

1. Open the CP (pick it from the list or type its id; the address becomes `/documents/<CP id>`).
2. Choose the **File**: PDF, Excel (`.xlsx` or old `.xls`) or CSV, up to 50 MB; a PDF up to 500 pages.
   Longer PDFs are refused with "Split it and upload the parts".
3. Choose the **Type**:
   - **Loan agreement** — the app reads the covenants from it.
   - **Financial statement** — the app reads the figures (revenue, EBITDA, debt…). Also enter the
     **Period end** (for example 31 March 2026).
   - **Evidence** — supporting files, stored as they are.
4. For an agreement that changes an earlier one, pick the earlier one in **Amendment of (optional)**.
5. **Linked credit proposals**: one agreement can cover several CPs; add each CP id.
6. Press **Upload**. "Uploaded. Processing has started." The file shows in the list at once and is
   read in the background, usually within a minute (a scanned PDF takes longer).

The same file uploaded twice is recognised and not stored twice.

### Check what was read from an agreement

1. Click the file. The **terms table** lists every covenant found: Term, Rule (in Core's formula
   language, for example `DSCR >= 1.25`), Page, Quote, Confidence, Status.
2. Click a row's page to see the **source page** of the PDF with the quote highlighted.
3. For each term:
   - **Approve** — it is right; it becomes a recommendation for onboarding.
   - **Edit** — correct the **Rule (Core formula)** and add a **Note**, then **Save**; then approve it.
   - **Reject** — it is not a covenant, or it is wrong.
4. When every term is decided, press **Review done**. The document job continues.

Some rows are **information only**, not covenants: the testing basis (quarterly, "last twelve
months"), step-downs, cure or grace periods and definitions such as how EBITDA is defined. They help the
reviewer and the RM, and are never applied as covenants.

Clauses about the security, such as minimum security cover, minimum collateral value and an
asset-sale limit, are always held for review, because a credit person must decide whether they are
ongoing covenants.

### Amendments

When the file was uploaded as an **Amendment of** an earlier agreement, its page says "This is an
amendment of an earlier agreement" and shows **Changes made by this amendment**: each covenant whose
rule changed, before and after. The amendment's covenant replaces the earlier one in onboarding;
covenants it does not mention stay as they were.

If the CP **already monitors** that covenant, the change is not applied by itself: open the covenant in
the CP workspace → **Schedule and changes** → enter the new rule → **Save rule** (section 8).

### Check what was read from a statement

1. Click the statement. Its **line items** show each figure with where it came from (the page of a PDF,
   or the cell of a spreadsheet, such as `PL!B12`) and its state.
2. Tick **Show lines not found** to see items the reader expected but could not find.
3. Correct any value and press **Save corrections**. "Covenants using them are checked again."
4. Data-quality checks run on every statement (the balance sheet balances, the period is right, the
   signs make sense, required items are present). A statement that fails one waits for review.

When a statement is reported, Core re-checks every covenant that uses its figures, within seconds.

### Ask about this agreement

On an agreement's page, type a question in **Ask about this agreement** (for example "What does it say
about paying dividends?") and press **Ask**. The answer quotes the agreement; click a page number to see
that page. If the agreement does not say, the answer says so.

### Change links, remove or replace a file

- **Applies to CPs** → add or remove CP ids → **Save links**.
- **Remove or replace** → optionally choose a **Replace with** file → write **Why** (for example
  "Uploaded to the wrong CP") → confirm. The file is taken out of use but kept for the audit trail; its
  figures stop being used, any earlier figures for the same period come back, and the covenants that
  used them are checked again.

## 6. Onboarding: choosing what to monitor

**Menu → Credit proposals → Onboarding.** Decide which covenants a CP is monitored for.

### The onboarding queue

The queue lists every CP with its **Stage** and **Next step**:

| Stage | Meaning | Next step |
|---|---|---|
| No agreement yet | Nothing uploaded | Upload the agreement (Documents) |
| Agreement to check | Terms wait for review | Review it in Documents |
| Ready to onboard | Reviewed terms are ready | Open it and apply covenants |
| Onboarded | Covenants are monitored | Nothing |

### Apply covenants to a CP

1. Open the CP (the address becomes `/onboarding/<CP id>`).
2. **Proposed covenants** lists, in one table:
   - covenants **from the agreement** (with the page, quote and confidence),
   - **library matches** (covenants the bank's rules say fit this kind of loan, with the reason),
   - covenants **already applied**.
   Use the filters at the top (Proposed, From the agreement, Library matches, Already applied, Ticked
   to apply) and **Search covenants**.
3. Tick each covenant to monitor.
4. **Rule**: leave it empty to use the library rule, or type this CP's own rule (for example
   `DSCR >= 1.30` when this loan's agreement says 1.30). A rule from the agreement is filled in for you.
5. **Frequency**: how often it is checked (daily, weekly, monthly, quarterly, yearly).
6. Optional: **Suggest thresholds** asks the model for a suggested rule per covenant, with the reason.
   You decide; nothing is applied without you.
7. Press **Apply**. "N covenant(s) applied." Each covenant gets its schedule and is checked from then on.

A covenant with **Manual check** (for example "insurance policy renewed") is not calculated: a person
records its result each time (section 8).

## 7. CP workspace: one loan in detail

**Menu → Credit proposals → CP workspace**, then pick a CP with **Go to a CP: borrower or id** (or
click a CP anywhere in the app). The address becomes `/cp/<CP id>`.

### At the top

Numbers for this CP: **Covenants**, **Breached**, **At risk**, **Could not be checked**, **Next check**.

### Tab: Covenants

One row per monitored covenant: Covenant, **Rule**, **Actual** (the latest figure), **Headroom** (how
far from the limit), **Result**, **Status**, **Next due**. The buttons on a row depend on its state:

| Button | Shown when | Section |
|---|---|---|
| **Waive / extend** | The covenant is breached and you are its RM | 9 |
| **Awaiting approver**, **Withdraw** | A waiver or extension was asked for | 9 |
| **Approve** / **Reject** | You are an approver and a request waits | 9 |
| **Record result** | It is a manual covenant waiting for a result | 8 |
| **Onboard covenants** | The CP has no covenants yet | 6 |

Click a covenant's name to open it (section 8).

### Tab: Status history

Every change to every covenant on this CP: **When**, **Change** (for example Breached → Waiver
requested) and **Note**. Nothing is ever deleted.

### Tab: Period comparison

The statement figures side by side, one column per period, with a short trend (advisory). Choose the
**Periods** and press **Download Excel** for the spreadsheet.

### Tab: Certificates

Compliance certificates the borrower must send, borrower reminders and portal access (section 10).

### Tab: Manual values

Only an **approver** can enter a figure by hand, for when no document or system has it:
1. Choose the **Line item**, enter the **Value** (INR crore) and its **Period end**.
2. Write **Why (required)**.
3. **Save value**. "Value saved; covenants using it will be re-assessed."

Others see "Only an approver can enter values by hand."

### Tab: AI suggestions

Press **Get AI suggestions**. Core sends this CP's covenants and figures to the AI suggestion service,
which suggests a status per covenant with its confidence and reasoning. By default that is the bank's own
service (a stand-in on the local stack); with `COVENANT_AI_PROVIDER=dps` it is DPS on Azure OpenAI. It is
advice only: nothing changes unless a person acts. If the service is not available, it says so.

### Tab: Cycles

The earlier and later cycles (renewals) of the same loan, so you can follow a covenant across them.
"This loan has no earlier or later cycles" when there are none.

## 8. One covenant in detail

Click a covenant's name in the CP workspace. A panel opens on the right.

### Check now

Press **Check now** to check this covenant immediately with the latest figures. The new result is added
to its history. (It is also checked by itself on its schedule, section 19.)

### Tab: Trend

The results over time: a chart once there are two or more numbered results, and a table of **When**,
**Value**, **Result** and **Note**. If the figures came from Core's stored copy because the source could
not be read, the result carries the note *"Data may be stale: the latest refresh of … failed, so these
figures come from the stored copy of …"*.

### Record result (manual covenants)

1. Press **Record result**.
2. Choose the result: **Met**, **Not met** (a breach) or **Not applicable**.
3. In **What you checked**, describe it (for example "Insurance policy 123 renewed until 31 March 2027,
   copy attached").
4. **Save result**. "Result recorded."

### Tab: Evidence

1. **Add evidence** → choose one or more files. "Evidence added."
2. The list shows each **File**, **Added by** and when; **Remove** takes one away.

### Tab: Schedule and changes

- **Rule for this CP**: change this CP's rule (for example after an amendment), write **Why**, then
  **Save rule**. "Rule saved. It applies from the next check." **Use the library rule** goes back to the
  bank's standard rule.
- **How often**: daily, weekly, monthly, quarterly, yearly, or custom. For custom, write a **Custom
  schedule (cron)** in Quartz form (for example `0 0 9 15 * ?` = 9:00 on the 15th of every month). Press
  **Save schedule**.
- **Monitoring**: **Pause monitoring** stops the scheduled checks (for example while a loan is being
  restructured); **Resume monitoring** starts them again.
- **Changes to this covenant**: every change, **What**, **From → to**, who and when.

### Letters

When they fit the covenant's state, buttons download a Word letter to edit and send:
**Breach notice** (breached), **Waiver letter** (waived), **Extension letter** (extended).

## 9. Waivers and extensions (maker-checker)

When a covenant is breached, the bank can **waive** it (ignore this breach) or **extend** the deadline.
One person asks (the **maker**, the RM) and another decides (the **checker**, the approver).

### The RM asks

1. CP workspace → the breached covenant → **Waive / extend**.
2. Choose **Waiver** or **Extension**. For an extension, choose **Extended to** (the new date).
3. Write the **Reason**. The bank's credit-policy reference for this covenant is shown to help.
4. **Submit**. The status becomes **Awaiting approver**, and the approver gets a task and an alert.
5. While it waits, the RM can press **Withdraw** ("Withdraw this request?") to take it back.

The RM cannot approve their own request: there is no button, and Core refuses the call as well.

### The approver decides

1. **My tasks** (kind *Approve*) or the alert bell → open the covenant.
2. Read the reason and the figures.
3. **Approve** → status *Waived* or *Extended*; or **Reject** → the breach stays open.
4. The **Status history** keeps both steps, with who and when.

If an administrator switches off **Waivers and extensions need an approver (maker-checker)** in Settings,
the RM's request takes effect at once.

## 10. Certificates, borrower reminders and the borrower portal

### Certificate requirements (RM)

CP workspace → **Certificates**:

1. **Add requirement**: tick **Require period end** if the certificate is for a period, set **Due by**,
   save.
2. Each requirement shows its **Period end**, **State** (received, overdue, or waiting) and **Files**.
3. To upload a certificate the borrower sent you: choose the **Certificate file**, choose **For period**,
   press **Upload**. "Certificate uploaded."
4. **Delete** removes a requirement.

### Reminders to the borrower

On the same tab, **Reminders to the borrower**:

1. Enter the borrower's **Contact name** (for example "Ms Rao, CFO") and **E-mail**.
2. **Days before due**: when the first reminder goes.
3. Tick **Reminders on** and **Save**. "Borrower contact saved."

The app then e-mails that person once, that many days before a certificate is due, and once a week
while it is overdue. The **Sent** list shows each reminder with its kind and outcome.

### Give a borrower portal access

On the same tab, **Borrower portal access**: type the borrower's user (for example `borrower-reliance`)
and press **Give access**. "Portal access given." **Remove** takes it away.

### The borrower portal (borrower)

A borrower signs in like everyone else and sees only the **Borrower portal**:

1. **What the bank is waiting for**: each certificate due, with its state. "Nothing is due" when clear.
2. **What the bank checks, and when next**: the covenants on their loan and the next check date.
3. **Send a compliance certificate**: choose **For the period ending**, choose the **Signed certificate
   (PDF)**, type **Signed by (your full name and title)**, tick the confirmation ("I confirm this
   certificate is true and complete…"), press **Send to the bank**. "Certificate sent to the bank.
   Thank you."
4. **Send a financial statement**: choose the file (PDF, XLSX, XLS or CSV) and the **Period end**, press
   **Send to the bank**. "Statement sent to the bank. It is being read now." It is read exactly like an
   upload by the RM, and the covenants are checked when the figures are in.
5. **What you sent**: everything sent so far.
6. **Sign out**.

If no RM has shared a loan: "The bank has not shared any loan with you yet."

## 11. My tasks and the alert bell

### My tasks

**Menu → Monitor → My tasks.** Work assigned to RMs and approvers. The app creates and closes these
tasks by itself (every 5 minutes, section 19), so the list stays in step with the covenants.

| Kind | Created when | Who |
|---|---|---|
| **Approve** | A waiver or extension waits for a decision | Approver |
| **Breach** | A covenant is breached and nothing is decided | RM |
| **Overdue check** | A covenant's check date passed without a result | RM |
| **Record result** | A manual covenant waits for a person | RM |
| **Certificate** | A compliance certificate is due or overdue | RM |

1. Choose **Mine**, **All on my CPs** or **Closed**; filter by **Any kind**; search by task, CP or
   borrower; tick **Group by CP** to group them.
2. Click a task to open its CP and covenant.
3. **Update**: change **Assigned to** (a user id, for example `rm-anil`), **Due by**, and write a
   **Note** ("What has been done so far"). **Save**.
4. **Mark done** closes it. A task whose cause is gone (for example the breach was waived) closes by
   itself.

### The alert bell

The 🔔 in the top bar lists what needs you now: **to approve**, **breach**, **overdue**, **to record**
and **at risk**. The red number counts the urgent ones (everything except *at risk*). Click an alert to
go to it. "Nothing needs you right now" when clear.

## 12. Reports and Ask AI

**Menu → Monitor → Reports.**

### Numbers and charts

- **Breach rate**, **Open breaches**, **Due for escalation** (open longer than the days you choose,
  14 by default), **Could not be checked**.
- **Breach rate** chart, **By segment** or **By sector**.
- **Exposure by sector**: a treemap of the bank's exposure (₹ crore) per sector, coloured by breach rate.
- **Check results by month**: checked, breached and errors per month for the last 12 months.

### Open breaches

1. The table lists every open breach in your portfolio: Covenant, Result, Status, **Open since**,
   **Days**, **Promise** (an extension date, if any), and **Escalate** for those open longer than
   **Escalate after** days. Change that number to see more or fewer.
2. Below it, **Covenants with an error** are grouped by CP.
3. **Download Excel** or **Download PDF** for the management report.

### Your reports (build, save and e-mail)

1. **Build your own report**: choose the **Columns**, **Only these results** (for example only
   breached), the **Business segment**, and **CP contains** (part of a CP id or name).
2. The rows show at once. **Excel** downloads them.
3. To keep it: give it a **Report name** (for example "Weekly breaches"). To have it sent, tick
   **E-mail it**, enter **To (comma-separated)** addresses and choose how often (**Never**, **Daily**,
   **Weekly** or **Monthly**). Save. "<name> saved."
4. **Saved report** lists your reports with **E-mailed**, **Last sent**, **Excel** and **Delete**.
   Due reports are e-mailed by the 06:00 run (section 19).

### Ask AI

In **Ask about your portfolio**, type a question (for example "Which of my covenants are breached?"
or "Which breaches should I escalate?") and press **Ask**. The answer is written by the AI model from **your CPs
only**, citing the records it used. Another RM's CPs are never used. Without an AI key, or when the model
takes longer than a minute, a rules-based answer is given instead. The same applies to questions about one
agreement.

## 13. Covenant library

**Menu → Setup → Covenant library.** The covenants the bank can apply. Everyone can read it; authors
and admins change it.

### Find and export

1. **Search name or description**, filter by **Category** (financial, non-financial), tick **Include
   switched-off covenants** to see old ones.
2. Click a covenant to see **Where it is used** ("On N loan(s), M still monitored") and **Changes to the
   rule** (When, From → to).
3. **Download Excel** exports the list you see.

### Create a covenant (author, admin)

1. **New covenant**.
2. **Name** (for example "Minimum Current Ratio"), **Financial** or **Non-financial**, and
   **Description** ("What the borrower must do, in plain words").
3. **Checked by the app** — write the **Rule** in Core's formula language, using measure codes (for
   example `CURRENT_RATIO >= 1.33`); or **Checked by a person** — write **What to check**. A
   non-financial covenant is checked by a person.
4. **How often**, or a **Custom schedule (cron)**.
5. **Credit policy reference** (shown to the RM when they ask for a waiver).
6. **Business segments**: leave empty for all, or add segments such as `CORPORATE`.
7. Save.

### Change or switch off a covenant

- **Edit** → change and save. When the **rule** changes, every CP that uses the library rule is
  checked again at once: "Checking the new rule on N CP covenant(s) now. Each CP page shows its result
  as it finishes." The change is logged.
- **Switch off**: it can no longer be applied to new loans. "<name> switched off. Loans already using
  it keep it."

## 14. Measures

**Menu → Setup → Measures** (author, admin). A **measure** is one figure a covenant rule can use, such as
DSCR or revenue, and says where the app reads it from.

1. **New measure**.
2. **Code**: the name used in rules, letters, digits and `_` (for example `FIXED_CHARGE_COVER`).
3. **Name** and **Description**.
4. **Where the figure comes from**: the data-source table, and the **Column** in it.
5. **When there are several values**: which one to use (for example the latest).
6. **Type** and **Unit** (for example `x`, `%`, `INR_CR`).
7. **Save measure**. "<code> saved."

**Edit** changes one; **Switch off** retires it. Search with **Search code or name**.

## 15. Data sources

**Menu → Setup → Data sources** (admin). The databases and services the app reads covenant figures
from. Each measure points at a table here.

1. **New data source**: **Name**, **Description**, the **Type** (SQL Server database or REST service),
   and its **Connection settings** (**Add setting** for each). A password is never typed here: it is
   stored in the secret store and referenced by name.
2. **Save**, then **Test** to check the connection. "<name> saved. Use Test to check the connection."
3. **Add table**: **Schema** (for example `dbo`), **Table** (for example `cp_ratios`), the **CP id
   column** and the **As-of date column**. Every other column holds one figure. Names must be plain SQL
   identifiers. **Save table**. "Table saved. Measures can now point at it."
4. **Edit** or **Switch off** a source.

The **Document Processing Service (statements)** source is DPS itself: figures read from uploaded
statements reach covenants through it.

## 16. Settings

**Menu → Setup → Settings** (admin). These apply to the whole bank and take effect on the next check.

| Setting | What it does | Local value |
|---|---|---|
| Fiscal year starts in month | 4 means April to March | 4 |
| Periodic windows include the current period | Whether "last 4 quarters" counts the quarter in progress | off |
| 'Due soon' window (days) | How early a coming check shows as *due soon* | 7 |
| Upcoming window (days) | How far ahead "due in the next … days" looks | 30 |
| 'At risk' warning: headroom below this % of the limit | 10: a DSCR of 1.60 against a floor of 1.50 is at risk; 0 switches it off | 10 |
| Waivers and extensions need an approver (maker-checker) | On: an approver must decide (section 9) | on |
| Reporting currency (ISO code) | The currency amounts are reported in | INR |

Press **Save settings**. "Settings saved."

**FX rates**: to add one, enter **From**, **To**, **Date** and **Rate** and save. Amounts in other
currencies are converted with the rate for their date. **Delete** removes a rate.

## 17. Document jobs

**Menu → Operations → Document jobs** (support, admin). Every file DPS reads is a **job**.

1. Filter by **Job status** (queued, running, needs review, failed…).
2. Each job shows its **Flow** (agreement or statement), **Status**, **Pool** (normal or heavy),
   **Attempts** and the **Error**, if any.
3. **Retry** restarts a failed job after the cause is fixed.

A job that fails for a passing reason (the database was busy, a network blip) is retried by itself, up to
3 attempts. One that fails for a lasting reason (a damaged file) stops at once with a reason.

## 18. Support tools

**Menu → Operations → Support tools** (support, admin). First paste the **Support token** (a separate
secret the support team holds) and press **Use token**; without it the tools are refused.

### Tab: Diagnose a CP

1. Type a **CP id or borrower name** and press **Diagnose**.
2. For each covenant: its **Rule**, whether the **Rule valid**, and whether the **Figures found**.
3. **Re-check** runs that covenant's check now. "Re-checked: <result>."
4. **Reload data** fetches the CP's figures from their sources again, ignoring the stored copy.
5. **CPs with data errors, most first** lists where to look.

### Tab: Sent e-mails

What Core sent to the bank's e-mail service: **When**, **To**, **Copy to** (the approver), the
**CPs** it covers and the **Outcome**. Click one to see the template and the rows it fills in. The
buttons start the e-mail jobs by hand (section 20):

- **Send today's e-mails now** — runs today's covenant check and sends the RM/approver summaries and
  the escalation digest. It asks to confirm, runs in the background, and says when it is done: "N
  covenant(s) checked, M e-mail(s) sent".
- **Send borrower reminders now** — "Borrower reminders: N sent (M contact(s) checked)".
- **Send scheduled reports now** — "Scheduled reports: N sent".

### Tab: Data source health

Every data source: **Reachable**, **Response** time, **Tables**, **Staged rows** (the stored copy) and
**Last read**. **Check again** re-tests it.

### Tab: Audit log

Choose what it is **About** (**A CP**, **A covenant on a CP** or **Exchange rates**) and an optional
**Id**, press **Show**: every action with **When**, **Action**, **By**, **Outcome** and **Detail**.
Deletes are recorded here too: a removed compliance certificate (`DELETE_CERTIFICATE`), certificate
due date (`DELETE_CERTIFICATE_DUE_DATE`) or evidence file (`DELETE_EVIDENCE`) appears under the CP; a
deleted exchange rate (`DELETE_FX_RATE`) appears under **Exchange rates**.

### Tab: Loan system sync

The nightly sync with the bank's loan system (who owns each CP, and loans that have closed). **Run the sync now** starts
it by hand: "N CP(s) checked, M changed, K closed". The list shows each change, **What** and
**From → to**.

## 19. What the app does by itself (the timetable)

All times are the server's time zone, **India Standard Time** (`COVENANT_TIME_ZONE=Asia/Kolkata`).

| When | What runs | Details |
|---|---|---|
| **Every 5 minutes** | Task sync | Creates, updates and closes the tasks and alerts of every CP (section 11) |
| **02:00–02:59** on each covenant's due day | The covenant's own scheduled check | Daily covenants every day; weekly on Mondays; monthly on the 1st; quarterly on 1 January, April, July and October; yearly on 1 January; custom by its cron. Each covenant has its own minute and second within the hour, so they do not all start at once. A trigger that fires late is skipped if the covenant was already checked since it was due |
| **05:30** | Fetch ahead | Copies the figures for covenants due in the next 3 days from their sources, so the checks do not wait for slow systems |
| **06:00** | The daily run | In this order: **1.** loan-system sync (owners and closed loans); **2.** checks every covenant that is due or overdue today and was not already checked since it was due; **3.** sends **one summary e-mail per RM and approver pair**, covering all their CPs; **4.** borrower reminders; **5.** scheduled reports that are due; **6.** the escalation digest (breaches open longer than 14 days) to the credit head |
| **Seconds after new figures arrive** | Re-check | When a statement is reported, or figures are corrected or entered by hand, Core re-checks every covenant that uses them |
| **Continuously** | Document readers (DPS) | Pick up each uploaded file, read it, move hard scans to the heavy reader, retry passing failures (3 attempts), and send the results to Core |

The 06:00 time is the setting `covenant.notifications.cron` (`0 0 6 * * ?`); the escalation days and
recipients are `COVENANT_ESCALATION_AFTER_DAYS` (14) and `COVENANT_ESCALATION_RECIPIENTS`.

### How fresh the figures are

Core keeps a **stored copy** of each CP's figures. Before a check it refreshes that copy when the copy is
older than 24 hours, the check needs a later date, or the source says the figures changed. If the
refresh fails (for example DPS is down) and a stored copy exists, the check still runs on the stored copy
and says *"Data may be stale…"*. A value older than its measure's maximum age is never used: the check
then shows *could not be checked*. With no stored copy at all, it shows a data-source failure.

## 20. Everything you can start by hand

| What | Where | Who |
|---|---|---|
| Check one covenant now | Covenant panel → **Check now** | RM, approver, admin |
| Re-check one covenant | Support tools → Diagnose a CP → **Re-check** | Support, admin |
| Reload a CP's figures from the sources | Support tools → Diagnose a CP → **Reload data** | Support, admin |
| Today's check and summary e-mails (the 06:00 run, by hand) | Support tools → Sent e-mails → **Send today's e-mails now** | Support, admin |
| Borrower reminders | Support tools → Sent e-mails → **Send borrower reminders now** | Support, admin |
| Scheduled reports | Support tools → Sent e-mails → **Send scheduled reports now** | Support, admin |
| Loan-system sync | Support tools → Loan system sync → **Run the sync now** | Support, admin |
| Restart a failed document | Document jobs → **Retry** | Support, admin |
| Re-check every CP using a library rule | Covenant library → **Edit** the rule and save | Author, admin |

For testers, the 06:00 run can also be started from a script; see
[`local-real-testing.md`](local-real-testing.md), section 6.

## 21. The e-mails the app sends

| E-mail | To | When |
|---|---|---|
| Daily covenant summary | Each RM, with their approver in copy: one e-mail per pair covering all their CPs | 06:00, or **Send today's e-mails now** |
| Escalation digest | The configured recipients (for example the credit head) | After the daily run, for breaches open longer than 14 days |
| Borrower reminder | The borrower contact | Once before a certificate is due, then weekly while overdue |
| Scheduled report | The addresses saved on the report | Daily, weekly or monthly, by the 06:00 run |

Every e-mail goes through the bank's notification service and is recorded: **Support tools → Sent
e-mails** shows each one, with its template and contents.

Locally there is no real mail server. Either:
- the default stack sends to the WireMock stand-in — see the calls at
  http://localhost:9311/__admin/requests; or
- the stack started with the mail file delivers real e-mails to the Mailpit inbox at
  **http://localhost:8025** (`local-real-testing.md`, section 5b).

## 22. When something goes wrong

| You see | What it means | What to do |
|---|---|---|
| A covenant shows **Could not be checked** | A figure is missing or too old, or the rule cannot be calculated | Open it: the reason is shown. Upload the missing statement, or ask support to diagnose the CP |
| *"Data may be stale: the latest refresh of … failed"* | The source could not be read, so the stored copy was used | Usually nothing: the next check refreshes it. If it stays, ask support (Data source health) |
| A document **Failed** with a reason | It could not be read | Follow the reason (for example upload a copy without a password, or save the spreadsheet again as `.xlsx` / `.csv`) |
| A document **Waiting for review** for long | Nobody reviewed its terms | Open it in Documents, approve or reject each term, press **Review done** |
| "<page> is not part of your role" | The page is for other roles | Ask an administrator if you need it |
| A CP "not found" | It is not one of your CPs | Check the id; ask your manager if it should be yours |
| A statement is held with "LOW_OCR_QUALITY" | It was a scan and the text recognition was not confident | Check each figure against the scan, correct any, then approve |
| A statement is held with "SECOND_READER_DIFFERS" | The second reader (when your bank turned it on) read that figure differently from the table reader | Open the page shown, check that one figure, correct it if needed, then approve. The stored value is always the table reader's unless you change it |
| Ask AI answers from rules, not the model | The AI service is not configured or unreachable | Support: check the AI key |
| An e-mail did not arrive | It may have failed at the notification service | Support tools → Sent e-mails shows the outcome |

## 23. Words used in the app

| Word | Meaning |
|---|---|
| CP (credit proposal) | One loan facility; everything is organised by CP |
| Covenant | A promise in the loan agreement the borrower must keep |
| Financial covenant | One checked against figures (for example DSCR ≥ 1.25) |
| Non-financial / manual covenant | One a person checks (for example insurance renewed) |
| Rule | The covenant written in Core's formula language, for example `DSCR >= 1.25` |
| Measure | One figure a rule uses (DSCR, revenue, net worth…) and where it comes from |
| Headroom | How far the figure is from the limit |
| Library rule / rule for this CP | The bank's standard rule / this loan's own rule, which wins |
| Breach | The figure breaks the rule |
| Waiver | The bank agrees to ignore a breach |
| Extension | The borrower gets until a later date to fix a breach |
| Maker-checker | One person asks, a different person decides |
| Escalation | Telling senior credit staff about a breach left open too long |
| Compliance certificate | A signed statement from the borrower that it kept its covenants |
| Amendment | A later agreement that changes an earlier one |
| Statement | The borrower's financial statements for a period |
| Stored copy | Core's own copy of the figures, used when a source is slow or down |
| DPS | Document Processing Service: reads the files, proposes covenants, answers AI questions |
| Core | The service that holds and checks the covenants and sends the e-mails |
| RM / WRM | Relationship manager / their approver |
