# Speaker Script — Covenant Monitoring management demo

> **Format:** each stage has **SAY** (the narration), **DO** (what to click), and **SO WHAT** (the
> management takeaway). Target run-time **12–15 minutes**. App runs on the dev profile at
> **http://localhost:9310**. Keep the hero canvas (`covenant-journey.excalidraw`) on a second screen
> and the cheat-sheet in hand.

---

## Open (30 sec)

**SAY:** "In the next twelve minutes I'll walk one covenant from *birth to breach to resolution* — and
at every step I'll show you not just what the user does, but **exactly what the system records**, so
you can see the governance built into the platform. The theme to watch for: **every decision is
tagged with where it came from, and nothing can be quietly rewritten.**"

**DO:** Show the hero canvas — four lanes (User action · Screen · Data model · Tag), eight stages left
to right. "We'll move along these eight stages."

---

## Stage 1 — Author a covenant master (1.5 min)

**SAY:** "First, a credit officer authors a covenant *once* in the central library. They give it a
name, a category — financial or non-financial — and the **condition formula**, written against named
measures like DSCR. They choose whether it's assessed automatically or manually, set a default
frequency, and define **eligibility**: which segments, industries, countries, deal types, and so on
this covenant should apply to."

**DO:** Covenant Library → **New**. Fill name, category, a simple formula (e.g. `DSCR > 1.5`), set
assessment type AUTO, frequency Quarterly, pick a couple of eligibility values. Save.

**SO WHAT:** "This is authored **once, centrally**, and the formula is validated on save — so a broken
covenant can't enter the library. In the database this is one row in **`cov_covenant_master`**, tagged
`is_custom = false`. If we later edit the formula, the old and new versions are written to
**`cov_expression_change_log`** — so we always know what the covenant *used to* say."

---

## Stage 2 — Recommend for a CP: rules + ML (2.5 min)

**SAY:** "Now I open a specific deal — a Credit Proposal — and ask the system, *which covenants belong
on this deal?* Two engines run. First, **rules**: the system matches the CP's attributes against every
covenant's eligibility. Islamic-banking has to match exactly; the other eight dimensions are all
combined with AND; and a blank or 'ALL' means 'applies to everyone'. Second, **ML**: a model suggests
the actual *threshold numbers* for this borrower, each with a **confidence score and a plain-English
reason.**"

**DO:** Open a CP → **Recommendations**. Show the suggested list; point at a `match_reason` like
*"Segment: Commercial; Industry: Technology"*. Show an ML-suggested threshold with its confidence and
rationale.

**SO WHAT:** "Every suggestion lands as a row tagged **`SUGGESTED`** in
**`cov_covenant_recommendation`**, with the human-readable reason stored. Each ML suggestion is written
to an **append-only** audit — **`cov_threshold_audit`**, tagged `source = ML`, `event = RECOMMENDED` —
**even if the banker later rejects it.** Two things for governance: the ML model only ever sees the
*borrower's* attributes, never our covenant library; and if the ML service is unavailable, the whole
thing **degrades gracefully to rules only** — the banker is never blocked."

---

## Stage 3 — Tag, adjust, add custom (1.5 min)

**SAY:** "The banker is in control. They accept the ones that fit, reject the ones that don't, adjust a
threshold if needed, and can add a **custom covenant** that lives only on this deal."

**DO:** Accept two rows, reject one. Add a custom covenant (name + formula). Show it appear tagged as
custom.

**SO WHAT:** "Each decision flips the tag: **`ACCEPTED`** or **`REJECTED`**. A custom covenant creates
its own **`cov_covenant_master`** row — but tagged **`is_custom = true`** and **owned by this CP** — and
a recommendation tagged **`MANUAL_ADD`**. So even the 'library vs bespoke' distinction is a queryable
tag, and nothing the banker declines is silently discarded — it's recorded as rejected."

---

## Stage 4 — Apply to the CP (1.5 min)

**SAY:** "Now we apply the accepted covenants to the deal. For each, the banker sets the monitoring
frequency, a start date, whether it's a condition precedent or subsequent, and internal vs external."

**DO:** Apply grid → select covenants → set frequency/start date → **Apply**.

**SO WHAT:** "Each becomes a row in **`cov_applied_covenant`**, status **`ACTIVE`**. The most important
tag here is **`threshold_source`** — **MASTER** if we used the library default, **USER** if a person
changed it, **ML** if we took the model's number. That single column answers *'where did this
threshold come from?'* for every covenant on every deal. Applying also **reconciles** the
recommendations — anything left un-actioned is auto-marked rejected — so the recommendation list can
never drift out of sync with what's actually applied."

---

## Stage 5 — Schedule (automatic) (45 sec)

**SAY:** "The moment we apply, the system schedules the monitoring automatically — no one has to
remember to set up a job."

