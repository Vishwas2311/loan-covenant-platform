import { expect, test } from '@playwright/test';
import { closeSessions, demoPassword, envValue, sessionAs, signIn, uniqueAgreement } from './helpers';

test.afterEach(closeSessions);

// The manual script in docs/local-real-testing.md (section 6), automated. Each test signs in for real.

test('1. an RM signs in, sees the portfolio and no admin menus', async ({ page }) => {
  await signIn(page, 'rm-anil');
  await expect(page.getByRole('heading', { name: 'Portfolio' })).toBeVisible();
  await expect(page.getByText('rm-anil (RM)')).toBeVisible();
  await expect(page.getByRole('cell', { name: 'CP-1001' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Settings' })).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'Document jobs' })).toHaveCount(0);
});

test("2. an RM cannot open another RM's CP", async ({ page }) => {
  await signIn(page, 'rm-anil', '/cp/CP-1008');
  await expect(page.getByText('Not found, or you do not have access.')).toBeVisible();
});

test('3. an agreement upload is processed by DPS', async ({ page }) => {
  await signIn(page, 'rm-anil', '/documents/CP-1003');
  const name = `e2e-agreement-${Date.now()}.pdf`;
  await page.locator('input[type=file]').setInputFiles({ name, mimeType: 'application/pdf', buffer: uniqueAgreement() });
  await page.getByRole('button', { name: 'Upload' }).click();
  await expect(page.getByText('Uploaded. Processing has started.')).toBeVisible();
  const row = page.getByRole('row').filter({ hasText: name });
  await expect(row).toBeVisible();
  await expect(async () => {
    await page.reload();
    await expect(page.getByRole('row').filter({ hasText: name })
      .getByText(/^(Published|Needs review)$/)).toBeVisible({ timeout: 5_000 });
  }).toPass({ timeout: 120_000, intervals: [5_000] });
});

test('4. onboarding shows covenants extracted from agreements', async ({ page }) => {
  await signIn(page, 'rm-anil', '/onboarding/CP-1003');
  await expect(page.getByRole('button', { name: 'Show covenants: From the agreement' })).toBeVisible({ timeout: 60_000 });
  await expect(page.getByRole('heading', { name: 'Proposed covenants' })).toBeVisible();
});

test('5. a financial statement becomes values with cell references', async ({ page }) => {
  await signIn(page, 'rm-anil', '/documents/CP-1001');
  const name = `e2e-statement-${Date.now()}.csv`;
  const csv = `Amounts in INR lakhs,\nEBITDA,${500 + (Date.now() % 97)}\nShort-term borrowings,300\n`
    + 'Long-term borrowings,700\nTotal equity,2000\nCash and cash equivalents,100\n'
    + `Prepared,${Date.now()}\n`; // unique bytes: DPS treats an identical file as a re-upload
  await page.locator('input[type=file]').setInputFiles({ name, mimeType: 'text/csv', buffer: Buffer.from(csv) });
  await page.getByRole('textbox', { name: 'Type' }).click();
  await page.getByRole('option', { name: 'Financial statement' }).click();
  await page.getByLabel('Period end').fill('2026-09-30');
  await page.getByRole('button', { name: 'Upload' }).click();
  await expect(page.getByText('Uploaded. Processing has started.')).toBeVisible();
  await expect(async () => {
    await page.reload();
    await expect(page.getByRole('row').filter({ hasText: name }).getByText('Published'))
      .toBeVisible({ timeout: 5_000 });
  }).toPass({ timeout: 120_000, intervals: [5_000] });
  await page.getByRole('row').filter({ hasText: name }).click();
  await expect(page.getByRole('dialog').getByText(/!?[A-Z]+\d+|EBITDA/).first()).toBeVisible();
});

test('6-8. maker-checker: the RM requests a waiver, cannot decide it, the approver does', async ({ browser }) => {
  // Use the first of rm-anil's CPs that has a breach open for a waiver (figures change which ones do).
  const rm = await sessionAs(browser, 'rm-anil', '/portfolio');
  let cp = '';
  for (const candidate of ['CP-1001', 'CP-1005', 'CP-1007', 'CP-1002', 'CP-1003', 'CP-1004', 'CP-1006']) {
    await rm.goto(`/cp/${candidate}`);
    await expect(rm.getByRole('row').nth(1)).toBeVisible(); // the covenant grid has loaded
    if (await rm.getByRole('button', { name: 'Waive / extend' }).count() > 0) {
      cp = candidate;
      break;
    }
  }
  test.skip(cp === '', 'none of rm-anil’s CPs has a breach open for a waiver');
  const request = rm.getByRole('button', { name: 'Waive / extend' }).first();
  const covenant = (await request.locator('xpath=ancestor::tr').locator('td').first().innerText()).trim();
  await request.click();
  const dialog = rm.getByRole('dialog');
  await dialog.getByLabel('Reason').fill('e2e: lender agreed to waive this test period');
  await dialog.getByRole('button', { name: 'Submit' }).click();
  const rmRow = rm.getByRole('row').filter({ hasText: covenant });
  await expect(rmRow.getByText('Awaiting approver')).toBeVisible();
  await expect(rmRow.getByRole('button', { name: 'Approve' })).toHaveCount(0);

  // Reject rather than approve, so the covenant stays breached and the test can run again.
  const approver = await sessionAs(browser, 'wrm-meera', `/cp/${cp}`);
  const approverRow = approver.getByRole('row').filter({ hasText: covenant });
  await expect(approverRow.getByRole('button', { name: 'Approve' })).toBeVisible();
  await approverRow.getByRole('button', { name: 'Reject' }).click();
  await expect(approverRow.getByRole('button', { name: 'Waive / extend' })).toBeVisible();
  await expect(approverRow.getByText(/rejected/i)).toBeVisible();
});

test('9. reports download as Excel and PDF', async ({ page }) => {
  await signIn(page, 'rm-anil', '/reports');
  for (const [button, extension] of [['Download Excel', '.xlsx'], ['Download PDF', '.pdf']] as const) {
    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: button }).first().click();
    expect((await download).suggestedFilename()).toMatch(new RegExp(`\\${extension}$`));
  }
});

test('10. Ask AI answers about the RM portfolio with Azure OpenAI', async ({ page }) => {
  await signIn(page, 'rm-anil', '/reports');
  await page.getByRole('tab', { name: 'Ask AI' }).click();
  await page.getByRole('textbox', { name: 'Question' }).fill('Which of my covenants are breached?');
  await page.getByRole('button', { name: 'Ask', exact: true }).click();
  await expect(page.getByText('Sources:')).toBeVisible({ timeout: 120_000 });
  await expect(page.getByRole('tabpanel', { name: 'Ask AI' }).getByText(/CP-\d{4}/).first()).toBeVisible();
});

test('11. the covenant library loads and downloads', async ({ page }) => {
  await signIn(page, 'author-1', '/library');
  await expect(page.getByRole('heading', { name: 'Covenant library' })).toBeVisible();
  await page.getByRole('textbox', { name: 'Search covenants' }).fill('DSCR');
  await expect(page.getByRole('cell', { name: /DSCR/ }).first()).toBeVisible();
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download Excel' }).click();
  expect((await download).suggestedFilename()).toMatch(/\.xlsx$/);
});

test('12. support sees the document jobs', async ({ page }) => {
  await signIn(page, 'support-1', '/ops/jobs');
  await expect(page.getByRole('heading', { name: 'Document jobs' })).toBeVisible();
  await expect(page.getByRole('row').nth(1)).toBeVisible();
});

test('13. only an admin sees Settings', async ({ page }) => {
  await signIn(page, 'admin-1', '/settings');
  await expect(page.getByRole('link', { name: 'Settings' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Settings' })).toBeVisible();
  await expect(page.getByLabel('Reporting currency (ISO code)')).toBeVisible();
});

test('15. a covenant opens with its trend and its schedule', async ({ page }) => {
  await signIn(page, 'rm-anil', '/cp/CP-1005');
  await page.getByRole('button', { name: 'Minimum DSCR' }).click();
  const drawer = page.getByRole('dialog');
  const trend = drawer.getByRole('img', { name: /Trend of \d+ results, limit [\d.]+/ });
  // Fresh demo data holds one result per covenant, and the chart needs two: check it once more.
  const tooFew = drawer.getByText('A chart appears once there are at least two numeric results.');
  await expect(trend.or(tooFew)).toBeVisible();
  if (await tooFew.isVisible()) {
    await drawer.getByRole('button', { name: 'Check now' }).click();
    await expect(page.getByText(/^Checked: /)).toBeVisible();
  }
  await expect(trend).toBeVisible();
  await drawer.getByRole('tab', { name: 'Schedule and changes' }).click();
  await expect(drawer.getByRole('button', { name: 'Pause monitoring' })).toBeVisible();
});

test('16. an RM records the result of a person-checked covenant', async ({ page }) => {
  // A manual covenant from the demo data (CP-1010 belongs to rm-priya).
  await signIn(page, 'rm-priya', '/cp/CP-1010');
  const row = page.getByRole('row').filter({ hasText: 'Export Authorisation Valid' });
  await row.getByRole('button', { name: 'Record result' }).click();
  await page.getByLabel('What you checked').fill('e2e: export licence seen, valid to March 2027');
  await page.getByRole('button', { name: 'Save result' }).click();
  await expect(page.getByText('Result recorded.')).toBeVisible();
  await expect(row.getByText('Compliant')).toBeVisible();
});

test('17. the library shows where a covenant is used', async ({ page }) => {
  await signIn(page, 'author-1', '/library');
  await page.getByRole('button', { name: 'Minimum DSCR', exact: true }).click();
  await expect(page.getByText(/On \d+ loan\(s\), \d+ still monitored/)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Edit' })).toBeVisible();
});

test('18. authors see measures; admins see data sources', async ({ browser }) => {
  const author = await sessionAs(browser, 'author-1', '/measures');
  await expect(author.getByRole('heading', { name: 'Measures' })).toBeVisible();
  await expect(author.getByRole('cell', { name: 'DPS_REVENUE' })).toBeVisible();
  const admin = await sessionAs(browser, 'admin-1', '/data-sources');
  await expect(admin.getByText('Document Processing Service (statements)')).toBeVisible();
});

test('19. support diagnoses a CP and reads the sent e-mails with the support token', async ({ page }) => {
  await signIn(page, 'support-1', '/ops/support');
  await page.getByLabel('Support token').fill(envValue('COVENANT_SUPPORT_TOKEN'));
  await page.getByRole('button', { name: 'Use token' }).click();
  await page.getByPlaceholder('CP id or borrower name').fill('CP-1001');
  await page.getByRole('button', { name: 'Diagnose' }).click();
  await expect(page.getByRole('cell', { name: /Minimum DSCR/ })).toBeVisible();
  await page.getByRole('tab', { name: 'Sent e-mails' }).click();
  await expect(page.getByRole('button', { name: /Send today/ })).toBeVisible();
});

test('20. an RM changes a covenant\'s rule for one CP, then goes back to the library rule', async ({ page }) => {
  await signIn(page, 'rm-anil', '/cp/CP-1005');
  await page.getByRole('button', { name: 'Minimum Interest Coverage' }).click();
  const drawer = page.getByRole('dialog');
  await drawer.getByRole('tab', { name: 'Schedule and changes' }).click();
  const rule = drawer.getByLabel('Rule for this CP');
  const original = await rule.inputValue();
  await rule.fill(original.replace(/(\d+(\.\d+)?)\s*$/, (n) => String(Number(n) + 0.5)));
  await drawer.getByLabel('Why').fill('e2e: amendment test');
  await drawer.getByRole('button', { name: 'Save rule' }).click();
  await expect(page.getByText('Rule saved. It applies from the next check.')).toBeVisible();
  await expect(drawer.getByText('e2e: amendment test').first()).toBeVisible();
  await drawer.getByLabel('Why').fill('e2e: back to the library rule');
  await drawer.getByRole('button', { name: 'Use the library rule' }).click();
  await expect(drawer.getByText('Same as the library rule')).toBeVisible();
});

test('21. an RM withdraws their own waiver request', async ({ page }) => {
  await signIn(page, 'rm-anil', '/cp/CP-1007');
  const request = page.getByRole('button', { name: 'Waive / extend' }).first();
  test.skip(await request.count() === 0, 'CP-1007 has no breach open for a waiver');
  const covenant = (await request.locator('xpath=ancestor::tr').locator('td').first().innerText()).trim();
  await request.click();
  await page.getByRole('dialog').getByLabel('Reason').fill('e2e: to be withdrawn');
  await page.getByRole('dialog').getByRole('button', { name: 'Submit' }).click();
  const row = page.getByRole('row').filter({ hasText: covenant });
  page.once('dialog', (d) => void d.accept());
  await row.getByRole('button', { name: 'Withdraw' }).click();
  await expect(page.getByText('Request withdrawn.')).toBeVisible();
  await expect(row.getByRole('button', { name: 'Waive / extend' })).toBeVisible();
});

test('22. alerts, charts and the at-risk setting', async ({ browser }) => {
  const approver = await sessionAs(browser, 'wrm-meera', '/reports');
  await expect(approver.getByRole('button', { name: /Alerts \(\d+\)/ })).toBeVisible();
  await expect(approver.getByRole('img', { name: /Checks per month for 12 months/ })).toBeVisible();
  const admin = await sessionAs(browser, 'admin-1', '/settings');
  await expect(admin.getByLabel(/At risk/)).toBeVisible();
});

test('23. an RM removes a wrongly uploaded document', async ({ page }) => {
  await signIn(page, 'rm-anil', '/documents/CP-1003');
  const name = `e2e-evidence-${Date.now()}.pdf`;
  await page.getByRole('textbox', { name: 'Type' }).click();
  await page.getByRole('option', { name: 'Evidence' }).click();
  await page.locator('input[type=file]').first().setInputFiles({ name, mimeType: 'application/pdf', buffer: uniqueAgreement() });
  await page.getByRole('button', { name: 'Upload' }).click();
  await page.getByRole('cell', { name }).click();
  await page.getByRole('button', { name: 'Remove or replace' }).click();
  await page.getByLabel('Why').fill('e2e: uploaded by mistake');
  await page.getByRole('button', { name: 'Remove', exact: true }).click();
  await expect(page.getByText('Removed. Its figures no longer count.')).toBeVisible();
  await page.keyboard.press('Escape');
  // Removed documents are hidden from the list until asked for.
  await expect(page.getByRole('row').filter({ hasText: name })).toHaveCount(0);
  await page.getByRole('button', { name: /^Show removed/ }).click();
  await expect(page.getByRole('row').filter({ hasText: name }).getByText('Removed')).toBeVisible();
});

test('24. My tasks lists the RM\'s work, and the approver sees requests to decide', async ({ browser }) => {
  const rm = await sessionAs(browser, 'rm-anil', '/tasks');
  await expect(rm.getByRole('heading', { name: 'My tasks' })).toBeVisible();
  await rm.getByText('All on my CPs').click();
  await expect(rm.getByRole('row').nth(1)).toBeVisible();
  const approver = await sessionAs(browser, 'wrm-meera', '/tasks');
  await expect(approver.getByRole('heading', { name: 'My tasks' })).toBeVisible();
});

test('25. an RM sets the borrower contact for certificate reminders', async ({ page }) => {
  await signIn(page, 'rm-anil', '/cp/CP-1003');
  await page.getByRole('tab', { name: 'Certificates' }).click();
  await page.getByLabel('Contact name').fill('Ms Rao, CFO');
  await page.getByLabel('E-mail').fill('cfo@borrower.test');
  await page.getByLabel('Days before due').fill(String(5 + (Date.now() % 20))); // always a change to save
  await page.getByRole('button', { name: 'Save' }).first().click();
  await expect(page.getByText('Borrower contact saved.')).toBeVisible();
});

test('26. an RM asks a question about an agreement', async ({ page }) => {
  await signIn(page, 'rm-anil', '/documents/CP-1003');
  await page.getByRole('row').filter({ hasText: /e2e-agreement-|AGR-003/ }).first().click();
  await page.getByLabel('Question about this agreement').fill('What is the minimum debt service coverage ratio?');
  await page.getByRole('button', { name: 'Ask', exact: true }).click();
  await expect(page.getByText('Advisory only (AI); the covenant status is decided in Core.').last())
    .toBeVisible({ timeout: 120_000 });
});

test('27. a breach notice downloads as a Word file', async ({ page }) => {
  await signIn(page, 'rm-anil', '/cp/CP-1005');
  await page.getByRole('button', { name: 'Maximum Net Leverage' }).click();
  const download = page.waitForEvent('download');
  await page.getByRole('dialog').getByRole('button', { name: 'Breach notice' }).click();
  expect((await download).suggestedFilename()).toMatch(/breach_notice-CP-1005\.docx$/);
});

test('28. a custom report runs, saves, downloads and is deleted', async ({ page }) => {
  await signIn(page, 'rm-anil', '/reports');
  await page.getByRole('tab', { name: 'Your reports' }).click();
  await page.getByRole('button', { name: 'Run' }).click();
  await expect(page.getByRole('cell', { name: /CP-10\d\d/ }).first()).toBeVisible();
  const name = `e2e report ${Date.now()}`;
  await page.getByLabel('Report name').fill(name);
  await page.getByRole('button', { name: 'Save report' }).click();
  await expect(page.getByText(`${name} saved.`)).toBeVisible();
  const row = page.getByRole('row').filter({ hasText: name });
  const download = page.waitForEvent('download');
  await row.getByRole('button', { name: 'Excel' }).click();
  expect((await download).suggestedFilename()).toMatch(/\.xlsx$/);
  page.once('dialog', (d) => void d.accept());
  await row.getByRole('button', { name: 'Delete' }).click();
  await expect(row).toHaveCount(0);
});

test('29. a statement shows its full spread', async ({ page }) => {
  await signIn(page, 'rm-anil', '/documents/CP-1001');
  await page.getByRole('row').filter({ hasText: 'e2e-statement-' }).getByText('Published').first().click();
  await expect(page.getByText(/\d+ of 43 standard lines found/)).toBeVisible();
  await expect(page.getByRole('cell', { name: 'EBITDA', exact: true })).toBeVisible();
});

test('30. the RM gives a borrower portal access and asks for a certificate', async ({ page }) => {
  await signIn(page, 'rm-anil', '/cp/CP-1003');
  await page.getByRole('tab', { name: 'Certificates' }).click();
  const card = page.locator('div').filter({ has: page.getByRole('heading', { name: 'Borrower portal access' }) }).last();
  if (await card.getByText('borrower-reliance').count() === 0) {
    await page.getByLabel("Borrower's user id").fill('borrower-reliance');
    await page.getByRole('button', { name: 'Give access' }).click();
    await expect(page.getByText('Portal access given.')).toBeVisible();
  }
  await expect(card.getByText('borrower-reliance')).toBeVisible();
  await page.getByLabel('Require period end').fill('2026-12-31');
  await page.getByLabel('Due by').fill('2027-02-14');
  await page.getByRole('button', { name: 'Add requirement' }).click();
  await expect(page.getByRole('cell', { name: '2026-12-31' })).toBeVisible();
});

test('31. a borrower sees only the portal, and sends a signed certificate', async ({ page }) => {
  await signIn(page, 'borrower-reliance', '/portfolio');
  await expect(page.getByRole('heading', { name: 'Borrower portal' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Portfolio' })).toHaveCount(0);
  await expect(page.getByText(/What the bank is waiting for/)).toBeVisible();
  const waiting = page.getByText('You are up to date');
  if (await waiting.count() === 0) {
    await page.locator('input[type=file]').first()
      .setInputFiles({ name: 'cert-q4.pdf', mimeType: 'application/pdf', buffer: uniqueAgreement() });
    await page.getByLabel(/Signed by/).fill('Priya Rao, Chief Financial Officer');
    await page.getByLabel(/I confirm this certificate/).check();
    await page.getByRole('button', { name: 'Send to the bank' }).click();
    await expect(page.getByText('Certificate sent to the bank. Thank you.')).toBeVisible();
  }
  const token = await page.request.post('http://localhost:8180/realms/covenant/protocol/openid-connect/token', {
    form: { grant_type: 'password', client_id: 'covenant-cli', username: 'borrower-reliance', password: demoPassword() },
  });
  const auth = { Authorization: `Bearer ${(await token.json()).access_token}` };
  expect((await page.request.get('/api/v1/monitoring/summary', { headers: auth })).status()).toBe(403);
  expect((await page.request.get('/api/v1/portal/cps', { headers: auth })).status()).toBe(200);
});

test('32. support runs the loan-system sync', async ({ page }) => {
  await signIn(page, 'support-1', '/ops/support');
  await page.getByLabel('Support token').fill(envValue('COVENANT_SUPPORT_TOKEN'));
  await page.getByRole('button', { name: 'Use token' }).click();
  await page.getByRole('tab', { name: 'Loan system sync' }).click();
  page.once('dialog', (d) => void d.accept());
  await page.getByRole('button', { name: 'Run the sync now' }).click();
  await expect(page.getByText(/\d+ CP\(s\) checked, \d+ changed, \d+ closed/)).toBeVisible();
});

test('33. a stale sign-in reply starts a fresh sign-in instead of an error', async ({ page }) => {
  await page.goto('/auth/callback?state=stale-from-another-tab&code=x&iss=http%3A%2F%2Flocalhost%3A8180%2Frealms%2Fcovenant');
  await page.waitForURL(/\/realms\/covenant\//);
  await expect(page.locator('#username')).toBeVisible();
  await expect(page.getByText(/Sign-in failed/)).toHaveCount(0);
});

test('14. sign out returns to the sign-in page', async ({ page }) => {
  await signIn(page, 'rm-anil');
  await page.getByRole('button', { name: 'Sign out' }).click();
  await page.waitForURL(/\/realms\/covenant\//);
  await expect(page.locator('#username')).toBeVisible();
});
