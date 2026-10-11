// T-026 + T-025 (E6-F1-S1/S2, lane D): the decision-table editor seam's contract - FAILING FIRST.
//
//   Scenario: The fallback grid plugs into the same seam
//     Given the fallback grid's design and seam stub
//     When the seam's contract test runs against the stub
//     Then it emits the same ZEN JSON shape as the editor adapter
//
// The same fixture JDM documents (one validation, one routing, one lookup table - the starter
// templates the console creates rule sets from, the samples lane B's API suite accepts) run through
// BOTH implementations of DecisionTableEditorAdapter: the jdm-editor adapter (ADR-003, pinned 1.52.0)
// and the fallback grid's stub (designed, not built - ui/src/rules/fallback/DESIGN.md). A planted
// drifting adapter proves the comparison fails when the shapes differ (R02).

import test from 'node:test';
import assert from 'node:assert/strict';
import { RULE_KINDS, starterJdm } from '../../src/rules/templates.ts';
import { JdmEditorAdapter } from '../../src/rules/jdm/JdmEditorAdapter.ts';
import { FallbackGridAdapter } from '../../src/rules/fallback/FallbackGridAdapter.ts';

/** The ZEN JSON shape: key names and value types, recursively (arrays by their first element). */
function shapeOf(value) {
  if (Array.isArray(value)) return [value.length === 0 ? 'empty' : shapeOf(value[0])];
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, shapeOf(value[key])]));
  }
  return value === null ? 'null' : typeof value;
}

/** What the ZEN engine needs of a decision graph, and nothing an editor keeps for itself. */
function zenProblems(jdm) {
  const problems = [];
  if (!Array.isArray(jdm?.nodes) || !Array.isArray(jdm?.edges)) return ['nodes and edges must be arrays'];
  for (const node of jdm.nodes) {
    for (const key of ['id', 'type', 'name']) if (typeof node[key] !== 'string') problems.push(`node ${node.id}: ${key}`);
    if (typeof node.position?.x !== 'number' || typeof node.position?.y !== 'number') problems.push(`node ${node.id}: position`);
    if (node.type === 'decisionTableNode') {
      const table = node.content ?? {};
      if (typeof table.hitPolicy !== 'string') problems.push(`node ${node.id}: hitPolicy`);
      for (const key of ['inputs', 'outputs', 'rules']) if (!Array.isArray(table[key])) problems.push(`node ${node.id}: ${key}`);
    }
  }
  for (const edge of jdm.edges) {
    for (const key of ['id', 'sourceId', 'targetId']) if (typeof edge[key] !== 'string') problems.push(`edge ${edge.id}: ${key}`);
  }
  if (JSON.stringify(jdm).includes('"_diff"')) problems.push('editor-only _diff markers leaked into the ZEN JSON');
  return problems;
}

/** The seam contract between two implementations for one fixture: same shape, same document, valid ZEN. */
function contractProblems(left, right, fixture) {
  left.load(structuredClone(fixture));
  right.load(structuredClone(fixture));
  const a = left.toZen();
  const b = right.toZen();
  const problems = [...zenProblems(a).map((p) => `left: ${p}`), ...zenProblems(b).map((p) => `right: ${p}`)];
  try {
    assert.deepEqual(shapeOf(a), shapeOf(b));
  } catch {
    problems.push('the two adapters emit different ZEN JSON shapes');
  }
  if (JSON.stringify(a) !== JSON.stringify(b)) problems.push('the two adapters emit different ZEN JSON documents');
  return problems;
}

for (const kind of RULE_KINDS) {
  test(`the fallback grid's stub emits the same ZEN JSON shape as the jdm-editor adapter (${kind})`, () => {
    assert.deepEqual(contractProblems(new JdmEditorAdapter(), new FallbackGridAdapter(), starterJdm(kind)), []);
  });

  test(`a document loaded through the seam comes back exactly as it went in (${kind})`, () => {
    const fixture = starterJdm(kind);
    for (const adapter of [new JdmEditorAdapter(), new FallbackGridAdapter()]) {
      adapter.load(structuredClone(fixture));
      assert.equal(JSON.stringify(adapter.toZen()), JSON.stringify(fixture), adapter.constructor.name);
    }
  });
}

test('the fixtures are the three kinds, one decision table each', () => {
  assert.deepEqual(RULE_KINDS, ['VALIDATION', 'ROUTING', 'LOOKUP']);
  for (const kind of RULE_KINDS) {
    const tables = new JdmEditorAdapter();
    tables.load(starterJdm(kind));
    assert.equal(tables.tables().length, 1, kind);
  }
});

test('the jdm-editor adapter emits clean ZEN JSON after the editor hands back its own markers', () => {
  const adapter = new JdmEditorAdapter();
  adapter.load(starterJdm('ROUTING'));
  const [{ nodeId, table }] = adapter.tables();
  const edited = structuredClone(table);
  edited.rules[0].c1 = '"BE"';
  // jdm-editor keeps diff markers on the table, its columns and cells, and a private symbol on nodes.
  edited._diff = { status: 'modified' };
  edited.inputs[0]._diff = { status: 'unchanged' };
  edited[Symbol('jdm-private')] = { selected: true };
  adapter.updateTable(nodeId, edited);

  const zen = adapter.toZen();
  assert.deepEqual(zenProblems(zen), []);
  const node = zen.nodes.find((n) => n.id === nodeId);
  assert.equal(node.content.rules[0].c1, '"BE"');
  assert.deepEqual(Object.getOwnPropertySymbols(node.content), []);
});

test('@negative a drifting implementation is caught by the contract (planted violation)', () => {
  class DroppingEdges extends FallbackGridAdapter {
    toZen() {
      return { ...super.toZen(), edges: [] };
    }
  }
  class LeakingDiff extends FallbackGridAdapter {
    toZen() {
      const zen = super.toZen();
      zen.nodes[1].content._diff = { status: 'modified' };
      return zen;
    }
  }
  const fixture = starterJdm('VALIDATION');
  assert.ok(contractProblems(new JdmEditorAdapter(), new DroppingEdges(), fixture).length > 0, 'dropped edges');
  assert.ok(contractProblems(new JdmEditorAdapter(), new LeakingDiff(), fixture).length > 0, 'leaked _diff');
});

test('the fallback grid is a stub: it plugs into the seam but its editing UI is not built (ADR-003 rule 2)', () => {
  const stub = new FallbackGridAdapter();
  stub.load(starterJdm('LOOKUP'));
  const [{ nodeId, table }] = stub.tables();
  assert.throws(() => stub.updateTable(nodeId, table), /not built/);
});
