# Borrower portal (C1): security notes

The borrower portal lets people outside the bank sign in. This page records how it is protected today and
what must be decided before it is used with real borrowers. It is the input for the bank's security
review; nothing here replaces that review.

## How it works

- A borrower signs in through the same identity provider (Keycloak locally, Entra ID or the bank's IdP in
  production) and carries the `BORROWER` role and no bank role.
- The bank decides who sees what: an RM or approver adds the borrower's user id to a CP on the CP
  workspace (Certificates tab, **Borrower portal access**). Access is per CP and can be removed at any time.
- The UI shows a borrower-only user the portal and nothing else (`isBorrowerOnly` in `ui/src/auth/identity.ts`).

## What protects the bank's data

| Control | Where |
|---|---|
| `/api/v1/portal/**` needs the `BORROWER` role | `SecurityConfig` |
| A user whose only role is `BORROWER` is refused on every other Core endpoint (403) | `SecurityConfig.bankUser` |
| Every portal call checks the CP was granted to that user; an ungranted CP answers 404, so CP ids cannot be probed | `PortalService.requireAccess` |
| The portal shows only what is due (certificate periods and dates, covenant names). Never results, figures, headroom, notes, waivers or internal users | `PortalService.view` |
| DPS is never called with the borrower's token: Core forwards a statement with its own service identity (client credentials), and DPS refuses a borrower's token for CP data because Core's CP access check does not count portal access | `PortalService.forwardToDps`, `CpAccessGuard` |
| Files: one per request, 20 MB limit, file name stripped of any path; DPS keeps its own type and PDF checks | `PortalService.checkFile`, DPS upload validation |
| A certificate needs the signer's name and an explicit confirmation, and must match a period the bank asked for | `PortalService.submitCertificate` |
| Every submission is recorded with the borrower's user id and time | `cov_portal_submission` |
| Only the CP's RM, approver or an admin may change portal access; auditors can only read it | `PortalAccessController` |

Tests: `BorrowerAccessRuleTest`, `Phase4ServicesTest` (Core), `phase4.test.tsx` (UI) and browser test 31,
which signs in as the borrower and checks a bank API answers 403 while the portal API answers 200.

## Open before real use

1. **Identity for outsiders.** Borrowers should not be in the bank's staff directory. Decide on a separate
   realm or tenant (for example Entra External ID), with multi-factor sign-in, password policy, lockout and
   an onboarding and offboarding process. Locally, `borrower-reliance` is a test user in the same realm.
2. **Malware scanning** of uploaded files before anyone at the bank opens them.
3. **Electronic signature.** Today "signed by" is a typed name plus a confirmation box. Decide whether a
   qualified e-signature or a signed PDF check is required.
4. **Exposure.** The portal must sit behind the bank's internet-facing controls (WAF, rate limiting, DDoS
   protection), on its own host name if possible, separate from the staff UI.
5. **Notifications to borrowers** (reminders, receipts) go through the bank's mail service; check sender
   domain, SPF/DKIM and wording with compliance.
6. **Data protection.** Confirm what personal data borrowers' users give, how long submissions are kept,
   and the legal basis.
7. **Penetration test** of the portal endpoints and the role separation before go-live.
