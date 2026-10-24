// Row 323 T139 (E15-F1-S1), made runnable by row 372 L28: the Release 1 browser journey through the REAL
// console and the REAL Keycloak login. Run it ONLY through scripts/run-release1-journey.mjs (it boots the
// backend, Artemis and the console, seeds REF-ORDER and sets every E2E_* name below); a bare
// `playwright test` fails fast on the first missing name.
//
// Scope, stated plainly: REF-ORDER (two targets, four-eyes, published) is seeded through the admin API by the
// runner, exactly as the frozen Java gate does. The browser then walks what a person does on the screens:
// import a contract version, rebuild a mapping with Auto-map, send ONE customer call (target A gets v1.0),
// then the failure path - a 403 dead letter shown on the failures screen and replayed through the
// confirm step. Target B (Artemis, MQ-CSV v2.0) is asserted by platform-e2e SC-01 through the Artemis CLI.
// The delivery oracle is the base suite's (tests/base-suite/lib/wiremock.mjs) - never duplicated.

import fs from 'node:fs';
import { test, expect } from '@playwright/test';
import { browserLogin } from '../screens/keycloak-login.mjs';
import { consoleUrl } from '../screens/boot-state.mjs';
import { waitsOn } from './pending.mjs';
import { deliveriesOfOrder, targetAStub, utcDay, FAIL_STUB_ID } from './lib/journey-lib.mjs';

const GATE = waitsOn('T98', 'T143');
const RUN = Date.now();
const GOOD_ORDER = `R1-JOURNEY-${RUN}`;
const FAIL_ORDER = `R1-JOURNEY-FAIL-${RUN}`;

