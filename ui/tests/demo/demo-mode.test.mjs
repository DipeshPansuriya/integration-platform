import assert from 'node:assert/strict';
import { test } from 'node:test';
import { demoLaunchOptions, demoPauseMs } from './demo-mode.mjs';
import { deliveryLines, intakeLines } from './narration-text.mjs';

test('the beat pause is 0 outside demo mode, 4000 ms by default in it, and bounded', () => {
  assert.equal(demoPauseMs({}), 0);
  assert.equal(demoPauseMs({ E2E_DEMO: '1' }), 4000);
  assert.equal(demoPauseMs({ E2E_DEMO: '1', E2E_DEMO_PAUSE_MS: '2500' }), 2500);
  for (const bad of ['x', '-5', '30001']) assert.throws(() => demoPauseMs({ E2E_DEMO: '1', E2E_DEMO_PAUSE_MS: bad }), /E2E_DEMO_PAUSE_MS/, bad);
});

test('narration: beat b shows the 202 and the tracking id, and refuses anything else', () => {
  const lines = intakeLines({ status: 202, trackingId: 'abc-123', orderId: 'A-ORD-8001' });
  assert.match(lines.join('\n'), /202 Accepted/);
  assert.match(lines.join('\n'), /tracking id abc-123/);
  assert.throws(() => intakeLines({ status: 404, trackingId: 'x', orderId: 'o' }), /not 202/);
  assert.throws(() => intakeLines({ status: 202, trackingId: '', orderId: 'o' }), /no tracking id/);
});

test('narration: beat c shows the transformed body, the key and whether the trace id survived', () => {
  const sent = '00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01';
  const same = deliveryLines({ path: '/tenant-a/orders', sentTraceparent: sent,
    entry: { body: { referenceNo: 'A-ORD-8001' }, idempotencyKey: 'k-1', traceparent: '00-0af7651916cd43dd8448eb211c80319c-1111111111111111-01' } }).join('\n');
  assert.match(same, /POST \/tenant-a\/orders/);
  assert.match(same, /"referenceNo":"A-ORD-8001"/);
  assert.match(same, /same trace id: YES/);
  const lost = deliveryLines({ path: '/p', sentTraceparent: sent, entry: { body: {}, idempotencyKey: 'k', traceparent: null } }).join('\n');
  assert.match(lost, /\(none\).*same trace id: NO/);
  assert.throws(() => deliveryLines({ path: '/p', sentTraceparent: sent, entry: null }), /no delivery/);
});

test('off by default: no launch option changes, so every suite stays headless and fast', () => {
  assert.deepEqual(demoLaunchOptions({}), {});
  assert.deepEqual(demoLaunchOptions({ E2E_DEMO: '0' }), {});
});

test('E2E_DEMO=1: headed with the default 700 ms pause before each action', () => {
  const options = demoLaunchOptions({ E2E_DEMO: '1' });
  assert.equal(options.headless, false);
  assert.equal(options.slowMo, 700);
});

test('E2E_DEMO_SLOWMO_MS sets the pause; a bad value fails before any boot', () => {
  assert.equal(demoLaunchOptions({ E2E_DEMO: '1', E2E_DEMO_SLOWMO_MS: '1200' }).slowMo, 1200);
  assert.equal(demoLaunchOptions({ E2E_DEMO: '1', E2E_DEMO_SLOWMO_MS: '0' }).slowMo, 0);
  for (const bad of ['abc', '-1', '5001', '1.5']) {
    assert.throws(() => demoLaunchOptions({ E2E_DEMO: '1', E2E_DEMO_SLOWMO_MS: bad }), /E2E_DEMO_SLOWMO_MS/, bad);
  }
});
