// Row 278 T57 (Kavita's judging, "drawn twice" stale save) - FAILING FIRST.
//
// A drawing with one target field drawn twice is refused by the emitter (emit.ts), and the designer
// keeps the PREVIOUS expression. Saving it would PUT that stale expression with the new
// fieldMappings - the stored mapping would not match the drawing. So while the emitter refuses the
// drawn links, save() refuses too (no PUT) and the refusal stays visible: saveRefusal(graph) names
// it; a drawing the emitter accepts saves (null).

import test from 'node:test';
import assert from 'node:assert/strict';
import { saveRefusal } from '../../src/mapping/saveRefusal.ts';

const graphOf = (links, expression = '{ "clientId": customerId }') => ({
  routeId: 'r1',
  binding: { sourceContractVersionId: 's1', targetContractVersionId: 't1' },
  links,
  expression,
  sourceFields: [],
  targetFields: []
});

test('one target field drawn twice: the save is refused, naming the field (no stale expression stored)', () => {
  const graph = graphOf([
    { id: 'l1', source: 'customerId', target: 'clientId' },
    { id: 'l2', source: 'orderId', target: 'clientId' }
  ]);
  const refusal = saveRefusal(graph);
  assert.ok(refusal, 'a drawing the emitter refuses must not be saved');
  assert.match(refusal, /clientId/);
  assert.match(refusal, /drawn twice/);
});

test('a drawing the emitter accepts saves (no refusal)', () => {
  const graph = graphOf([
    { id: 'l1', source: 'customerId', target: 'clientId' },
    { id: 'l2', source: 'orderId', target: 'referenceNo' }
  ]);
  assert.equal(saveRefusal(graph), null);
});

test('no links drawn: saves (no refusal)', () => {
  assert.equal(saveRefusal(graphOf([], '$')), null);
});
