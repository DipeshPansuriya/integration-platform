// T-034 + T-035 (E7 wave 1 lane D1, row 278) - the mapping designer canvas, FAILING FIRST.
//
// E7-F2-S1 against the REAL API (no mock, E3-F3-S1), scenarios from backlog.md:1824-1844:
//
//   Map Customer A to Target A v1.0        - links drawn on the pair, stored for that pair only,
//                                            re-rendered exactly as left after a reload.
//   Add, change and delete elements        - a link added, changed and deleted; all three stored.
//   Two targets never share one mapping    - the UI leg: Target A's save never touches Target B
//                                            (separate routes, separate rows), and the other way.
//   A link targeting a field the target contract does not have is refused - on the canvas, naming
//                                            the field; nothing is saved.
//
// Row 278 close-E7 (T-162 gaps, FAILING FIRST) - the screens a human uses in the 30-minute trial:
//   T93 open a mapping from the screens    - Mappings menu -> the run's "orders" flow -> its Target B
//                                            route -> "Open mapping": clicks only, no typed address.
//   T92 run a sample on the saved mapping  - the two-target orders example on Target B; the output is shown.
//   T92 the refusals are shown              - not JSON / unsaved drawing: refused, no call; a server
//                                            refusal shows the server's message.
//
// Seeding through the real API from a signed-in page inside this file (the contracts spec's
// pattern): source + two target systems, their contract versions (Target A/B approved through the
// harness's second binding, the approver - never the author), one flow "orders" with the two
// destinations. The flow's routes are rows the API does not list today, so the route ids are read
// read-only from the suite's own pinned postgres container (`ipass-ui-t41-pg-*`, named by
// boot-backend.mjs; the docker-suite lock guarantees it is this run's container).

import { execFileSync } from 'node:child_process';
import { suitePgContainer } from './suite-pg.mjs';
import { expect, test } from '@playwright/test';
import { consoleUrl } from './boot-state.mjs';

const PAGE = '[data-page="mapping-designer"]';
const RUN = Date.now().toString(36);
const SOURCE_SYSTEM = `src-orders-${RUN}`;
const TARGET_A = `tgt-a-${RUN}`;
const TARGET_B = `tgt-b-${RUN}`;

const CUSTOMER_A_V1 = {
  $schema: 'https://json-schema.org/draft/2020-12/schema',
  type: 'object',
  required: ['customerId', 'orderId', 'amount'],
  properties: { customerId: { type: 'string' }, orderId: { type: 'string' }, amount: { type: 'number' } }
};
const TARGET_A_V1_SCHEMA = {
  $schema: 'https://json-schema.org/draft/2020-12/schema',
  type: 'object',
  required: ['customer_code', 'order_number', 'total_amount'],
  properties: { customer_code: { type: 'string' }, order_number: { type: 'string' }, total_amount: { type: 'number' } }
};
const TARGET_B_V2_SCHEMA = {
  $schema: 'https://json-schema.org/draft/2020-12/schema',
  type: 'object',
  required: ['clientId', 'referenceNo', 'invoiceValue'],
  properties: { clientId: { type: 'string' }, referenceNo: { type: 'string' }, invoiceValue: { type: 'number' } }
};

/** One API call from inside the page: the browser's own session and CSRF cookies (contracts spec's pattern). */
async function inBrowser(page, method, apiPath, body, asLogin) {
  return page.evaluate(
    async ({ method, apiPath, body, asLogin }) => {
      const xsrf = document.cookie.split('; ').filter((pair) => pair.startsWith('XSRF-TOKEN=')).pop();
      const headers = {};
      if (body !== undefined) headers['content-type'] = 'application/json';
      if (method !== 'GET' && xsrf) headers['X-XSRF-TOKEN'] = decodeURIComponent(xsrf.slice('XSRF-TOKEN='.length));
      if (asLogin) headers['X-Harness-Login'] = asLogin;
      const answer = await fetch(apiPath, {
        method, headers, credentials: 'include',
        body: body === undefined ? undefined : JSON.stringify(body)
      });
      return { status: answer.status, data: await answer.json().catch(() => null) };
    },
    { method, apiPath, body, asLogin }
  );
}

const must = (answer, what) => {
  if (!(answer.status === 200 || answer.status === 201)) {
    throw new Error(`seed ${what}: HTTP ${answer.status} ${JSON.stringify(answer.data)}`);
  }
  return answer.data;
};

