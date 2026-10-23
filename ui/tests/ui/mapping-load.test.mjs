// Lane A (B27, audit A5/A6): the mapping designer never treats a failed load as an empty mapping, and
// never carries one route's state into another.
//   A5 a failed load shows a worded error with Retry and DISABLES Save (it used to be `mapping.data ?? {}`
//      with Save enabled, so a Save overwrote the stored mapping).
//   A6 every route gets a fresh designer (keyed inner component), so missing/targetSchema/selected/
//      mappingId from the previous route cannot leak.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/mapping-load.test.mjs

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const { classifyMappingLoad, canSaveMapping, designerKey } = await import('../../src/pages/mapping-load.ts');
const page = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'pages', 'MappingDesigner.tsx'), 'utf8');

const res = (status) => ({ ok: status >= 200 && status < 300, status });

test('A5: a stored mapping loads; 404 is the missing-route empty state', () => {
  const saved = { mappingId: 'm1', fieldMappings: [] };
  assert.deepEqual(classifyMappingLoad({ data: saved, response: res(200) }), { kind: 'loaded', saved });
  assert.deepEqual(classifyMappingLoad({ error: {}, response: res(404) }), { kind: 'missing' });
});

test('A5: an empty 2xx body is an empty mapping, but any other failure is a worded error - never an empty mapping', () => {
  assert.deepEqual(classifyMappingLoad({ response: res(204) }), { kind: 'loaded', saved: {} });
  for (const status of [401, 403, 429, 500, 503]) {
    const out = classifyMappingLoad({ error: { message: 'boom' }, response: res(status) });
    assert.equal(out.kind, 'failed', String(status));
    assert.match(out.text, /^Could not open the mapping/);
  }
});

test('A5: no response at all (network error) is a worded error', () => {
  const out = classifyMappingLoad({ error: new TypeError('Failed to fetch') });
  assert.equal(out.kind, 'failed');
  assert.match(out.text, /reached/);
});

test('A5: Save is allowed only on an open route that finished loading without a failure', () => {
  assert.equal(canSaveMapping({ routeId: 'r1', loaded: true, loadError: null }), true);
  assert.equal(canSaveMapping({ routeId: 'r1', loaded: false, loadError: null }), false);
  assert.equal(canSaveMapping({ routeId: 'r1', loaded: true, loadError: 'x' }), false);
  assert.equal(canSaveMapping({ routeId: null, loaded: true, loadError: null }), false);
});

test('A6: each route (and the landing) has its own designer key', () => {
  assert.notEqual(designerKey('r1'), designerKey('r2'));
  assert.notEqual(designerKey(null), designerKey('r1'));
});

test('wiring: the page shows the error with Retry, gates Save on canSaveMapping, and keys the inner designer by route', () => {
  assert.match(page, /data-load-error="mapping"/);
  assert.match(page, /data-action="retry-load"/);
  assert.match(page, /disabled=\{!canSaveMapping\(/);
  assert.match(page, /key=\{designerKey\(routeId\)\}/);
  assert.doesNotMatch(page, /mapping\.data \?\? \{\}/, 'a failed load is never read as an empty mapping');
});
