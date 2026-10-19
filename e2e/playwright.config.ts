import { defineConfig, devices } from '@playwright/test';
if (process.env.E2E_ISOLATED_STACK !== '1' || !process.env.E2E_ADMIN_PASSWORD) {
  throw new Error('Run through scripts/e2e-stack.py; external targets are not supported.');
}
export default defineConfig({
  testDir: './tests', fullyParallel: false, workers: 1, retries: 0,
  timeout: 120_000, expect: { timeout: 15_000 }, forbidOnly: true,
  reporter: './safe-reporter.ts', outputDir: '.private-results',
  use: { baseURL: 'http://127.0.0.1:18081', locale: 'en-GB', timezoneId: 'UTC',
    trace: 'off', screenshot: 'off', video: 'off', actionTimeout: 15_000 },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'webkit', use: { ...devices['Desktop Safari'] } },
  ],
});