/** The suite's own pinned postgres (boot-backend names it ipass-ui-t41-pg-*) asked read-only for route ids. */
function routeIdsOfInterface(interfaceId) {
  const names = [suitePgContainer()]; // L22: this run's postgres (IPASS_UI_PG_CONTAINER), else the single running one
  const rows = execFileSync('docker', ['exec', names[0], 'psql', '-U', 'owner', '-d', 'platform', '-t', '-A',
    '-c', `select r.sequence, m.route_id::text from public.route r join public.mapping m on m.route_id = r.id`
      + ` where r.interface_id = '${interfaceId}' order by r.sequence`], { encoding: 'utf8' });
  return rows.split('\n').filter(Boolean).map((row) => row.trim().split('|')[1]);
}

const sourceNode = (page, path) => page.locator(`[data-tree="source"] [data-node="${path}"]`);
const targetNode = (page, path) => page.locator(`[data-tree="target"] [data-node="${path}"]`);
const linkRows = (page) => page.locator('[data-link-row]');

/** Opens the canvas for one route and waits until the saved mapping and the contract lists are loaded. */
async function openCanvas(page, routeId) {
  await page.goto(`${consoleUrl()}/#/mappings/${routeId}`);
  await expect(page.locator(`${PAGE}[data-mapping-loaded="true"]`)).toBeVisible();
}

/** Selects a saved link's target path in the DOM editor of its row (the "a link changed" edit). */
function targetInputOf(page, source, target) {
  return page.locator(`[data-link-row="${source}->${target}"] [data-field="link-target"]`);
}

test.describe.configure({ mode: 'serial' });

let FLOW_ID;
let ROUTE_A;
let ROUTE_B;
let SOURCE_VERSION_ID;
let TARGET_A_VERSION_ID;
let TARGET_B_VERSION_ID;

test.beforeAll(async ({ browser }) => {
  const context = await browser.newContext();
  const page = await context.newPage();
  try {
    await page.goto(consoleUrl());
    for (const s of [
      { code: SOURCE_SYSTEM, name: 'Customer A', direction: 'INBOUND' },
      { code: TARGET_A, name: 'Target A', direction: 'OUTBOUND' },
      { code: TARGET_B, name: 'Target B', direction: 'OUTBOUND' }
    ]) {
      must(await inBrowser(page, 'POST', '/api/v1/admin/systems', {
        code: s.code, name: s.name, direction: s.direction, type: 'API',
        settings: { baseUrl: `https://${s.code}.partner.test/` }
      }), `system ${s.code}`);
    }

    const importVersion = async (system, role, label, document) => must(
      await inBrowser(page, 'POST', '/api/v1/admin/contracts/versions/import',
        { system, role, versionLabel: label, format: 'JSON_SCHEMA', document }),
      `contract ${system}/${role}/${label}`);

    const sourceVersion = importVersion(SOURCE_SYSTEM, 'SOURCE_REQUEST', '1.0', CUSTOMER_A_V1); // draft source is allowed
    const targetA = importVersion(TARGET_A, 'TARGET_REQUEST', '1.0', TARGET_A_V1_SCHEMA);
    const targetB = importVersion(TARGET_B, 'TARGET_REQUEST', '2.0', TARGET_B_V2_SCHEMA);
    SOURCE_VERSION_ID = (await sourceVersion).id;
    TARGET_A_VERSION_ID = (await targetA).id;
    TARGET_B_VERSION_ID = (await targetB).id;
    for (const [id, label] of [[TARGET_A_VERSION_ID, 'Target A v1.0'], [TARGET_B_VERSION_ID, 'Target B v2.0']]) {
      must(await inBrowser(page, 'POST', `/api/v1/admin/contracts/versions/${id}/submit`,
        { changeNote: `ready for mapping (${label})` }), `submit ${label}`);
      must(await inBrowser(page, 'POST', `/api/v1/admin/contracts/versions/${id}/approve`, undefined, 'approver'),
        `approve ${label}`);
    }

    // Interface "orders": one flow, two destinations -> two routes, each with its own mapping row.
    const flow = must(await inBrowser(page, 'POST', '/api/v1/admin/flows', {
      code: `orders-${RUN}`, name: 'orders', sourceSystem: SOURCE_SYSTEM,
      destinations: [TARGET_A, TARGET_B], businessKeyRule: 'orderId'
    }), 'flow orders');
    FLOW_ID = flow.id;
    [ROUTE_A, ROUTE_B] = routeIdsOfInterface(flow.id);
  } finally {
    await context.close();
  }
});

