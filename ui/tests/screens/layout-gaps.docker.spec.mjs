// Row 310 B15 (Varun's measured findings on B9/T88) - layout gaps, measured in the real console.
//
// Every assertion here is a MEASUREMENT (boundingBox / getComputedStyle), never source text:
//  - flow editor: a destination's Remove button bottom-aligns with its input (0px, +-1);
//  - flow editor: the ordering hint sits close to its select, with a clearer gap before "Source schema";
//  - Flows list: Clear lines up with the filter input; the header button lines up with the page title;
//  - B9 kept: 8px between neighbouring Versions action buttons and between a Contracts change note
//    and its Submit button;
//  - B9 scoped: the bare-button sibling gap adds NO margin on the Rules and Mappings pages.
// Seeds through the browser's own signed-in session (the specs' inBrowser pattern), real API only.

import { expect, test } from '@playwright/test';
import { consoleUrl } from './boot-state.mjs';

const RUN = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
const TOLERANCE = 1; // px - sub-pixel rounding only

/** One API call from inside the page: the browser's own session and XSRF cookie, like the console's client. */
async function inBrowser(page, method, apiPath, body, asLogin) {
  return page.evaluate(
    async ({ method, apiPath, body, asLogin }) => {
      const xsrf = document.cookie.split('; ').filter((pair) => pair.startsWith('XSRF-TOKEN=')).pop();
      const headers = {};
      if (body !== undefined) headers['content-type'] = 'application/json';
      if (method !== 'GET' && xsrf) headers['X-XSRF-TOKEN'] = decodeURIComponent(xsrf.slice('XSRF-TOKEN='.length));
      if (asLogin) headers['X-Harness-Login'] = asLogin;
      const answer = await fetch(apiPath, { method, headers, credentials: 'include', body: body === undefined ? undefined : JSON.stringify(body) });
      return { status: answer.status, data: await answer.json().catch(() => null) };
    },
    { method, apiPath, body, asLogin }
  );
}

const must = (answer, what) => {
  if (!(answer.status === 200 || answer.status === 201)) throw new Error(`seed ${what}: HTTP ${answer.status} ${JSON.stringify(answer.data)}`);
  return answer.data;
};

async function box(locator) {
  await expect(locator).toBeVisible();
  const b = await locator.boundingBox();
  if (!b) throw new Error('element has no layout box');
  return { top: b.y, bottom: b.y + b.height, left: b.x, right: b.x + b.width, middle: b.y + b.height / 2 };
}

const marginStart = (locator) => locator.evaluate((el) => getComputedStyle(el).marginInlineStart);

const DRAFT = { sourceSystem: 'S', destinations: ['T1'], businessKeyRule: 'customer_id' };

test.beforeEach(async ({ page }) => {
  await page.goto(consoleUrl());
});

test('flow editor: a destination Remove button bottom-aligns with its input', async ({ page }) => {
  await page.goto(`${consoleUrl()}/#/flow/new`);
  await expect(page.locator('[data-page="flow-definition"]')).toBeVisible();
  await page.click('[data-action="add-destination"]'); // two rows: each gets its Remove button

  for (const n of [1, 2]) {
    const input = await box(page.locator(`[data-field="destination-${n}"]`));
    const remove = await box(page.locator(`[data-action="remove-destination-${n}"]`));
    expect(Math.abs(remove.bottom - input.bottom), `Remove ${n} bottom vs input bottom`).toBeLessThanOrEqual(TOLERANCE);
  }
});

test('flow editor: the ordering hint sits close to its select, with a clearer gap before Source schema', async ({ page }) => {
  await page.goto(`${consoleUrl()}/#/flow/new`);
  await expect(page.locator('[data-page="flow-definition"]')).toBeVisible();

  const select = await box(page.locator('[data-field="ordering-setting"]'));
  const hint = await box(page.locator('#flow-ordering-help'));
  const schemaLabel = await box(page.locator('label:has([data-field="source-schema"])'));

  const toField = hint.top - select.bottom;
  const toNext = schemaLabel.top - hint.bottom;
  expect(toField, 'hint to its select: the 4px field gap').toBeLessThanOrEqual(4 + TOLERANCE);
  expect(toField).toBeGreaterThanOrEqual(0);
  expect(toNext, 'hint to the next field: a clear 12px').toBeGreaterThanOrEqual(12 - TOLERANCE);
  expect(toNext, 'the hint reads as part of the field above, not the one below').toBeGreaterThan(toField);
});

test('Flows list: Clear lines up with the filter input, and the header button with the page title', async ({ page }) => {
  must(await inBrowser(page, 'POST', '/api/v1/admin/flows', DRAFT), 'draft flow'); // the filter shows only with rows
  await page.goto(`${consoleUrl()}/#/flows`);
  await expect(page.locator('[data-page="flows-list"][data-flows-loaded="true"]')).toBeVisible();

  const filter = await box(page.locator('[data-field="flows-filter"]'));
  const clear = await box(page.locator('[data-action="clear-filter"]'));
  expect(Math.abs(clear.middle - filter.middle), 'Clear centre vs filter input centre').toBeLessThanOrEqual(TOLERANCE);

  const title = await box(page.locator('[data-page="flows-list"] .flows-head h2'));
  const define = await box(page.locator('[data-action="define-flow"]'));
  expect(Math.abs(define.middle - title.middle), 'header button centre vs title centre').toBeLessThanOrEqual(TOLERANCE);
});

