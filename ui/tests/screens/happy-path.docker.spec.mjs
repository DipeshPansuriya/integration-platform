// Happy-path smoke only: scenarios that pass against the current artifact set and do not wait on any pending
// seam (no T111/T112/T98/T143 etc.). Each assertion uses only features already on main.

import { expect, test } from '@playwright/test';
import { consoleUrl } from './boot-state.mjs';

test('UI happy path: the signed-in shell opens the dashboard for its own tenant', async ({ page }) => {
  await page.goto(`${consoleUrl()}/#/`);
  await expect(page.locator('[data-page="dashboard"]')).toBeVisible();
  await expect(page.locator('[data-session-tenant]')).toHaveText(process.env.CONSOLE_TENANT_CODE);
});

// The session cookie is Secure (tenancy-console.yml): Playwright's page.request (Node cookie jar) does not send a
// Secure cookie to http://127.0.0.1, so those calls answered 401 - the calls run in the page like the rest of the suite.
test('UI+API happy path: a signed-in session exposes its own tenant through /api/v1/me', async ({ page }) => {
  await page.goto(`${consoleUrl()}/#/`);
  const answer = await apiFetch(page, 'GET', `${consoleUrl()}/api/v1/me`);
  expect(answer.status).toBe(200);
  expect(answer.body.tenantCode ?? answer.body.tenant ?? answer.body.code).toBe(process.env.CONSOLE_TENANT_CODE);
});

test('Direct intake check: a human (console) caller is refused with 403', async ({ page }) => {
  await page.goto(`${consoleUrl()}/#/`);
  const stamp = Date.now();
  const answer = await apiFetch(page, 'POST', `${consoleUrl()}/api/v1/interfaces/SMOKE-UNKNOWN/messages`, {
    headers: { 'content-type': 'application/json', 'X-Source-Contract-Version': '1.0', 'Idempotency-Key': `smoke-${stamp}` },
    body: JSON.stringify({ orderNo: `SMOKE-${stamp}`, customer: 'CUST-A', qty: 1 })
  });
  expect(answer.status).toBe(403);
});

test('Direct intake check: malformed JSON is refused (400 or the role guard 403 before it)', async ({ page }) => {
  await page.goto(`${consoleUrl()}/#/`);
  const answer = await apiFetch(page, 'POST', `${consoleUrl()}/api/v1/interfaces/SMOKE-UNKNOWN/messages`, {
    headers: { 'content-type': 'application/json', 'X-Source-Contract-Version': '1.0', 'Idempotency-Key': `smoke-${Date.now()}` },
    body: '{not json'
  });
  expect([400, 403, 415]).toContain(answer.status);
});

// In-browser fetch helper: carries the session cookie and the CSRF token, so a POST is judged by its endpoint
// rather than by the CSRF filter.
async function apiFetch(page, method, path, { headers = {}, body } = {}) {
  return page.evaluate(
    async ({ method, path, headers, body }) => {
      const xsrf = document.cookie.split('; ').filter((pair) => pair.startsWith('XSRF-TOKEN=')).pop();
      const sent = { ...headers };
      if (method !== 'GET' && xsrf) sent['X-XSRF-TOKEN'] = decodeURIComponent(xsrf.slice('XSRF-TOKEN='.length));
      const answer = await fetch(path, { method, headers: sent, credentials: 'include', body });
      return { status: answer.status, body: await answer.json().catch(() => ({})) };
    },
    { method, path, headers, body }
  );
}

// Tiny in-browser fetch helper (matches the screens suite pattern): carries the session cookie and the
// CSRF token, so a replay POST is judged by the delivery endpoint rather than by the CSRF filter.
async function deliveryFetch(page, method, path) {
  return page.evaluate(
    async ({ method, path }) => {
      const xsrf = document.cookie.split('; ').filter((pair) => pair.startsWith('XSRF-TOKEN=')).pop();
      const headers = {};
      if (method !== 'GET' && xsrf) headers['X-XSRF-TOKEN'] = decodeURIComponent(xsrf.slice('XSRF-TOKEN='.length));
      const answer = await fetch(path, { method, headers, credentials: 'include' });
      return answer.status;
    },
    { method, path }
  );
}

test('Delivery assertion: replaying an unknown delivery returns 404 and changes nothing', async ({ page }) => {
  await page.goto(`${consoleUrl()}/#/`);
  // 8 nil-ish but well-formed UUIDs never exist for this tenant; the endpoint must surface 404, not 5xx.
  const unknown = '00000000-0000-0000-0000-000000000001';
  const status = await deliveryFetch(page, 'POST', `${consoleUrl()}/api/v1/admin/messages/deliveries/${unknown}/replay`);
  expect(status).toBe(404);
});
