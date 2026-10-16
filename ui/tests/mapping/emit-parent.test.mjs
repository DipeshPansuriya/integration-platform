// Row 278 B18 (found under T57) - one target drawn both as a field and as its parent, FAILING FIRST.
//
// Links [a -> client, b -> client.id] draw `client` as a leaf (a's value) AND as the parent of
// client.id. The emitter used to emit {'client': {'id': b}} with no refusal - link a silently
// dropped. Now, in EITHER drawn order, emitJsonata refuses with "the target field ... is drawn twice
// (both as a field and as a parent)", and the designer's save path (saveRefusal, T57) refuses the
// same drawing, so no PUT is sent. A valid nested drawing still emits exactly as before.

import test from 'node:test';
import assert from 'node:assert/strict';
import { emitJsonata } from '../../src/mapping/emit/emit.ts';
import { saveRefusal } from '../../src/mapping/saveRefusal.ts';

const FIELD_AND_PARENT = /^the target field client(\.id)? is drawn twice \(both as a field and as a parent\)$/;

test('field drawn first, then a child of it: refused, nothing emitted', () => {
  const result = emitJsonata([{ source: 'a', target: 'client' }, { source: 'b', target: 'client.id' }]);
  assert.match(result.refusal ?? '', FIELD_AND_PARENT);
  assert.equal(result.expression, '');
});

test('child drawn first, then its parent as a field: refused, nothing emitted', () => {
  const result = emitJsonata([{ source: 'b', target: 'client.id' }, { source: 'a', target: 'client' }]);
  assert.match(result.refusal ?? '', FIELD_AND_PARENT);
  assert.equal(result.expression, '');
});

test('the designer refuses to save that drawing (no PUT): saveRefusal names it', () => {
  const graph = {
    routeId: 'r1',
    binding: { sourceContractVersionId: 's1', targetContractVersionId: 't1' },
    links: [
      { id: 'l1', source: 'a', target: 'client' },
      { id: 'l2', source: 'b', target: 'client.id' }
    ],
    expression: '$',
    sourceFields: [],
    targetFields: []
  };
  assert.match(saveRefusal(graph) ?? '', FIELD_AND_PARENT);
});

test('a valid nested drawing still emits exactly as before', () => {
  const result = emitJsonata([{ source: 'b', target: 'client.id' }, { source: 'c', target: 'client.name' }]);
  assert.deepEqual(result, { expression: "{'client': {'id': b, 'name': c}}", refusal: null });
});
