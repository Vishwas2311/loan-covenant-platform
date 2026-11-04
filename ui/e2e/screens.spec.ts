import { test } from '@playwright/test';
import { envValue, signIn } from './helpers';

// Not a test: a screenshot of every screen, per role, for a visual review. Skipped unless SHOTS names a
// folder:  SHOTS=../screens npx playwright test e2e/screens.spec.ts   (add DARK=1 for dark mode)
const OUT = process.env.SHOTS ?? '';
test.skip(!OUT, 'set SHOTS to a folder to take the review screenshots');
test.use({ colorScheme: process.env.DARK ? 'dark' : 'light' });

const SHOTS: { user: string; pages: { name: string; path: string; tab?: string; click?: string }[] }[] = [
  { user: 'rm-anil', pages: [
    { name: 'portfolio', path: '/portfolio' },
    { name: 'tasks', path: '/tasks' },
    { name: 'cp-empty', path: '/cp' },
    { name: 'cp', path: '/cp/CP-1005' },
    { name: 'cp-drawer', path: '/cp/CP-1005', click: 'Minimum DSCR' },
    { name: 'cp-certificates', path: '/cp/CP-1005', tab: 'Certificates' },
    { name: 'onboarding-empty', path: '/onboarding' },
    { name: 'onboarding', path: '/onboarding/CP-1003' },
    { name: 'documents-empty', path: '/documents' },
    { name: 'documents', path: '/documents/CP-1003' },
    { name: 'library', path: '/library' },
    { name: 'reports', path: '/reports' },
  ] },
  { user: 'author-1', pages: [{ name: 'portfolio', path: '/portfolio' }, { name: 'cp-empty', path: '/cp' },
    { name: 'measures', path: '/measures' }] },
  { user: 'admin-1', pages: [{ name: 'data-sources', path: '/data-sources' }, { name: 'settings', path: '/settings' }] },
  { user: 'auditor-1', pages: [{ name: 'portfolio', path: '/portfolio' }, { name: 'cp', path: '/cp/CP-1008' }] },
  { user: 'support-1', pages: [{ name: 'jobs', path: '/ops/jobs' }, { name: 'support', path: '/ops/support' }] },
  { user: 'borrower-reliance', pages: [{ name: 'portal', path: '/' }] },
];

for (const s of SHOTS) {
  test(`screens for ${s.user}`, async ({ page }) => {
    test.setTimeout(240_000);
    await page.setViewportSize({ width: 1440, height: 900 });
    await signIn(page, s.user, s.pages[0].path);
    for (const p of s.pages) {
      await page.goto(p.path);
      await page.waitForLoadState('networkidle').catch(() => undefined);
      if (p.tab) {
        await page.getByRole('tab', { name: p.tab }).click();
      }
      if (p.click) {
        await page.getByRole('button', { name: p.click }).first().click();
      }
      if (p.name === 'support') {
        await page.getByLabel('Support token').fill(envValue('COVENANT_SUPPORT_TOKEN'));
        await page.getByRole('button', { name: 'Use token' }).click();
      }
      await page.waitForTimeout(1200);
      await page.screenshot({ path: `${OUT}/${s.user}--${p.name}.png`, fullPage: true });
    }
  });
}
