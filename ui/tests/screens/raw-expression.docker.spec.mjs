// T124 (row 278 E7 housekeeping C, lane D, 2026-10-16) - the RAW-EXPRESSION editor of the mapping
// designer has no screens coverage (kavita/Varun follow-up m-c91a37: "no raw-editor screens E2E").
// This file closes that gap against the REAL API (E3-F3-S1: no mock, no stub): the CodeMirror 6
// surface of RawExpressionEditor.tsx / raw.ts (T-039, ADR-006 Decision 7 as amended 2026-10-15 -
// plain CodeMirror 6, the Dashjoin JSONata 0.9.10 language), opened on #/mappings/<route>.
//
// Scenarios (scope = what the UX actually offers, MappingDesigner.tsx:394-401 + raw.ts):
//   opens from the designer      - the raw card renders on the route's canvas and first shows
//                                  exactly the expression the mapping keeps (drawn equals typed).
//   a valid edit applies         - the typed JSONata lands back through applyExpressionEdit, saves
//                                  (data-saved) and is stored - the API's compiledExpression and a
//                                  reload prove the saved state.
//   @negative a $round edit      - ADR-006 rule 8: the refusal shows naming the rule and the edit
//          never saves            is NOT applied; saving after it still stores the previous
//                                  expression (raw.ts: "the graph keeps its previous expression").
//   an edit left unsaved is      - the raw editor offers NO explicit discard/cancel control; the
//   discarded                      discard the UX does offer is leaving without saving: a reload
//                                  restores exactly the stored mapping.
//
// Seeding: the mapping-designer spec's own pattern - systems and contract versions through the real
// API from a signed-in page, one flow with one destination, and its route id read read-only from
// the suite's pinned postgres (the docker-suite lock guarantees it is this run's container).

import { execFileSync } from 'node:child_process';
import { suitePgContainer } from './suite-pg.mjs';
import { expect, test } from '@playwright/test';
import { consoleUrl } from './boot-state.mjs';

const PAGE = '[data-page="mapping-designer"]';
const RAW = '[data-field="raw-expression"] .cm-content';
const RUN = Date.now().toString(36);
const SOURCE_SYSTEM = `src-raw-${RUN}`;
const TARGET = `tgt-raw-${RUN}`;

const SOURCE_V1 = {
  $schema: 'https://json-schema.org/draft/2020-12/schema',
  type: 'object',
  required: ['customerId', 'amount'],
  properties: { customerId: { type: 'string' }, amount: { type: 'number' } }
};
const TARGET_V1_SCHEMA = {
  $schema: 'https://json-schema.org/draft/2020-12/schema',
  type: 'object',
  required: ['clientId'],
  properties: { clientId: { type: 'string' } }
};

/** One API call from inside the page: the browser's own session and CSRF cookies (mapping-designer spec's pattern). */
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

/** Opens the canvas for one route and waits until the saved mapping and the contract lists are loaded. */
async function openCanvas(page, routeId) {
  await page.goto(`${consoleUrl()}/#/mappings/${routeId}`);
  await expect(page.locator(`${PAGE}[data-mapping-loaded="true"]`)).toBeVisible();
}

/** The text the CodeMirror document holds now (zero-width caret anchors removed; never the DOM chrome). */
async function editorText(page) {
  const text = await page.locator(RAW).innerText();
  return text.replace(/\u200b/g, ''); // zero-width caret anchors CodeMirror inserts at the caret
}

/** Replaces the whole document the way a configurator does (rules-editor's CM technique). */
async function typeRaw(page, text) {
  await page.locator(RAW).click();
  await page.keyboard.press('ControlOrMeta+A');
  await page.keyboard.type(text);
}

const stored = async (page, routeId) => must(await inBrowser(page, 'GET', `/api/v1/admin/routes/${routeId}/mapping`), 'read mapping');

test.describe.configure({ mode: 'serial' });

let ROUTE;
let SOURCE_VERSION_ID;
let TARGET_VERSION_ID;