function setting(name) {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is not set - run this journey through scripts/run-release1-journey.mjs`);
  return value;
}

async function journal() {
  const answer = await fetch(`${setting('E2E_WIREMOCK_ADMIN_URL')}/requests`);
  if (!answer.ok) throw new Error(`WireMock journal answered ${answer.status}`);
  return answer.json();
}

async function intake(request, orderNo) {
  const answer = await request.post(`${setting('E2E_BACKEND_URL')}/api/v1/interfaces/${setting('E2E_INTERFACE_CODE')}/messages`, {
    headers: { 'X-Source-Contract-Version': '1.0', 'Idempotency-Key': `journey-${orderNo}`, Authorization: `Bearer ${setting('E2E_BEARER_CUSTOMER_A')}` },
    data: { orderNo, customer: 'CUST-A', qty: 1 }
  });
  expect(answer.status(), await answer.text()).toBe(202);
  return (await answer.json()).trackingId;
}

async function setTargetAStatus(status) {
  const answer = await fetch(`${setting('E2E_WIREMOCK_ADMIN_URL')}/mappings/${FAIL_STUB_ID}`, {
    method: 'PUT', headers: { 'content-type': 'application/json' }, body: JSON.stringify(targetAStub(status))
  });
  if (answer.status !== 200 && answer.status !== 201) throw new Error(`WireMock stub PUT answered ${answer.status}`);
}

test.describe('Release 1 journey (E15-F1-S1)', () => {
  test.fixme(GATE !== null, GATE ?? '');

  test('the console journey: import, auto-map, one customer call, then a dead letter replayed', async ({ page, context }) => {
    test.setTimeout(8 * 60 * 1000);
    const base = consoleUrl();
    await browserLogin(page, base, { username: setting('E2E_ADMIN_USER'), password: setting('E2E_ADMIN_PASSWORD') });

    await test.step('import contract version 1.1 of the source through the Contracts screen', async () => {
      await page.goto(`${base}/#/contracts`);
      await expect(page.locator('[data-contracts-loaded="true"]')).toBeVisible();
      await page.locator('[data-action="open-import"]').click();
      await page.locator('[data-field="contract-system"]').selectOption('ref-source');
      await page.locator('[data-field="contract-role"]').selectOption('SOURCE_REQUEST');
      await page.locator('[data-field="version-label"]').fill('1.1');
      await page.locator('[data-field="contract-format"]').selectOption('JSON_SCHEMA');
      await page.locator('[data-field="contract-document"]').fill(fs.readFileSync('tests/release1/fixtures/ref-source-1.1.json', 'utf8'));
      await page.locator('[data-action="import-contract"]').click();
      await expect(page.locator('[data-imported="true"]')).toContainText('1.1');
    });

    await test.step('rebuild the target A mapping: clear the links, Auto-map matching names puts them back', async () => {
      // its own page: leaving it with unsaved edits must never prompt, and the published mapping is not saved over
      const mapPage = await context.newPage();
      await mapPage.goto(`${base}/#/mappings/${setting('E2E_ROUTE_A_ID')}`);
      await expect(mapPage.locator('[data-page="mapping-designer"]')).toBeVisible();
      const rows = mapPage.locator('[data-link-row]');
      await expect(rows).toHaveCount(3);
      while ((await rows.count()) > 0) await rows.first().locator('[data-action="delete-link"]').click();
      await mapPage.locator('[data-action="auto-map-matching"]').click();
      for (const field of ['orderNo', 'customer', 'qty']) {
        await expect(mapPage.locator(`[data-link-row="${field}->${field}"]`)).toBeVisible();
      }
      await expect(rows).toHaveCount(3);
      await mapPage.close();
    });

    await test.step('customer A makes ONE call; target A receives it under contract v1.0', async () => {
      await intake(page.request, GOOD_ORDER);
      await expect.poll(async () => deliveriesOfOrder(await journal(), GOOD_ORDER).length, { timeout: 90_000 }).toBe(1);
      const [sent] = deliveriesOfOrder(await journal(), GOOD_ORDER);
      expect(sent.body.contractVersion).toBe('1.0');
    });

    try {
      let trackingId;
      await test.step('target A refuses (403): the delivery dead-letters after one send', async () => {
        await setTargetAStatus(403);
        trackingId = await intake(page.request, FAIL_ORDER);
        await expect.poll(async () => deliveriesOfOrder(await journal(), FAIL_ORDER).length, { timeout: 90_000 }).toBe(1);
      });

      await test.step('the failures screen lists it as a DELIVER failure', async () => {
        await page.goto(`${base}/#/failures`);
        await expect(page.locator('[data-page="failures"]')).toHaveAttribute('data-failures-loaded', 'true');
        await page.locator('[data-field="failures-flow"]').selectOption(setting('E2E_FLOW_ID'));
        await page.locator('[data-field="failures-date"]').fill(utcDay());
        await expect(page.locator(`[data-failure-row="${trackingId}"]`)).toHaveAttribute('data-failing-stage', 'DELIVER', { timeout: 60_000 });
      });

      await test.step('fix the target, press Replay: the first press only asks to confirm, the second sends', async () => {
        const replay = page.locator(`[data-failure-row="${trackingId}"] [data-action="replay"]`);
        await replay.click();
        await expect(replay).toHaveAttribute('data-state', 'confirm');
        await expect(replay).toHaveText('Confirm replay');
        expect(deliveriesOfOrder(await journal(), FAIL_ORDER), 'arming the confirm step sends nothing').toHaveLength(1);
        await setTargetAStatus(200);
        await replay.click();
        await expect(page.locator('[data-action-answer]').first()).toContainText('Replay queued');
        await expect.poll(async () => deliveriesOfOrder(await journal(), FAIL_ORDER).length, { timeout: 90_000 }).toBe(2);
        const [first, second] = deliveriesOfOrder(await journal(), FAIL_ORDER);
        expect(second.deliveryId, 'the replay keeps the delivery id').toBe(first.deliveryId);
      });
    } finally {
      await fetch(`${setting('E2E_WIREMOCK_ADMIN_URL')}/mappings/${FAIL_STUB_ID}`, { method: 'DELETE' }).catch(() => {});
    }
  });
});
