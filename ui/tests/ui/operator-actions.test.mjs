// T112 (E13 base N3, row 278 lane B - rohit-developer): the retry/replay buttons' pure view model over the
// T111 endpoints. POST /api/v1/admin/messages/deliveries/{deliveryId}/replay moves a DEAD_LETTERED delivery
// to DUE; .../retry moves a RETRY_WAIT one to DUE now - both re-send with the pinned versions. 404 = not
// visible to the bound tenant, 409 = wrong state or not pinned, 403 = role not granted. No DOM, no fetch.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/operator-actions.test.mjs

import test from 'node:test';
import assert from 'node:assert/strict';

const { failureAction, deliveryAction, actionPath, actionAnswerText } = await import('../../src/pages/operator-actions.ts');

const D1 = 'aa11bb22-cc33-4d44-8e55-ff6677889900';
const M1 = '3f2b8a52-6c1d-4e0f-9a7b-1c2d3e4f5a6b';

const deadLettered = {
  trackingId: M1, deliveryId: D1, flowId: 'f', targetId: 't-1', failingStage: 'DELIVER',
  errorClass: 'PERMANENT', lastError: 'HTTP 422', origin: 'TARGET', failedAt: '2026-10-17T12:05:00Z'
};
const routeFailed = { ...deadLettered, deliveryId: null, failingStage: 'ROUTE', errorClass: 'ROUTE_FAILED' };

test('a dead-lettered delivery on the failures screen offers Replay; a route failure offers nothing', () => {
  assert.deepEqual(failureAction(deadLettered), { kind: 'replay', deliveryId: D1, label: 'Replay' });
  assert.equal(failureAction(routeFailed), null, 'no delivery to re-send');
  assert.equal(failureAction({ ...deadLettered, deliveryId: null }), null);
});

test('on the detail screen a delivery waiting to retry (transient/ambiguous) offers Retry now; others nothing', () => {
  const d = (outcome) => ({ deliveryId: D1, targetId: 't-1', durationMs: 10, outcome });
  assert.deepEqual(deliveryAction(d('TRANSIENT')), { kind: 'retry', deliveryId: D1, label: 'Retry now' });
  assert.deepEqual(deliveryAction(d('AMBIGUOUS')), { kind: 'retry', deliveryId: D1, label: 'Retry now' });
  assert.equal(deliveryAction(d('SUCCESS')), null);
  assert.equal(deliveryAction(d('PERMANENT')), null, 'permanent is dead-lettered: replayed from the failures screen');
  assert.equal(deliveryAction(d(null)), null, 'no attempt yet');
});

test('each action posts to its T111 path', () => {
  assert.equal(actionPath('replay'), '/api/v1/admin/messages/deliveries/{deliveryId}/replay');
  assert.equal(actionPath('retry'), '/api/v1/admin/messages/deliveries/{deliveryId}/retry');
});

test('the answer is worded for the operator: moved, gone, wrong state, not pinned, not allowed', () => {
  assert.equal(actionAnswerText('replay', { ok: true, data: { outcome: 'MOVED_TO_DUE', state: 'DUE' } }),
    'Replay queued: the delivery is due and will be sent again with its pinned versions.');
  assert.equal(actionAnswerText('retry', { ok: true, data: { outcome: 'MOVED_TO_DUE', state: 'DUE' } }),
    'Retry queued: the delivery is due now and will be sent again with its pinned versions.');
  assert.equal(actionAnswerText('replay', { ok: false, status: 404, error: 'not found' }),
    'This delivery was not found.');
  assert.equal(actionAnswerText('replay', { ok: false, status: 409, error: 'wrong state', state: 'DELIVERED' }),
    'Not replayed: the delivery is DELIVERED, not dead-lettered. Reload to see its current state.');
  assert.equal(actionAnswerText('retry', { ok: false, status: 409, error: 'wrong state', state: 'DEAD_LETTERED' }),
    'Not retried: the delivery is DEAD_LETTERED, not waiting to retry. Reload to see its current state.');
  assert.equal(actionAnswerText('replay', { ok: false, status: 409, error: 'not pinned', state: 'DEAD_LETTERED' }),
    'Not replayed: the delivery is not pinned to a mapping and contract version.');
  assert.equal(actionAnswerText('retry', { ok: false, status: 403, refusal: 'ROLE_NOT_GRANTED' }),
    'You are not allowed to retry or replay deliveries.');
  assert.equal(actionAnswerText('retry', { ok: false, status: 500 }), 'Not retried: the server answered 500.');
});