test.beforeAll(async ({ browser }) => {
  const context = await browser.newContext();
  const page = await context.newPage();
  try {
    await page.goto(consoleUrl());
    for (const s of [
      { code: SOURCE_SYSTEM, name: 'Customer R', direction: 'INBOUND' },
      { code: TARGET, name: 'Target R', direction: 'OUTBOUND' }
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
    SOURCE_VERSION_ID = (await importVersion(SOURCE_SYSTEM, 'SOURCE_REQUEST', '1.0', SOURCE_V1)).id;
    TARGET_VERSION_ID = (await importVersion(TARGET, 'TARGET_REQUEST', '1.0', TARGET_V1_SCHEMA)).id;
    must(await inBrowser(page, 'POST', `/api/v1/admin/contracts/versions/${TARGET_VERSION_ID}/submit`,
      { changeNote: `ready for the raw editor (${TARGET})` }), 'submit target');
    must(await inBrowser(page, 'POST', `/api/v1/admin/contracts/versions/${TARGET_VERSION_ID}/approve`, undefined, 'approver'),
      'approve target');

    const flow = must(await inBrowser(page, 'POST', '/api/v1/admin/flows', {
      code: `raw-orders-${RUN}`, name: 'raw orders', sourceSystem: SOURCE_SYSTEM,
      destinations: [TARGET], businessKeyRule: 'customerId'
    }), 'flow raw orders');
    const routes = routeIdsOfInterface(flow.id);
    if (routes.length !== 1) throw new Error(`one route expected for the single destination, saw ${routes.length}`);
    ROUTE = routes[0];
  } finally {
    await context.close();
  }
});

// Scenario: opens from the designer - the raw card renders on the route's canvas, lazy-loaded, and
// first shows exactly the expression the saved mapping keeps (drawn equals typed, editor side).
test('the raw expression editor opens on the mapping designer showing exactly the saved expression', async ({ page }) => {
  await openCanvas(page, ROUTE);
  // draw customerId -> clientId and save: the pair now keeps an emitted expression
  await page.locator('[data-field="source-version"]').selectOption(SOURCE_VERSION_ID);
  await page.locator('[data-field="target-version"]').selectOption(TARGET_VERSION_ID);
  await page.locator('[data-tree="source"] [data-node="customerId"]').click();
  await page.locator('[data-tree="target"] [data-node="clientId"]').click();
  await page.click('[data-action="add-link"]');
  await page.click('[data-action="save"]');
  await expect(page.locator('[data-saved="true"]')).toBeVisible();

  await page.reload();
  await expect(page.locator(`${PAGE}[data-mapping-loaded="true"]`)).toBeVisible();
  const raw = page.locator(RAW);
  await expect(raw).toBeVisible({ timeout: 15_000 }); // the lazy chunk mounted (ADR-006 Decision 7 as amended)
  await expect(raw).toHaveAttribute('aria-label', 'Raw expression');
  const expression = (await stored(page, ROUTE)).compiledExpression;
  expect(await editorText(page)).toBe(expression); // the editor renders exactly what storage keeps
  expect(expression).toContain('customerId'); // ... and it is the emitted drawing, not the '$' default
});

// Scenario: a valid expression applies - the typed JSONata lands back through applyExpressionEdit,
// saves, and the SAVED state is asserted both on the stored row and after a reload.
test('a valid typed expression applies and is stored as typed', async ({ page }) => {
  await openCanvas(page, ROUTE);
  await expect(page.locator(RAW)).toBeVisible();
  const before = (await stored(page, ROUTE)).compiledExpression;

  await typeRaw(page, '$'); // the identity expression - valid JSONata, no $round (raw.ts accepts it)
  await page.click('[data-action="save"]');
  await expect(page.locator('[data-saved="true"]')).toBeVisible();

  const after = (await stored(page, ROUTE)).compiledExpression;
  expect(after).toBe('$');
  expect(after, 'the typed expression really replaced the stored one').not.toBe(before);
  await page.reload();
  await expect(page.locator(`${PAGE}[data-mapping-loaded="true"]`)).toBeVisible();
  await expect(page.locator(RAW)).toBeVisible();
  expect(await editorText(page)).toBe('$'); // re-rendered exactly as left
});

// @negative Scenario: an invalid expression (ADR-006 rule 8: money rounding never via $round) shows
// the refusal naming the rule and never reaches the mapping - saving after the refusal still stores
// the PREVIOUS expression (raw.ts: "the graph keeps its previous expression").
// The edit arrives as ONE paste-equivalent insert (keyboard.insertText = a single CM transaction):
// guard text is the completed edit. Per-keystroke typing is live editing by design - every partial
// ('$r', '$ro'...) is a document that does not contain '$round' yet and is accepted on its own;
// the red run of this file showed '$roun' persisting when the guard was typed one character at a
// time - a property of transaction-level guarding, not a rule-8 bypass (reported to the lead).
test('a $round edit is refused on screen with the rule named and is never saved', async ({ page }) => {
  await openCanvas(page, ROUTE);
  await expect(page.locator(RAW)).toBeVisible();
  const before = (await stored(page, ROUTE)).compiledExpression;

  await page.locator(RAW).click();
  await page.keyboard.press('ControlOrMeta+A');
  await page.keyboard.insertText('$round(amount, 2)'); // one transaction: the completed edit
  await expect(page.locator('[data-refusal]')).toBeVisible();
  await expect(page.locator('[data-refusal]')).toContainText('$round');
  await expect(page.locator('[data-refusal]')).toContainText('ADR-006 rule 8');

  // the refused edit is not the graph's expression: saving now stores the previous one, not $round
  await page.click('[data-action="save"]');
  await expect(page.locator('[data-saved="true"]')).toBeVisible(); // the PUT landed
  const after = (await stored(page, ROUTE)).compiledExpression;
  expect(after).toBe(before);
  expect(after).not.toContain('$round');

  await page.reload();
  await expect(page.locator(`${PAGE}[data-mapping-loaded="true"]`)).toBeVisible();
  await expect(page.locator(RAW)).toBeVisible();
  expect(await editorText(page)).toBe(before); // re-renders as stored - the edit never landed
});

// Scenario: the discard the UX offers - the raw editor has NO explicit discard/cancel control
// (MappingDesigner.tsx renders the card with the shared Save only; reported by the lane), so an
// abandoned edit is discarded by leaving: a reload restores exactly the stored mapping.
test('an unsaved typed edit is discarded by leaving - the mapping is unchanged', async ({ page }) => {
  await openCanvas(page, ROUTE);
  await expect(page.locator(RAW)).toBeVisible();

  await typeRaw(page, '$$'); // a valid typed edit, deliberately never saved
  await page.reload();
  await expect(page.locator(`${PAGE}[data-mapping-loaded="true"]`)).toBeVisible();
  await expect(page.locator(RAW)).toBeVisible();
  const expression = (await stored(page, ROUTE)).compiledExpression;
  expect(expression).toBe('$'); // nothing was stored
  expect(await editorText(page)).toBe('$'); // and the editor re-renders the stored mapping, not the abandoned edit
});
