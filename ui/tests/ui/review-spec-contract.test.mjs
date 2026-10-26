// Row 372 review fixes H1 / M1: real-backend specs must match what the screens now do.
//   H1: Failures arms Replay first ("Confirm replay"), so the READ_ONLY 403 spec has to press twice.
//   M1: MessageDetail words the state ("Delivered"); the raw enum travels in data-state-raw, never in the text.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/review-spec-contract.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { findAll, mount } from './hook-harness.mjs';

const tests = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', 'e2e-ui', 'tests'); // L29: the specs it reads moved to e2e-ui/tests
const spec = (p) => readFileSync(join(tests, p), 'utf8');

test('H1 the READ_ONLY 403 replay spec arms Replay before it presses (Failures asks to confirm first)', () => {
  const text = spec('screens/operator-actions.docker.spec.mjs');
  const start = text.indexOf('a READ_ONLY user sees its own failure');
  const end = text.indexOf('tenant B', start);
  const body = text.slice(start, end);
  assert.match(body, /armAndPress\(replayButton\(page, msg\)\)/);
  assert.doesNotMatch(body, /replayButton\(page, msg\)\.click\(\)/, 'a single click only arms; no POST would be sent');
});

test('M1 MessageDetail: the state dd shows the word and carries the raw enum in data-state-raw', async () => {
  const screen = await mount('pages/MessageDetail.tsx', 'MessageDetail', { trackingId: '3f2b8a52-6c1d-4e0f-9a7b-1c2d3e4f5a6b', failedHint: null }, {
    messageTimeline: async () => ({
      ok: true,
      data: { trackingId: '3f2b8a52-6c1d-4e0f-9a7b-1c2d3e4f5a6b', flowId: '0b6c2d1e-7f8a-4b9c-8d0e-1f2a3b4c5d6e', state: 'DELIVERED', acceptedAt: null, stages: [], totalMs: null, failingStage: null, deliveries: [] }
    })
  }, ['../api/messages', '../api/flows']);
  await screen.settle();
  const [dd] = findAll(screen.tree(), (n) => n.type === 'dd' && n.props['data-message-state'] !== undefined);
  assert.ok(dd, 'the state dd renders');
  assert.equal(dd.props['data-state-raw'], 'DELIVERED');
  const badge = findAll(dd, (n) => n.type === 'span')[0];
  assert.equal(badge.props.children, 'Delivered', 'the word is the visible text');
});

test('M1 the e2e-full journey reads the raw enum from the attribute, not the worded text', () => {
  const text = spec('e2e-full/e2e-full-journeys.docker.spec.mjs');
  assert.match(text, /\[data-message-state\]'\)\)\.toHaveAttribute\('data-state-raw', 'ROUTED'\)/); // main: the message state is the routing outcome
  assert.doesNotMatch(text, /\[data-message-state\]'\)\)\.toHaveText\('(DELIVERED|ROUTED)'\)/);
});
