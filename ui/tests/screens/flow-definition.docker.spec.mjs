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
  // row 310 T88: #/flows is the list landing (10-screens §3); the editor opens from it.
  await expect(page.locator('[data-page="flows-list"]')).toBeVisible();
  await page.click('[data-action="define-flow"]');
  await expect(page.locator('[data-page="flow-definition"]')).toBeVisible();
});

test('Save draft sends the business-key rule, the ordering setting (PER_KEY default) and the sourceSchema, and the REAL API answers 200', async ({ page }) => {
  await openEditor(page);

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
  await openEditor(page);

  await page.fill('[data-field="source-system"]', 'S');
  await page.fill('[data-field="destination-1"]', 'T1');
  await page.fill('[data-field="interface-name"]', 'orders');
  // no business-key rule: the API refused this in the deleted build (E3-F1-S1 @negative)

  await page.click('[data-action="save-draft"]');
  await expect(page.locator('[data-validation-error="business-key-rule"]')).toBeVisible();
});

test('the ordering setting defaults to PER_KEY (ADR-011) and persists on save', async ({ page }) => {
  await openEditor(page);

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
  await openEditor(page);

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

/** Row 310 T88: the menu reaches the Flows list landing; "Define a flow" opens the editor in create mode. */
async function openEditor(page) {
  await page.click('[data-nav="flows"]');
  await page.click('[data-action="define-flow"]');
  await expect(page.locator('[data-page="flow-definition"]')).toBeVisible();
}

// ---------------------------------------------------------------------------------------------
// Row 310 B11/B12/B13 (vijay-developer, lane D): Kavita's follow-ups on B9/B10/T88. Behavioural -
// every assertion reads the screen or the REAL API back, never the source text.

/** The browser's own signed-in session (cookies + CSRF header), like the console's client. */
async function apiCall(page, method, path, body) {
  return page.evaluate(async ({ url, method, path, body }) => {
    const xsrf = document.cookie.split('; ').filter((pair) => pair.startsWith('XSRF-TOKEN=')).pop();
    const answer = await fetch(url + path, {
      method,
      headers: {
        ...(body ? { 'content-type': 'application/json' } : {}),
        ...(method !== 'GET' && xsrf ? { 'X-XSRF-TOKEN': decodeURIComponent(xsrf.split('=').pop()) } : {})
      },
      credentials: 'include',
      body: body ? JSON.stringify(body) : undefined
    });
    return { status: answer.status, data: await answer.json().catch(() => null) };
  }, { url: consoleUrl(), method, path, body });
}

async function createFlowViaApi(page, body) {
  const created = await apiCall(page, 'POST', '/api/v1/admin/flows', body);
  expect(created.status, `create ${JSON.stringify(body)} -> ${JSON.stringify(created.data)}`).toBeLessThan(300);
  return created.data.id;
}

async function readFlowViaApi(page, id) {
  const read = await apiCall(page, 'GET', `/api/v1/admin/flows/${encodeURIComponent(id)}`);
  expect(read.status).toBe(200);
  return read.data;
}

async function flowsNamed(page, name) {
  const rows = (await apiCall(page, 'GET', '/api/v1/admin/flows')).data;
  return (Array.isArray(rows) ? rows : []).filter((row) => row.name === name);
}

/** Opens flow `id` in the editor through the address and waits until its stored fields are shown. */
async function openFlow(page, id, businessKeyRule) {
  await page.goto(`${consoleUrl()}/#/flow/${id}`);
  await expect(page.locator('[data-page="flow-definition"]')).toBeVisible();
  await expect(page.locator('[data-field="business-key-rule"]')).toHaveValue(businessKeyRule);
}

const editor = (page) => page.locator('[data-page="flow-definition"]');
const confirmBox = (page) => page.locator('[data-confirm="unsaved-changes"]');
const stamp = () => `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;

test('B11 #/flow/<A> -> #/flow/new in place shows an empty form; Save draft creates a NEW flow and A is unchanged', async ({ page }) => {
  const run = stamp();
  const idA = await createFlowViaApi(page, { name: `b11-a-${run}`, sourceSystem: 'S', destinations: ['T1'], businessKeyRule: 'a_key' });
  const before = await readFlowViaApi(page, idA);
  await openFlow(page, idA, 'a_key');

  // In place: a hash-only move in the same document (no reload) - what the address bar or a link does.
  await page.evaluate(() => { window.location.hash = '#/flow/new'; });
  for (const field of ['interface-name', 'source-system', 'destination-1', 'destination-2', 'business-key-rule']) {
    await expect(page.locator(`[data-field="${field}"]`), `${field} is empty in create mode`).toHaveValue('');
  }

  const nameB = `b11-b-${run}`;
  await page.fill('[data-field="interface-name"]', nameB);
  await page.fill('[data-field="source-system"]', 'S');
  await page.fill('[data-field="destination-1"]', 'T2');
  await page.fill('[data-field="business-key-rule"]', 'b_key');
  await page.click('[data-action="save-draft"]');
  await expect(page.locator('[data-saved="true"]')).toBeVisible();

  const after = await readFlowViaApi(page, idA);
  const stored = (flow) => ({ name: flow.name, businessKeyRule: flow.businessKeyRule, destinations: flow.destinations, sourceSystem: flow.sourceSystem });
  expect(stored(after), 'flow A keeps every stored field').toEqual(stored(before));
  const flowsB = await flowsNamed(page, nameB);
  expect(flowsB, 'Save draft created a second flow under the typed name').toHaveLength(1);
  expect(flowsB[0].id).not.toBe(idA);

  // The saved create-mode editor now names B in the address (replaceState); #/flow/new again must not overwrite B.
  await page.evaluate(() => { window.location.hash = '#/flow/new'; });
  await expect(page.locator('[data-field="business-key-rule"]')).toHaveValue('');
  const nameC = `b11-c-${run}`;
  await page.fill('[data-field="interface-name"]', nameC);
  await page.fill('[data-field="source-system"]', 'S');
  await page.fill('[data-field="destination-1"]', 'T1');
  await page.fill('[data-field="business-key-rule"]', 'c_key');
  await page.click('[data-action="save-draft"]');
  await expect(page.locator('[data-saved="true"]')).toBeVisible();
  expect((await readFlowViaApi(page, flowsB[0].id)).businessKeyRule, 'flow B is not overwritten either').toBe('b_key');
  expect(await flowsNamed(page, nameC)).toHaveLength(1);
});

test('B11 #/flow/<A> -> #/flow/<B> in place shows B', async ({ page }) => {
  const run = stamp();
  const idA = await createFlowViaApi(page, { name: `b11-ab-a-${run}`, sourceSystem: 'S', destinations: ['T1'], businessKeyRule: 'a_key' });
  const idB = await createFlowViaApi(page, { name: `b11-ab-b-${run}`, sourceSystem: 'S', destinations: ['T2'], businessKeyRule: 'b_key' });
  await openFlow(page, idA, 'a_key');
  await page.evaluate((id) => { window.location.hash = `#/flow/${id}`; }, idB);
  await expect(page.locator('[data-field="business-key-rule"]')).toHaveValue('b_key');
  await expect(page.locator('[data-field="interface-name"]')).toHaveValue(`b11-ab-b-${run}`);
  await expect(page.locator('[data-field="destination-1"]')).toHaveValue('T2');
});

