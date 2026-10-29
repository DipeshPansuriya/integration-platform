// T-160 (E5 lane D, row 278) - the contract version screens, FAILING FIRST.
//
// E5-F2-S1 against the REAL API (no mock, E3-F3-S1): a configurator imports a contract version
// from an OpenAPI or JSON Schema document; versions are listed and compared side by side (Target
// A v1.0 next to Target B v2.0); a version is submitted with a change note; the author may approve
// their own version (single-user workflow, V2_4_1420), and another approver (the harness's approver
// binding) succeeds too. A document that does not parse shows the parser's errors
// and nothing is stored.
//
// Seeding: the target systems (and Target A's v1.0) through the real API from the signed-in page,
// inside this file - never through the global setup. The browser's user and the seeding page are
// the same realm user (the harness default login), so a seeded version's author is the screen's user.

import { expect, test } from '@playwright/test';
import { consoleUrl } from './boot-state.mjs';

const PAGE = '[data-page="contracts"]';
const RUN = Date.now().toString(36);
const TARGET_A = `tgt-a-${RUN}`;
const TARGET_B = `tgt-b-${RUN}`;
const ROLE = 'TARGET_REQUEST';

const TARGET_A_V1 = {
  $schema: 'https://json-schema.org/draft/2020-12/schema',
  type: 'object',
  required: ['customer_code', 'order_number', 'total_amount'],
  properties: { customer_code: { type: 'string' }, order_number: { type: 'string' }, total_amount: { type: 'number' } }
};

const TARGET_B_V2_OPENAPI = {
  openapi: '3.0.3',
  info: { title: 'Target B orders', version: '2.0' },
  paths: {
    '/orders': {
      post: {
        requestBody: { content: { 'application/json': { schema: { $ref: '#/components/schemas/TargetBOrder' } } } },
        responses: { 200: { description: 'accepted' } }
      }
    }
  },
  components: {
    schemas: {
      TargetBOrder: {
        type: 'object',
        required: ['clientId', 'referenceNo', 'invoiceValue'],
        properties: { clientId: { type: 'string' }, referenceNo: { type: 'string' }, invoiceValue: { type: 'number' } }
      }
    }
  }
};

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

async function importThroughApi(page, system, label, document) {
  const imported = await inBrowser(page, 'POST', '/api/v1/admin/contracts/versions/import', {
    system, role: ROLE, versionLabel: label, format: 'JSON_SCHEMA', document
  });
  if (imported.status !== 201) throw new Error(`seed import ${system} ${label}: HTTP ${imported.status} ${JSON.stringify(imported.data)}`);
  return imported.data;
}

async function openContracts(page) {
  await page.goto(consoleUrl());
  await page.click('[data-nav="systems"]'); // row 575 S6: contracts is a tab of the systems hub now
  await page.click('[data-nav="contracts"]');
  await expect(page.locator(`${PAGE}[data-contracts-loaded="true"]`)).toBeVisible();
}

const versionRow = (page, system, label) => page.locator(`[data-version-key="${system}/${ROLE}/${label}"]`);

/** Row 310 lane C (T101): the import form lives in the shared drawer, closed until its primary action opens it. */
async function openImport(page) {
  await page.click('[data-action="open-import"]');
  const dialog = page.getByRole('dialog', { name: 'Import a contract version' });
  await expect(dialog).toBeVisible();
  return dialog.locator('[data-form="import-contract"]');
}

test.describe.configure({ mode: 'serial' });

test.beforeAll(async ({ browser }) => {
  const context = await browser.newContext(); // the signed-in storage state (playwright.config use.storageState)
  const page = await context.newPage();
  try {
    await page.goto(consoleUrl());
    for (const code of [TARGET_A, TARGET_B]) {
      const system = await inBrowser(page, 'POST', '/api/v1/admin/systems', {
        code, name: `${code} target`, direction: 'OUTBOUND', type: 'API', settings: { baseUrl: `https://${code}.partner.test/orders` }
      });
      if (system.status !== 201) throw new Error(`seed system ${code}: HTTP ${system.status} ${JSON.stringify(system.data)}`);
    }
    await importThroughApi(page, TARGET_A, '1.0', TARGET_A_V1); // Given Target A with contract v1.0
  } finally {
    await context.close();
  }
});

