import { expect, test, type Page } from '@playwright/test';
import { closeSessions, envValue, sessionAs, signIn } from './helpers';

test.afterEach(closeSessions);

// Every page, as every role: no error box, no failed API call, no console error, and the right menus.
// A page that calls an endpoint its own role may not use shows up here as a 401/403.

interface Role {
  user: string;
  cp: string;              // a CP this user can open
  menus: string[];         // menu links that must be there
  hidden: string[];        // menu links that must not be there
  pages: string[];
}

const STAFF_PAGES = (cp: string) => [
  '/portfolio', '/tasks', `/cp/${cp}`, `/onboarding/${cp}`, `/documents/${cp}`, '/library', '/reports',
];
const ALL_MENUS = ['Portfolio', 'My tasks', 'CP workspace', 'Onboarding', 'Documents', 'Covenant library', 'Reports'];

const READ_PAGES = (cp: string) => ['/portfolio', `/cp/${cp}`, `/documents/${cp}`, '/library'];
const ROLES: Role[] = [
  { user: 'rm-anil', cp: 'CP-1001', menus: ALL_MENUS, hidden: ['Measures', 'Data sources', 'Settings', 'Support tools'],
    pages: STAFF_PAGES('CP-1001') },
  { user: 'wrm-meera', cp: 'CP-1001', menus: ALL_MENUS, hidden: ['Measures', 'Settings', 'Support tools'],
    pages: STAFF_PAGES('CP-1001') },
  { user: 'author-1', cp: '', menus: ['Covenant library', 'Measures'],
    hidden: ['Portfolio', 'My tasks', 'CP workspace', 'Onboarding', 'Documents', 'Reports', 'Settings', 'Support tools',
      'Data sources'],
    pages: ['/library', '/measures'] },
  { user: 'admin-1', cp: 'CP-1001', menus: [...ALL_MENUS, 'Measures', 'Data sources', 'Settings', 'Support tools'],
    hidden: [], pages: [...STAFF_PAGES('CP-1001'), '/measures', '/data-sources', '/settings', '/ops/jobs', '/ops/support'] },
  { user: 'auditor-1', cp: 'CP-1008', menus: ['Portfolio', 'CP workspace', 'Documents', 'Covenant library', 'Reports'],
    hidden: ['My tasks', 'Onboarding', 'Measures', 'Settings', 'Support tools'],
    pages: [...READ_PAGES('CP-1008'), '/reports'] },
  { user: 'support-1', cp: 'CP-1001', menus: ['Portfolio', 'CP workspace', 'Documents', 'Covenant library',
    'Support tools', 'Document jobs'], hidden: ['My tasks', 'Onboarding', 'Reports', 'Settings', 'Measures'],
    pages: [...READ_PAGES('CP-1001'), '/ops/jobs', '/ops/support'] },
];

const CP_TABS = ['Covenants', 'Status history', 'Period comparison', 'Certificates', 'Manual values', 'AI suggestions', 'Cycles'];

function watch(page: Page): string[] {
  const problems: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error' && !/favicon|ResizeObserver|Download the React DevTools/.test(m.text())) {
      problems.push(`console: ${m.text().slice(0, 160)}`);
    }
  });
  page.on('response', (r) => {
    const url = r.url();
    if (/\/(api|dps)\/v1\//.test(url) && r.status() >= 400 && r.request().method() === 'GET') {
      problems.push(`${r.status()} ${r.request().method()} ${url.replace(/^https?:\/\/[^/]+/, '')}`);
    }
  });
  return problems;
}

async function settle(page: Page) {
  await page.waitForLoadState('networkidle').catch(() => undefined);
  await expect(page.getByText('Something went wrong')).toHaveCount(0);
  await expect(page.getByText('Page not found.')).toHaveCount(0);
}

