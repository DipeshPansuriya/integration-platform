// T215: LIVE end-to-end proof of the NESTED-APPOINTMENT showcase flow (T214) on the real backend. Run it ONLY through
// scripts/run-nested-appointment-live.mjs: that runner boots Keycloak, Postgres, Kafka and WireMock, seeds the flow through the
// admin API and sets every E2E_* name below; a bare `playwright test` fails fast on the first missing name.
//
// Oracle: the EXPECTED records below are written out by hand here - never computed from the seed's expression.jsonata - so a wrong
// mapping cannot agree with itself. What arrives is read from the WireMock request journal (the target stub), as the customer's
// real target would see it: the raw POST body text.
// Negative control: E2E_NEGATIVE_CONTROL=1 breaks ONE expected value (containerNo "26" -> "27"); the sample test must then go RED.

import fs from 'node:fs';
import path from 'node:path';
import { test, expect } from '@playwright/test';
import { browserLogin } from '../screens/keycloak-login.mjs';
import { consoleUrl } from '../screens/boot-state.mjs';
import { deliveriesUnder } from './lib/journey-lib.mjs';

const TARGET_PREFIX = '/nested-target/';
const RUN = Date.now();
const NEGATIVE = process.env.E2E_NEGATIVE_CONTROL === '1';

