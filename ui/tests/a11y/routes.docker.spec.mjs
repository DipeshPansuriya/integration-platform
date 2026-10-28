// BS-14 (lane 4, QA release-suite base): WCAG AA basics on every console route - keyboard, focus,
// labels, text contrast - measured in the REAL console behind the REAL backend (the screens protocol:
// e2e-ui/tests/screens/global-setup.mjs boots it, the signed-in storage state is the session). No axe: the
// checks are Playwright built-ins judged by the pure helpers in ./lib (node --test units in ./unit).
// The probes themselves are proven on inline pages in fixtures.fixture.spec.mjs.
//
// Every assertion is soft and carries the element + the WCAG criterion, so ONE run lists every defect of
// a route. A red here is a product FINDING (route, element, criterion), never a relaxed assertion.
//
// WRITTEN, NOT RUN against the console while the docker-suite lock slots are held (see hand-back).
//   npx playwright test -c playwright.a11y.config.mjs --project=routes
//
// Seeding goes through the real API from the signed-in browser (the specs' inBrowser pattern); the one
// message + dead letter are fixture rows in the suite's own pinned postgres (failures.docker.spec.mjs's
// pattern), so Failures and Message detail show real rows (tables, links, timeline).

import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import process from 'node:process';
import { expect, test } from '@playwright/test';
import { consoleUrl, STORAGE_STATE } from '../../../e2e-ui/tests/screens/boot-state.mjs';
import { describeFailures, describeStops, labelAudit, tabWalk, textContrast } from './lib/audit.mjs';
import { appliedTheme } from './lib/probes.mjs';

test.describe.configure({ mode: 'serial' });

const RUN = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
const THEMES = ['light', 'dark'];
const NO_SESSION = { cookies: [], origins: [] };
const CONTENT_ROOTS = ['header.topbar', 'nav.sidenav', 'main.content'];

// ---------- seeding (real API + the suite's own postgres) ----------

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

function psql(sql) {
  const names = execFileSync('docker', ['ps', '--format', '{{.Names}}'], { encoding: 'utf8' })
    .split('\n').map((name) => name.trim()).filter((name) => name.startsWith('ipass-ui-t41-pg-'));
  if (names.length !== 1) throw new Error(`exactly one booted suite postgres expected, saw: ${names.join(', ')}`);
  return execFileSync('docker', ['exec', names[0], 'psql', '-U', 'owner', '-d', 'platform', '-t', '-A', '-c', sql], { encoding: 'utf8' }).trim();
}

const seed = { flowId: null, messageId: null };

test.beforeAll(async ({ browser }) => {
  const context = await browser.newContext({ storageState: STORAGE_STATE });
  const page = await context.newPage();
  try {
    await page.goto(consoleUrl());
    await expect(page.locator('nav')).toBeVisible();

    const flow = await inBrowser(page, 'POST', '/api/v1/admin/flows', { sourceSystem: 'S', destinations: ['T1'], businessKeyRule: 'customer_id' });
    expect(flow?.data?.id, `seed flow: ${JSON.stringify(flow)}`).toBeTruthy();
    seed.flowId = flow.data.id;

    const system = await inBrowser(page, 'POST', '/api/v1/admin/systems', {
      code: `a11y-${RUN}`, name: 'A11y probe system', direction: 'OUTBOUND', type: 'DB', settings: { host: 'db.partner.test', port: 5432, database: 'orders', user: 'a11y_probe' },
    });
    expect(system.status, `seed system: ${JSON.stringify(system)}`).toBe(201);

    const tenantId = psql(`select id::text from public.tenant where code = '${process.env.CONSOLE_TENANT_CODE}'`);
    expect(tenantId, 'the harness tenant row exists').not.toBe('');
    const message = randomUUID();
    psql(`insert into public.message (id, tenant_id, interface_id, interface_version_id, source_contract_version_id, business_key, state, accepted_at, updated_at)`
      + ` values ('${message}'::uuid, '${tenantId}'::uuid, '${seed.flowId}'::uuid, gen_random_uuid(), gen_random_uuid(), 'order-${message}', 'ROUTED', now(), now())`);
    psql(`insert into public.message_stage_timing (message_id, tenant_id, accepted_at, stage, started_at, finished_at)`
      + ` values ('${message}'::uuid, '${tenantId}'::uuid, now(), 'INTAKE', now(), now()), ('${message}'::uuid, '${tenantId}'::uuid, now(), 'DELIVER', now(), null)`);
    const delivery = randomUUID();
    psql(`insert into public.delivery (id, tenant_id, state, created_at, updated_at, dead_letter_class, interface_id, target_id, message_id)`
      + ` values ('${delivery}'::uuid, '${tenantId}'::uuid, 'DEAD_LETTERED', now(), now(), 'PERMANENT', '${seed.flowId}'::uuid, gen_random_uuid(), '${message}'::uuid)`);
    psql(`insert into public.delivery_event (tenant_id, delivery_id, type, detail, occurred_at) values ('${tenantId}'::uuid, '${delivery}'::uuid, 'DEAD_LETTER', 'PERMANENT', now())`);
    seed.messageId = message;
  } finally {
    await context.close();
  }
});

