// T-159 (E5 lane D, row 278) - the systems registry screens, FAILING FIRST.
//
// E5-F1-S1 against the REAL API (no mock, E3-F3-S1): a configurator registers a system of each
// type with its type-specific settings (stored as the flow-configuration systems row's type +
// settings extension - lane B's V2_2_7, no second systems table), edits the settings, and a
// refused save shows EVERY validation error the API lists (body {error, errors[]}). Systems are
// tenant-scoped: tenant B's configurator (the harness's other-tenant binding) never sees tenant
// A's system in the list, and opening its id shows not found.
//
// Seeding: through the real API from the signed-in page (session cookie + CSRF), inside this
// file - never through the global setup.

import { expect, test } from '@playwright/test';
import { consoleUrl } from './boot-state.mjs';

const PAGE = '[data-page="systems"]';
const RUN = Date.now().toString(36);

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

async function openSystems(page) {
  await page.goto(consoleUrl());
  await page.click('[data-nav="systems"]');
  await expect(page.locator(`${PAGE}[data-systems-loaded="true"]`)).toBeVisible();
}

/** Fills the register form: code, name, OUTBOUND, the type and each setting by its API name. */
async function fillRegister(page, { code, name, type, settings }) {
  // Row 310 lane C (T101): the register form lives in the shared drawer, closed until opened
  await page.click('[data-action="open-register"]');
  const form = page.getByRole('dialog', { name: 'Register a system' }).locator('[data-form="register-system"]');
  await form.locator('[data-field="system-code"]').fill(code);
  await form.locator('[data-field="system-name"]').fill(name);
  await form.locator('[data-field="system-direction"]').selectOption('OUTBOUND');
  await form.locator('[data-field="system-type"]').fill(type);
  for (const [setting, value] of Object.entries(settings)) {
    const field = form.locator(`[data-setting="${setting}"]`);
    if (typeof value === 'boolean') await field.setChecked(value);
    else await field.fill(String(value));
  }
}

const EXAMPLES = [
  { type: 'API', label: 'API', settings: { baseUrl: 'https://partner.example.test/orders', timeoutMs: 5000 } },
  { type: 'DB', label: 'DB', settings: { host: 'db.partner.test', port: 5432, database: 'orders' } },
  { type: 'MQ', label: 'MQ', settings: { host: 'mq.partner.test', port: 5672, queue: 'orders.in' } },
  { type: 'FILE', label: 'File', settings: { rootPath: '/data/inbound/orders' } }
];

// Scenario Outline: Register a system of each type (Examples: API | DB | MQ | File).
for (const example of EXAMPLES) {
  test(`registers a ${example.label} system with its type-specific settings; it is listed after reload`, async ({ page }) => {
    const code = `sys-${example.type.toLowerCase()}-${RUN}`;
    await openSystems(page);
    await fillRegister(page, { code, name: `${example.label} partner`, type: example.type, settings: example.settings });
    await page.click('[data-action="register-system"]');
    await expect(page.locator('[data-saved="true"]')).toBeVisible();

    await page.reload();
    await expect(page.locator(`${PAGE}[data-systems-loaded="true"]`)).toBeVisible();
    const row = page.locator(`[data-system-row="${code}"]`);
    await expect(row).toBeVisible();
    await expect(row).toHaveAttribute('data-system-type', example.type);

    // stored as the ONE systems table's type + settings extension: the real API reads it back
    const listed = (await inBrowser(page, 'GET', '/api/v1/admin/systems')).data ?? [];
    const stored = listed.find((system) => system.code === code);
    expect(stored?.type).toBe(example.type);
    expect(stored?.settings).toEqual(example.settings);
  });
}

test('edits the type-specific settings; the new value is stored and shown after reload', async ({ page }) => {
  await page.goto(consoleUrl());
  const code = `sys-edit-${RUN}`;
  const seeded = await inBrowser(page, 'POST', '/api/v1/admin/systems', {
    code, name: 'Edit me', direction: 'OUTBOUND', type: 'DB', settings: { host: 'db-old.partner.test', port: 5432, database: 'orders' }
  });
  expect(seeded.status).toBe(201);

  await openSystems(page);
  await page.locator(`[data-system-row="${code}"] [data-action="open-system"]`).click();
  const editor = page.getByRole('dialog', { name: `Edit system ${code}` }).locator('[data-form="edit-system"]'); // T101: edit opens the drawer
  await expect(editor.locator('[data-setting="host"]')).toHaveValue('db-old.partner.test');
  await editor.locator('[data-setting="host"]').fill('db-new.partner.test');
  await editor.locator('[data-setting="port"]').fill('6432');
  await page.click('[data-action="save-system"]');
  await expect(page.locator('[data-saved="true"]')).toBeVisible();

  await page.reload();
  await expect(page.locator(`${PAGE}[data-systems-loaded="true"]`)).toBeVisible();
  await page.locator(`[data-system-row="${code}"] [data-action="open-system"]`).click();
  await expect(page.locator('[data-form="edit-system"] [data-setting="host"]')).toHaveValue('db-new.partner.test');
  await expect(page.locator('[data-form="edit-system"] [data-setting="port"]')).toHaveValue('6432');
});

