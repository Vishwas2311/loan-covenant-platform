import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { signIn } from './helpers';

// Accessibility: axe-core (WCAG 2.1 A and AA, colour contrast included) on every main page in light and
// dark, and the structure a screen reader announces: landmarks, one h1, named controls.
const PAGES: Record<string, string[]> = {
  'rm-anil': ['/portfolio', '/tasks', '/cp', '/cp/CP-1005', '/onboarding', '/onboarding/CP-1003', '/documents',
    '/documents/CP-1003', '/library', '/reports'],
  'admin-1': ['/settings', '/data-sources', '/measures'],
  'support-1': ['/ops/jobs', '/ops/support'],
  'borrower-reliance': ['/'],
};

async function scan(page: import('@playwright/test').Page) {
  const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  return result.violations.map((v) => ({
    id: v.id, impact: v.impact, nodes: v.nodes.length,
    sample: v.nodes.slice(0, 2).map((n) => `${n.target.join(' ')} :: ${n.failureSummary?.split('\n')[1] ?? ''}`),
  }));
}

for (const scheme of ['light', 'dark'] as const) {
  for (const [user, paths] of Object.entries(PAGES)) {
    test(`no WCAG AA violations for ${user} (${scheme})`, async ({ browser }) => {
      test.setTimeout(300_000);
      const context = await browser.newContext({ colorScheme: scheme });
      const page = await context.newPage();
      await signIn(page, user, paths[0]);
      const found: Record<string, unknown> = {};
      for (const path of paths) {
        await page.goto(path);
        await page.waitForLoadState('networkidle').catch(() => undefined);
        await expect(page.locator('h1').first()).toBeVisible({ timeout: 30_000 });
        const violations = await scan(page);
        if (violations.length) {
          found[path] = violations;
        }
      }
      await context.close();
      expect(found, JSON.stringify(found, null, 1)).toEqual({});
    });
  }
}

test('a screen reader finds the landmarks, one page heading and a skip link', async ({ page }) => {
  await signIn(page, 'rm-anil', '/portfolio');
  await expect(page.getByRole('banner')).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Main menu' })).toBeVisible();
  await expect(page.getByRole('main')).toBeVisible();
  await expect(page.getByRole('heading', { level: 1 })).toHaveCount(1);
  await page.keyboard.press('Tab');
  const skip = page.getByRole('link', { name: 'Skip to content' });
  await expect(skip).toBeFocused();
  await skip.press('Enter');
  await expect(page.getByRole('main')).toBeFocused();
});

test('on a phone the menu folds behind a button and no page scrolls sideways', async ({ page }) => {
  test.setTimeout(240_000);
  await signIn(page, 'rm-anil', '/portfolio');
  await page.setViewportSize({ width: 375, height: 800 });
  const menu = page.getByRole('navigation', { name: 'Main menu' });
  await expect(menu).not.toBeInViewport();
  await page.getByRole('button', { name: 'Open the menu' }).click();
  await expect(menu).toBeInViewport();
  await menu.getByRole('link', { name: 'Reports' }).click();
  await expect(page.getByRole('heading', { name: 'Reports' })).toBeVisible();
  await expect(menu).not.toBeInViewport();
  for (const path of PAGES['rm-anil']) {
    await page.goto(path);
    await page.waitForLoadState('networkidle').catch(() => undefined);
    await expect(page.locator('h1').first()).toBeVisible({ timeout: 30_000 });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow, `${path} scrolls sideways by ${overflow}px`).toBeLessThanOrEqual(0);
  }
});
