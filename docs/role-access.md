# Covenant Platform: who sees what (role access review, 4 Nov 2026)

## How it was checked

- Read the server rules: Core `SecurityConfig` (which roles each API needs) and `CpAccessGuard` (who may open which CP).
- Signed in as each test user against the running stack and called the main APIs: summary, CP list, open breaches, tasks, alerts, data sources, measures.
- Opened every page as each role, using the role browser tests and screenshots.

## What was wrong

1. **Admin, auditor and support saw empty pages.**
   - The server lets these roles open any CP. But Portfolio, Reports, the CP chooser and trends only listed CPs where the user is RM or WRM.
   - So these three roles saw "0 covenants" on every list page, even though they could open any CP by typing its id.
   - The probe confirmed it: summary, CP list and open breaches all returned 0 rows for admin-1, auditor-1 and support-1.
2. **Every bank role got the same seven menus.**
   - An author (who maintains the covenant library) saw Portfolio, My tasks, CP workspace, Onboarding, Documents and Reports. All were empty for them.
   - Auditors and support saw Onboarding and My tasks, where they can do nothing.
3. **Typed links opened pages the role cannot use.**
   - For example, an author could open `/settings`, `/data-sources` or `/ops/support` directly.
   - The page loaded, then its calls failed or came back empty.
4. **Authors could change data sources through the API.**
   - Data sources hold connection details for bank systems, which is an administrator's job. The UI showed the page only to admins, but the server allowed authors to change them.
5. **Links went to pages the role cannot open.**
   - "Onboard covenants" was shown to auditors.
   - The library's "Where it is used" linked authors to CP pages.
6. **Nothing told auditors and support why buttons were missing.**

## What we decided, and why

| Page | RM | Approver | Author | Auditor | Support | Admin |
|---|---|---|---|---|---|---|
| Portfolio | own CPs | own CPs | hidden | all CPs | all CPs | all CPs |
| My tasks | yes | yes | hidden | hidden | hidden | yes |
| CP workspace | yes | yes | hidden | read-only | read-only | yes |
| Onboarding | yes | yes | hidden | hidden | hidden | yes |
| Documents | yes | yes | hidden | read-only | read-only | yes |
| Covenant library | read | read | edit | read | read | edit |
| Reports | yes | yes | hidden | yes | hidden | yes |
| Measures | hidden | hidden | yes | hidden | hidden | yes |
| Document jobs, Support tools | hidden | hidden | hidden | hidden | yes | yes |
| Data sources, Settings | hidden | hidden | hidden | hidden | hidden | yes |
| Borrower portal | borrowers only | | | | | |

**Why each choice:**

- **Hide or explain.** Menu items a role cannot use are hidden, so each person sees only their own job.
- **Typed links.** A page reached by a typed address or old link shows a short explanation instead of a broken screen: what the page is for, which roles use it, who you are signed in as, how to get the role, and a link to your start page.
- **Bank-wide portfolio.** Admin, auditor and support now see every CP in Portfolio, Reports and the CP chooser. This matches what the server already lets them open, so their pages are no longer empty.
- **Alerts stay personal.** The alert bell still shows only CPs the user is RM or WRM on. Alerts mean "waiting on you", and an auditor is not the person who must act.
- **Read-only label.** Auditors and support get a "Read-only on loans" badge in the header. Its tooltip explains they can look at every CP but not change covenants, results or documents.
- **Start pages.** Each role starts on its first page: an author starts on the Covenant library.
- **Data-source writes.** Only admins can change data sources on the server now. Authors keep library and measure editing.

## What did not change

- **Server checks.** Core and DPS still check every call. The menus are a convenience, not the security.
- **The borrower portal.** Borrowers still see only the portal, and every other API refuses them.

## Tests

- `ui/src/auth/access.test.tsx` checks the menu for each role, the start pages, how paths map to pages, the read-only rule, and the explanation page.
- `ui/e2e/roles.spec.ts` checks each user in a real browser, against the matrix above:
  - their menus and hidden menus;
  - every page loads with no errors;
  - auditor and support get the explanation on Onboarding and see the read-only badge;
  - an author typing `/settings` gets the explanation;
  - an auditor sees the whole portfolio.
- Core tests check that the bank-wide view applies only to the signed-in caller's own request, so an admin request about an RM's e-mail still uses that RM's own CPs. They also check that data-source writes are admin-only.