// ---------- the route table ----------

/** [key, hash, ready selector, optional prepare(page) that makes the screen show its data, anonymous?] */
const routes = () => [
  { key: 'dashboard', hash: '#/', ready: '[data-page="dashboard"][data-dashboard-loaded="true"]' },
  { key: 'flows', hash: '#/flows', ready: '[data-page="flows-list"][data-flows-loaded="true"]' },
  { key: 'flow-new', hash: '#/flow/new', ready: '[data-page="flow-definition"]' },
  { key: 'flow-id', hash: `#/flow/${seed.flowId}`, ready: '[data-page="flow-definition"]' },
  { key: 'versions', hash: '#/versions', ready: '[data-page="versions"]' },
  { key: 'contracts', hash: '#/contracts', ready: '[data-page="contracts"][data-contracts-loaded="true"]' },
  { key: 'mappings', hash: '#/mappings', ready: '[data-page="mapping-designer"]' },
  { key: 'rules', hash: '#/rules', ready: '[data-page="rules-home"]' },
  { key: 'systems', hash: '#/systems', ready: '[data-page="systems"][data-systems-loaded="true"]' },
  { key: 'queue', hash: '#/queue', ready: '[data-page="queue-dashboard"][data-queue-loaded="true"]' },
  {
    key: 'failures', hash: '#/failures', ready: '[data-page="failures"][data-failures-loaded="true"]',
    prepare: async (page) => {
      await page.getByRole('combobox', { name: 'Flow', exact: true }).selectOption(seed.flowId);
      await expect(page.locator('[data-page="failures"][data-failures-loaded="true"]')).toBeVisible();
    },
  },
  { key: 'message', hash: `#/messages/${seed.messageId}?failed=DELIVER`, ready: '[data-page="message-detail"][data-message-loaded="true"]' },
  { key: 'activity', hash: '#/activity', ready: '[data-page="activity"][data-activity-loaded="true"]' },
  { key: 'reports', hash: '#/reports', ready: '[data-page="delivery-report"]' },
  { key: 'login', hash: '#/login', ready: '[data-page="login"][data-session-state="signed-out"]', anonymous: true },
];

// Route keys are static so test titles exist at collection time; the hashes resolve at run time.
const KEYS = ['dashboard', 'flows', 'flow-new', 'flow-id', 'versions', 'contracts', 'mappings', 'rules', 'systems', 'queue', 'failures', 'message', 'activity', 'reports', 'login'];

/** Open a route in the given theme (a signed-out context for #/login) and wait until it is settled. */
async function openRoute(browser, page, key, theme) {
  const route = routes().find((r) => r.key === key);
  let target = page;
  let context = null;
  if (route.anonymous) {
    context = await browser.newContext({ storageState: NO_SESSION });
    target = await context.newPage();
  }
  await target.addInitScript((t) => {
    try { localStorage.setItem('ip.theme', t); } catch { /* storage blocked: the OS preference decides */ }
  }, theme);
  await target.goto(`${consoleUrl()}/${route.hash}`);
  await expect(target.locator(route.ready).first()).toBeVisible();
  if (route.prepare) await route.prepare(target);
  // let any "Loading ..." placeholder go before measuring
  await target.waitForFunction(() => !/\bLoading\b/.test(document.body.innerText), undefined, { timeout: 10000 }).catch(() => {});
  expect(await target.evaluate(appliedTheme), `the pre-paint theme boot applied ${theme}`).toBe(theme);
  return { page: target, close: async () => context?.close() };
}

// ---------- keyboard + focus, both themes (WCAG 2.1.1, 2.1.2, 2.4.3, 2.4.7, 1.4.11) ----------

