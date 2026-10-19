import { test as base, expect, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { randomBytes } from 'node:crypto';
export const adminEmail = 'browser-admin@example.invalid';
export const secret = () => randomBytes(24).toString('base64url');
export const test = base.extend<{ diagnostics: void }>({
  diagnostics: [async ({ page }, use, info) => {
    await use();
    if (info.status !== info.expectedStatus) {
      await mkdir('safe-artifacts', { recursive: true });
      // Inputs and account identity are masked even on login/password-change failure.
      await page.screenshot({ path: `safe-artifacts/${info.project.name}-${info.testId.replace(/[^a-zA-Z0-9-]/g, '')}.png`,
        mask: [page.locator('input, textarea, select, .sidebar-note, .auth-card')],
        animations: 'disabled', timeout: 5000 }).catch(() => {});
    }
  }, { auto: true }],
});
export { expect };
export async function login(page: Page, email = adminEmail, password = process.env.E2E_ADMIN_PASSWORD!) {
  await page.goto('/');
  await page.getByLabel('Email', { exact: true }).fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
}
export async function nav(page: Page, name: string) {
  await page.getByRole('navigation', { name: 'Main navigation' }).getByRole('button', { name, exact: true }).click();
}
export async function write(page: Page, path: string, method: string, data?: unknown) {
  const tokenResponse = await page.request.get('/api/v1/auth/csrf');
  expect(tokenResponse.status()).toBe(200);
  const csrf = await tokenResponse.json();
  return page.request.fetch('/api/v1/' + path, { method, data,
    headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': crypto.randomUUID() } });
}
