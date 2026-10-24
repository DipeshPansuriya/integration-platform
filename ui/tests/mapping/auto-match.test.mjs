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

// ---- review fixes (V4): G1 ids, G2 shared target, G3 coverage, A1 message ----
import { autoMapMessage, deleteLink } from '../../src/mapping/graph.ts';

const deepFreeze = (o) => { Object.values(o).forEach((v) => typeof v === 'object' && v !== null && deepFreeze(v)); return Object.freeze(o); };

test('G1: after deleting a middle link, Auto-map mints an id no existing link has', () => {
  let g = graphOf(['a', 'b', 'c'], ['a', 'b', 'c']);
  for (const f of ['a', 'b', 'c']) g = addLink(g, f, f).graph;
  g = deleteLink(g, 'link-1'); // links link-0 and link-2 remain
  const { graph, added } = autoMatchLinks(g);
  assert.equal(added, 1);
  const ids = graph.links.map((l) => l.id);
  assert.equal(new Set(ids).size, ids.length, `ids ${ids}`);
});

test('G1: addLink itself never reuses an id after a gap', () => {
  let g = graphOf(['a', 'b', 'c'], ['a', 'b', 'c', 'd']);
  for (const f of ['a', 'b', 'c']) g = addLink(g, f, f).graph;
  g = deleteLink(g, 'link-0');
  g = addLink(g, 'a', 'd').graph;
  assert.deepEqual(g.links.map((l) => l.id), ['link-1', 'link-2', 'link-3']);
});

test('G2: a target that already has a link (from another source) gets no second feed', () => {
  const start = addLink(graphOf(['z', 'x'], ['x']), 'z', 'x').graph;
  const { graph, added } = autoMatchLinks(start);
  assert.equal(added, 0);
  assert.deepEqual(graph.links.map((l) => [l.source, l.target]), [['z', 'x']]);
});

test('G3: empty graph, case difference, nested paths, duplicate source, frozen input', () => {
  assert.deepEqual(autoMatchLinks(emptyGraph()), { graph: emptyGraph(), added: 0 });
  assert.equal(autoMatchLinks(graphOf(['OrderNo'], ['orderNo'])).added, 0, 'paths are case-sensitive');
  const nested = autoMatchLinks(graphOf(['customer.name', 'items[].sku'], ['items[].sku', 'customer.name', 'customer']));
  assert.deepEqual(nested.graph.links.map((l) => l.target), ['customer.name', 'items[].sku']);
  const dup = autoMatchLinks(graphOf(['a', 'a'], ['a']));
  assert.equal(dup.added, 1);
  assert.equal(dup.graph.links.length, 1);
  const frozen = deepFreeze(graphOf(['a', 'b'], ['a', 'b']));
  assert.equal(autoMatchLinks(frozen).added, 2);
  assert.deepEqual(frozen.links, []);
});

test('A1: the announcement says how many fields were linked, or that none matched', () => {
  assert.equal(autoMapMessage(0), 'No matching field names to link.');
  assert.equal(autoMapMessage(1), 'Linked 1 matching field.');
  assert.equal(autoMapMessage(3), 'Linked 3 matching fields.');
});