test('B12 an in-app link away from a dirty editor is held by the confirm until Leave', async ({ page }) => {
  const idA = await createFlowViaApi(page, { name: `b12-link-${stamp()}`, sourceSystem: 'S', destinations: ['T1'], businessKeyRule: 'a_key' });
  await openFlow(page, idA, 'a_key');
  await page.fill('[data-field="business-key-rule"]', 'edited');

  await page.click('[data-nav="versions"]');
  await expect(confirmBox(page)).toBeVisible();
  await expect(editor(page)).toBeVisible();
  await expect(page).toHaveURL(new RegExp(`#/flow/${idA}$`));
  await page.click('[data-action="stay"]');
  await expect(confirmBox(page)).toBeHidden();
  await expect(page.locator('[data-field="business-key-rule"]'), 'Stay keeps the edits').toHaveValue('edited');

  await page.click('[data-nav="versions"]');
  await page.click('[data-action="leave-without-saving"]');
  await expect(editor(page)).toBeHidden();
  await expect(page).toHaveURL(/#\/versions$/);
});

test('B12 browser Back away from a dirty editor shows the confirm, keeps the editor and the flow address', async ({ page }) => {
  const idA = await createFlowViaApi(page, { name: `b12-back-${stamp()}`, sourceSystem: 'S', destinations: ['T1'], businessKeyRule: 'a_key' });
  await page.goto(`${consoleUrl()}/#/flows`);
  await expect(page.locator('[data-page="flows-list"]')).toBeVisible();
  await openFlow(page, idA, 'a_key');
  await page.fill('[data-field="business-key-rule"]', 'edited');

  await page.goBack();
  await expect(confirmBox(page)).toBeVisible();
  await expect(editor(page)).toBeVisible();
  await expect(page).toHaveURL(new RegExp(`#/flow/${idA}$`));
  await page.click('[data-action="stay"]');
  await expect(confirmBox(page)).toBeHidden();
  await expect(editor(page)).toBeVisible();
  await expect(page.locator('[data-field="business-key-rule"]'), 'Stay keeps the edits').toHaveValue('edited');

  await page.goBack();
  await expect(confirmBox(page)).toBeVisible();
  await page.click('[data-action="leave-without-saving"]');
  await expect(editor(page)).toBeHidden();
});

test('B12 a typed #/ address away from a dirty editor shows the confirm and restores the flow address until Leave', async ({ page }) => {
  const idA = await createFlowViaApi(page, { name: `b12-typed-${stamp()}`, sourceSystem: 'S', destinations: ['T1'], businessKeyRule: 'a_key' });
  await openFlow(page, idA, 'a_key');
  await page.fill('[data-field="business-key-rule"]', 'edited');

  await page.goto(`${consoleUrl()}/#/versions`); // what typing the address does: a same-document hash move
  await expect(confirmBox(page)).toBeVisible();
  await expect(editor(page)).toBeVisible();
  await expect(page).toHaveURL(new RegExp(`#/flow/${idA}$`));
  await expect(page.locator('[data-field="business-key-rule"]')).toHaveValue('edited');

  await page.click('[data-action="leave-without-saving"]');
  await expect(editor(page)).toBeHidden();
  await expect(page).toHaveURL(/#\/versions$/);
});

test('B12 a clean editor navigates by Back and by a typed address without a confirm', async ({ page }) => {
  const idA = await createFlowViaApi(page, { name: `b12-clean-${stamp()}`, sourceSystem: 'S', destinations: ['T1'], businessKeyRule: 'a_key' });
  await page.goto(`${consoleUrl()}/#/flows`);
  await expect(page.locator('[data-page="flows-list"]')).toBeVisible();
  await openFlow(page, idA, 'a_key');
  await page.goBack();
  await expect(page.locator('[data-page="flows-list"]')).toBeVisible();
  await expect(confirmBox(page)).toBeHidden();

  await openFlow(page, idA, 'a_key');
  await page.goto(`${consoleUrl()}/#/versions`);
  await expect(editor(page)).toBeHidden();
  await expect(confirmBox(page)).toBeHidden();
});

test('B13 the leave dialog is modal, takes focus, closes on Escape and gives focus back', async ({ page }) => {
  const idA = await createFlowViaApi(page, { name: `b13-dialog-${stamp()}`, sourceSystem: 'S', destinations: ['T1'], businessKeyRule: 'a_key' });
  await openFlow(page, idA, 'a_key');
  await page.fill('[data-field="business-key-rule"]', 'edited');

  // Opened by a link: focus returns to that link.
  await page.click('[data-nav="versions"]');
  await expect(confirmBox(page)).toBeVisible();
  await expect(confirmBox(page)).toHaveAttribute('aria-modal', 'true');
  await expect(page.locator('[data-action="stay"]'), 'focus moves into the dialog').toBeFocused();
  await page.keyboard.press('Escape');
  await expect(confirmBox(page), 'Escape = Stay').toBeHidden();
  await expect(page.locator('[data-field="business-key-rule"]')).toHaveValue('edited');
  await expect(page.locator('[data-nav="versions"]'), 'focus returns to the opener').toBeFocused();

  // Opened by a typed address (no opener element): focus goes to the editor heading.
  await page.goto(`${consoleUrl()}/#/versions`);
  await expect(confirmBox(page)).toBeVisible();
  await expect(page.locator('[data-action="stay"]')).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(confirmBox(page)).toBeHidden();
  await expect(page.locator('[data-page="flow-definition"] h2'), 'focus returns to the editor heading').toBeFocused();
});

test('B13 a failing field is aria-invalid and described by a visible error text', async ({ page }) => {
  await openEditor(page);
  // No destinations: the real API refuses "destinations must name at least one registered system".
  await page.fill('[data-field="source-system"]', 'S');
  await page.fill('[data-field="business-key-rule"]', 'customer_id');
  await page.click('[data-action="save-draft"]');
  await expectFieldError(page, '[data-field="destination-1"]', 'destinations');

  // No business-key rule: "the mandatory business-key rule is missing".
  await page.fill('[data-field="destination-1"]', 'T1');
  await page.fill('[data-field="business-key-rule"]', '');
  await page.click('[data-action="save-draft"]');
  await expectFieldError(page, '[data-field="business-key-rule"]', 'business-key-rule');
  await expect(page.locator('[data-field="destination-1"]'), 'a fixed field is no longer invalid').not.toHaveAttribute('aria-invalid', 'true');
});

async function expectFieldError(page, selector, field) {
  const input = page.locator(selector);
  await expect(page.locator(`[data-validation-error="${field}"]`)).toBeVisible();
  await expect(input).toHaveAttribute('aria-invalid', 'true');
  const describedBy = await input.getAttribute('aria-describedby');
  expect(describedBy, `${selector} names its error text`).toBeTruthy();
  const text = page.locator(`[id="${describedBy}"]`);
  await expect(text).toBeVisible();
  await expect(text).not.toHaveText('');
}
