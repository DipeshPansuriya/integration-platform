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
  { type: 'DB', label: 'DB', settings: { host: 'db.partner.test', port: 5432, database: 'orders', user: 'etl' } }, // user: required since E16 C3 (3250bf9a)
  { type: 'MQ', label: 'MQ', settings: { host: 'mq.partner.test', port: 5672, queue: 'orders.in' } },
  // FILE is not in this outline: a File root is refused unless the operator configures platform.config.destinations.file-base-dirs and
  // the path lies under <base>/<tenant id> (FileRoots.saveProblem); the screens boot configures none - see the FILE negative below.
  // E16 (lane ui-kafka): the closed register now names KAFKA - bootstrap servers + a tenant topic, no secret in the form
  { type: 'KAFKA', label: 'Kafka', settings: { bootstrapServers: 'broker1.partner.test:9092,broker2.partner.test:9092', topic: 'orders.out' } }
];

// Scenario Outline: Register a system of each type (Examples: API | DB | MQ | Kafka; File is the negative below).
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
    code, name: 'Edit me', direction: 'OUTBOUND', type: 'DB', settings: { host: 'db-old.partner.test', port: 5432, database: 'orders', user: 'etl' }
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
  // T101: the address is still #/systems/<id>, so the reload reopens the edit drawer by itself (modal - no Open click)
  await expect(page.getByRole('dialog', { name: `Edit system ${code}` })).toBeVisible();
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
  await expect(errors.filter({ hasText: 'type FTP is not one of API, DB, MQ, FILE, KAFKA' })).toHaveCount(1);
  await page.reload();
  await expect(page.locator(`${PAGE}[data-systems-loaded="true"]`)).toBeVisible();
  await expect(page.locator(`[data-system-row="sys-ftp-${RUN}"]`)).toHaveCount(0);
});

// E16 @negative: with no operator base folder (the screens boot sets none) a File system is refused at save and the screen shows the words.
test('a File system is refused at save while the operator configures no base folder', async ({ page }) => {
  await openSystems(page);
  await fillRegister(page, { code: `sys-file-${RUN}`, name: 'File partner', type: 'FILE', settings: { rootPath: '/data/inbound/orders' } });
  await page.click('[data-action="register-system"]');
  await expect(page.locator('[data-validation-error]')).toContainText('FILE targets are not enabled');
  await page.reload();
  await expect(page.locator(`${PAGE}[data-systems-loaded="true"]`)).toBeVisible();
  await expect(page.locator(`[data-system-row="sys-file-${RUN}"]`)).toHaveCount(0);
});

// E16 (lane ui-kafka) @negative: the server refuses a platform topic at save; the screen shows its words as-is
// and links the error to the Topic field, which is marked invalid and points at the error list.
test('a Kafka system on a platform topic is refused with the server text, shown as-is on the Topic field', async ({ page }) => {
  await openSystems(page);
  await fillRegister(page, { code: `sys-kplat-${RUN}`, name: 'Kafka platform topic', type: 'KAFKA', settings: { bootstrapServers: 'broker1.partner.test:9092', topic: 'platform.poison' } });
  await page.click('[data-action="register-system"]');

  const error = page.locator('[data-validation-error]');
  await expect(error).toHaveCount(1);
  // The shown text goes into the assertion MESSAGE: Playwright clips a received string to ~80 chars ("...registe..." in runs 1-3 of
  // 2026-10-22), but prints a custom message in full, so a mismatch names the whole text.
  const shown = await error.innerText();
  expect(shown.includes("platform.poison is a platform topic - the platform's own register is never a tenant destination (T-184 SF-2)"),
    `shown text: [${shown}]`).toBe(true);
  const topic = page.locator('[data-form="register-system"] [data-setting="topic"]');
  await expect(topic).toHaveAttribute('aria-invalid', 'true');
  await expect(topic).toHaveAttribute('aria-describedby', /sys-validation-errors/);
  await error.locator('a').click();
  await expect(topic).toBeFocused();
  await page.reload();
  await expect(page.locator(`${PAGE}[data-systems-loaded="true"]`)).toBeVisible();
  await expect(page.locator(`[data-system-row="sys-kplat-${RUN}"]`)).toHaveCount(0);
});

