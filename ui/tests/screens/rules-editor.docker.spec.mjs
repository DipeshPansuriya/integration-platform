// T-025 + T-028 (E6-F1-S1, E6-F3-S1, lane D, row 278) - the rules editor screen, FAILING FIRST.
//
// The rules editor at #/rules/<ruleSetId> authors validation rules, conditional routing rules and
// lookup tables as decision tables in jdm-editor 1.52.0 (ADR-003: pinned, behind ONE adapter seam
// that emits ZEN JSON) against the REAL rule-set API (lane B, T-023/T-024) through the one typed
// client - no mock (E3-F3-S1). Boot protocol: global-setup (real backend + Keycloak, the built
// console, signed in through the realm form). Gherkin proved on screen, word for word:
//
//   Scenario Outline: Author each kind of rule
//     Given the rules editor on an interface
//     When a configurator authors a <kind> as a decision table
//     Then it is stored as configuration rows in the open ZEN JSON format and re-opens exactly as left
//     Examples: | validation rule | conditional routing rule | lookup table |
//   @negative
//   Scenario: A malformed decision table is refused
//     Given a decision table with an empty condition column or an unparsable expression
//     When it is saved
//     Then it is refused with the cell and the reason
//   @negative @tenant
//   Scenario: Rules are tenant-scoped
//     Given a configurator of tenant B
//     When they open tenant A's rule set by id
//     Then they get 404
//   Scenario: Panel and runtime agree   (lane C's T-029 owns the 20-sample parity proof; here: the
//     panel stays visible at 1440x900 and one sample's verdict equals the evaluate API's answer)
//
// SELECTOR CONTRACT (lane C's e2e relies on it - never rename): [data-page="rules-editor"] with
// data-rule-set-id; [data-action="save-rule-set"]; [data-test-panel]; [data-test-panel-input];
// [data-test-panel-run]; [data-test-panel-verdict] (canonical JSON of {output, violations},
// data-state="done" | "error").

import { expect, test } from '@playwright/test';
import { consoleUrl } from './boot-state.mjs';

const EDITOR = '[data-page="rules-editor"]';

/** One API call from inside the page: the browser's own session and CSRF cookies, like the console's client. */
async function inBrowser(page, method, apiPath, body) {
  return page.evaluate(
    async ({ method, apiPath, body }) => {
      const xsrf = document.cookie.split('; ').filter((pair) => pair.startsWith('XSRF-TOKEN=')).pop();
      const headers = {};
      if (body !== undefined) headers['content-type'] = 'application/json';
      if (method !== 'GET' && xsrf) headers['X-XSRF-TOKEN'] = decodeURIComponent(xsrf.slice('XSRF-TOKEN='.length));
      const answer = await fetch(apiPath, { method, headers, credentials: 'include', body: body === undefined ? undefined : JSON.stringify(body) });
      return { status: answer.status, data: await answer.json().catch(() => null) };
    },
    { method, apiPath, body }
  );
}

/** Tenant A's flow (the seeded login's tenant): the first listed, created when there is none. */
async function tenantAFlowId(page) {
  let rows = (await inBrowser(page, 'GET', '/api/v1/admin/flows')).data ?? [];
  if (rows.length === 0) {
    await inBrowser(page, 'POST', '/api/v1/admin/flows', { sourceSystem: 'S', destinations: ['T1'], businessKeyRule: 'customer_id' });
    rows = (await inBrowser(page, 'GET', '/api/v1/admin/flows')).data ?? [];
  }
  if (!rows[0]?.id) throw new Error('no tenant A flow to hang rule sets on');
  return rows[0].id;
}

/** Keys sorted at every depth, no whitespace - the verdict's canonical form (an independent oracle here). */
function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value !== null && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

/** Creates a rule set through the screen's own create form and lands on its editor. */
async function createThroughScreen(page, flowId, kind, name) {
  await page.goto(`${consoleUrl()}/#/rules`);
  await expect(page.locator('[data-page="rules-home"]')).toBeVisible();
  await page.selectOption('[data-field="rule-set-flow"]', flowId);
  await page.selectOption('[data-field="rule-set-kind"]', kind);
  await page.fill('[data-field="rule-set-name"]', name);
  await page.click('[data-action="create-rule-set"]');
  await expect(page.locator(EDITOR)).toBeVisible();
  const id = await page.locator(EDITOR).getAttribute('data-rule-set-id');
  expect(id).toMatch(/^[0-9a-f-]{36}$/);
  expect(page.url()).toContain(`#/rules/${id}`);
  return id;
}

