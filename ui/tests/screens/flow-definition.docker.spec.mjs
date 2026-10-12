// T-017 (E3 lane D, row 278) - the flow-definition screen, FAILING FIRST.
//
// A configurator creates a flow with source system S and destinations T1/T2 and saves an
// interface with its mandatory business-key rule, its ordering setting (PER_KEY default,
// ADR-011) and its sourceSchema draft. Save draft hits the REAL API (E3-F3-S1: no mock,
// no MSW, no stub layer between the screen and the API - the two earlier UI-API defects
// died on a mock). Boot protocol: the same container harness boot-backend.mjs (docker-tagged,
// one suite at a time), the built console served by vite preview proxying the backend's routes,
// signed in through the real Keycloak login form (global-setup, E4 T-097).
//
// RED at this tip: the screen does not exist, and lane C's T-015 flow admin API is not merged
// into row278-integration yet. The red run's failures name what is missing.

import { expect, test } from '@playwright/test';
import { consoleUrl } from './boot-state.mjs';

test.beforeEach(async ({ page }) => {
  await page.goto(consoleUrl());
});

test('the console has a flow-definition screen the navigable menu reaches', async ({ page }) => {
  await page.click('[data-nav="flows"]');
  await expect(page.locator('[data-page="flow-definition"]')).toBeVisible();
});

test('Save draft sends the business-key rule, the ordering setting (PER_KEY default) and the sourceSchema, and the REAL API answers 200', async ({ page }) => {
  await page.click('[data-nav="flows"]');
  await page.click('[data-page="flow-definition"]');

  await page.fill('[data-field="source-system"]', 'S');
  await page.fill('[data-field="destination-1"]', 'T1');
  await page.fill('[data-field="destination-2"]', 'T2');
  await page.fill('[data-field="interface-name"]', 'orders');
  await page.fill('[data-field="business-key-rule"]', 'customer_id, order_date');
  await page.fill('[data-field="source-schema"]', '{"type":"object","properties":{"customer_id":{"type":"string"}}}');

  const response = page.waitForResponse((r) => r.status() === 200);
  await page.click('[data-action="save-draft"]');
  await expect(response).toBeTruthy();
  await expect(page.locator('[data-saved="true"]')).toBeVisible();
});

test('an interface without its mandatory business-key rule is refused by the real API and the screen shows the clear validation error', async ({ page }) => {
  await page.click('[data-nav="flows"]');
  await page.click('[data-page="flow-definition"]');

  await page.fill('[data-field="source-system"]', 'S');
  await page.fill('[data-field="destination-1"]', 'T1');
  await page.fill('[data-field="interface-name"]', 'orders');
  // no business-key rule: the API refused this in the deleted build (E3-F1-S1 @negative)

  await page.click('[data-action="save-draft"]');
  await expect(page.locator('[data-validation-error="business-key-rule"]')).toBeVisible();
});

test('the ordering setting defaults to PER_KEY (ADR-011) and persists on save', async ({ page }) => {
  await page.click('[data-nav="flows"]');
  await page.click('[data-page="flow-definition"]');

  const setting = page.locator('[data-field="ordering-setting"]');
  await expect(setting).toHaveValue('PER_KEY');
  await page.fill('[data-field="interface-name"]', 'orders');
  await page.fill('[data-field="business-key-rule"]', 'customer_id');
  await page.click('[data-action="save-draft"]');
  await page.reload();
  await expect(setting).toHaveValue('PER_KEY');
});

// F-1 (row 306 defect batch): the interface NAME typed by the configurator reaches the created
// flow - the flow is born with that name, not the generated flow-<uuid> code. The answer is the
// real API's collection read back through the browser's own session (no mock anywhere).
test('the created flow is born with the interface name the configurator typed', async ({ page }) => {
  await page.click('[data-nav="flows"]');
  await page.click('[data-page="flow-definition"]');

  await page.fill('[data-field="source-system"]', 'S');
  await page.fill('[data-field="destination-1"]', 'T1');
  await page.fill('[data-field="interface-name"]', `typed-name-${Date.now()}`);
  await page.fill('[data-field="business-key-rule"]', 'customer_id');
  await page.click('[data-action="save-draft"]');
  await expect(page.locator('[data-saved="true"]')).toBeVisible();
  const typedName = await page.inputValue('[data-field="interface-name"]');

  const named = await page.evaluate(async ({ url, name }) => {
    const rows = await fetch(`${url}/api/v1/admin/flows`, { credentials: 'include' })
      .then((answer) => answer.json());
    return (Array.isArray(rows) ? rows : []).filter((row) => row.name === name);
  }, { url: consoleUrl(), name: typedName });
  expect(named, `the flow the console created lists under the typed name "${typedName}", got: ${JSON.stringify(named.length)} flows with that name`)
    .toHaveLength(1);
});

// E3-F1-S1 @negative @tenant "Another tenant's flow is invisible": a configurator of tenant B
// opens tenant A's flow by id THROUGH THE SCREEN - the real API answers 404 (the flow is not
// hidden behind a 403 hint, no tenant-A row is read) and the screen shows the not-found state.
// The tenant-B identity is the harness's second-tenant binding (boot-backend logs it in through
// the realm form); the test attaches it through the console proxy with the X-Harness-Login
// marker, which swaps in that binding's server-side session - the browser never holds a token
// and the backend still enforces the isolation for real. Tenant A's flow id comes from the
// page's own signed-in session through a BROWSER fetch (window fetch, like the console's own
// client - the session/CSRF cookies are the browser's loopback cookies; E4 T-097: Playwright's
// request-level context does not carry the Secure session cookie over http, so a request-level
// read would be a different session than the screen's).
test('another tenant opening the flow by id is refused 404 through the screen', async ({ page }) => {
  await page.goto(consoleUrl());
  const list = await page.evaluate(async (url) => {
    const read = async (method, path, body) => {
      const xsrf = document.cookie.split('; ').filter((pair) => pair.startsWith('XSRF-TOKEN=')).pop();
      const answer = await fetch(url + path, {
        method,
        headers: { ...(body ? { 'content-type': 'application/json' } : {}), ...(['POST', 'PUT', 'PATCH', 'DELETE'].includes(method) && xsrf ? { 'X-XSRF-TOKEN': decodeURIComponent(xsrf.split('=').pop()) } : {}) },
        credentials: 'include',
        body: body ? JSON.stringify(body) : undefined
      });
      return { status: answer.status, data: answer.status === 0 ? null : await answer.json().catch(() => null) };
    };
    let rows = (await read('GET', '/api/v1/admin/flows')).data ?? [];
    if (rows.length === 0) {
      await read('POST', '/api/v1/admin/flows', { sourceSystem: 'S', destinations: ['T1'], businessKeyRule: 'customer_id' });
      rows = (await read('GET', '/api/v1/admin/flows')).data ?? [];
    }
    return (rows[0] ?? {}).id ?? null;
  }, consoleUrl());
  if (list === null) throw new Error('no tenant A flow to open as the other tenant');

  await page.setExtraHTTPHeaders({ 'X-Harness-Login': 'other-tenant' });
  await page.goto(`${consoleUrl()}/#/flow/${list}`);
  await expect(page.locator('[data-refusal="flow-not-found"]')).toBeVisible();
});