test('Versions: 8px between neighbouring action buttons on the action row', async ({ page }) => {
  const flowId = must(await inBrowser(page, 'POST', '/api/v1/admin/flows', DRAFT), 'draft flow').id;
  await page.click('[data-nav="versions"]');
  await page.click(`[data-flow-row="${flowId}"]`);
  await expect(page.locator(`[data-flow-row="${flowId}"] [data-version-status="DRAFT"]`)).toBeVisible();

  const save = await box(page.locator('[data-action="save-draft"]'));
  const submit = await box(page.locator('[data-action="submit-for-approval"]'));
  expect(Math.abs(submit.left - save.right - 8), 'Save draft -> Submit gap').toBeLessThanOrEqual(TOLERANCE);
});

test('Contracts: 8px between a draft version\'s change note and its Submit button', async ({ page }) => {
  const system = `tgt-gap-${RUN}`;
  must(await inBrowser(page, 'POST', '/api/v1/admin/systems', {
    code: system, name: `${system} target`, direction: 'OUTBOUND', type: 'API', settings: { baseUrl: `https://${system}.partner.test/` }
  }), `system ${system}`);
  must(await inBrowser(page, 'POST', '/api/v1/admin/contracts/versions/import', {
    system, role: 'TARGET_REQUEST', versionLabel: '1.0', format: 'JSON_SCHEMA',
    document: { $schema: 'https://json-schema.org/draft/2020-12/schema', type: 'object', properties: { id: { type: 'string' } } }
  }), `contract ${system}`);
  await page.click('[data-nav="contracts"]');
  await expect(page.locator('[data-page="contracts"][data-contracts-loaded="true"]')).toBeVisible();

  const row = page.locator(`[data-version-key="${system}/TARGET_REQUEST/1.0"]`);
  const note = await box(row.locator('[data-field="change-note"]'));
  const submit = await box(row.locator('[data-action="submit-version"]'));
  expect(Math.abs(submit.left - note.right - 8), 'change note -> Submit gap').toBeLessThanOrEqual(TOLERANCE);
});

test('Rules pages: a bare button after a field or button gets no added margin', async ({ page }) => {
  const flowId = must(await inBrowser(page, 'POST', '/api/v1/admin/flows', DRAFT), 'draft flow').id;
  await page.goto(`${consoleUrl()}/#/rules`);
  await expect(page.locator('[data-page="rules-home"]')).toBeVisible();
  expect(await marginStart(page.locator('[data-action="create-rule-set"]')), 'Rules home: Create').toBe('0px');

  const ruleSet = must(await inBrowser(page, 'POST', `/api/v1/admin/flows/${flowId}/rule-sets`, {
    kind: 'ROUTING',
    name: `gaps-${RUN}`,
    content: {
      nodes: [
        { id: 'request', type: 'inputNode', name: 'Request', position: { x: 0, y: 0 } },
        { id: 'checks', type: 'decisionTableNode', name: 'Checks', position: { x: 240, y: 0 },
          content: { hitPolicy: 'first', inputs: [{ id: 'c1', name: 'Country', field: 'country' }], outputs: [{ id: 'o1', name: 'Target', field: 'target' }],
            rules: [{ _id: 'r1', c1: '"NL"', o1: '"dst-eu"' }] } },
        { id: 'response', type: 'outputNode', name: 'Response', position: { x: 480, y: 0 } }
      ],
      edges: [
        { id: 'e1', sourceId: 'request', targetId: 'checks', type: 'edge' },
        { id: 'e2', sourceId: 'checks', targetId: 'response', type: 'edge' }
      ]
    }
  }), 'rule set');
  await page.goto(`${consoleUrl()}/#/rules/${ruleSet.id}`);
  await expect(page.locator('[data-page="rules-editor"] [data-action="save-rule-set"]')).toBeVisible();
  expect(await marginStart(page.locator('[data-action="save-rule-set"]')), 'Rules editor: Save').toBe('0px');
  expect(await marginStart(page.locator('[data-test-panel-run]')), 'Rules test panel: Run').toBe('0px');
});

test('Mappings page: a bare button after a button gets no added margin', async ({ page }) => {
  const source = `src-gap-${RUN}`;
  const target = `tgt-gapm-${RUN}`;
  for (const [code, direction] of [[source, 'INBOUND'], [target, 'OUTBOUND']]) {
    must(await inBrowser(page, 'POST', '/api/v1/admin/systems', { code, name: code, direction, type: 'API', settings: { baseUrl: `https://${code}.partner.test/` } }), `system ${code}`);
  }
  const flowId = must(await inBrowser(page, 'POST', '/api/v1/admin/flows', { sourceSystem: source, destinations: [target], businessKeyRule: 'orderId' }), 'flow').id;
  const routes = must(await inBrowser(page, 'GET', `/api/v1/admin/flows/${flowId}/routes`), `routes of ${flowId}`).routes ?? [];
  if (routes.length === 0) throw new Error(`flow ${flowId} lists no routes`);

  await page.goto(`${consoleUrl()}/#/mappings/${routes[0].id}`);
  await expect(page.locator('[data-page="mapping-designer"][data-mapping-loaded="true"]')).toBeVisible();
  expect(await marginStart(page.locator('[data-action="add-link"]')), 'Mappings: Link selected').toBe('0px');
  expect(await marginStart(page.locator('[data-action="save"]')), 'Mappings: Save after Link').toBe('0px');
});