// Scenario: Map Customer A to Target A v1.0 (backlog.md:1824-1829).
test('maps Customer A to Target A v1.0 by drawing the links on the pair', async ({ page }) => {
  await openCanvas(page, ROUTE_A);

  await page.locator('[data-field="source-version"]').selectOption(SOURCE_VERSION_ID);
  await page.locator('[data-field="target-version"]').selectOption(TARGET_A_VERSION_ID);
  for (const field of ['customerId', 'orderId', 'amount']) {
    await expect(sourceNode(page, field)).toBeVisible();
  }
  for (const field of ['customer_code', 'order_number', 'total_amount']) {
    await expect(targetNode(page, field)).toBeVisible();
  }

  // link customerId -> customer_code, orderId -> order_number, amount -> total_amount (backlog.md:1827)
  for (const [from, to] of [['customerId', 'customer_code'], ['orderId', 'order_number'], ['amount', 'total_amount']]) {
    await sourceNode(page, from).click();
    await targetNode(page, to).click();
    await page.click('[data-action="add-link"]');
  }
  await page.click('[data-action="save"]');
  await expect(page.locator('[data-saved="true"]')).toBeVisible();

  // the mapping is stored for that pair and re-renders exactly as left
  await page.reload();
  await expect(page.locator(`${PAGE}[data-mapping-loaded="true"]`)).toBeVisible();
  await expect(page.locator('[data-field="target-version"]')).toHaveValue(TARGET_A_VERSION_ID, { timeout: 10_000 });
  await expect(linkRows(page)).toHaveCount(3);
  await expect(targetInputOf(page, 'customerId', 'customer_code')).toHaveValue('customer_code');
  await expect(targetInputOf(page, 'orderId', 'order_number')).toHaveValue('order_number');
  await expect(targetInputOf(page, 'amount', 'total_amount')).toHaveValue('total_amount');
  await expect(sourceNode(page, 'customerId')).toBeVisible();
  await expect(targetNode(page, 'total_amount')).toBeVisible();
});

// Scenario: Add, change and delete elements (backlog.md:1831-1834).
test('an element added, a link changed and a link deleted all reach the stored mapping', async ({ page }) => {
  await openCanvas(page, ROUTE_A);
  // Target A has exactly three fields, all drawn: the delete comes first so the change has a free
  // target - a saved drawing never draws one target twice (T57: such a save is refused).

  // an element deleted: amount -> total_amount (total_amount is free now)
  await page.locator(`[data-link-row="amount->total_amount"] [data-action="delete-link"]`).click();
  await expect(linkRows(page)).toHaveCount(2);

  // a link changed: orderId now feeds total_amount (order_number is free now)
  await targetInputOf(page, 'orderId', 'order_number').fill('total_amount');
  await expect(page.locator('[data-link-row="orderId->total_amount"]')).toHaveCount(1);

  // an element is added: orderId -> order_number
  await sourceNode(page, 'orderId').click();
  await targetNode(page, 'order_number').click();
  await page.click('[data-action="add-link"]');
  await expect(linkRows(page)).toHaveCount(3);

  await page.click('[data-action="save"]');
  await expect(page.locator('[data-saved="true"]')).toBeVisible();

  await page.reload();
  await expect(page.locator(`${PAGE}[data-mapping-loaded="true"]`)).toBeVisible();
  await expect(linkRows(page)).toHaveCount(3, { timeout: 10_000 });
  await expect(targetInputOf(page, 'customerId', 'customer_code')).toHaveValue('customer_code');
  await expect(targetInputOf(page, 'orderId', 'total_amount')).toHaveValue('total_amount');
  await expect(targetInputOf(page, 'orderId', 'order_number')).toHaveValue('order_number');
  await expect(page.locator('[data-link-row="amount->total_amount"]')).toHaveCount(0);

  // T57: one target drawn twice (amount -> total_amount beside orderId -> total_amount) - the save is
  // refused with the emitter's refusal, nothing is saved and the stored mapping is unchanged.
  await sourceNode(page, 'amount').click();
  await targetNode(page, 'total_amount').click();
  await page.click('[data-action="add-link"]');
  await expect(linkRows(page)).toHaveCount(4);
  await page.click('[data-action="save"]');
  await expect(page.locator('[data-refusal]')).toContainText('drawn twice');
  await expect(page.locator('[data-saved="true"]')).toHaveCount(0);
  const stored = await inBrowser(page, 'GET', `/api/v1/admin/routes/${ROUTE_A}/mapping`);
  expect(stored.data.fieldMappings.map((l) => [l.source, l.target]))
    .toEqual([['customerId', 'customer_code'], ['orderId', 'total_amount'], ['orderId', 'order_number']]);

  // back to Target A as first drawn for the next scenarios: orderId -> total_amount deleted, saved
  await page.locator(`[data-link-row="orderId->total_amount"] [data-action="delete-link"]`).click();
  await expect(linkRows(page)).toHaveCount(3);
  await page.click('[data-action="save"]');
  await expect(page.locator('[data-saved="true"]')).toBeVisible();
});

