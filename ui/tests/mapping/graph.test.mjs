// T-034 (E7 wave 1 lane D1, row 278) - the mapping-graph contract, FAILING FIRST.
//
// E7-F2-S1 (backlog.md:1824-1841) against the pure graph module (`ui/src/mapping/graph.ts`,
// written by lane D, read-only for lane B's emitter):
//
//   Scenario: Map Customer A to Target A v1.0        -> links parse, save and round-trip
//   Scenario: Add, change and delete elements        -> add/change/delete keep the graph honest
//   Scenario: A link to a field the target contract does not have is refused -> names the field
//
// The backend PUT (T-190, mapping-admin-controller) answers fieldMappings entries shaped
// { source, target }; the canvas keeps an id for itself and strips it on save.

import test from 'node:test';
import assert from 'node:assert/strict';
import {
  addLink, changeLink, deleteLink, emptyGraph,
  parseSavedMapping, schemaPaths, targetRefusal, toSaveRequest
} from '../../src/mapping/graph.ts';

/** The saved mapping of the pair, with Target A v1.0's fields as the bound target tree. */
const bound = (saved) => ({ ...parseSavedMapping(saved), targetFields: schemaPaths(TARGET_A_V1) });

/** The source contract "Customer A" the scenarios name. */
export const CUSTOMER_A_V1 = {
  $schema: 'https://json-schema.org/draft/2020-12/schema',
  type: 'object',
  required: ['customerId', 'orderId', 'amount'],
  properties: {
    customerId: { type: 'string' },
    orderId: { type: 'string' },
    amount: { type: 'number' },
    address: { type: 'object', properties: { street: { type: 'string' }, city: { type: 'string' } } }
  }
};

/** Target A contract v1.0 (backlog.md:1826: the three fields the pair maps). */
export const TARGET_A_V1 = {
  $schema: 'https://json-schema.org/draft/2020-12/schema',
  type: 'object',
  required: ['customer_code', 'order_number', 'total_amount'],
  properties: {
    customer_code: { type: 'string' },
    order_number: { type: 'string' },
    total_amount: { type: 'number' }
  }
};

const SAVED = {
  routeId: '11111111-1111-1111-1111-111111111111',
  mappingId: '22222222-2222-2222-2222-222222222222',
  fieldMappings: [
    { source: 'customerId', target: 'customer_code' },
    { source: 'orderId', target: 'order_number' },
    { source: 'amount', target: 'total_amount' }
  ],
  compiledExpression: '$',
  sourceContractVersionId: '33333333-3333-3333-3333-333333333333',
  targetContractVersionId: '44444444-4444-4444-4444-444444444444',
  mappingVersionId: '55555555-5555-5555-5555-555555555555'
};

// Scenario: Map Customer A to Target A v1.0 - the saved mapping re-renders exactly as left.
test('a saved mapping parses into the graph and saves back to the backend shape', () => {
  const graph = parseSavedMapping(SAVED);
  assert.equal(graph.links.length, 3);
  assert.deepEqual(
    graph.links.map((l) => [l.source, l.target]),
    [['customerId', 'customer_code'], ['orderId', 'order_number'], ['amount', 'total_amount']]
  );
  assert.equal(graph.binding.sourceContractVersionId, SAVED.sourceContractVersionId);
  assert.equal(graph.binding.targetContractVersionId, SAVED.targetContractVersionId);
  assert.equal(graph.expression, '$');

  const request = toSaveRequest(graph);
  assert.deepEqual(request, {
    compiledExpression: '$',
    sourceContractVersionId: SAVED.sourceContractVersionId,
    targetContractVersionId: SAVED.targetContractVersionId,
    fieldMappings: SAVED.fieldMappings // the backend's shape: { source, target } only, no canvas ids
  });
  request.fieldMappings.forEach((entry) => assert.deepEqual(Object.keys(entry).sort(), ['source', 'target']));
});

test('an unbound route is an empty two-sided graph', () => {
  const graph = parseSavedMapping({ routeId: 'r1', fieldMappings: [], compiledExpression: '$',
    sourceContractVersionId: null, targetContractVersionId: null });
  assert.deepEqual(graph.binding, { sourceContractVersionId: null, targetContractVersionId: null });
  assert.deepEqual(graph.links, []);
  const fresh = emptyGraph();
  assert.deepEqual(fresh.links, []);
  assert.deepEqual(fresh.binding, { sourceContractVersionId: null, targetContractVersionId: null });
});