for (const theme of THEMES) {
  test.describe(`keyboard and focus (${theme} theme)`, () => {
    for (const key of KEYS) {
      test(`${key}: every control reachable by Tab, no trap, DOM order, visible focus >= 3:1`, async ({ browser, page }) => {
        const opened = await openRoute(browser, page, key, theme);
        try {
          const result = await tabWalk(opened.page);
          const label = (id) => result.expected.find((c) => c.id === id)?.label ?? id;
          expect(result.expected.length, `${key}: the page exposes tabbable controls`).toBeGreaterThan(0);
          expect.soft(result.walk.trap, `WCAG 2.1.2 keyboard trap on ${key}; walk: ${describeStops(result.stops)}`).toBe(false);
          expect.soft(result.walk.missing.map(label), `WCAG 2.1.1 controls Tab never reached on ${key}`).toEqual([]);
          expect.soft(result.walk.outOfOrder.map(label), `WCAG 2.4.3 focus order differs from DOM order on ${key}`).toEqual([]);
          expect.soft(result.positiveTabindex, `WCAG 2.4.3 positive tabindex on ${key}`).toEqual([]);
          const weak = result.indicators.filter((i) => !i.passes && i.measured !== false);
          expect.soft(
            weak.map((i) => `${i.tag}:${i.label} kind=${i.kind} ratio=${i.ratio}`),
            `WCAG 2.4.7 / 1.4.11 focus indicator missing or under 3:1 on ${key} (${theme})`
          ).toEqual([]);
          const unmeasured = result.indicators.filter((i) => i.measured === false);
          if (unmeasured.length) test.info().annotations.push({ type: 'focus not measurable', description: unmeasured.map((i) => `${i.tag}:${i.label}`).join(', ') });
          if (result.walk.extra.length) test.info().annotations.push({ type: 'extra tab stops', description: result.walk.extra.join(', ') });
          if (result.truncated) test.info().annotations.push({ type: 'tab walk truncated', description: `only the first stops of ${result.expected.length}+ were walked` });
        } finally {
          await opened.close();
        }
      });
    }
  });
}

// ---------- text contrast, both themes (WCAG 1.4.3) ----------

for (const theme of THEMES) {
  test.describe(`text contrast (${theme} theme)`, () => {
    for (const key of KEYS) {
      test(`${key}: text >= 4.5:1 (3:1 large)`, async ({ browser, page }) => {
        const opened = await openRoute(browser, page, key, theme);
        try {
          const roots = key === 'login' ? ['main.signin'] : CONTENT_ROOTS;
          const result = await textContrast(opened.page, roots);
          expect(result.checked, `${key}: some text was measurable`).toBeGreaterThan(0);
          expect.soft(result.failures, `WCAG 1.4.3 contrast on ${key} (${theme}):\n  ${describeFailures(result.failures)}`).toEqual([]);
          if (result.skipped) test.info().annotations.push({ type: 'contrast not measurable', description: `${result.skipped} sample(s) over an image/gradient or an unreadable colour` });
        } finally {
          await opened.close();
        }
      });
    }
  });
}

// ---------- labels / names / structure (WCAG 4.1.2, 1.1.1, 1.3.1, 2.4.6) ----------

test.describe('labels and structure (browser accessibility tree)', () => {
  for (const key of KEYS) {
    test(`${key}: every control named, one h1, tables headed, images with a text alternative`, async ({ browser, page }) => {
      const opened = await openRoute(browser, page, key, 'light');
      try {
        const audit = await labelAudit(opened.page);
        expect.soft(audit.nameless.map((n) => `${n.role} (${n.path})`), `WCAG 4.1.2 / 1.1.1 nameless controls or images on ${key}`).toEqual([]);
        expect.soft(audit.h1Count, `WCAG 2.4.6 exactly one h1 on ${key}`).toBe(1);
        expect.soft(audit.tablesWithoutHeaders, `WCAG 1.3.1 tables without a column/row header on ${key}`).toBe(0);
      } finally {
        await opened.close();
      }
    });
  }
});

// ---------- dialogs: focus in, Tab contained, Escape closes, focus returns (WCAG 2.1.2, 2.4.3, 4.1.2) ----------

/** Open with the keyboard (Enter on the focused opener), then judge the modal's keyboard contract. */
async function judgeDialog(page, opener, role, name) {
  await opener.focus();
  await page.keyboard.press('Enter');
  const dialog = page.getByRole(role, name === undefined ? {} : { name });
  await expect(dialog, 'the dialog opens on Enter and has an accessible name').toBeVisible();
  expect.soft(await dialog.locator(':focus').count(), 'WCAG 2.4.3 focus moved inside the dialog on open').toBe(1);

  const escaped = [];
  for (let i = 0; i < 15; i += 1) {
    await page.keyboard.press('Tab');
    if ((await dialog.locator(':focus').count()) === 0) escaped.push(`Tab #${i + 1}`);
  }
  await page.keyboard.press('Shift+Tab');
  if ((await dialog.locator(':focus').count()) === 0) escaped.push('Shift+Tab');
  expect.soft(escaped, 'aria-modal: Tab / Shift+Tab keep focus inside the dialog (WCAG 2.4.3, ARIA APG modal)').toEqual([]);

  // Escape is judged from inside the dialog even if Tab escaped above (that is reported separately)
  await dialog.locator('button, input, select, textarea, a[href]').first().focus();
  await page.keyboard.press('Escape');
  await expect.soft(dialog, 'Escape closes the dialog').toBeHidden();
  await expect.soft(opener, 'WCAG 2.4.3 focus returns to the control that opened the dialog').toBeFocused();
}

