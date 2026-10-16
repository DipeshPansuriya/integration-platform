// T130 (row 323, F21 BS-B) - the one local WireMock's stubs and request journal, FAILING FIRST. No
// docker: pure functions of tests/base-suite/lib/wiremock.mjs over WireMock admin-API JSON shapes.
//
// One WireMock serves both tenants, one stub path each (plan section 2): /tenant-a/orders,
// /tenant-b/orders, plus BS-07's unnamed-loopback leg /tenant-a/loop. A delivery is the API adapter's
// send: POST with an Idempotency-Key header = the delivery id (ApiTargetCall.java:73-78 on main), so a
// test-connection probe or a stray request is never counted as a delivery.

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { STUB_IDS, WIREMOCK_IMAGE, deliveriesTo, header, requestsTo, stubMapping } from '../lib/wiremock.mjs';

const entry = (method, url, headers = {}, body = '') => ({ request: { method, url, headers, body } });

test('the image is the pinned tag AND digest the Java suites use (R05, ApiAdapterWireMock.java:39)', () => {
  assert.equal(WIREMOCK_IMAGE,
    'wiremock/wiremock:3.13.1@sha256:d61e7720f89483fdef5366843b58d1dfd06bcce5828179c9f2f54de5c28354b0');
});

test('one stable stub per path, POST only, answering 200 or 403', () => {
  assert.deepEqual(Object.keys(STUB_IDS), ['/tenant-a/orders', '/tenant-b/orders', '/tenant-a/loop']);
  const forbidden = stubMapping('/tenant-a/orders', 403);
  assert.equal(forbidden.id, STUB_IDS['/tenant-a/orders']);
  assert.deepEqual(forbidden.request, { method: 'POST', urlPath: '/tenant-a/orders' });
  assert.equal(forbidden.response.status, 403);
  const restored = stubMapping('/tenant-a/orders', 200);
  assert.equal(restored.id, forbidden.id, 'the same id: restoring replaces the 403 stub in place');
  assert.equal(restored.response.status, 200);
  assert.throws(() => stubMapping('/tenant-c/orders', 200), /unknown stub path/);
  // plan section 2: 500 is AMBIGUOUS on a plain target, 503 TRANSIENT - the journey uses 403 only
  for (const status of [500, 503, 201]) assert.throws(() => stubMapping('/tenant-a/orders', status), /200 or 403/);
});

test('header reads one value case-insensitively, also from a multi-value array', () => {
  assert.equal(header({ 'Idempotency-Key': 'd1' }, 'idempotency-key'), 'd1');
  assert.equal(header({ 'idempotency-key': ['d2', 'x'] }, 'Idempotency-Key'), 'd2');
  assert.equal(header({}, 'Idempotency-Key'), null);
  assert.equal(header(undefined, 'Idempotency-Key'), null);
});

test("deliveriesTo counts only the adapter's sends on that path: POST + Idempotency-Key", () => {
  const journal = {
    requests: [
      entry('POST', '/tenant-a/orders', { 'Idempotency-Key': 'd1', 'Content-Type': 'application/json' }, '{"clientId":"A-CUST-0001"}'),
      entry('GET', '/tenant-a/orders', {}, ''),
      entry('POST', '/tenant-b/orders', { 'Idempotency-Key': 'd2' }, '{"clientId":"B-CUST-0001"}'),
      entry('POST', '/tenant-a/orders?trace=1', { 'idempotency-key': ['d3'] }, '{"clientId":"A-CUST-0001"}'),
      entry('POST', '/tenant-a/orders', {}, '{"probe":true}'),
      entry('POST', '/tenant-a/orders/extra', { 'Idempotency-Key': 'd4' }, '{}')
    ]
  };
  const toA = deliveriesTo(journal, '/tenant-a/orders');
  assert.deepEqual(toA.map((d) => d.deliveryId), ['d1', 'd3']);
  assert.deepEqual(toA[0].body, { clientId: 'A-CUST-0001' });
  assert.deepEqual(deliveriesTo(journal, '/tenant-b/orders').map((d) => d.deliveryId), ['d2']);
  assert.deepEqual(deliveriesTo({ requests: [] }, '/tenant-a/orders'), []);
});

test('a body that is not JSON is kept as text, never dropped', () => {
  const [only] = deliveriesTo({ requests: [entry('POST', '/tenant-b/orders', { 'Idempotency-Key': 'd9' }, 'not json')] }, '/tenant-b/orders');
  assert.equal(only.body, null);
  assert.equal(only.text, 'not json');
});

test('requestsTo counts EVERY request on a path - "nothing sent" means 0 of any method', () => {
  const journal = { requests: [entry('GET', '/tenant-a/loop'), entry('POST', '/tenant-a/loop?x=1'), entry('POST', '/tenant-a/orders')] };
  assert.equal(requestsTo(journal, '/tenant-a/loop').length, 2);
  assert.equal(requestsTo(journal, '/tenant-b/orders').length, 0);
});