// E16 @negative: a bad bootstrap list and a missing topic are named on the form, in the server's words, before any call.
test('a Kafka system with a bad bootstrap list and no topic lists both errors on the form', async ({ page }) => {
  await openSystems(page);
  await fillRegister(page, { code: `sys-kbad-${RUN}`, name: 'Kafka bad', type: 'KAFKA', settings: { bootstrapServers: 'broker1' } });
  await page.click('[data-action="register-system"]');

  const errors = page.locator('[data-validation-error]');
  await expect(errors).toHaveCount(2);
  await expect(errors.filter({ hasText: 'settings.bootstrapServers must be a comma-separated list of host:port, port 1 to 65535' })).toHaveCount(1);
  await expect(errors.filter({ hasText: 'settings.topic is required for type KAFKA' })).toHaveCount(1);
  // the form never offers a secret field for the credential
  await expect(page.locator('[data-form="register-system"] input[type="password"]')).toHaveCount(0);
  await expect(page.locator('[data-settings-note]')).toContainText('KAFKA_TOPIC');
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

// Lane ui-edit-merge (review blocker): the edit form lists only the fields it owns, so saving used to ERASE the stored
// settings it does not show. The save now merges the form over the stored object: a Kafka system with securityProtocol,
// saslMechanism, username and halfOpenTrialCalls keeps all four after only the topic is changed, and the screen lists
// them read-only. (Authored, not run here - needs the docker stack.)
test('editing a Kafka system keeps the security settings the form does not show', async ({ page }) => {
  await page.goto(consoleUrl());
  const code = `sys-kmerge-${RUN}`;
  const kept = { securityProtocol: 'SASL_SSL', saslMechanism: 'SCRAM-SHA-512', username: 'svc-orders', halfOpenTrialCalls: 2 };
  const seeded = await inBrowser(page, 'POST', '/api/v1/admin/systems', {
    code, name: 'Kafka merge', direction: 'OUTBOUND', type: 'KAFKA',
    settings: { bootstrapServers: 'broker1.partner.test:9092', topic: 'orders.out', ...kept }
  });
  expect(seeded.status).toBe(201);

  await openSystems(page);
  await page.locator(`[data-system-row="${code}"] [data-action="open-system"]`).click();
  const editor = page.locator('[data-form="edit-system"]');
  // the non-form keys are visible, read-only (no input for them)
  await expect(editor.locator('[data-extra-setting="securityProtocol"]')).toContainText('SASL_SSL');
  await expect(editor.locator('[data-extra-settings] input')).toHaveCount(0);
  await editor.locator('[data-setting="topic"]').fill('orders.v2');
  await page.click('[data-action="save-system"]');
  await expect(page.locator('[data-saved="true"]')).toBeVisible();

  const stored = await inBrowser(page, 'GET', `/api/v1/admin/systems/${seeded.data.id}`);
  expect(stored.status).toBe(200);
  expect(stored.data.settings).toEqual({ bootstrapServers: 'broker1.partner.test:9092', topic: 'orders.v2', ...kept });
});

// E16 R1's other side, reviewed against main ce40797 (the KafkaSecurityProtocol module test owns the SEND
// default): a Kafka system SAVED without a securityProtocol is accepted - clear text is never invented and
// nothing is written on screen; the run-time default is SSL (SystemSettings.KAFKA_DEFAULT_PROTOCOL).
test('a Kafka system saved without a securityProtocol is accepted and stays without one on screen', async ({ page }) => {
  await page.goto(consoleUrl());
  const code = `sys-kdefault-${RUN}`;
  const seeded = await inBrowser(page, 'POST', '/api/v1/admin/systems', {
    code, name: 'Kafka default TLS', direction: 'OUTBOUND', type: 'KAFKA',
    settings: { bootstrapServers: 'broker1.partner.test:9092', topic: 'orders.out' }
  });
  expect(seeded.status).toBe(201);

  await openSystems(page);
  await page.locator(`[data-system-row="${code}"] [data-action="open-system"]`).click();
  const editor = page.locator('[data-form="edit-system"]');
  // no securityProtocol stored -> the read-only "other stored settings" list carries no protocol row
  await expect(editor).toBeVisible();
  await expect(editor.locator('[data-extra-setting="securityProtocol"]')).toHaveCount(0);

  // an unchanged save is a no-op on the wire: the absent protocol is never refused, never filled in
  await page.click('[data-action="save-system"]');
  await expect(page.locator('[data-saved="true"]')).toBeVisible();
  const stored = await inBrowser(page, 'GET', `/api/v1/admin/systems/${seeded.data.id}`);
  expect(stored.status).toBe(200);
  expect(stored.data.settings).toEqual({ bootstrapServers: 'broker1.partner.test:9092', topic: 'orders.out' });
});

// E16 R1 (Karan, commit ce40797 "Kafka plaintext off by default"): at SAVE the server refuses a tenant's
// clear-text protocol - PLAINTEXT and SASL_PLAINTEXT alike - unless the operator set
// platform.config.destinations.kafka-allow-plaintext (default off in the screens boot, screen-hosts.mjs).
// The register form offers no protocol field (the screen's security stance: clear text cannot be typed),
// so this scenario is the signed-in session's API contract; the refusal wording is asserted as-is.
const R1_TEXT = 'settings.securityProtocol must be SSL or SASL_SSL; clear text is off unless the operator '
  + 'sets platform.config.destinations.kafka-allow-plaintext';

test('a Kafka create with a clear-text protocol is refused at save with the R1 server words', async ({ page }) => {
  await page.goto(consoleUrl());
  const base = { code: `sys-kplain-${RUN}`, name: 'Clear text partner', direction: 'OUTBOUND',
    settings: { bootstrapServers: 'broker1.partner.test:9092', topic: 'orders.out' } };
  for (const protocol of ['PLAINTEXT', 'SASL_PLAINTEXT']) {
    const refusal = await inBrowser(page, 'POST', '/api/v1/admin/systems', {
      ...base, type: 'KAFKA', settings: { ...base.settings, securityProtocol: protocol }
    });
    expect(refusal.status, JSON.stringify(refusal.data)).toBe(400);
    expect(refusal.data?.errors).toContain(R1_TEXT);
  }

  // the screen never offers the protocol as an input, so clear text cannot be typed from the console
  await openSystems(page);
  await page.click('[data-action="open-register"]');
  await expect(page.locator('[data-form="register-system"] [data-setting="securityProtocol"]')).toHaveCount(0);
  await expect(page.locator('[data-form="register-system"] [data-setting="saslMechanism"]')).toHaveCount(0);
});

// E16 F3 (merged to main): the approved tables' save-time refusal is live, so this scenario runs unguarded.
test('a DB system whose settings carry approvedTables is refused FIELD_NOT_ALLOWED at save (F3)', async ({ page }) => {
  await page.goto(consoleUrl());
  const probe = await inBrowser(page, 'POST', '/api/v1/admin/systems', {
    code: `sys-f3-${RUN}`, name: 'F3 probe', direction: 'OUTBOUND', type: 'DB',
    settings: { host: 'db.partner.test', port: 5432, database: 'orders', user: 'etl', approvedTables: ['orders', 'lines'] }
  });
  expect(probe.status, JSON.stringify(probe.data)).toBe(400);
  expect(probe.data?.refusal).toBe('FIELD_NOT_ALLOWED'); // the old-client marker the body carries (SystemValidationException)
  expect(probe.data?.errors).toContain('settings.approvedTables is not allowed (FIELD_NOT_ALLOWED): the tables a'
    + ' DB target may write to are requested and approved at /api/v1/admin/systems/{systemId}/approved-tables,'
    + ' never listed in the settings');

  // the form no longer lists the field, so the console cannot type an approval into settings
  await openSystems(page);
  await page.click('[data-action="open-register"]');
  await expect(page.getByRole('dialog', { name: 'Register a system' }).locator('[data-setting="approvedTables"]')).toHaveCount(0);
});

// V-3 (row 306 defect batch, lane C): the Test connection button on the systems screen calls the
// real POST /api/v1/admin/systems/{systemId}/test-connection and shows what the endpoint answers.
// The screens harness allow-list (screen-hosts.mjs) covers the seed host, so the test call reaches
// the network; the reserved .test TLD (RFC 6761) never resolves, so the answer is FAILED
// deterministically; result FAILED is a 200 answer, shown as the endpoint names it.
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
  await expect(answer).toHaveAttribute('data-test-connection-result', 'FAILED');
  await expect(answer).toContainText(code);
});

// @negative: over the per-user rate limit (default 10 per 60s, config-systems.yml) the endpoint
// answers 429 TEST_CONNECTION_RATE_LIMITED; the screen surfaces it the way its other refusals are.
test('over the per-user rate limit the connection test is refused 429 and the refusal is shown', async ({ page }) => {
  await page.goto(consoleUrl());
  const code = `sys-ratelimit-${RUN}`;
  const seeded = await inBrowser(page, 'POST', '/api/v1/admin/systems', {
    code, name: 'Rate limit me', direction: 'OUTBOUND', type: 'MQ', settings: { host: 'mq.partner.test', port: 5672, queue: 'rate' }
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
    code, name: 'Tenant A only', direction: 'OUTBOUND', type: 'MQ', settings: { host: 'mq.partner.test', port: 5672, queue: 'a' }
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
    code, name: 'Drawer', direction: 'OUTBOUND', type: 'MQ', settings: { host: 'mq.partner.test', port: 5672, queue: 'd' }
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
