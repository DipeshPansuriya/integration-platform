// The demo's narration: beats a, b and c made visible. The base-suite specs drive the pipeline through API calls the audience
// cannot see; this one posts a real order into Customer A (tenant A) and shows, on a banner inside the headed browser,
//   a  the console signed in (the real Keycloak sign-in happened in the headed global setup),
//   b  the intake answer: 202 and the tracking id,
//   c  the WireMock journal entry: the transformed body and the traceparent header at the target (and the journal page itself),
//   d  the same message's timeline screen (BS-08 asserts the four stages on its own message right after).
// Test code only; it asserts what it shows. Runs before BS-07 (the demo config makes BS-07 depend on it). Its order id
// (8001) is outside every other spec's range, and the message is registered with addTracking so BS-12's provenance check holds.

import { randomBytes } from 'node:crypto';
import { expect, test } from '@playwright/test';
import { SOURCE_CONTRACT, TENANTS, expectedTargetBody, orderRecord } from '../base-suite/lib/tenants.mjs';
import { traceparentOf } from '../base-suite/lib/trace.mjs';
import { header, requestsTo } from '../base-suite/lib/wiremock.mjs';
import { eventually, journal, postRecord, sourceToken } from '../base-suite/support/api.mjs';
import { bsEnv } from '../base-suite/support/boot-state.mjs';
import { addTracking, readRun } from '../base-suite/support/run-file.mjs';
import { intakeCode, rotatedSecret } from '../base-suite/support/seed.mjs';
import { demoPauseMs } from './demo-mode.mjs';
import { deliveryLines, intakeLines } from './narration-text.mjs';

test.describe.configure({ mode: 'serial' });

const A = TENANTS.A;
const PIPELINE_MS = 90_000;
const ORDER_NUMBER = 8001;

/** The beat banner, fixed at the bottom of the console page (it survives the console's hash navigation). */
async function banner(page, title, lines) {
  await page.evaluate(({ title: head, lines: body }) => {
    let box = document.getElementById('demo-beat');
    if (!box) {
      box = document.createElement('div');
      box.id = 'demo-beat';
      box.style.cssText = 'position:fixed;left:0;right:0;bottom:0;z-index:2147483647;background:#0b2545;color:#fff;'
        + 'font:15px/1.5 Consolas,monospace;padding:14px 20px;white-space:pre-wrap;border-top:4px solid #f5a623';
      document.body.appendChild(box);
    }
    box.textContent = `${head}\n${body.join('\n')}`;
  }, { title, lines });
}

const pause = (page) => page.waitForTimeout(demoPauseMs());

test('beats a to d, narrated: sign-in, a 202 with a tracking id, the stub journal entry, the timeline screen', async ({ page }) => {
  test.setTimeout(5 * 60 * 1000);
  await page.goto(`${bsEnv('consoleUrl')}/`);
  await expect(page.locator('[data-session-tenant]')).toHaveText(bsEnv('tenantA'));
  await banner(page, 'BEAT a - signed in', [`console session of tenant ${bsEnv('tenantA')}: the real Keycloak login form, then the backend's HttpOnly session cookie`]);
  await pause(page);

  const { credentialId } = readRun().seed.A;
  const { clientId, secretValue } = await rotatedSecret(page, A, credentialId); // secret in memory only
  const token = await sourceToken(clientId, secretValue);
  const record = orderRecord(A, ORDER_NUMBER);
  const sentTraceparent = traceparentOf(randomBytes(16), randomBytes(8));

  const answer = await postRecord(token, intakeCode(A), SOURCE_CONTRACT.versionLabel, record, { traceparent: sentTraceparent });
  expect(answer.status, JSON.stringify(answer.data)).toBe(202);
  const trackingId = answer.data.trackingId;
  addTracking('A', [trackingId]);
  await banner(page, 'BEAT b - one order into Customer A', intakeLines({ status: answer.status, trackingId, orderId: record.orderId }));
  await pause(page);

  const entry = await eventually(async () => {
    const hit = requestsTo(await journal(), A.stubPath).find(({ request }) => {
      if (request.method !== 'POST' || header(request.headers, 'Idempotency-Key') === null) return false;
      try {
        return JSON.parse(request.body)?.referenceNo === record.orderId;
      } catch {
        return false;
      }
    });
    return hit ? { body: JSON.parse(hit.request.body), idempotencyKey: header(hit.request.headers, 'Idempotency-Key'), traceparent: header(hit.request.headers, 'traceparent') } : null;
  }, `the delivery of ${record.orderId} on ${A.stubPath}`, { timeoutMs: PIPELINE_MS });
  expect(entry.body, 'the contract-translated body').toEqual(expectedTargetBody(record));
  expect(entry.traceparent, 'the traceparent reaches the target').not.toBeNull();
  await banner(page, 'BEAT c - routed, transformed and delivered to the target stub', deliveryLines({ path: A.stubPath, entry, sentTraceparent }));
  await pause(page);

  // the journal as WireMock itself shows it, in a second tab
  const tab = await page.context().newPage();
  await tab.goto(`${bsEnv('wiremockAdmin')}/requests`);
  await tab.waitForTimeout(demoPauseMs());
  await tab.close();

  await page.goto(`${bsEnv('consoleUrl')}/#/messages/${trackingId}`);
  await expect(page.locator('[data-page="message-detail"][data-message-loaded="true"]')).toBeVisible();
  await banner(page, 'BEAT d - the message timeline', [`tracking id ${trackingId}: INTAKE, ROUTE, TRANSFORM, DELIVER (the next test, BS-08, asserts the order)`]);
  await pause(page);
});
