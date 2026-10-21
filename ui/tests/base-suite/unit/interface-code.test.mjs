// lane qa-release (2026-10-22): the intake is addressed by the flow's GENERATED interface code (the live run showed
// 404 INTERFACE_NOT_FOUND for the requested code "orders" while the interface row was PUBLISHED as flow-<uuid>).

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { interfaceCodeOf } from '../lib/interface-code.mjs';

const RUN = { seed: { A: { interfaceCode: 'flow-aaa' }, B: { interfaceCode: 'flow-bbb' } } };

test('each tenant has its own recorded interface code', () => {
  assert.equal(interfaceCodeOf(RUN, 'A'), 'flow-aaa');
  assert.equal(interfaceCodeOf(RUN, 'B'), 'flow-bbb');
});

test('a seed without the code fails loudly - never falls back to the requested "orders"', () => {
  for (const run of [undefined, {}, { seed: {} }, { seed: { A: {} } }, { seed: { A: { interfaceCode: '  ' } } }]) {
    assert.throws(() => interfaceCodeOf(run, 'A'), /interface code/);
  }
});