// Scenario: Two targets never share one mapping - the UI leg (backlog.md:1836-1839).
test('Target A\'s mapping edits leave Target B\'s mapping untouched, and the other way round', async ({ page }) => {
  await page.goto(consoleUrl()); // the page context the seeding GETs run in (console origin's cookies)
  const bBefore = await inBrowser(page, 'GET', `/api/v1/admin/routes/${ROUTE_B}/mapping`);
  expect(bBefore.status).toBe(200);
  expect(bBefore.data.fieldMappings).toEqual([]);

  // Target A's mapping is changed again and saved
  await openCanvas(page, ROUTE_A);
  // (deleted and drawn again: Target A's three fields are all drawn, so no target is free to change to)
  await page.locator(`[data-link-row="amount->total_amount"] [data-action="delete-link"]`).click();
  await sourceNode(page, 'amount').click();
  await targetNode(page, 'total_amount').click();
  await page.click('[data-action="add-link"]');
  await expect(linkRows(page)).toHaveCount(3);
  await page.click('[data-action="save"]');
  await expect(page.locator('[data-saved="true"]')).toBeVisible();

  const bAfterAsSave = await inBrowser(page, 'GET', `/api/v1/admin/routes/${ROUTE_B}/mapping`);
  expect(bAfterAsSave.data.mappingId).toEqual(bBefore.data.mappingId); // separate row per target
  expect(bAfterAsSave.data.fieldMappings).toEqual([]);

  // Target B's mapping is bound and saved on its own canvas; Target A keeps its content
  await openCanvas(page, ROUTE_B);
  await page.locator('[data-field="source-version"]').selectOption(SOURCE_VERSION_ID);
  await page.locator('[data-field="target-version"]').selectOption(TARGET_B_VERSION_ID);
  await sourceNode(page, 'customerId').click();
  await targetNode(page, 'clientId').click();
  await page.click('[data-action="add-link"]');
  await page.click('[data-action="save"]');
  await expect(page.locator('[data-saved="true"]')).toBeVisible();

  const aAfterBSave = await inBrowser(page, 'GET', `/api/v1/admin/routes/${ROUTE_A}/mapping`);
  expect(aAfterBSave.data.targetContractVersionId).toEqual(TARGET_A_VERSION_ID);
  expect(aAfterBSave.data.fieldMappings.map((l) => [l.source, l.target]))
    .toEqual([['customerId', 'customer_code'], ['orderId', 'order_number'], ['amount', 'total_amount']]);
});

// Scenario: A link to a field the target contract does not have is refused - names the field (backlog.md:1841-1844).
test('a link changed to a field Target A does not have is refused on the canvas, naming the field', async ({ page }) => {
  await openCanvas(page, ROUTE_A);
  await targetInputOf(page, 'amount', 'total_amount').fill('customerCod');
  await expect(page.locator('[data-refusal]')).toBeVisible();
  await expect(page.locator('[data-refusal]')).toContainText('customerCod');
  await expect(linkRows(page)).toHaveCount(3); // refused: still the three saved links, nothing saved
  await page.reload();
  await expect(page.locator(`${PAGE}[data-mapping-loaded="true"]`)).toBeVisible();
  await expect(linkRows(page)).toHaveCount(3, { timeout: 10_000 });
  await expect(targetInputOf(page, 'amount', 'total_amount')).toHaveValue('total_amount');
});

