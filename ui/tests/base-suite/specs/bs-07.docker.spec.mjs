// T130 (row 323, F21 BS-B) BS-07 - Dipesh's example, single target, two tenants (backlog.md:3038-3062,
// narrowed to the Target B v2.0 leg; multi-target fan-out is bypass B11). The app runs under the dev-only
// exception's profile with its REAL host resolver; both tenants' targets are the ONE WireMock on the one
// exception host, one path per tenant (plan section 2, Dipesh "Option 1").
//
// State on main c37db54b (T106 + T138 landed; the exception boots; intake, route and transform work): the 202 lands
// and the "exactly 1 delivery" step times out with 0 deliveries on /tenant-a/orders, because the delivery sends to
// target_endpoint.base_url, which the system create fixes at 'https://endpoint.invalid/' (JdbcSystemRepository.java:83-88)
// and nothing in the public admin API changes (settings.baseUrl is only the save-time check). Green needs the product
// to write the system's baseUrl into its default endpoint (owner arjun). Never run against production; never a psql-seeded row.

import { expect, test } from '@playwright/test';
import { messageRowsSql, deliveryRowsSql } from '../lib/direct-sql.mjs';
import { exceptionSettings, requireException } from '../lib/settings.mjs';
import { ipLiterals } from '../lib/source-scan.mjs';
import { FLOW, LOOP, SOURCE_CONTRACT, TENANTS, expectedTargetBody, forgedRecord, leakHits, orderRecord } from '../lib/tenants.mjs';
import { deliveriesTo, requestsTo } from '../lib/wiremock.mjs';
import { eventually, inBrowser, journal, postRecord, sameAppProcess, sourceToken } from '../support/api.mjs';
import { bsEnv } from '../support/boot-state.mjs';
import { readRows } from '../support/db.mjs';
import { addTracking, readRun } from '../support/run-file.mjs';
import { intakeCode, rotatedSecret } from '../support/seed.mjs';

test.describe.configure({ mode: 'serial' });

const PIPELINE_MS = 90000; // intake -> route -> transform -> deliver on one booted app

/** Signs a tenant's source system in for this scenario: rotate (secret in memory only), then a token. */
async function sourceOf(page, tenant) {
  const { credentialId } = readRun().seed[tenant.key];
  const { clientId, secretValue } = await rotatedSecret(page, tenant, credentialId);
  return sourceToken(clientId, secretValue);
}

const tenantCode = (tenant) => bsEnv(tenant.key === 'A' ? 'tenantA' : 'tenantB');
const messagesOf = (tenant) => readRows(messageRowsSql(tenantCode(tenant))).map(([id, flowId, state, day]) => ({ id, flowId, state, day }));
const deliveriesOf = (tenant) => readRows(deliveryRowsSql(tenantCode(tenant))).map(([messageId, flowId, state, day]) => ({ messageId, flowId, state, day }));
const deliveriesFor = async (tenant, record) => deliveriesTo(await journal(), tenant.stubPath).filter((d) => d.body?.referenceNo === record.orderId);

test.beforeAll(() => {
  const settings = requireException(exceptionSettings(process.env));
  expect(ipLiterals(settings.host), 'the exception host is a NAME, never the unnamed loopback').toEqual([]);
});

for (const [own, other] of [[TENANTS.A, TENANTS.B], [TENANTS.B, TENANTS.A]]) {
  test(`tenant ${own.key}'s post delivers exactly 1 translated body to ${own.stubPath} and nothing to ${other.stubPath}`, async ({ page }) => {
    await page.goto(`${bsEnv('consoleUrl')}/`);
    const token = await sourceOf(page, own);
    const record = orderRecord(own, 1);

    const answer = await postRecord(token, intakeCode(own), SOURCE_CONTRACT.versionLabel, record);
    expect(answer.status, JSON.stringify(answer.data)).toBe(202);
    const trackingId = answer.data.trackingId;
    addTracking(own.key, [trackingId]);

    // TBD(T106): the relay + consumer that route, transform and deliver an accepted message.
    const delivered = await eventually(async () => {
      const hits = await deliveriesFor(own, record);
      return hits.length > 0 ? hits : null;
    }, `a delivery of ${record.orderId} on ${own.stubPath}`, { timeoutMs: PIPELINE_MS });
    expect(delivered, 'exactly ONE delivery').toHaveLength(1);
    expect(delivered[0].body, 'the contract-translated body {clientId, referenceNo, invoiceValue}').toEqual(expectedTargetBody(record));

    const j = await journal();
    expect(leakHits(JSON.stringify(requestsTo(j, other.stubPath)), other), `nothing of tenant ${own.key} on ${other.stubPath}`).toEqual([]);

    const { flowId } = readRun().seed[own.key];
    await eventually(() => deliveriesOf(own).some((d) => d.messageId === trackingId && d.state === 'DELIVERED'), 'delivery DELIVERED', { timeoutMs: 15000 });
    const message = messagesOf(own).find((m) => m.id === trackingId);
    expect(message, 'the message row of the tracking id').toMatchObject({ flowId, state: 'ROUTED' });
    expect(deliveriesOf(own).filter((d) => d.messageId === trackingId), 'one delivery row for the message').toHaveLength(1);
    expect(sameAppProcess(), '0 deploys: the one app process still serves').toBe(true);
  });
}