/** Types into one decision-table cell of jdm-editor, the way a configurator does: click, replace, leave. */
async function editCell(page, from, to) {
  const cell = page.locator(`${EDITOR} [data-decision-table] .grl-dt__cell__input`).filter({ hasText: from }).first();
  await cell.click();
  const focused = page.locator(`${EDITOR} [data-decision-table] .cm-editor.cm-focused .cm-content`);
  await expect(focused).toBeVisible();
  await page.keyboard.press('ControlOrMeta+A');
  await page.keyboard.type(to);
  await page.locator(`${EDITOR} [data-field="rule-set-name"]`).click();
  await expect(page.locator(`${EDITOR} [data-decision-table] .grl-dt__cell__input`).filter({ hasText: to }).first()).toBeVisible();
}

/** Clicks save and returns the PUT body the screen sent and the API's status. */
async function save(page) {
  const request = page.waitForRequest((r) => r.method() === 'PUT' && /\/api\/v1\/admin\/rule-sets\//.test(r.url()));
  const response = page.waitForResponse((r) => r.request().method() === 'PUT' && /\/api\/v1\/admin\/rule-sets\//.test(r.url()));
  await page.click('[data-action="save-rule-set"]');
  return { body: JSON.parse((await request).postData() ?? 'null'), status: (await response).status() };
}

test('the console menu reaches the rules screen', async ({ page }) => {
  await page.goto(consoleUrl());
  await page.click('[data-nav="rules"]');
  await expect(page.locator('[data-page="rules-home"]')).toBeVisible();
});

const KINDS = [
  { kind: 'validation rule', code: 'VALIDATION', from: '> 100', to: '> 250' },
  { kind: 'conditional routing rule', code: 'ROUTING', from: '"NL"', to: '"BE"' },
  { kind: 'lookup table', code: 'LOOKUP', from: '"Germany"', to: '"Deutschland"' }
];

for (const { kind, code, from, to } of KINDS) {
  test(`a configurator authors a ${kind} as a decision table: stored as ZEN JSON rows, re-opens exactly as left`, async ({ page }) => {
    await page.goto(consoleUrl());
    const flowId = await tenantAFlowId(page);
    const id = await createThroughScreen(page, flowId, code, `${code.toLowerCase()}-${Date.now()}`);
    await expect(page.locator(`${EDITOR} [data-decision-table]`)).toHaveCount(1);

    await editCell(page, from, to);
    const first = await save(page);
    expect(first.status).toBe(200);
    await expect(page.locator(`${EDITOR} [data-saved="true"]`)).toBeVisible();

    // Stored as configuration rows in the open ZEN JSON format: the API's stored content is the
    // decision graph the screen sent, with the authored cell in it.
    const stored = await inBrowser(page, 'GET', `/api/v1/admin/rule-sets/${id}`);
    expect(stored.status).toBe(200);
    expect(stored.data.kind).toBe(code);
    expect(canonical(stored.data.content)).toBe(canonical(first.body.content));
    const table = stored.data.content.nodes.find((n) => n.type === 'decisionTableNode');
    expect(JSON.stringify(table.content.rules)).toContain(JSON.stringify(to).slice(1, -1));

    // Re-opens exactly as left: after a reload the cell shows what was typed, and saving untouched
    // sends back the very same document.
    await page.reload();
    await expect(page.locator(EDITOR)).toHaveAttribute('data-rule-set-id', id);
    await expect(page.locator(`${EDITOR} [data-decision-table] .grl-dt__cell__input`).filter({ hasText: to }).first()).toBeVisible();
    const again = await save(page);
    expect(again.status).toBe(200);
    expect(JSON.stringify(again.body.content)).toBe(JSON.stringify(first.body.content));
  });
}

test('@negative a decision table with an unparsable expression is refused with the cell and the reason', async ({ page }) => {
  await page.goto(consoleUrl());
  const flowId = await tenantAFlowId(page);
  await createThroughScreen(page, flowId, 'VALIDATION', `malformed-${Date.now()}`);

  await editCell(page, '> 100', '> > 100');
  const refused = await save(page);
  expect(refused.status).toBe(400);
  const refusal = page.locator(`${EDITOR} [data-refusal="rule-table-invalid"]`);
  await expect(refusal).toBeVisible();
  await expect(refusal).toHaveAttribute('data-cell-node', 'checks');
  await expect(refusal).toHaveAttribute('data-cell-row', '0');
  await expect(refusal).toHaveAttribute('data-cell-column', 'c1');
  await expect(refusal.locator('[data-refusal-reason]')).not.toBeEmpty();
  await expect(page.locator(`${EDITOR} [data-saved="true"]`)).toHaveCount(0);
});

// Tenant B opens tenant A's rule set by id THROUGH THE SCREEN: the harness's second-tenant binding
// is attached with the X-Harness-Login marker (console-server swaps in that binding's server-side
// session; the backend isolates for real) - the API answers 404 and the screen shows not-found.
test('@negative @tenant tenant B opening tenant A\'s rule set by id gets the not-found state (404)', async ({ page }) => {
  await page.goto(consoleUrl());
  const flowId = await tenantAFlowId(page);
  const created = await inBrowser(page, 'POST', `/api/v1/admin/flows/${flowId}/rule-sets`, {
    kind: 'ROUTING',
    name: `tenant-a-${Date.now()}`,
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
  });
  expect(created.status).toBe(201);

  await page.setExtraHTTPHeaders({ 'X-Harness-Login': 'other-tenant' });
  const answer = page.waitForResponse((r) => r.url().includes(`/api/v1/admin/rule-sets/${created.data.id}`));
  await page.goto(`${consoleUrl()}/#/rules/${created.data.id}`);
  expect((await answer).status()).toBe(404);
  await expect(page.locator('[data-refusal="rule-set-not-found"]')).toBeVisible();
  await expect(page.locator('[data-decision-table]')).toHaveCount(0);
});

test('the test panel is visible at 1440x900 without scrolling and its verdict equals the evaluate API\'s answer', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(consoleUrl());
  const flowId = await tenantAFlowId(page);
  const id = await createThroughScreen(page, flowId, 'VALIDATION', `panel-${Date.now()}`);

  for (const part of ['[data-test-panel]', '[data-test-panel-input]', '[data-test-panel-run]', '[data-test-panel-verdict]']) {
    await expect(page.locator(part)).toBeInViewport({ ratio: 1 });
  }

  const sample = { amount: 150, currency: 'XXX', reference: '' };
  await page.fill('[data-test-panel-input]', JSON.stringify(sample));
  const evaluated = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().endsWith(`/api/v1/admin/rule-sets/${id}/evaluate`));
  await page.click('[data-test-panel-run]');
  expect((await evaluated).status()).toBe(200);
  const verdict = page.locator('[data-test-panel-verdict]');
  await expect(verdict).toHaveAttribute('data-state', 'done');

  const port = await inBrowser(page, 'POST', `/api/v1/admin/rule-sets/${id}/evaluate`, { input: sample });
  expect(port.status).toBe(200);
  expect(port.data.violations.length).toBeGreaterThan(0);
  await expect(verdict).toHaveText(canonical({ output: port.data.output, violations: port.data.violations }));

  // Still in view after the page is scrolled: the panel never needs scrolling or toggling.
  await page.mouse.wheel(0, 2000);
  await expect(page.locator('[data-test-panel]')).toBeInViewport({ ratio: 1 });
});

test('@negative a sample that is not JSON shows the error state in the panel', async ({ page }) => {
  await page.goto(consoleUrl());
  const flowId = await tenantAFlowId(page);
  await createThroughScreen(page, flowId, 'ROUTING', `panel-error-${Date.now()}`);
  await page.fill('[data-test-panel-input]', '{"country":');
  await page.click('[data-test-panel-run]');
  await expect(page.locator('[data-test-panel-verdict]')).toHaveAttribute('data-state', 'error');
});