for (const role of ROLES) {
  test(`every page works for ${role.user}`, async ({ page }) => {
    test.setTimeout(240_000);
    const problems = watch(page);
    await signIn(page, role.user, '/portfolio');
    for (const m of role.menus) {
      await expect(page.getByRole('link', { name: m, exact: true }), `${role.user} should see ${m}`).toBeVisible();
    }
    for (const m of role.hidden) {
      await expect(page.getByRole('link', { name: m, exact: true }), `${role.user} should not see ${m}`).toHaveCount(0);
    }
    for (const path of role.pages) {
      await page.goto(path);
      await settle(page);
      if (path.startsWith('/cp/')) {
        for (const tab of CP_TABS) {
          await page.getByRole('tab', { name: tab }).click();
          await settle(page);
        }
        await page.getByRole('tab', { name: 'Covenants' }).click();
        const first = page.getByRole('row').nth(1).getByRole('button').first();
        if (await first.count()) {
          await first.click();
          const drawer = page.getByRole('dialog');
          if (await drawer.count()) {
            for (const tab of ['Trend', 'Evidence', 'Schedule and changes']) {
              await drawer.getByRole('tab', { name: new RegExp(tab) }).click();
              await settle(page);
            }
            await page.keyboard.press('Escape');
          }
        }
      }
      if (path === '/ops/support') {
        await page.getByLabel('Support token').fill(envValue('COVENANT_SUPPORT_TOKEN'));
        await page.getByRole('button', { name: 'Use token' }).click();
        for (const tab of ['Sent e-mails', 'Data source health', 'Audit log', 'Loan system sync']) {
          await page.getByRole('tab', { name: tab }).click();
          await settle(page);
        }
      }
    }
    expect(problems, `problems for ${role.user}:\n${problems.join('\n')}`).toEqual([]);
  });
}

test('the borrower sees the portal and only the portal', async ({ page }) => {
  const problems = watch(page);
  await signIn(page, 'borrower-reliance', '/library');
  await expect(page.getByRole('heading', { name: 'Borrower portal' })).toBeVisible();
  for (const m of ALL_MENUS) {
    await expect(page.getByRole('link', { name: m, exact: true })).toHaveCount(0);
  }
  for (const tab of ['Send a compliance certificate', 'Send a financial statement', 'What you sent']) {
    await page.getByRole('tab', { name: tab }).click();
  }
  await settle(page);
  expect(problems).toEqual([]);
});

for (const user of ['auditor-1', 'support-1']) {
  test(`${user} gets no buttons that would change a loan`, async ({ page }) => {
    await signIn(page, user, '/cp/CP-1005');
    await expect(page.getByRole('row').nth(1)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Waive / extend' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Record result' })).toHaveCount(0);
    await page.getByRole('tab', { name: 'Certificates' }).click();
    await expect(page.getByRole('button', { name: 'Add requirement' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Give access' })).toHaveCount(0);
    await page.goto('/documents/CP-1005');
    await expect(page.getByRole('heading', { name: 'Documents for CP-1005' })).toBeVisible();
    await expect(page.getByRole('heading', { name: /^Upload to/ })).toHaveCount(0);
    await page.goto('/onboarding/CP-1005');
    await expect(page.getByRole('heading', { name: 'Onboarding is not part of your role' })).toBeVisible();
    await expect(page.getByText('Read-only on loans')).toBeVisible();
  });
}

test('a page outside the role explains itself, and bank-wide roles see the whole portfolio', async ({ page, browser }) => {
  await signIn(page, 'author-1', '/settings');
  await expect(page.getByRole('heading', { name: 'Settings is not part of your role' })).toBeVisible();
  await page.getByRole('link', { name: 'Go to your start page' }).click();
  await expect(page.getByRole('heading', { name: 'Covenant library' })).toBeVisible();
  await page.goto('/');
  await expect(page).toHaveURL(/\/library$/);
  const auditor = await sessionAs(browser, 'auditor-1', '/cp');
  const cards = auditor.getByRole('button', { name: /^Open CP-/ });
  await expect(cards.first()).toBeVisible({ timeout: 30_000 });
  // The whole portfolio arrives in one list, but it can still be rendering when the first card shows:
  // count again until it settles instead of reading it once (this failed once on a busy machine).
  await expect(async () => expect(await cards.count()).toBeGreaterThan(12)).toPass({ timeout: 30_000 });
});

test('pages opened without a CP list the user CPs to choose from', async ({ page }) => {
  await signIn(page, 'rm-anil', '/cp');
  for (const [path, heading] of [['/cp', /^Tata Steel Ltd$/], ['/onboarding', /^Onboard CP-/], ['/documents', /^Documents for CP-/]] as const) {
    await page.goto(path);
    const cards = page.getByRole('button', { name: /^Open CP-/ });
    await expect(cards.first()).toBeVisible();
    expect(await cards.count()).toBeGreaterThan(1);
    await page.getByLabel('Search your CPs').fill('CP-1005');
    await expect(cards).toHaveCount(1);
    await cards.first().click();
    await expect(page.getByRole('heading', { name: heading })).toBeVisible();
    await expect(page.getByText(/RM rm-anil/).first()).toBeVisible();
  }
});
