// Lane screens-gap (IPass lane 2): the type-specific settings fields of the system register form
// (src/pages/systems/SystemFields.tsx, SETTINGS_BY_TYPE in src/api/systems.ts) - what tests/screens/systems.docker.spec.mjs
// leaves open. WRITTEN, NOT RUN: needs a docker-suite lock slot (all held at authoring time).
//
// systems.docker.spec.mjs registers each of API | DB | MQ | File | Kafka with ONLY the required settings, and walks the
// refusals. Not exercised there (grep: followRedirects 0 hits, data-settings-for 0 hits): the fieldset a type shows
// and nothing else, the optional API followRedirects checkbox, the optional DB user/table fields with their hint
// wiring, and that an unknown type shows no settings fieldset at all. This file adds exactly those, against the real
// API (no mock); the round-trip assertions read the stored system back through GET /api/v1/admin/systems.
//
// Not a gap, checked: the brief named "Kafka / MQ / DB route-template fields" - the console has NO route-template
// field (grep -i "routeTemplate|route-template|route template" ui/src = 0 hits); Kafka (bootstrapServers, topic) and
// MQ (host, port, queue) are the existing ones and already covered. Fixture hosts are on the screens allow-list
// (*.partner.test, screen-hosts.mjs; unit test tests/screens/unit/screen-hosts.test.mjs keeps that true).

import { expect, test } from '@playwright/test';
import { consoleUrl } from './boot-state.mjs';
import { inBrowser } from './screen-fixtures.mjs';

const PAGE = '[data-page="systems"]';
const RUN = Date.now().toString(36);

/** The fields each type's fieldset offers, in screen order, with the required ones starred (SETTINGS_BY_TYPE). */
const FIELDS = {
  API: { names: ['baseUrl', 'timeoutMs', 'followRedirects'], required: ['baseUrl'] },
  DB: { names: ['host', 'port', 'database', 'user', 'table'], required: ['host', 'port', 'database'], extra: ['approvedTables'] },
  MQ: { names: ['host', 'port', 'queue'], required: ['host', 'port', 'queue'] },
  FILE: { names: ['rootPath'], required: ['rootPath'] },
  KAFKA: { names: ['bootstrapServers', 'topic'], required: ['bootstrapServers', 'topic'] }
};

async function openRegister(page) {
  await page.goto(consoleUrl());
  await page.click('[data-nav="systems"]');
  await expect(page.locator(`${PAGE}[data-systems-loaded="true"]`)).toBeVisible();
  await page.click('[data-action="open-register"]');
  return page.getByRole('dialog', { name: 'Register a system' }).locator('[data-form="register-system"]');
}

async function register(form, { code, name, type, settings }) {
  await form.locator('[data-field="system-code"]').fill(code);
  await form.locator('[data-field="system-name"]').fill(name);
  await form.locator('[data-field="system-direction"]').selectOption('OUTBOUND');
  await form.locator('[data-field="system-type"]').fill(type);
  for (const [setting, value] of Object.entries(settings)) {
    const field = form.locator(`[data-setting="${setting}"]`);
    if (typeof value === 'boolean') await field.setChecked(value);
    else await field.fill(String(value));
  }
  await form.locator('[data-action="register-system"]').click();
}

async function storedSystem(page, code) {
  const listed = (await inBrowser(page, 'GET', '/api/v1/admin/systems')).data ?? [];
  return listed.find((system) => system.code === code);
}

// Scenario Outline: the type picks the fieldset (Examples: API | DB | MQ | File | Kafka).
for (const [type, spec] of Object.entries(FIELDS)) {
  test(`typing the type ${type} shows its settings fieldset with exactly its fields; required ones are starred and announced`, async ({ page }) => {
    const form = await openRegister(page);
    await form.locator('[data-field="system-type"]').fill(type);

    const fieldset = form.locator(`fieldset[data-settings-for="${type}"]`);
    await expect(fieldset).toBeVisible();
    await expect(fieldset.locator('legend')).toHaveText(`${type} settings`);
    const shown = await fieldset.locator('[data-setting]').evaluateAll((els) => els.map((el) => el.getAttribute('data-setting')));
    // DB may still offer approvedTables on main (the F3 change drops it, see systems.docker.spec.mjs:285): allowed, not required
    expect(shown.filter((name) => !(spec.extra ?? []).includes(name))).toEqual(spec.names);
    for (const name of spec.names) {
      const input = fieldset.locator(`[data-setting="${name}"]`);
      const label = form.locator(`label:has(#sys-setting-${name})`);
      if (spec.required.includes(name)) {
        await expect(label).toContainText('*');
        await expect(input).toHaveAttribute('aria-required', 'true');
      } else {
        await expect(label).not.toContainText('*');
        await expect(input).not.toHaveAttribute('aria-required', 'true');
      }
    }
    await expect(form.locator('fieldset[data-settings-for]')).toHaveCount(1);
  });
}