// Row 310 T103 (wave 4 lane E, 10-screens s8 a11y): the canvas is decoration - its only tab stops are the
// node buttons (aria-pressed twin); every drawn link is an edge whose label is mono ink, in both themes;
// the links table stays below it as the accessible, editable truth.
test('T103: the canvas is non-focusable decoration; edges carry mono ink labels; the links table is the truth', async ({ page }) => {
  await openCanvas(page, ROUTE_A);
  await expect(linkRows(page)).toHaveCount(3, { timeout: 10_000 });
  await expect(page.locator('table:has(caption:text("Drawn links"))')).toBeVisible();

  const canvas = page.locator('[data-canvas="mapping"]');
  await expect(canvas.locator('[data-node]').first()).toBeVisible();
  const stops = await canvas.evaluate((root) => [...root.querySelectorAll('*')]
    .filter((el) => el.tabIndex >= 0 && !el.disabled && !(el instanceof HTMLButtonElement && el.hasAttribute('data-node')))
    .map((el) => el.className || el.tagName));
  expect(stops, 'tab stops in the canvas other than the node buttons').toEqual([]);
  expect(await canvas.evaluate((root) => root.tabIndex)).toBeLessThan(0);

  const labels = canvas.locator('.react-flow__edge-text');
  await expect(labels).toHaveCount(3);
  await expect(labels.first()).toContainText('→');
  for (const theme of ['light', 'dark']) {
    await page.evaluate((t) => document.documentElement.setAttribute('data-theme', t), theme);
    const look = await labels.first().evaluate((el) => {
      const probe = document.createElement('span');
      probe.style.color = 'var(--color-ink)';
      probe.style.fontFamily = 'var(--font-mono)';
      el.closest('[data-canvas]').appendChild(probe);
      const want = getComputedStyle(probe);
      const got = getComputedStyle(el);
      const answer = { fill: got.fill, ink: want.color, font: got.fontFamily, mono: want.fontFamily, size: parseFloat(got.fontSize) };
      probe.remove();
      return answer;
    });
    expect(look.fill, `${theme}: edge label fill is the ink token`).toBe(look.ink);
    expect(look.font, `${theme}: edge label is the mono token`).toBe(look.mono);
    expect(look.size, `${theme}: 12.5px floor`).toBeGreaterThanOrEqual(12.5);
  }
});

/** Every POST to the mapping test endpoint this page makes (the "no call" proof of the refusals). */
function sampleCalls(page) {
  const calls = [];
  page.on('request', (request) => {
    if (request.method() === 'POST' && /\/api\/v1\/admin\/mappings\/[^/]+\/test$/.test(new URL(request.url()).pathname)) {
      calls.push(request.url());
    }
  });
  return calls;
}

// T93 (row 278 close-E7, T-162 gap 2): a mapping is opened from the screens - clicks only.
// S10 pre-condition: the legacy Mappings menu item is gone - reached through the flow workspace's
// Mapping tab, then the designer's own "All mappings" link re-opens the worklist.
test("T93: the flow workspace opens a route's mapping, and the worklist lists the run's flows and routes", async ({ page }) => {
  await page.goto(`${consoleUrl()}/#/flow/${FLOW_ID}`);
  await expect(page.locator('[data-page="flow-definition"]')).toBeVisible();
  await page.click('[data-flow-tab="mapping"]');
  await expect(page.locator(`${PAGE}[data-mapping-loaded="true"]`)).toBeVisible(); // the Mapping tab opens routes[0]'s designer
  await page.click('[data-action="all-mappings"]');
  const flows = page.locator('[data-mapping-flows]');
  await expect(flows).toBeVisible();
  const flow = page.locator(`[data-mapping-flow="${FLOW_ID}"]`);
  await expect(flow).toContainText('orders');
  await flow.click();

  const routeB = page.locator(`[data-mapping-route="${ROUTE_B}"]`);
  await expect(routeB).toContainText(TARGET_B);
  await expect(page.locator(`[data-mapping-route="${ROUTE_A}"]`)).toContainText(TARGET_A);
  await routeB.locator('a[data-action="open-mapping"]').click();

  await expect(page).toHaveURL(new RegExp(`#/mappings/${ROUTE_B}$`));
  await expect(page.locator(`${PAGE}[data-mapping-loaded="true"]`)).toBeVisible();
  await expect(page.locator('[data-field="target-version"]')).toHaveValue(TARGET_B_VERSION_ID, { timeout: 10_000 });
});