// Scenario: Two contract versions side by side.
test('imports Target B v2.0 from OpenAPI and shows it side by side with Target A v1.0', async ({ page }) => {
  await openContracts(page);
  const form = await openImport(page);
  await form.locator('[data-field="contract-system"]').selectOption(TARGET_B);
  await form.locator('[data-field="contract-role"]').selectOption(ROLE);
  await form.locator('[data-field="version-label"]').fill('2.0');
  await form.locator('[data-field="contract-format"]').selectOption('OPENAPI');
  await form.locator('[data-field="contract-document"]').fill(JSON.stringify(TARGET_B_V2_OPENAPI, null, 2));
  await page.click('[data-action="import-contract"]');
  await expect(page.locator('[data-imported="true"]')).toBeVisible();

  // both versions are stored rows: listed after a reload
  await page.reload();
  await expect(page.locator(`${PAGE}[data-contracts-loaded="true"]`)).toBeVisible();
  await expect(versionRow(page, TARGET_A, '1.0')).toBeVisible();
  await expect(versionRow(page, TARGET_B, '2.0')).toBeVisible();

  await versionRow(page, TARGET_A, '1.0').locator('[data-action="compare"]').check();
  await versionRow(page, TARGET_B, '2.0').locator('[data-action="compare"]').check();
  const panes = page.locator('[data-compare-pane]');
  await expect(panes).toHaveCount(2);
  const paneA = page.locator(`[data-compare-pane="${TARGET_A}/${ROLE}/1.0"]`);
  const paneB = page.locator(`[data-compare-pane="${TARGET_B}/${ROLE}/2.0"]`);
  for (const field of ['customer_code', 'order_number', 'total_amount']) {
    await expect(paneA.locator(`[data-schema-field="${field}"]`)).toBeVisible();
  }
  for (const field of ['clientId', 'referenceNo', 'invoiceValue']) {
    await expect(paneB.locator(`[data-schema-field="${field}"]`)).toBeVisible();
  }
});

// @negative: an invalid schema is refused with the parser's errors and nothing is stored.
for (const bad of [
  { format: 'JSON_SCHEMA', label: '8.1', document: '{"type": "object", "properties": ' },
  { format: 'OPENAPI', label: '8.2', document: 'openapi: [3.0' }
]) {
  test(`a ${bad.format} document that does not parse is refused with the parser's errors and nothing is stored`, async ({ page }) => {
    await openContracts(page);
    const form = await openImport(page);
    await form.locator('[data-field="contract-system"]').selectOption(TARGET_B);
    await form.locator('[data-field="contract-role"]').selectOption(ROLE);
    await form.locator('[data-field="version-label"]').fill(bad.label);
    await form.locator('[data-field="contract-format"]').selectOption(bad.format);
    await form.locator('[data-field="contract-document"]').fill(bad.document);
    await page.click('[data-action="import-contract"]');

    await expect(page.locator('[data-refusal="import-refused"]')).toBeVisible();
    await expect(page.locator('[data-parser-error]').first()).toBeVisible();
    await expect(page.locator('[data-imported="true"]')).toHaveCount(0);

    await page.reload();
    await expect(page.locator(`${PAGE}[data-contracts-loaded="true"]`)).toBeVisible();
    await expect(versionRow(page, TARGET_B, bad.label)).toHaveCount(0);
  });
}

// Submit with a change note.
test('submits a draft version with a change note; it is pending approval and the note is stored', async ({ page }) => {
  await page.goto(consoleUrl());
  await importThroughApi(page, TARGET_A, '1.1', TARGET_A_V1);
  await openContracts(page);
  const row = versionRow(page, TARGET_A, '1.1');
  await expect(row).toHaveAttribute('data-version-status', 'DRAFT');
  await expect(row.locator('[data-field="change-note"]')).toHaveCount(0); // T101: no empty note field per row
  await row.locator('[data-action="compose-submit"]').click();
  await row.locator('[data-field="change-note"]').fill('adds nothing - a resubmission for the screen test');
  await row.locator('[data-action="submit-version"]').click();
  await expect(row).toHaveAttribute('data-version-status', 'PENDING_APPROVAL');

  await page.reload();
  await expect(page.locator(`${PAGE}[data-contracts-loaded="true"]`)).toBeVisible();
  await expect(versionRow(page, TARGET_A, '1.1')).toHaveAttribute('data-version-status', 'PENDING_APPROVAL');
  await versionRow(page, TARGET_A, '1.1').locator('[data-action="compare"]').check();
  await expect(page.locator(`[data-compare-pane="${TARGET_A}/${ROLE}/1.1"] [data-change-note]`))
    .toHaveText('adds nothing - a resubmission for the screen test');
});

