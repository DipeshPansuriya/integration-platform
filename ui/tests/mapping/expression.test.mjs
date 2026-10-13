// T-039 (E7 wave 2 lane D2, row 278) - the raw-expression editor state, FAILING FIRST.
//
// E7-F2-S2 (backlog.md:1873) as scoped by ADR-006 (Dashjoin JSONata 0.9.10, Accepted 2026-10-12):
// the raw editor is the CodeMirror 6 surface where a configurator types the mapping's raw
// expression. The React component (ui/src/mapping/expression/RawExpressionEditor.tsx) mounts the
// editor; this module (ui/src/mapping/expression/raw.ts) is the pure contract under it, so
// `node --test` can hold the three scenarios without a browser:
//
//   Scenario: Drawn equals typed (editor side)  -> the editor renders the graph's expression and
//                                                  an edit lands back, round-tripping to the save
//                                                  request (graph -> editor -> graph -> save body).
//   Scenario: Money rounding never via $round   -> ADR-006 rule 8 (memory m-89cfec): an edit that
//                                                  introduces $round is refused with the rule
//                                                  named, and the graph keeps its old expression.
//
// The emitter itself is lane B3's (ui/src/mapping/emit/**) - this lane only owns the typed surface.

import test from 'node:test';
import assert from 'node:assert/strict';
import { applyExpressionEdit, renderExpression } from '../../src/mapping/expression/raw.ts';
import { emptyGraph, parseSavedMapping, toSaveRequest } from '../../src/mapping/graph.ts';

/** The saved mapping of the pair (graph.test.mjs's shape): a compiled expression from storage. */
const SAVED = {
  routeId: '11111111-1111-1111-1111-111111111111',
  mappingId: '22222222-2222-2222-2222-222222222222',
  fieldMappings: [
    { source: 'amount', target: 'total_amount' }
  ],
  compiledExpression:
    '{ "customer_code": customerId, "total_amount": $sum(items.amount) + items.tax : $number() }',
  sourceContractVersionId: '33333333-3333-3333-3333-333333333333',
  targetContractVersionId: '44444444-4444-4444-4444-444444444444',
  mappingVersionId: '55555555-5555-5555-5555-555555555555'
};

test('the editor renders exactly the mapping expression that storage keeps', () => {
  const graph = parseSavedMapping(SAVED);
  assert.equal(renderExpression(graph), graph.expression);
  assert.ok(renderExpression(graph).startsWith('{ "customer_code"'));
});

test('an edit round-trips: editor text -> graph -> save request body', () => {
  const graph = emptyGraph();
  const editorText = '{ "customer_code": customerId }';
  const { graph: next, refusal } = applyExpressionEdit(graph, editorText);
  assert.equal(refusal, null);
  assert.equal(next.expression, editorText);
  assert.equal(toSaveRequest(next).compiledExpression, editorText);
});

test('other graph parts survive the edit untouched', () => {
  const graph = {
    ...emptyGraph(),
    links: [{ id: 'link-0', source: 'customerId', target: 'customer_code' }],
    targetFields: ['customer_code']
  };
  const { graph: next, refusal } = applyExpressionEdit(graph, '$');
  assert.equal(refusal, null);
  assert.deepEqual(next.links, graph.links);
  assert.deepEqual(next.binding, graph.binding);
  assert.deepEqual(next.sourceFields, graph.sourceFields);
  assert.deepEqual(next.targetFields, graph.targetFields);
});

test('money rounding never via $round: the edit is refused and the rule is named', () => {
  const graph = parseSavedMapping(SAVED);
  const { graph: next, refusal } = applyExpressionEdit(graph, '{ "total_amount": $round(amount) }');
  assert.match(refusal ?? '', /\$round/);
  assert.match(refusal ?? '', /money/i);
  assert.equal(next.expression, graph.expression, 'the refused edit is not applied');
});

test('$round is refused even deeper in the expression text', () => {
  const graph = emptyGraph();
  const { graph: next, refusal } = applyExpressionEdit(
    graph, '{ "total": {"$sum": [$.a]}, "note": $round($.b) }'
  );
  assert.match(refusal ?? '', /\$round/);
  assert.equal(next.expression, graph.expression);
});