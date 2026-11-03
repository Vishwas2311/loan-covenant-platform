import { expect, test } from '@playwright/test';
import { sessionAs, signIn, uniqueAgreement } from './helpers';

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
      .getByText(/^(PUBLISHED|NEEDS REVIEW)$/)).toBeVisible({ timeout: 5_000 });
  }).toPass({ timeout: 120_000, intervals: [5_000] });
});

test('4. onboarding shows covenants extracted from agreements', async ({ page }) => {
  await signIn(page, 'rm-anil', '/onboarding/CP-1003');
  await expect(page.getByText(/EXTRACTED/).first()).toBeVisible({ timeout: 60_000 });
});

test('5. a financial statement becomes values with cell references', async ({ page }) => {
  await signIn(page, 'rm-anil', '/documents/CP-1001');
  const name = `e2e-statement-${Date.now()}.csv`;
  const csv = `Amounts in INR lakhs,\nEBITDA,${500 + (Date.now() % 97)}\nShort-term borrowings,300\n`
    + 'Long-term borrowings,700\nTotal equity,2000\nCash and cash equivalents,100\n';
  await page.locator('input[type=file]').setInputFiles({ name, mimeType: 'text/csv', buffer: Buffer.from(csv) });
  await page.getByRole('textbox', { name: 'Type' }).click();
  await page.getByRole('option', { name: 'Financial statement' }).click();
  await page.getByLabel('Period end').fill('2026-09-30');
  await page.getByRole('button', { name: 'Upload' }).click();
  await expect(page.getByText('Uploaded. Processing has started.')).toBeVisible();
  await expect(async () => {
    await page.reload();
    await expect(page.getByRole('row').filter({ hasText: name }).getByText('PUBLISHED'))
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
  await page.getByRole('textbox', { name: 'Question' }).fill('Which of my covenants are breached?');
  await page.getByRole('button', { name: 'Ask', exact: true }).click();
  await expect(page.getByText('Sources:')).toBeVisible({ timeout: 120_000 });
  await expect(page.getByText(/CP-\d{4}/).first()).toBeVisible();
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

test('14. sign out returns to the sign-in page', async ({ page }) => {
  await signIn(page, 'rm-anil');
  await page.getByRole('button', { name: 'Sign out' }).click();
  await page.waitForURL(/\/realms\/covenant\//);
  await expect(page.locator('#username')).toBeVisible();
});
