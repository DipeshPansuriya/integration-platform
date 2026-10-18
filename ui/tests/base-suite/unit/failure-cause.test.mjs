// T131 (row 323, F21 BS-C, BS-10) - the failure-cause classifier, FAILING FIRST. No docker: pure functions
// of tests/base-suite/lib/failure-cause.mjs over the texts main writes.
//
// BS-10 must see the TARGET's 403. On screen a destination refusal looks the same: both are PERMANENT
// (ApiTargetCallTest.java:66 refused / :155 403), both dead-letter at DELIVER, and the failures read names
// origin TARGET whenever a non-success attempt has a detail (lane t112 MessageTrackingService.java:101-104).
// Only the last-error text tells them apart (ApiTargetCall.java on main):
//   :94-95  "the target answered HTTP <status> for delivery <id>: class <CLASS>"
//   :66-67  "the destination of <uri> is refused (<Refusal>): nothing is sent to it"
//   :50-52, :59-61, :82-84  platform-side refusals (no target_endpoint row / no transform result / TLS)
// The layer-1 attempt row prefixes "layer-1 try <n>: " (LayerOneRouteBuilder.java:96).

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { REFUSALS, causeOf, deliverFailureProblems } from '../lib/failure-cause.mjs';

const DELIVERY = '7d6c5b4a-0000-4000-8000-0000000000d1';
const TRACKING = '7d6c5b4a-0000-4000-8000-0000000000a1';
const target403 = `layer-1 try 1: the target answered HTTP 403 for delivery ${DELIVERY}: class PERMANENT`;
const refused = (name) => `layer-1 try 1: the destination of http://showcase.example.test:8080/tenant-a/orders is refused (${name}): nothing is sent to it`;
const row = (lastError, extra = {}) => ({
  trackingId: TRACKING, deliveryId: DELIVERY, flowId: 'f1', targetId: 't1', failingStage: 'DELIVER',
  errorClass: 'PERMANENT', lastError, origin: 'TARGET', failedAt: '2026-10-20T10:00:00Z', ...extra
});

test('the refusal names are the guard\'s closed set (Refusal.java on main)', () => {
  assert.deepEqual(REFUSALS, ['SCHEME_NOT_ALLOWED', 'CREDENTIALS_IN_ADDRESS', 'NO_HOST', 'NOT_ON_ALLOW_LIST', 'REFUSED_ADDRESS']);
});

test("the target's answer is read with its status", () => {
  assert.deepEqual(causeOf(target403), { kind: 'TARGET_HTTP', status: 403 });
  assert.deepEqual(causeOf('the target answered HTTP 500 for delivery x: class AMBIGUOUS'), { kind: 'TARGET_HTTP', status: 500 });
});

test('every destination refusal is named - REFUSED_ADDRESS and NOT_ON_ALLOW_LIST first among them', () => {
  assert.equal(REFUSALS.length, 5);
  for (const name of REFUSALS) assert.deepEqual(causeOf(refused(name)), { kind: 'DESTINATION_REFUSAL', refusal: name });
});

test('platform-side refusals are not the target either', () => {
  assert.equal(causeOf('the send\'s endpoint e1 has no target_endpoint row for the bound tenant - the route resolution and the endpoint are out of step; nothing sent').kind, 'PLATFORM_REFUSAL');
  assert.equal(causeOf('no transform result exists for delivery d - the pipeline sent stage 2 without one; the transform lane produces it').kind, 'PLATFORM_REFUSAL');
  assert.equal(causeOf('the TLS handshake with h failed - the destination\'s identity cannot be verified').kind, 'PLATFORM_REFUSAL');
});

test('a refusal wins over anything else in the text - fail closed', () => {
  assert.equal(causeOf(`${refused('REFUSED_ADDRESS')} (the target answered HTTP 403 earlier)`).kind, 'DESTINATION_REFUSAL');
});

test('no text, or a text naming neither, is not a cause', () => {
  assert.deepEqual(causeOf(null), { kind: 'NONE' });
  assert.deepEqual(causeOf(''), { kind: 'NONE' });
  assert.deepEqual(causeOf('something else went wrong'), { kind: 'UNKNOWN' });
});

test("BS-10's failure row: DELIVER, PERMANENT, TARGET, the target's 403 - no problem", () => {
  assert.deepEqual(deliverFailureProblems(row(target403), { trackingId: TRACKING, deliveryId: DELIVERY }), []);
});

test('a destination refusal on the same-looking row FAILS BS-10 and says the exception is not working', () => {
  for (const name of ['REFUSED_ADDRESS', 'NOT_ON_ALLOW_LIST']) {
    const problems = deliverFailureProblems(row(refused(name)), { trackingId: TRACKING, deliveryId: DELIVERY });
    assert.equal(problems.length, 1, problems.join('; '));
    assert.match(problems[0], new RegExp(`destination refusal ${name}.*dev-only exception is not working`));
  }
});

test('another status, a platform refusal or no last error each fail', () => {
  const ids = { trackingId: TRACKING, deliveryId: DELIVERY };
  assert.match(deliverFailureProblems(row(target403.replace('403', '404')), ids).join(';'), /HTTP 404, not the stub's 403/);
  assert.match(deliverFailureProblems(row('no transform result exists for delivery d'), ids).join(';'), /platform-side refusal/);
  assert.match(deliverFailureProblems(row(null), ids).join(';'), /no last error/);
});

test('the wrong stage, class, origin or ids each fail; a missing row fails', () => {
  const ids = { trackingId: TRACKING, deliveryId: DELIVERY };
  assert.match(deliverFailureProblems(row(target403, { failingStage: 'ROUTE' }), ids).join(';'), /failing stage ROUTE, not DELIVER/);
  assert.match(deliverFailureProblems(row(target403, { errorClass: 'AMBIGUOUS' }), ids).join(';'), /error class AMBIGUOUS, not PERMANENT/);
  assert.match(deliverFailureProblems(row(target403, { origin: 'PLATFORM' }), ids).join(';'), /origin PLATFORM, not TARGET/);
  assert.match(deliverFailureProblems(row(target403, { deliveryId: 'other' }), ids).join(';'), /delivery id other/);
  assert.match(deliverFailureProblems(undefined, ids).join(';'), /not listed/);
});
