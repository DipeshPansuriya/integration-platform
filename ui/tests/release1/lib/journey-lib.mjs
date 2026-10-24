// L28 (row 372): the Release 1 browser journey's pure helpers - no browser, no docker. Unit-tested in
// tests/release1/unit/journey-lib.test.mjs. The WireMock journal reading reuses the base suite's own
// oracle (tests/base-suite/lib/wiremock.mjs deliveriesTo) - never duplicated.
import { deliveriesTo } from '../../base-suite/lib/wiremock.mjs';

export const TARGET_A_PREFIX = '/target-a/';
export const FAIL_STUB_ID = '6a1e0a01-3130-4b5e-9a01-0000000028f1';

const pathOf = (url) => (String(url).startsWith('/') ? String(url).split('?')[0] : new URL(String(url)).pathname);

/** Deliveries (POST with an Idempotency-Key) on any path under `prefix`, journal order. */
export function deliveriesUnder(journal, prefix = TARGET_A_PREFIX) {
  const paths = [...new Set((journal?.requests ?? []).map((e) => pathOf(e?.request?.url ?? '')).filter((p) => p.startsWith(prefix)))];
  return paths.flatMap((p) => deliveriesTo(journal, p)).sort((a, b) => String(a.loggedDate).localeCompare(String(b.loggedDate)));
}

/** The deliveries whose body carries `orderNo` - one scenario's own sends, whatever else is in the journal. */
export function deliveriesOfOrder(journal, orderNo, prefix = TARGET_A_PREFIX) {
  return deliveriesUnder(journal, prefix).filter((d) => d.body?.orderNo === orderNo);
}

/** A WireMock stub that makes target A answer `status` for every call, above the runner's priority-10 default. */
export function targetAStub(status) {
  return { id: FAIL_STUB_ID, priority: 1, request: { method: 'POST', urlPathPattern: `${TARGET_A_PREFIX}.*` }, response: { status } };
}

/** The UTC day (yyyy-mm-dd) the failures screen's date filter takes. */
export function utcDay(now = new Date()) {
  return now.toISOString().slice(0, 10);
}

/**
 * Creates or replaces a WireMock stub by its fixed id. WireMock answers 404 to a PUT of an id it has never seen
 * (first live run, 24-Oct), so: PUT, and on 404 POST it. Returns the final status; throws on anything but 200/201.
 */
export async function upsertStub(adminUrl, stub, fetchImpl = fetch) {
  const send = (method, url) => fetchImpl(url, { method, headers: { 'content-type': 'application/json' }, body: JSON.stringify(stub) });
  let answer = await send('PUT', `${adminUrl}/mappings/${stub.id}`);
  if (answer.status === 404) answer = await send('POST', `${adminUrl}/mappings`);
  if (answer.status !== 200 && answer.status !== 201) throw new Error(`WireMock stub ${stub.id} answered ${answer.status}`);
  return answer.status;
}
