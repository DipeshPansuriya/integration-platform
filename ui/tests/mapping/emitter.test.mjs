// T-036 (E7 wave 2 lane B3, row 278) - the graph -> JSONata emitter, FAILING FIRST.
//
// E7 (backlog.md:1858-1868): "10 reference mappings written by hand vs drawn-and-emitted,
// 10 of 10 equal strings" - the emitter (ui/src/mapping/emit/emit.ts) derives the JSONata
// expression from the drawn links; the same expressions here are written by hand first.
//
//   Scenario: Drawn equals typed            -> 10 of 10 hand-written strings equal the emitted
//   Scenario: A difference fails the test   -> a planted wrong hand-write is caught (notEqual)
//
// The backend parity leg (platform-transform/src/test/.../parity) evaluates the SAME ten
// hand-written and emitted strings on the same samples through com.dashjoin:jsonata 0.9.10.
//
// graph.ts (T-034, lane D) is READ here - never edited; a change goes through arjun-fullstack.

import test from 'node:test';
import assert from 'node:assert/strict';
import { emptyGraph } from '../../src/mapping/graph.ts';
import { emitJsonata } from '../../src/mapping/emit/emit.ts';

/** One drawn link [source, target]; ids are re-minted positionally, as the canvas does. */
const drawn = (links) => ({
  ...emptyGraph(),
  links: links.map(([source, target], index) => ({ id: `link-${index}`, source, target }))
});

/** Source contract "Customer A v1" (graph.test.mjs contract, plus the zip field ref 4 needs). */
const CUSTOMER_A_V1 = {
  customerId: 'C-0001', orderId: 'O-777', amount: 42.5, 'address.city': 'Ujjain',
  'address.street': 'Station Road', 'address.zip': '456010', 'lines.sku': ['SKU-1', 'SKU-2']
};

/**
 * The ten reference mappings: written BY HAND (the expected string) and drawn (the links).
 * Order within a mapping is the drawn order - a re-draw in another order is another mapping.
 */
export const REFERENCE_MAPPINGS = [
  { name: 'flat three fields',
    links: [['customerId', 'customer_code'], ['orderId', 'order_number'], ['amount', 'total_amount']],
    hand: "{'customer_code': customerId, 'order_number': orderId, 'total_amount': amount}" },
  { name: 'single drawn link',
    links: [['orderId', 'order_number']],
    hand: "{'order_number': orderId}" },
  { name: 'nested source path flattens into navigation',
    links: [['address.street', 'customer_code']],
    hand: "{'customer_code': address.street}" },
  { name: 'two links merge under one nested target',
    links: [['address.street', 'ship_to.street'], ['address.zip', 'ship_to.zip']],
    hand: "{'ship_to': {'street': address.street, 'zip': address.zip}}" },
  { name: 'three-level target nests two object templates',
    links: [['orderId', 'envelope.header.order_number']],
    hand: "{'envelope': {'header': {'order_number': orderId}}}" },
  { name: 'array item property navigates the whole array',
    links: [['lines.sku', 'item_skus']],
    hand: "{'item_skus': lines.sku}" },
  { name: 'nested and flat targets side by side keep drawn order',
    links: [['address.street', 'ship_to.street'], ['orderId', 'billing_ref']],
    hand: "{'ship_to': {'street': address.street}, 'billing_ref': orderId}" },
  { name: 'drawn order is the emitted order (amount drawn before customerId)',
    links: [['amount', 'total_amount'], ['customerId', 'customer_code']],
    hand: "{'total_amount': amount, 'customer_code': customerId}" },
  { name: 'two levels with two children',
    links: [['customerId', 'header.party.code'], ['address.city', 'header.party.name']],
    hand: "{'header': {'party': {'code': customerId, 'name': address.city}}}" },
  { name: 'deep and flat mix',
    links: [['address.city', 'envelope.address'], ['amount', 'total_amount']],
    hand: "{'envelope': {'address': address.city}, 'total_amount': amount}" }
];

// Scenario: Drawn equals typed - 10 of 10 equal strings.
test('the ten hand-written reference mappings equal the drawn-and-emitted strings', () => {
  const equal = REFERENCE_MAPPINGS.filter((m) => emitJsonata(drawn(m.links).links).expression === m.hand);
  assert.deepEqual(equal.map((m) => m.name), REFERENCE_MAPPINGS.map((m) => m.name),
    `${equal.length} of ${REFERENCE_MAPPINGS.length} reference mappings emit the hand-written string`);
});

test('every reference mapping emits without refusal', () => {
  for (const m of REFERENCE_MAPPINGS) {
    const result = emitJsonata(drawn(m.links).links);
    assert.equal(result.refusal, null, m.name);
  }
});

// Scenario: A difference fails the test - the harness catches any string difference.
test('a planted wrong hand-write is caught by the same comparison', () => {
  const wrong = REFERENCE_MAPPINGS[0];
  const planted = wrong.hand.replace('customer_code', 'customer_cod'); // one character of drift
  assert.notEqual(emitJsonata(drawn(wrong.links).links).expression, planted,
    'a drifted hand-write must fail drawn-equals-typed');
});

// The engine's identity: nothing drawn emits `$` (the empty graph's default expression).
test('a graph with nothing drawn emits the identity expression', () => {
  assert.equal(emitJsonata([]).expression, '$');
});

// Release-1 refusal: two links drawn into one target (graph.ts allows the duplicate in the
// graph; the emitter refuses it - one target field is produced by one link per mapping).
test('two links into one target field are refused and named', () => {
  const result = emitJsonata(drawn([['orderId', 'total_amount'], ['amount', 'total_amount']]).links);
  assert.equal(result.refusal, 'the target field total_amount is drawn twice');
  assert.equal(result.expression, '');
});

// The emitter never touches the graph: same links in, same links out (read-only contract).
test('emitting reads the links and leaves the graph untouched', () => {
  const graph = drawn([['amount', 'total_amount']]);
  const snapshot = JSON.stringify(graph.links);
  emitJsonata(graph.links);
  assert.equal(JSON.stringify(graph.links), snapshot);
});