// @negative: an unknown type or a missing setting is refused with every validation error listed.
test('a system of type FTP is refused and the API error is shown on screen', async ({ page }) => {
  await openSystems(page);
  await fillRegister(page, { code: `sys-ftp-${RUN}`, name: 'FTP partner', type: 'FTP', settings: {} });
  await page.click('[data-action="register-system"]');

  const errors = page.locator('[data-validation-error]');
  await expect(errors.first()).toBeVisible();
  await expect(errors.filter({ hasText: 'type FTP is not one of API, DB, MQ, FILE' })).toHaveCount(1);
  await page.reload();
  await expect(page.locator(`${PAGE}[data-systems-loaded="true"]`)).toBeVisible();
  await expect(page.locator(`[data-system-row="sys-ftp-${RUN}"]`)).toHaveCount(0);
});

test('an API system without a base address is refused with EVERY validation error listed on screen', async ({ page }) => {
  await openSystems(page);
  // three problems at once: no name, no baseUrl, a timeout out of range - all three must show
  await fillRegister(page, { code: `sys-nourl-${RUN}`, name: '', type: 'API', settings: { timeoutMs: 50 } });
  await page.click('[data-action="register-system"]');

  const errors = page.locator('[data-validation-error]');
  await expect(errors).toHaveCount(3);
  await expect(errors.filter({ hasText: 'name is required' })).toHaveCount(1);
  await expect(errors.filter({ hasText: 'settings.baseUrl is required for type API' })).toHaveCount(1);
  await expect(errors.filter({ hasText: 'settings.timeoutMs must be a whole number of milliseconds from 100 to 300000' })).toHaveCount(1);
});

test('a refused edit shows every validation error and keeps the stored settings', async ({ page }) => {
  await page.goto(consoleUrl());
  const code = `sys-badedit-${RUN}`;
  const seeded = await inBrowser(page, 'POST', '/api/v1/admin/systems', {
    code, name: 'Bad edit', direction: 'OUTBOUND', type: 'MQ', settings: { host: 'mq.partner.test', port: 5672, queue: 'q1' }
  });
  expect(seeded.status).toBe(201);

  await openSystems(page);
  await page.locator(`[data-system-row="${code}"] [data-action="open-system"]`).click();
  const editor = page.locator('[data-form="edit-system"]');
  await editor.locator('[data-setting="host"]').fill('');
  await editor.locator('[data-setting="port"]').fill('70000');
  await page.click('[data-action="save-system"]');

  const errors = page.locator('[data-validation-error]');
  await expect(errors).toHaveCount(2);
  await expect(errors.filter({ hasText: 'settings.host is required for type MQ' })).toHaveCount(1);
  await expect(errors.filter({ hasText: 'settings.port must be a whole number from 1 to 65535' })).toHaveCount(1);
  const stored = await inBrowser(page, 'GET', `/api/v1/admin/systems/${seeded.data.id}`);
  expect(stored.data.settings).toEqual({ host: 'mq.partner.test', port: 5672, queue: 'q1' });
});

// V-3 (row 306 defect batch, lane C): the Test connection button on the systems screen calls the
// real POST /api/v1/admin/systems/{systemId}/test-connection and shows what the endpoint answers.
// The suite's backend boots with an EMPTY destination allow-list (config-systems.yml - the
// allow-list refuses every destination, fail closed), so an API system's test answers REFUSED
// deterministically; result REFUSED is a 200 answer, shown as the endpoint names it.
test('the row Test connection button tests an API system and shows the endpoint answer', async ({ page }) => {
  await page.goto(consoleUrl());
  const code = `sys-testconn-${RUN}`;
  const seeded = await inBrowser(page, 'POST', '/api/v1/admin/systems', {
    code, name: 'Test me', direction: 'OUTBOUND', type: 'API',
    settings: { baseUrl: 'https://partner.example.test/orders', timeoutMs: 5000 }
  });
  expect(seeded.status).toBe(201);

  await openSystems(page);
  const row = page.locator(`[data-system-row="${code}"]`);
  await row.locator('[data-action="test-connection"]').click();

  const answer = row.locator('[data-test-connection-result]'); // T101: the answer sits in its row cell
  await expect(answer).toBeVisible();
  await expect(answer).toHaveAttribute('role', 'status');
  await expect(answer).toHaveAttribute('data-test-connection-result', 'REFUSED');
  await expect(answer).toContainText(code);
});

