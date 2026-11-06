import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, type Browser, type BrowserContext, type Page } from '@playwright/test';

const here = dirname(fileURLToPath(import.meta.url));

/** A value from the environment, else from deploy/.env (never printed). */
export function envValue(name: string): string {
  if (process.env[name]) {
    return process.env[name]!;
  }
  const env = readFileSync(resolve(here, '../../deploy/.env'), 'utf8');
  const line = env.split(/\r?\n/).find((l) => l.startsWith(`${name}=`));
  if (!line) {
    throw new Error(`${name} is missing from deploy/.env (docs/local-real-testing.md, step 2)`);
  }
  return line.slice(name.length + 1).trim();
}

/** The shared test-user password: KC_DEMO_PASSWORD in deploy/.env. */
export function demoPassword(): string {
  return envValue('KC_DEMO_PASSWORD');
}

/** Opens the app and signs in on the Keycloak page, landing on `path`. */
export async function signIn(page: Page, user: string, path = '/portfolio'): Promise<void> {
  // On a busy machine Docker's port forwarding sometimes resets the first connection; try it again.
  await expect(async () => {
    await page.goto(path);
    await page.waitForURL(/\/realms\/covenant\//, { timeout: 30_000 });
  }).toPass({ timeout: 120_000, intervals: [2_000, 5_000] });
  await page.locator('#username').fill(user);
  await page.locator('#password').fill(demoPassword());
  await page.locator('#kc-login').click();
  await page.waitForURL((url) => url.port === '8080' && !url.pathname.startsWith('/auth/'));
  await expect(page.getByText(`${user} (`).first()).toBeVisible();
}

const openSessions: BrowserContext[] = [];

/** A fresh browser session (own cookies), signed in as `user`. Closed by {@link closeSessions}. */
export async function sessionAs(browser: Browser, user: string, path?: string): Promise<Page> {
  const context = await browser.newContext();
  openSessions.push(context);
  const page = await context.newPage();
  await signIn(page, user, path);
  return page;
}

/**
 * Closes every session opened by {@link sessionAs}. Run it after each test: an open session keeps its
 * page polling Core, and a full run otherwise piles up enough of them to slow the later tests.
 */
export async function closeSessions(): Promise<void> {
  await Promise.all(openSessions.splice(0).map((c) => c.close()));
}

/** Bytes of a golden-set agreement, made unique so DPS does not treat it as a re-upload. */
export function uniqueAgreement(name = 'AGR-003.pdf'): Buffer {
  const pdf = readFileSync(resolve(here, '../../golden-set/documents', name));
  return Buffer.concat([pdf, Buffer.from(`\n% e2e ${Date.now()}-${Math.random()}\n`)]);
}