// Scenario: Add, change and delete elements - all three edits reflect in the stored mapping.
test('adding, changing and deleting a link edits the graph in place semantics', () => {
  let graph = bound(SAVED);

  // an element is added
  const added = addLink(graph, 'amount', 'total_amount');
  assert.equal(added.refusal, null);
  graph = added.graph;
  assert.equal(graph.links.length, 4);

  // a link changed: orderId now maps into total_amount as well
  const changed = changeLink(graph, 'link-1', { target: 'total_amount' });
  assert.equal(changed.refusal, null);
  graph = changed.graph;
  assert.equal(graph.links[1].target, 'total_amount');

  // an element deleted
  graph = deleteLink(graph, 'link-3');
  assert.equal(graph.links.length, 3);
  assert.equal(graph.links.find((l) => l.id === 'link-3'), undefined);
  assert.equal(
    graph.links.map((l) => `${l.source}->${l.target}`).join(','),
    'customerId->customer_code,orderId->total_amount,amount->total_amount'
  );
});

// Scenario: A link to a field the target contract does not have is refused - and names the field.
test('a link targeting a field the target contract does not have is refused and named', () => {
  const added = addLink(bound(SAVED), 'customerId', 'customerCod');
  assert.equal(added.refusal, 'the target contract does not have field customerCod');
  assert.equal(added.graph.links.length, 3, 'the refused link is not added');

  const changed = changeLink(bound(SAVED), 'link-0', { target: 'no_such_field' });
  assert.equal(changed.refusal, 'the target contract does not have field no_such_field');
  assert.equal(changed.graph.links[0].target, 'customer_code', 'the refused change is not applied');
});

test('the mutators refuse against the bound target fields, not the schema object', () => {
  const graph = { ...bound(SAVED), targetFields: ['customer_code', 'order_number', 'total_amount'] };
  assert.equal(changeLink(graph, 'link-2', { target: 'lines.sku' }).refusal,
    'the target contract does not have field lines.sku');
});

test('a graph re-rendered from storage that names an unknown target path is refused on save', () => {
  const stale = parseSavedMapping({ ...SAVED, fieldMappings: [{ source: 'x', target: 'gone_field' }] });
  assert.equal(targetRefusal(stale, TARGET_A_V1), 'the target contract does not have field gone_field');
  assert.equal(targetRefusal(parseSavedMapping(SAVED), TARGET_A_V1), null);
});

test('a nested target path is refused unless the contract schema carries it', () => {
  const withNested = {
    type: 'object',
    properties: { ship_to: { type: 'object', properties: { street: { type: 'string' } } } }
  };
  const links = ([target]) => [{ id: 'link-0', source: 'a', target }];
  assert.equal(targetRefusal(
    { ...parseSavedMapping(SAVED), links: links(['ship_to.street']), binding: { sourceContractVersionId: null, targetContractVersionId: 't' } },
    withNested
  ), null);
  assert.equal(targetRefusal(
    { ...parseSavedMapping(SAVED), links: links(['ship_to.zip']), binding: { sourceContractVersionId: null, targetContractVersionId: 't' } },
    withNested
  ), 'the target contract does not have field ship_to.zip');
});

// The two schema trees: exactly the bound contract versions' fields.
test('schemaPaths lists the contract fields, nesting by dotted path', () => {
  assert.deepEqual(schemaPaths(CUSTOMER_A_V1),
    ['customerId', 'orderId', 'amount', 'address', 'address.street', 'address.city']);
  assert.deepEqual(schemaPaths(TARGET_A_V1), ['customer_code', 'order_number', 'total_amount']);
});

test('schemaPaths walks through array item properties and skips schema keywords', () => {
  const schema = {
    type: 'object',
    properties: {
      lines: { type: 'array', items: { type: 'object', properties: { sku: { type: 'string' } } } }
    }
  };
  assert.deepEqual(schemaPaths(schema), ['lines', 'lines.sku']);
});