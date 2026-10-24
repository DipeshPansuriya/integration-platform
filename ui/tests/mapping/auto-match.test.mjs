// L28 (row 372): "Auto-map matching names" - the pure rule behind the designer's auto-map-matching button.
// A source field whose path also exists in the target contract is linked to it; nothing else is touched.
import test from 'node:test';
import assert from 'node:assert/strict';
import { autoMatchLinks, emptyGraph, addLink } from '../../src/mapping/graph.ts';

const graphOf = (sourceFields, targetFields) => ({ ...emptyGraph(), sourceFields, targetFields });

test('links every source path that the target also has, in source order', () => {
  const { graph, added } = autoMatchLinks(graphOf(['orderNo', 'customer', 'qty'], ['qty', 'orderNo', 'customer', 'contractVersion']));
  assert.equal(added, 3);
  assert.deepEqual(graph.links.map((l) => [l.source, l.target]), [['orderNo', 'orderNo'], ['customer', 'customer'], ['qty', 'qty']]);
});

test('leaves differently named fields alone and reports zero when nothing matches', () => {
  const { graph, added } = autoMatchLinks(graphOf(['orderNo', 'qty'], ['order_no', 'total']));
  assert.equal(added, 0);
  assert.deepEqual(graph.links, []);
});

test('is idempotent: a pair already linked is not linked twice', () => {
  const start = addLink(graphOf(['orderNo', 'qty'], ['orderNo', 'qty']), 'orderNo', 'orderNo').graph;
  const first = autoMatchLinks(start);
  assert.equal(first.added, 1);
  const second = autoMatchLinks(first.graph);
  assert.equal(second.added, 0);
  assert.equal(second.graph.links.length, 2);
});

test('link ids stay unique after an auto-map on a graph that already has links', () => {
  const start = addLink(graphOf(['a', 'b'], ['a', 'b']), 'b', 'b').graph;
  const { graph } = autoMatchLinks(start);
  assert.equal(new Set(graph.links.map((l) => l.id)).size, graph.links.length);
});
