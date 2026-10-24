// BS-13f (plan section 3) - one failing target cannot harm the other tenant: tenant A's target answers 403 and
// A's delivery dead-letters; tenant B's delivery, posted right after, is DELIVERED with exactly one body on
// B's own path. Needs a delivery that really reaches the one WireMock, i.e. the delivery base-URL seam
// (ON_MAIN.DELIVERY_BASE_URL in lib/pending-names.mjs: on main since 73257d41 the system create/save writes settings.baseUrl into the endpoint).
// Never run against production; never a psql-seeded row (unit/direct-sql.test.mjs scans this file).

import { expect, test } from '@playwright/test';
import { deliveryOfMessageSql } from '../lib/direct-sql.mjs';
import { waitsOn } from '../lib/pending-names.mjs';
import { SOURCE_CONTRACT, TENANTS, expectedTargetBody, orderRecord } from '../lib/tenants.mjs';
import { deliveriesTo } from '../lib/wiremock.mjs';
import { eventually, journal, postRecord, setStub, sourceToken } from '../support/api.mjs';
import { bsEnv } from '../support/boot-state.mjs';
import { readRows } from '../support/db.mjs';
import { addTracking, readRun } from '../support/run-file.mjs';
import { intakeCode, rotatedSecret } from '../support/seed.mjs';

const WAITS = waitsOn('T106', 'DELIVERY_BASE_URL');
test.fixme(WAITS !== null, WAITS ?? '');

const PIPELINE_MS = 90000;
const A = TENANTS.A;
const B = TENANTS.B;
let stubAt403 = false;
test.afterAll(async () => {
  if (stubAt403) await setStub(A.stubPath, 200); // never leave tenant A's target refusing for BS-12
});

const stateOf = (tenant, messageId) => {
  const rows = readRows(deliveryOfMessageSql(bsEnv(tenant.key === 'A' ? 'tenantA' : 'tenantB'), messageId));
  return rows.length === 1 ? rows[0][1] : null;
};

test("BS-13f: tenant A's dead-lettering target leaves tenant B's delivery DELIVERED", async ({ page }) => {
  test.setTimeout(4 * 60 * 1000);
  await setStub(A.stubPath, 403);
  stubAt403 = true;
  await page.goto(`${bsEnv('consoleUrl')}/`);
  const post = async (tenant, n) => {
    const { clientId, secretValue } = await rotatedSecret(page, tenant, readRun().seed[tenant.key].credentialId);
    const record = orderRecord(tenant, n);
    const answer = await postRecord(await sourceToken(clientId, secretValue), intakeCode(tenant), SOURCE_CONTRACT.versionLabel, record);
    expect(answer.status, JSON.stringify(answer.data)).toBe(202);
    addTracking(tenant.key, [answer.data.trackingId]);
    return { record, messageId: answer.data.trackingId };
  };
  const a = await post(A, 14);
  const b = await post(B, 14);

  await eventually(() => stateOf(A, a.messageId) === 'DEAD_LETTERED', "A's delivery dead-lettered on the 403", { timeoutMs: PIPELINE_MS });
  await eventually(() => stateOf(B, b.messageId) === 'DELIVERED', "B's delivery DELIVERED", { timeoutMs: PIPELINE_MS });

  const toB = deliveriesTo(await journal(), B.stubPath).filter((d) => d.body?.referenceNo === b.record.orderId);
  expect(toB, "exactly one body reached B's target").toHaveLength(1);
  expect(toB[0].body).toEqual(expectedTargetBody(b.record));
});