test('switching the type replaces the fieldset; an unknown type shows no settings fieldset at all', async ({ page }) => {
  const form = await openRegister(page);
  const type = form.locator('[data-field="system-type"]');
  await type.fill('KAFKA');
  await expect(form.locator('fieldset[data-settings-for="KAFKA"]')).toBeVisible();
  await expect(form.locator('[data-settings-note]')).toContainText('KAFKA_TOPIC');

  await type.fill('API');
  await expect(form.locator('fieldset[data-settings-for="KAFKA"]')).toHaveCount(0);
  await expect(form.locator('fieldset[data-settings-for="API"]')).toBeVisible();
  await expect(form.locator('[data-settings-note]')).toHaveCount(0);

  await type.fill('FTP');
  await expect(form.locator('fieldset[data-settings-for]')).toHaveCount(0);
  await type.fill('');
  await expect(form.locator('fieldset[data-settings-for]')).toHaveCount(0);
});

test('an API system with the optional Follow redirects box ticked stores followRedirects: true and the timeout as a number', async ({ page }) => {
  const code = `sysf-api-${RUN}`;
  const form = await openRegister(page);
  await expect(form.locator('[data-setting="followRedirects"]')).not.toBeChecked();
  await register(form, {
    code, name: 'API with redirects', type: 'API',
    settings: { baseUrl: 'https://partner.example.test/redirects', timeoutMs: 7000, followRedirects: true }
  });
  await expect(page.locator('[data-saved="true"]')).toBeVisible();

  const stored = await storedSystem(page, code);
  expect(stored?.type).toBe('API');
  expect(stored?.settings).toEqual({ baseUrl: 'https://partner.example.test/redirects', timeoutMs: 7000, followRedirects: true });
});

test('an API system with the box left unticked stores no followRedirects key (nothing is invented)', async ({ page }) => {
  const code = `sysf-api-plain-${RUN}`;
  const form = await openRegister(page);
  await register(form, { code, name: 'API plain', type: 'API', settings: { baseUrl: 'https://partner.example.test/plain' } });
  await expect(page.locator('[data-saved="true"]')).toBeVisible();

  const stored = await storedSystem(page, code);
  expect(stored?.settings).toEqual({ baseUrl: 'https://partner.example.test/plain' });
});

test('a DB system with the optional account name and table stores them; the account hint is wired to its input', async ({ page }) => {
  const code = `sysf-db-${RUN}`;
  const form = await openRegister(page);
  await form.locator('[data-field="system-type"]').fill('DB');
  const user = form.locator('[data-setting="user"]');
  await expect(form.locator('#sys-setting-user-hint')).toContainText('The password is a credential, stored separately.');
  await expect(user).toHaveAttribute('aria-describedby', /sys-setting-user-hint/);

  await register(form, {
    code, name: 'DB with account', type: 'DB',
    settings: { host: 'db.partner.test', port: 5432, database: 'orders', user: 'etl', table: 'orders' }
  });
  await expect(page.locator('[data-saved="true"]')).toBeVisible();

  const stored = await storedSystem(page, code);
  expect(stored?.type).toBe('DB');
  expect(stored?.settings).toEqual({ host: 'db.partner.test', port: 5432, database: 'orders', user: 'etl', table: 'orders' });
  // no credential-looking key was ever offered or stored
  expect(Object.keys(stored.settings).filter((key) => /password|secret|token/i.test(key))).toEqual([]);
});
