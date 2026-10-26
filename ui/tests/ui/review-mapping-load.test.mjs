// Row 372 M10 (Dipesh approved): the mapping designer still blocks when ANY of its loads fails, but the
// message names which load failed - the mapping, the contracts list, or the contract versions the mapping
// uses - in the shared plain words (refusalWords), never a bare status code.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/review-mapping-load.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const load = await import('../../src/pages/mapping-load.ts');
const designer = readFileSync(new URL('../../src/pages/MappingDesigner.tsx', import.meta.url), 'utf8');

test('M10 each failed load is named in plain words', () => {
  assert.match(load.loadFailureText('contracts', 403), /contracts list/);
  assert.match(load.loadFailureText('contracts', 403), /permission/);
  assert.match(load.loadFailureText('mapping', 500), /^Could not open the mapping: the server had a problem/);
  assert.match(load.loadFailureText('contract-version', 404), /contract versions this mapping uses/);
  const texts = ['mapping', 'contracts', 'contract-version'].map((p) => load.loadFailureText(p, 500));
  assert.equal(new Set(texts).size, 3, 'three loads, three different messages');
  for (const t of texts) assert.doesNotMatch(t, /HTTP|\b500\b/);
});

test('M10 a network failure (status 0) is still named', () => {
  assert.match(load.loadFailureText('contracts', 0), /contracts list/);
});

test('M10 classifyMappingLoad words a failed mapping read as "the mapping"', () => {
  const out = load.classifyMappingLoad({ response: { ok: false, status: 403 } });
  assert.equal(out.kind, 'failed');
  assert.match(out.text, /the mapping/);
  assert.match(out.text, /permission/);
});

test('M10 a contract version that did not load is a typed error carrying its status', () => {
  const error = new load.VersionLoadError(404);
  assert.ok(error instanceof Error);
  assert.equal(error.status, 404);
});

test('M10 the designer wires the three cases (contracts list failure still blocks, with its own words)', () => {
  assert.match(designer, /loadFailureText\('contracts', contracts\.response\?\.status \?\? 0\)/);
  assert.match(designer, /new VersionLoadError\(/);
  assert.match(designer, /e instanceof VersionLoadError \? 'contract-version' : 'mapping'/);
  assert.match(designer, /!contracts\.response\?\.ok/, 'the block on a contracts-list failure is kept');
});
