// T130 (row 323, F21 BS-B) - the two-tenant seed table, FAILING FIRST. No docker: pure data and
// functions of tests/base-suite/lib/tenants.mjs.
//
// Plan section 2 (release-suite-base-20261022.md): two tenants with the SAME flow shape - source
// "Customer A" contract v1.0 {customerId, orderId, amount}, target "Target B" contract v2.0
// {clientId, referenceNo, invoiceValue}, flow "orders" with one route (the T94 trial seed shape,
// e2e-ui/scripts/trial-up.mjs:17-21) - and a tenant marker in every value, so a leak search is a plain
// text search. Logins: tenant A rides the harness's existing TENANT_ADMIN + APPROVER
// (boot-backend.mjs:357-365); tenant B gets its OWN TENANT_ADMIN + APPROVER, never a repurposed
// harness login (the other-tenant CONFIGURATOR must stay refused the dashboard, queue-dashboard
// spec :133-140).

import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  FLOW,
  LINKS,
  LOOP,
  SOURCE_CONTRACT,
  TARGET_CONTRACT,
  TENANTS,
  TENANT_B_PLAN,
  expectedTargetBody,
  forgedRecord,
  leakHits,
  orderRecord
} from '../lib/tenants.mjs';

const HARNESS_LOGINS = ['default', 'approver', 'other-tenant', 'empty-tenant'];

test('two tenants: one flow shape, their own customer marker and their own stub path', () => {
  assert.deepEqual(Object.keys(TENANTS), ['A', 'B']);
  assert.equal(TENANTS.A.customerId, 'A-CUST-0001');
  assert.equal(TENANTS.B.customerId, 'B-CUST-0001');
  assert.equal(TENANTS.A.stubPath, '/tenant-a/orders');
  assert.equal(TENANTS.B.stubPath, '/tenant-b/orders');
  assert.deepEqual({ ...FLOW }, { code: 'orders', name: 'orders', businessKeyRule: 'orderId' });
});

test('tenant A rides the existing harness logins; tenant B has its own admin and approver', () => {
  assert.equal(TENANTS.A.admin, 'default');
  assert.equal(TENANTS.A.approver, 'approver');
  assert.equal(TENANTS.B.admin, 'bs-b-admin');
  assert.equal(TENANTS.B.approver, 'bs-b-approver');
  assert.deepEqual({ ...TENANT_B_PLAN.logins }, { 'bs-b-admin': 'TENANT_ADMIN', 'bs-b-approver': 'APPROVER' });
  for (const login of Object.keys(TENANT_B_PLAN.logins)) assert.ok(!HARNESS_LOGINS.includes(login), login);
  assert.equal(TENANT_B_PLAN.key, 'bs-b');
  assert.ok(!['b', 'c'].includes(TENANT_B_PLAN.key), 'never the harness tenants -b (other) or -c (empty)');
});

test('the contracts are Customer A v1.0 and Target B v2.0 and the drawing links each field once', () => {
  assert.equal(SOURCE_CONTRACT.system, 'customer-a');
  assert.equal(SOURCE_CONTRACT.role, 'SOURCE_REQUEST');
  assert.equal(SOURCE_CONTRACT.versionLabel, '1.0');
  assert.deepEqual(SOURCE_CONTRACT.document.required, ['customerId', 'orderId', 'amount', 'cardNumber']);
  assert.equal(TARGET_CONTRACT.system, 'target-b');
  assert.equal(TARGET_CONTRACT.role, 'TARGET_REQUEST');
  assert.equal(TARGET_CONTRACT.versionLabel, '2.0');
  assert.deepEqual(TARGET_CONTRACT.document.required, ['clientId', 'referenceNo', 'invoiceValue', 'cardRef']);
  assert.deepEqual(LINKS.map((link) => [...link]), [['customerId', 'clientId'], ['orderId', 'referenceNo'], ['amount', 'invoiceValue'], ['cardNumber', 'cardRef']]);
});

test('orderRecord carries the tenant marker in every text value', () => {
  const record = orderRecord(TENANTS.B, 7);
  assert.equal(record.customerId, 'B-CUST-0001');
  assert.equal(record.orderId, 'B-ORD-0007');
  assert.equal(typeof record.amount, 'number');
  assert.equal(record.cardNumber, 'B-CARD-0007', 'T206: the field BS-11 masks carries a marked, non-identity value');
  assert.notEqual(record.cardNumber, record.customerId);
  assert.deepEqual(leakHits(JSON.stringify(record), TENANTS.B), []);
  assert.notDeepEqual(orderRecord(TENANTS.A, 1), orderRecord(TENANTS.A, 2), 'each record is its own');
  assert.throws(() => orderRecord(TENANTS.A, 0), /1\.\.9999/);
});

test('expectedTargetBody is exactly the contract translation of the record', () => {
  const record = orderRecord(TENANTS.A, 12);
  assert.deepEqual(expectedTargetBody(record), { clientId: 'A-CUST-0001', referenceNo: 'A-ORD-0012', invoiceValue: record.amount, cardRef: 'A-CARD-0012' });
});

test("forgedRecord is tenant B's own order naming tenant A's customer", () => {
  const forged = forgedRecord(1);
  assert.equal(forged.customerId, TENANTS.A.customerId);
  assert.match(forged.orderId, /^B-ORD-F\d{3}$/);
  assert.match(forged.cardNumber, /^B-CARD-F\d{3}$/);
});

test("leakHits finds only the other tenant's markers", () => {
  assert.deepEqual(leakHits('{"clientId":"A-CUST-0001","referenceNo":"A-ORD-0003"}', TENANTS.B), ['A-CUST-0001', 'A-ORD-0003']);
  assert.deepEqual(leakHits('B-CUST-0001 B-ORD-0001', TENANTS.B), []);
  assert.deepEqual(leakHits('B-CUST-0001', TENANTS.A), ['B-CUST-0001']);
  assert.deepEqual(leakHits('{"cardRef":"A-CARD-0003"}', TENANTS.B), ['A-CARD-0003'], 'a card number is a tenant marker too');
});

test('the unnamed-loopback leg names the 127.0.0.1 literal on its own path, apart from both tenants', () => {
  assert.equal(LOOP.host, '127.0.0.1');
  assert.equal(LOOP.path, '/tenant-a/loop');
  assert.equal(LOOP.system, 'target-loop');
  assert.ok(![TENANTS.A.stubPath, TENANTS.B.stubPath].includes(LOOP.path));
});