test("tenant B's source naming tenant A's customerId reaches nothing of tenant A", async ({ page }) => {
  await page.goto(`${bsEnv('consoleUrl')}/`);
  const aMessagesBefore = messagesOf(TENANTS.A).length;
  const aDeliveriesBefore = deliveriesOf(TENANTS.A).length;
  const token = await sourceOf(page, TENANTS.B);
  const forged = forgedRecord(1);

  const answer = await postRecord(token, intakeCode(TENANTS.B), SOURCE_CONTRACT.versionLabel, forged);
  // TBD(T106): whether intake refuses a customerId the credential is not bound to, or accepts it into
  // tenant B only [Inferred]. Either way nothing of tenant A may move: asserted below, not the status.
  if (answer.status === 202) {
    addTracking('B', [answer.data.trackingId]);
    await eventually(() => messagesOf(TENANTS.B).some((m) => m.id === answer.data.trackingId && m.state !== 'ACCEPTED'),
      'the forged message settles in tenant B', { timeoutMs: PIPELINE_MS });
  } else {
    expect(answer.data?.trackingId, 'a refusal carries no tracking id').toBeUndefined();
  }
  const aPath = requestsTo(await journal(), TENANTS.A.stubPath);
  expect(aPath.filter((entry) => String(entry.request.body ?? '').includes(forged.orderId)), `nothing on ${TENANTS.A.stubPath}`).toEqual([]);
  expect(messagesOf(TENANTS.A), 'no new tenant-A message row').toHaveLength(aMessagesBefore);
  expect(deliveriesOf(TENANTS.A), 'no new tenant-A delivery row').toHaveLength(aDeliveriesBefore);
});

test('under the same profile a target on the unnamed loopback (127.0.0.1) is still refused - nothing sent', async ({ page }) => {
  await page.goto(`${bsEnv('consoleUrl')}/`);
  const port = Number(bsEnv('wiremockPort'));
  const created = await inBrowser(page, 'POST', '/api/v1/admin/systems', {
    code: LOOP.system, name: LOOP.name, direction: 'OUTBOUND', type: 'API', settings: { baseUrl: `http://${LOOP.host}:${port}${LOOP.path}` }
  }, TENANTS.A.admin);
  if (created.status === 200 || created.status === 201) {
    // accepted at configuration -> it must be refused at send: the probe is the configuration-time send
    const probe = await inBrowser(page, 'POST', `/api/v1/admin/systems/${created.data.id}/test-connection`, undefined, TENANTS.A.admin);
    expect(JSON.stringify(probe.data), `test-connection to ${LOOP.host} is refused`).toMatch(/REFUSED_ADDRESS|NOT_ON_ALLOW_LIST/);
    // TBD(T106): the delivery leg - a published single-target flow to target-loop dead-letters with
    // REFUSED_ADDRESS (PERMANENT, ApiTargetCallTest.java:66); added at the lock slot once T106 lands.
  } else {
    expect(created.status, `refused at configuration: ${JSON.stringify(created.data)}`).toBeGreaterThanOrEqual(400);
  }
  expect(requestsTo(await journal(), LOOP.path), `nothing reached ${LOOP.path}`).toEqual([]);
  expect(sameAppProcess(), '0 deploys').toBe(true);
});