// @negative: over the per-user rate limit (default 10 per 60s, config-systems.yml) the endpoint
// answers 429 TEST_CONNECTION_RATE_LIMITED; the screen surfaces it the way its other refusals are.
test('over the per-user rate limit the connection test is refused 429 and the refusal is shown', async ({ page }) => {
  await page.goto(consoleUrl());
  const code = `sys-ratelimit-${RUN}`;
  const seeded = await inBrowser(page, 'POST', '/api/v1/admin/systems', {
    code, name: 'Rate limit me', direction: 'OUTBOUND', type: 'FILE', settings: { rootPath: '/data/x' }
  });
  expect(seeded.status).toBe(201);

  await openSystems(page);
  const testButton = page.locator(`[data-system-row="${code}"] [data-action="test-connection"]`);
  // click past the fresh suite's per-user budget (10 per 60s); the limit check runs before the
  // type check, so a FILE row reaches 429 without its adapter; this run shares test 1's clicks
  for (let i = 0; i < 14; i++) {
    await testButton.click();
  }
  const refusal = page.locator(`[data-system-row="${code}"] [data-refusal="test-connection-refused-429"]`); // T101: in the row cell
  await expect(refusal).toBeVisible();
  await expect(refusal).toContainText('TEST_CONNECTION_RATE_LIMITED');
  await expect(refusal).toContainText('Too many connection tests, wait a moment');
});

// @negative @tenant: systems are tenant-scoped.
test('tenant B lists none of tenant A\'s systems and opening tenant A\'s id shows not found', async ({ page }) => {
  await page.goto(consoleUrl());
  const code = `sys-tenant-a-${RUN}`;
  const seeded = await inBrowser(page, 'POST', '/api/v1/admin/systems', {
    code, name: 'Tenant A only', direction: 'OUTBOUND', type: 'FILE', settings: { rootPath: '/data/a' }
  });
  expect(seeded.status).toBe(201);

  // from here every API call is tenant B's configurator (the harness's other-tenant binding)
  await page.setExtraHTTPHeaders({ 'X-Harness-Login': 'other-tenant' });
  await openSystems(page);
  await expect(page.locator('[data-system-row="S"]')).toBeVisible(); // tenant B's own seeded system
  await expect(page.locator(`[data-system-row="${code}"]`)).toHaveCount(0);

  await page.goto(`${consoleUrl()}/#/systems/${seeded.data.id}`);
  await expect(page.locator('[data-refusal="system-not-found"]')).toBeVisible();
  await expect(page.locator('[data-form="edit-system"]')).toHaveCount(0);
});

// Row 310 lane C (T101): the shared drawer - register closed by default, a 480px modal dialog,
// Esc closes it and focus returns to the opener; row buttons carry the system's code in their name.
test('the register drawer is closed by default, is a modal dialog, closes on Esc and gives focus back', async ({ page }) => {
  await openSystems(page);
  await expect(page.locator('[data-form="register-system"]')).toHaveCount(0);
  const opener = page.locator('[data-action="open-register"]');
  await opener.click();
  const dialog = page.getByRole('dialog', { name: 'Register a system' });
  await expect(dialog).toBeVisible();
  await expect(dialog).toHaveAttribute('aria-modal', 'true');
  const width = await dialog.evaluate((el) => el.getBoundingClientRect().width);
  expect(Math.round(width)).toBe(480);
  await expect(dialog.locator(':focus')).toHaveCount(1);
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(opener).toBeFocused();
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBe(0);
});

test('closing the edit drawer returns to the list address and focus to the row Open button', async ({ page }) => {
  await page.goto(consoleUrl());
  const code = `sys-drawer-${RUN}`;
  const seeded = await inBrowser(page, 'POST', '/api/v1/admin/systems', {
    code, name: 'Drawer', direction: 'OUTBOUND', type: 'FILE', settings: { rootPath: '/data/d' }
  });
  expect(seeded.status).toBe(201);
  await openSystems(page);
  const open = page.locator(`[data-system-row="${code}"] [data-action="open-system"]`);
  await expect(page.getByRole('button', { name: `Test connection for ${code}` })).toBeVisible();
  await open.click();
  await expect(page.getByRole('dialog', { name: `Edit system ${code}` })).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page).toHaveURL(/#\/systems$/);
  await expect(open).toBeFocused();
});