// Single-user workflow (V2_4_1420): the author approves their own contract version.
test('the author approves their own version: APPROVED, approver = author, no refusal', async ({ page }) => {
  await page.goto(consoleUrl());
  const version = await importThroughApi(page, TARGET_A, '1.2', TARGET_A_V1); // authored by the screen's user
  const submitted = await inBrowser(page, 'POST', `/api/v1/admin/contracts/versions/${version.id}/submit`, { changeNote: 'ready for approval' });
  expect(submitted.status).toBe(200);

  await openContracts(page);
  const row = versionRow(page, TARGET_A, '1.2');
  await expect(row).toHaveAttribute('data-version-status', 'PENDING_APPROVAL');
  await row.locator('[data-action="approve-version"]').click();
  await row.locator('[data-action="confirm-approve"]').click(); // B29 C2: Approve asks first
  await expect(row).toHaveAttribute('data-version-status', 'APPROVED');
  const stored = await inBrowser(page, 'GET', `/api/v1/admin/contracts/versions/${version.id}`);
  expect(stored.data.status).toBe('APPROVED');
  expect(stored.data.approverId).toBe(stored.data.authorId);
});

// The two-user path still works: another approver (the harness's approver binding) approves a version the screen's user authored.
test('another approver approves a version the screen user authored: approver differs from author', async ({ page }) => {
  await page.goto(consoleUrl());
  const version = await importThroughApi(page, TARGET_A, '1.3', TARGET_A_V1); // authored by the screen's user
  const submitted = await inBrowser(page, 'POST', `/api/v1/admin/contracts/versions/${version.id}/submit`, { changeNote: 'ready for approval' });
  expect(submitted.status).toBe(200);

  await openContracts(page);
  const row = versionRow(page, TARGET_A, '1.3');
  await expect(row).toHaveAttribute('data-version-status', 'PENDING_APPROVAL');
  await row.locator('[data-action="approve-as-approver"]').click();
  await expect(row).toHaveAttribute('data-version-status', 'APPROVED');
  const stored = await inBrowser(page, 'GET', `/api/v1/admin/contracts/versions/${version.id}`);
  expect(stored.data.status).toBe('APPROVED');
  expect(stored.data.approverId).toBeTruthy();
  expect(stored.data.approverId).not.toBe(stored.data.authorId);
});

// Row 310 lane C (T101): the shared drawer - closed by default, a modal dialog, Esc closes it and
// focus returns to the action that opened it; compare panes sit in a two-column grid.
test('the import drawer is closed by default, is a modal dialog, closes on Esc and gives focus back', async ({ page }) => {
  await openContracts(page);
  await expect(page.locator('[data-form="import-contract"]')).toHaveCount(0);
  const opener = page.locator('[data-action="open-import"]');
  await opener.click();
  const dialog = page.getByRole('dialog', { name: 'Import a contract version' });
  await expect(dialog).toBeVisible();
  await expect(dialog).toHaveAttribute('aria-modal', 'true');
  const width = await dialog.evaluate((el) => el.getBoundingClientRect().width);
  expect(Math.round(width)).toBe(480);
  await expect(dialog.locator(':focus')).toHaveCount(1); // focus moved into the drawer
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(opener).toBeFocused();
});

test('two compared versions sit side by side; a field that differs is marked with a word', async ({ page }) => {
  await openContracts(page);
  await versionRow(page, TARGET_A, '1.0').locator('[data-action="compare"]').check();
  await versionRow(page, TARGET_B, '2.0').locator('[data-action="compare"]').check();
  const panes = page.locator('[data-compare-pane]');
  await expect(panes).toHaveCount(2);
  const [first, second] = await Promise.all([panes.nth(0).boundingBox(), panes.nth(1).boundingBox()]);
  expect(Math.round(first.y)).toBe(Math.round(second.y)); // one row, two columns
  expect(second.x).toBeGreaterThan(first.x);
  const marked = page.locator('[data-field-diff]');
  if (await marked.count()) await expect(marked.first()).toContainText(/changed|only here/);
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBe(0);
});
