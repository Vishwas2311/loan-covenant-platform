import { defineConfig, devices } from '@playwright/test';

/**
 * Browser tests against the running "real" local stack (docs/local-real-testing.md): Keycloak sign-in,
 * Core, DPS and Azure OpenAI. Not part of CI, which has no stack. Run with `npm run e2e`.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 180_000,
  expect: { timeout: 30_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'e2e-report' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8080',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