function setting(name) {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is not set - run this proof through scripts/run-nested-appointment-live.mjs`);
  return value;
}

// the sample exactly as given in the T215 brief (an independent copy; a test below proves it equals the seed's sample.json)
const SAMPLE = {
  TerminalOpsId: 3, TerminalId: 2, AppointmentId: 90726015, AppointmentDate: '2026-07-09T11:11:05.834Z', AppointmentStatus: 'F', NoofVehicle: 1, BerthNo: '68',
  VehicleList: [{
    SlotDate: '2026-07-09T11:11:05.834Z', SlotStartTime: '2026-07-09T14:00:00.834Z', SlotEndTime: '2026-07-09T15:00:00.834Z', DriverPortId: '907261',
    ContainerList: [{ BookingType: 'FO', TransactionID: 13, ContainerNo: '26', Size: '40', Type: 'GB', ISOCode: '1232', NoOfPackages: 0, GrossWeight: 0, ActualWeight: 0 }]
  }]
};

// the 20 target fields in order, with their exact values and JSON types
const EXPECTED_SAMPLE_PAIRS = [
  ['terminalOpsId', 3], ['terminalId', 2], ['appointmentId', 90726015], ['appointmentDate', '2026-07-09T11:11:05.834Z'], ['appointmentStatus', 'F'],
  ['noOfVehicles', 1], ['berthNo', '68'], ['slotDate', '2026-07-09T11:11:05.834Z'], ['slotStartTime', '2026-07-09T14:00:00.834Z'], ['slotEndTime', '2026-07-09T15:00:00.834Z'],
  ['driverPortId', '907261'], ['bookingType', 'FO'], ['transactionId', 13], ['containerNo', NEGATIVE ? '27' : '26'], ['size', '40'], ['type', 'GB'], ['isoCode', '1232'],
  ['noOfPackages', 0], ['grossWeight', 0], ['actualWeight', 0]
];

const container = (no, txn) => ({ BookingType: 'FO', TransactionID: txn, ContainerNo: no, Size: '20', Type: 'GP', ISOCode: '22G1', NoOfPackages: 0, GrossWeight: 0, ActualWeight: 0 });
const vehicle = (driver, containers) => ({
  SlotDate: '2026-07-09T11:11:05.834Z', SlotStartTime: '2026-07-09T14:00:00.834Z', SlotEndTime: '2026-07-09T15:00:00.834Z', DriverPortId: driver, ContainerList: containers
});
const appointment = (id, vehicles) => ({
  TerminalOpsId: 3, TerminalId: 2, AppointmentId: id, AppointmentDate: '2026-07-09T11:11:05.834Z', AppointmentStatus: 'F', NoofVehicle: vehicles.length, BerthNo: '68', VehicleList: vehicles
});
const expectedRecord = (id, noOfVehicles, driver, no, txn) => Object.fromEntries([
  ['terminalOpsId', 3], ['terminalId', 2], ['appointmentId', id], ['appointmentDate', '2026-07-09T11:11:05.834Z'], ['appointmentStatus', 'F'], ['noOfVehicles', noOfVehicles],
  ['berthNo', '68'], ['slotDate', '2026-07-09T11:11:05.834Z'], ['slotStartTime', '2026-07-09T14:00:00.834Z'], ['slotEndTime', '2026-07-09T15:00:00.834Z'], ['driverPortId', driver],
  ['bookingType', 'FO'], ['transactionId', txn], ['containerNo', no], ['size', '20'], ['type', 'GP'], ['isoCode', '22G1'], ['noOfPackages', 0], ['grossWeight', 0], ['actualWeight', 0]
]);

const wiremockAdmin = () => setting('E2E_WIREMOCK_ADMIN_URL');

async function journal() {
  const answer = await fetch(`${wiremockAdmin()}/requests`, { signal: AbortSignal.timeout(15_000) });
  if (!answer.ok) throw new Error(`WireMock journal answered ${answer.status}`);
  return answer.json();
}

/** Each case starts from an empty journal, so "what arrived" is only this case's own sends. */
async function clearJournal() {
  const answer = await fetch(`${wiremockAdmin()}/requests`, { method: 'DELETE', signal: AbortSignal.timeout(15_000) });
  if (answer.status !== 200) throw new Error(`WireMock journal reset answered ${answer.status}`);
}

async function post(request, payload, key, { token = setting('E2E_BEARER_SOURCE') } = {}) {
  return request.post(`${setting('E2E_BACKEND_URL')}/api/v1/interfaces/${setting('E2E_INTERFACE_CODE')}/messages`, {
    headers: { 'X-Source-Contract-Version': '1.0', 'Idempotency-Key': `t215-${RUN}-${key}`, ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    data: payload
  });
}

async function accepted(request, payload, key) {
  const answer = await post(request, payload, key);
  expect(answer.status(), await answer.text()).toBe(202);
  return (await answer.json()).trackingId;
}

/** Waits for `count` POSTs at the target, then a short settle so a duplicate would show; returns them (parsed body + raw text). */
async function awaitDeliveries(count) {
  await expect.poll(async () => deliveriesUnder(await journal(), TARGET_PREFIX).length, { timeout: 90_000, intervals: [1000] }).toBe(count);
  await new Promise((resolve) => setTimeout(resolve, 4000));
  const sent = deliveriesUnder(await journal(), TARGET_PREFIX);
  expect(sent, 'no duplicate delivery after the settle').toHaveLength(count);
  return sent;
}

function note(name, value) {
  const line = `OBSERVED ${name}: ${typeof value === 'string' ? value : JSON.stringify(value)}`;
  console.log(line);
  test.info().annotations.push({ type: 'observed', description: line });
}

test.describe.configure({ mode: 'serial' });

test.describe('NESTED-APPOINTMENT live (T215)', () => {
  test('the brief sample equals the seed sample.json (no drift between the oracle and the seed)', async () => {
    const seedSample = JSON.parse(fs.readFileSync(path.join(setting('E2E_SEED_DIR'), 'sample.json'), 'utf8'));
    expect(seedSample).toEqual(SAMPLE);
  });

  test('the sample gives ONE record with all 20 fields and exact values, and the tracking page shows it delivered', async ({ page }) => {
    await clearJournal();
    const trackingId = await accepted(page.request, SAMPLE, 'sample');
    const [sent] = await awaitDeliveries(1);

    expect(Array.isArray(sent.body), 'the body is a JSON ARRAY').toBe(true);
    expect(sent.body, 'one container -> one record').toHaveLength(1);
    const [record] = sent.body;
    expect(Object.keys(record), '20 fields, in the documented order').toEqual(EXPECTED_SAMPLE_PAIRS.map(([key]) => key));
    expect(Object.keys(record)).toHaveLength(20);
    // exact values AND JSON types (toEqual is strict on number vs string): dates unchanged, TransactionID 13 a number, ContainerNo "26" a string, zeros kept
    expect(record).toEqual(Object.fromEntries(EXPECTED_SAMPLE_PAIRS));
    expect(typeof record.transactionId).toBe('number');
    expect(typeof record.containerNo).toBe('string');
    expect([record.noOfPackages, record.grossWeight, record.actualWeight]).toEqual([0, 0, 0]);
    // the raw text as it travelled: no re-formatting of any date or number
    expect(sent.text).toBe(JSON.stringify([Object.fromEntries(EXPECTED_SAMPLE_PAIRS)]));

    // screen proof: the console's tracking page (message detail) shows the message went through every stage to DELIVER
    const base = consoleUrl();
    await browserLogin(page, base, { username: setting('E2E_ADMIN_USER'), password: setting('E2E_ADMIN_PASSWORD') });
    await page.goto(`${base}/#/messages/${trackingId}`);
    await expect(page.locator('[data-page="message-detail"][data-message-loaded="true"]')).toBeVisible({ timeout: 60_000 });
    await expect(page.locator('[data-tracking-id]')).toHaveText(trackingId);
    for (const stage of ['INTAKE', 'ROUTE', 'TRANSFORM', 'DELIVER']) {
      await expect(page.locator(`[data-stage-row="${stage}"]`)).toHaveAttribute('data-stage-status', 'done', { timeout: 60_000 });
    }
    await expect(page.locator('[data-failing-stage]')).toHaveCount(0);
    const shot = path.join(setting('E2E_PROOF_DIR'), 'nested-appointment-tracking-delivered.png');
    await page.screenshot({ path: shot, fullPage: true });
    note('screenshot', shot);
  });

  test('2 vehicles x 2 containers give 4 records, each with its own vehicle and container', async ({ request }) => {
    await clearJournal();
    const payload = appointment(90726016, [
      vehicle('P1', [container('C1', 1), container('C2', 2)]),
      vehicle('P2', [container('C3', 3), container('C4', 4)])
    ]);
    await accepted(request, payload, 'two-by-two');
    const [sent] = await awaitDeliveries(1);
    expect(Array.isArray(sent.body)).toBe(true);
    expect(sent.body).toEqual([
      expectedRecord(90726016, 2, 'P1', 'C1', 1), expectedRecord(90726016, 2, 'P1', 'C2', 2),
      expectedRecord(90726016, 2, 'P2', 'C3', 3), expectedRecord(90726016, 2, 'P2', 'C4', 4)
    ]);
    expect(sent.body.every((record) => Object.keys(record).length === 20)).toBe(true);
  });

  test('an empty ContainerList is delivered as one POST of [] (documented behaviour) and the tracking page shows it delivered', async ({ request, page }) => {
    await clearJournal();
    const trackingId = await accepted(request, appointment(90726017, [vehicle('P1', [])]), 'empty-containers');
    const [sent] = await awaitDeliveries(1);
    expect(sent.text, 'the target receives an empty JSON array').toBe('[]');
    note('empty-containers', 'one POST, body [] (no PERMANENT refusal)');
    const base = consoleUrl();
    await browserLogin(page, base, { username: setting('E2E_ADMIN_USER'), password: setting('E2E_ADMIN_PASSWORD') });
    await page.goto(`${base}/#/messages/${trackingId}`);
    await expect(page.locator('[data-page="message-detail"][data-message-loaded="true"]')).toBeVisible({ timeout: 60_000 });
    await expect(page.locator('[data-stage-row="DELIVER"]')).toHaveAttribute('data-stage-status', 'done', { timeout: 60_000 });
  });

  test('a second seed run changes nothing (idempotent)', async () => {
    const reseed = JSON.parse(setting('E2E_RESEED_JSON'));
    expect(reseed.skipped, 'the second run skips: the flow is already PUBLISHED').toBe(true);
    expect(reseed.credential, 'no second credential is issued').toBeNull();
    expect(reseed.after, 'systems, contracts and flows are exactly as before').toEqual(reseed.before);
    expect(reseed.before.flowsWithCode, 'exactly one NESTED-APPOINTMENT flow').toBe(1);
  });

  test('a call without a source token is refused and nothing reaches the target', async ({ request }) => {
    await clearJournal();
    const answer = await post(request, SAMPLE, 'no-token', { token: null });
    expect(answer.status(), await answer.text()).toBe(401);
    await new Promise((resolve) => setTimeout(resolve, 3000));
    expect(deliveriesUnder(await journal(), TARGET_PREFIX)).toHaveLength(0);
  });
});