test.describe('dialogs', () => {
  test('systems: the Register drawer', async ({ page }) => {
    await page.goto(`${consoleUrl()}/#/systems`);
    await expect(page.locator('[data-page="systems"][data-systems-loaded="true"]')).toBeVisible();
    await judgeDialog(page, page.locator('[data-action="open-register"]'), 'dialog');
  });

  test('contracts: the Import drawer', async ({ page }) => {
    await page.goto(`${consoleUrl()}/#/contracts`);
    await expect(page.locator('[data-page="contracts"][data-contracts-loaded="true"]')).toBeVisible();
    await judgeDialog(page, page.locator('[data-action="open-import"]'), 'dialog', 'Import a contract version');
  });

  test('flow editor: the unsaved-changes alertdialog', async ({ page }) => {
    await page.goto(`${consoleUrl()}/#/flow/new`);
    await expect(page.locator('[data-page="flow-definition"]')).toBeVisible();
    await page.locator('[data-field="interface-name"]').fill(`a11y-${RUN}`);
    await judgeDialog(page, page.locator('[data-action="back-to-flows"]'), 'alertdialog');
  });
});

// ---------- the shell: Enter and Space activate, status and errors are announced ----------

test.describe('shell keyboard activation', () => {
  test('buttons activate on Enter and Space; a nav link on Enter', async ({ page }) => {
    await page.goto(`${consoleUrl()}/#/queue`);
    await expect(page.locator('[data-page="queue-dashboard"]')).toBeVisible();

    const theme = () => page.evaluate(appliedTheme);
    const before = await theme();
    await page.locator('[data-action="toggle-theme"]').focus();
    await page.keyboard.press('Enter');
    expect.soft(await theme(), 'Enter activates the theme toggle').not.toBe(before);
    await page.keyboard.press('Space');
    expect.soft(await theme(), 'Space activates the theme toggle').toBe(before);

    const navToggle = page.locator('[data-action="toggle-nav"]');
    const expanded = await navToggle.getAttribute('aria-expanded');
    await navToggle.focus();
    await page.keyboard.press('Space');
    expect.soft(await navToggle.getAttribute('aria-expanded'), 'Space toggles the navigation (aria-expanded flips)').not.toBe(expanded);
    await page.keyboard.press('Enter');
    expect.soft(await navToggle.getAttribute('aria-expanded'), 'Enter toggles it back').toBe(expanded);

    await page.locator('[data-nav="activity"]').focus();
    await page.keyboard.press('Enter');
    await expect.soft(page, 'Enter follows a navigation link').toHaveURL(/#\/activity$/);

    await page.locator('[data-action="account"]').focus();
    await page.keyboard.press('Enter');
    await expect.soft(page.locator('[data-action="logout"]'), 'Enter opens the account menu').toBeVisible();
  });
});

test.describe('errors and status are announced (WCAG 3.3.1, 4.1.3)', () => {
  test('an unknown flow is announced through role=alert', async ({ page }) => {
    await page.goto(`${consoleUrl()}/#/flow/${randomUUID()}`);
    await expect(page.getByRole('alert').filter({ hasText: /./ }).first()).toBeVisible();
  });

  test('an invalid flow save marks the field, ties its error text to it, and puts focus or an alert on it', async ({ page }) => {
    await page.goto(`${consoleUrl()}/#/flow/new`);
    await expect(page.locator('[data-page="flow-definition"]')).toBeVisible();
    await page.locator('[data-action="save-draft"]').click();
    const invalid = page.locator('[aria-invalid="true"]').first();
    await expect(invalid, 'a failing field is aria-invalid').toBeVisible();
    const describedBy = await invalid.getAttribute('aria-describedby');
    expect.soft(describedBy, 'the failing field names its error text via aria-describedby').toBeTruthy();
    const text = describedBy ? await page.locator(`#${describedBy.split(/\s+/)[0]}`).innerText() : '';
    expect.soft(text.trim(), 'the described error text is visible and not empty').not.toBe('');
    const announced = (await invalid.evaluate((el) => el === document.activeElement)) || (await page.getByRole('alert').count()) > 0;
    expect.soft(announced, 'WCAG 4.1.3 the error is announced: focus moved to the field, or a role=alert shows').toBe(true);
  });
});