**DO:** Show the schedule / next-run time on the covenant (Monitoring → Schedule).

**SO WHAT:** "This writes **`cov_monitoring_schedule`** tagged **`SCHEDULED`**, plus the underlying
Quartz job and cron trigger. Monitoring is **not** a manual chore — it's wired up the instant a
covenant is applied."

---

## Stage 6 — Scheduler fires, expression evaluated (automatic) (2 min)

**SAY:** "At the covenant's frequency, the scheduler wakes up and evaluates the formula. It pulls the
borrower's actual figures from the data source, aggregates them over the right window, plugs them into
the expression, and gets a verdict: **true means compliant, false means breached.**"

**DO:** Trigger an assessment (or show a recent one). Open the assessment detail: show the evaluated
expression, the computed value, and the result. Show one **COMPLIANT** and one **BREACHED**.

**SO WHAT:** "Each run inserts a row in **`cov_assessment`** — and this is the governance centrepiece:
**it is immutable.** Once completed it is never edited; a re-run creates a *new* row (that's our
US-036 control). We also store a **point-in-time snapshot of the data and the exact expression**, and
break the condition into per-comparison rows in **`cov_assessment_threshold`** so auditors don't have
to re-parse a formula string. And on the daily breach sweep, RMs get an **email summary** — every send
logged in **`cov_notification_audit`**."

---

## Stage 7 — Monitor at CP level (1 min)

**SAY:** "The RM clicks straight from that breach email into the deal's monitoring grid. They can view
by-CP or by-covenant, and see the expression, the actual value, the headroom, and the status at a
glance."

**DO:** Monitoring workspace → toggle By-CP / By-Covenant → open the breached CP → show the grid.

**SO WHAT:** "This screen is **read-only** — it writes **nothing**. The status you see is *derived* from
the latest assessment. Reviewing a breach doesn't change any data; that matters for the integrity of
the numbers."

---

## Stage 8 — Disposition: Waiver / Extension (2 min)

**SAY:** "Finally, the RM rules on the breach. They can grant a **Waiver** or an **Extension** — an
extension has to carry a new date — with a note explaining why. They can also pull an **AI
suggestion** for how to handle it and apply that."

**DO:** On the breached row → **Disposition** → choose **Extension**, set a date, add a note → save.
(Optionally open the **AI Suggestion** modal first and apply it.)

**SO WHAT:** "This writes one **`cov_covenant_disposition`** row per covenant-on-deal, tagged
**`WAIVER`** or **`EXTENSION`**, with **`status_source = USER`** — or **AI** if they took the
suggestion. And every change appends to **`cov_covenant_status_audit`**: old status, new status, the
date, the source, who did it, and when. Crucially — **the breach itself is never erased.** The red
assessment stays; the dashboard counts don't move. The waiver sits *on top* as a documented,
attributed decision. So if a regulator ever asks *'who waived this covenant, when, and on what
basis?'* — we have the answer, permanently."

---

## Close (45 sec)

**SAY:** "So in one journey: **authored once**, **recommended by rules and ML**, **applied with full
provenance**, **evaluated automatically and immutably**, and **resolved with a complete, attributed
audit trail.** Three sentences to take away:

1. **Every threshold is tagged with its origin** — library, human, or model.
2. **Assessments can never be quietly rewritten** — history is append-only.
3. **Every waiver and extension is attributed** — who, when, and why.

That's compliance monitoring that is not just automated, but **defensible.**"

---

### Timing summary

| Stage | Minutes |
|-------|---------|
| Open | 0.5 |
| 1 Author | 1.5 |
| 2 Recommend (rules + ML) | 2.5 |
| 3 Tag / custom | 1.5 |
| 4 Apply | 1.5 |
| 5 Schedule | 0.75 |
| 6 Evaluate | 2.0 |
| 7 Monitor | 1.0 |
| 8 Disposition | 2.0 |
| Close | 0.75 |
| **Total** | **~14 min** |

### If you're short on time
Cut Stage 5 to a single sentence and fold Stage 7 into Stage 8. The **must-keep** beats are
**Stage 2 (rules + ML), Stage 6 (immutability), and Stage 8 (attributed disposition)** — those carry
the whole governance story.

### Likely questions (have these ready)
- *"What if two people edit the same covenant?"* — versioned rows; edits logged to change-log / audit tables.
- *"Can the ML model act on its own?"* — No. It only *suggests*; a human accepts, and the acceptance is tagged ML and audited.
- *"What happens to a waiver when the next assessment runs?"* — A new assessment row is created; the disposition is per covenant-on-deal and is updated in place, with the change appended to the status audit.
- *"Is any of this destructive?"* — No. Assessments are immutable; provenance and status trails are append-only.
