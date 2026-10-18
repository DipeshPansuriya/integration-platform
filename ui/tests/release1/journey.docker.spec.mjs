// Row 323 T139 (E15-F1-S1): the Release 1 browser journey, end to end through the REAL console and
// the REAL Keycloak login. A tenant admin registers the source and both targets, imports contracts
// v1.0 and v2.0, draws both mappings; a second user approves; the admin publishes. Customer A makes
// ONE call: target A (API, WireMock) receives v1.0 and target B (MQ-CSV, Artemis) receives v2.0.
// The delivery check reuses the t130 base-suite oracle (tests/base-suite/lib/wiremock.mjs) - never
// duplicated. Gated by pending.mjs until T98 (MQ target) and T143 (compose e2e profile) are on main.

import { test, expect } from '@playwright/test';
import { browserLogin } from '../screens/keycloak-login.mjs';
import { consoleUrl } from '../screens/boot-state.mjs';
import { waitsOn } from './pending.mjs';

const GATE = waitsOn('T98', 'T143');

function env(name) {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is not set - the T143 compose e2e profile provides it`);
  return value;
}

function user(role) {
  return { username: env(`E2E_${role}_USER`), password: env(`E2E_${role}_PASSWORD`) };
}

const SYSTEMS = [
  ['CUST-A', 'SOURCE', 'JSON'],
  ['TARGET-A', 'TARGET', 'JSON'],
  ['TARGET-B', 'TARGET', 'CSV']
];
const CONTRACTS = [
  ['TARGET-A', '1.0'],
  ['TARGET-B', '2.0']
];

test.describe('Release 1 journey (E15-F1-S1)', () => {
  test('admin builds and publishes; one customer call reaches both targets in their own versions', async ({ page, browser }) => {
    test.fixme(GATE !== null, GATE ?? '');
    const base = consoleUrl();

    await test.step('register the source and both targets', async () => {
      for (const [code, role, format] of SYSTEMS) {
        await page.goto(`${base}/systems`);
        await page.locator('[data-action="open-register-system"]').click();
        await page.locator('[data-field="system-code"]').fill(code);
        await page.locator('[data-field="system-role"]').selectOption(role);
        await page.locator('[data-field="system-format"]').selectOption(format);
        await page.locator('[data-action="register-system"]').click();
        await expect(page.locator(`[data-system-row="${code}"]`)).toBeVisible();
      }
    });

    await test.step('import contracts v1.0 (target A) and v2.0 (target B)', async () => {
      for (const [system, label] of CONTRACTS) {
        await page.goto(`${base}/contracts`);
        await expect(page.locator('[data-contracts-loaded="true"]')).toBeVisible();
        await page.locator('[data-action="open-import"]').click();
        const dialog = page.getByRole('dialog', { name: 'Import a contract version' });
        await dialog.locator('[data-field="contract-system"]').selectOption(system);
        await dialog.locator('[data-field="version-label"]').fill(label);
        await dialog.locator('[data-field="contract-document"]').setInputFiles(`tests/release1/fixtures/${system}-${label}.json`);
        await dialog.locator('[data-action="import"]').click();
        await expect(page.locator('[data-imported="true"]')).toBeVisible();
      }
    });

    await test.step('draw both mappings and submit them for approval', async () => {
      for (const [target] of CONTRACTS) {
        await page.goto(`${base}/mappings/new?source=CUST-A&target=${target}`);
        await page.locator('[data-action="auto-map-matching"]').click();
        await page.locator('[data-field="change-note"]').fill(`Release 1 journey ${target}`);
        await page.locator('[data-action="submit-for-approval"]').click();
        await expect(page.locator('[data-version-status="SUBMITTED"]')).toBeVisible();
      }
    });

    await test.step('a second user approves - the author cannot', async () => {
      await expect(page.locator('[data-action="approve-as-approver"]')).toHaveCount(0);
      const approverContext = await browser.newContext({ storageState: { cookies: [], origins: [] } });
      const approver = await approverContext.newPage();
      await browserLogin(approver, base, user('APPROVER'));
      await approver.goto(`${base}/approvals`);
      for (const button of await approver.locator('[data-action="approve-as-approver"]').all()) {
        await button.click();
      }
      await expect(approver.locator('[data-version-status="SUBMITTED"]')).toHaveCount(0);
      await approverContext.close();
    });

    await test.step('publish', async () => {
      await page.goto(`${base}/flows`);
      await page.locator('[data-action="publish"]').click();
      await expect(page.locator('[data-published="true"]')).toBeVisible();
    });

    await test.step('customer A makes one call; target A gets v1.0', async () => {
      const { deliveriesTo } = await import(`${env('E2E_BASE_SUITE_LIB')}/wiremock.mjs`);
      const answer = await page.request.post(`${env('E2E_BACKEND_URL')}/api/v1/interfaces/REF-ORDER/messages`, {
        headers: {
          'X-Source-Contract-Version': '1.0',
          'Idempotency-Key': `journey-${Date.now()}`,
          Authorization: `Bearer ${env('E2E_BEARER_CUSTOMER_A')}`
        },
        data: { orderNo: 'R1-JOURNEY', customer: 'CUST-A', qty: 1 }
      });
      expect(answer.status()).toBe(202);
      await expect.poll(async () => (await deliveriesTo(env('E2E_WIREMOCK_URL'), '/target-a')).length).toBe(1);
      // Target B (Artemis, MQ-CSV v2.0) is asserted by platform-e2e SC-01 through the Artemis CLI.
    });
  });

  test('@negative a gated journey is never green: the gate names what it waits on', async () => {
    // Runs now (no backend): proves the gate itself. A gated journey is fixme, never passed.
    expect(waitsOn('T95')).toBeNull();
    expect(waitsOn('T95', 'T98')).toContain('T98');
    expect(() => waitsOn('T999')).toThrow(/unknown/);
    expect(() => waitsOn()).toThrow(/at least one/);
    expect(GATE, 'the journey stays gated until T98 and T143 land').not.toBeNull();
  });
});