// Row 310 T103 (wave 4 lane E): the #/mappings landing is a worklist - Route, Interface, Target system,
// Mapping (a worded badge, never colour alone), Open - clicks only, unchanged hash URLs.
test('T103: the Mappings landing is a worklist with worded mapping badges', async ({ page }) => {
  await page.goto(`${consoleUrl()}/#/mappings`);
  await expect(page.locator(`${PAGE}[data-mapping-loaded="true"]`)).toBeVisible();
  await page.locator(`[data-mapping-flow="${FLOW_ID}"]`).click();
  await expect(page.locator(`[data-mapping-flow="${FLOW_ID}"]`)).toHaveAttribute('aria-pressed', 'true');
  const routeA = page.locator(`[data-mapping-route="${ROUTE_A}"]`);
  await expect(routeA).toBeVisible();
  const headers = await page.locator('[data-mapping-routes] thead th').allInnerTexts();
  expect(headers.map((h) => h.trim())).toEqual(['Route', 'Interface', 'Target system', 'Mapping', 'Open']);
  await expect(routeA.locator('.badge')).toHaveText(/^(Saved|Not saved)$/);
  await expect(routeA).toContainText('orders');
  // the content column holds the 1280px floor with zero horizontal overflow
  await page.setViewportSize({ width: 1280, height: 900 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
});

// T92 (row 278 close-E7, T-162 gap 1): the two-target orders example on the Target B route - draw, save, run a sample.
test('T92: Customer A v1.0 -> Target B v2.0 drawn and saved, "Run sample" shows the mapped output', async ({ page }) => {
  await openCanvas(page, ROUTE_B);
  await page.locator('[data-field="source-version"]').selectOption(SOURCE_VERSION_ID);
  await page.locator('[data-field="target-version"]').selectOption(TARGET_B_VERSION_ID);
  await expect(targetNode(page, 'invoiceValue')).toBeVisible();
  // a clean drawing: the earlier scenario left customerId -> clientId on this pair
  while (await linkRows(page).count() > 0) {
    await linkRows(page).first().locator('[data-action="delete-link"]').click();
  }
  for (const [from, to] of [['customerId', 'clientId'], ['orderId', 'referenceNo'], ['amount', 'invoiceValue']]) {
    await sourceNode(page, from).click();
    await targetNode(page, to).click();
    await page.click('[data-action="add-link"]');
  }
  await expect(linkRows(page)).toHaveCount(3);
  await page.click('[data-action="save"]');
  await expect(page.locator('[data-saved="true"]')).toHaveText('Mapping saved.');

  const panel = page.locator('[data-sample-panel]');
  await expect(panel).toBeVisible();
  const result = page.locator('[data-sample-result]');
  await expect(result).toHaveAttribute('data-state', 'idle');
  await page.locator('[data-sample-input]').fill('{"customerId":"C-0001","orderId":"O-777","amount":42.5}');
  await expect(page.locator('[data-sample-run]')).toHaveText('Run sample');
  await page.click('[data-sample-run]');

  await expect(result).toHaveAttribute('data-state', 'ok');
  await expect(result).toContainText('OK');
  const output = JSON.parse(await result.locator('[data-sample-output]').textContent());
  expect(output).toEqual({ clientId: 'C-0001', referenceNo: 'O-777', invoiceValue: 42.5 });
});

// T92: every refusal is on screen - not JSON and an unsaved drawing make no call; a server refusal is shown.
test('T92: a sample that is not JSON or an unsaved drawing is refused with no call; a server refusal is shown', async ({ page }) => {
  const calls = sampleCalls(page);
  await openCanvas(page, ROUTE_B);
  const result = page.locator('[data-sample-result]');
  await expect(result).toHaveAttribute('data-state', 'idle');

  await page.locator('[data-sample-input]').fill('{"customerId":');
  await page.click('[data-sample-run]');
  await expect(result).toHaveAttribute('data-state', 'refused');
  await expect(result).toContainText('not valid JSON');

  // an unsaved edit on the drawing: the sample would run the saved mapping, not this drawing
  await page.locator('[data-link-row="amount->invoiceValue"] [data-action="delete-link"]').click();
  await page.locator('[data-sample-input]').fill('{"customerId":"C-0001"}');
  await page.click('[data-sample-run]');
  await expect(result).toHaveAttribute('data-state', 'refused');
  await expect(result).toContainText('Save the mapping first - the sample runs the saved mapping');
  expect(calls).toEqual([]);

  // a server refusal: the reload drops the unsaved edit; a null sample is the server's own 400
  await page.reload();
  await expect(page.locator(`${PAGE}[data-mapping-loaded="true"]`)).toBeVisible();
  await expect(linkRows(page)).toHaveCount(3, { timeout: 10_000 });
  await page.locator('[data-sample-input]').fill('null');
  await page.click('[data-sample-run]');
  await expect(result).toHaveAttribute('data-state', 'refused');
  await expect(result).toContainText('the mandatory sample is missing');
  expect(calls).toHaveLength(1);
});
