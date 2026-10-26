// Lane A (B27, audit A1-A3): the flow-definition save and hydrate outcomes are worded and never silent.
//   A1 a non-OK save that is not 400/404 gets a plain sentence (not nothing).
//   A2 a failed schema save is NOT reported as "Draft saved".
//   A3 a failed hydrate is an error with Retry - never a blank form that would create a duplicate.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/flow-save.test.mjs

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const { failureText, saveFlowDefinition, loadFlowForm } = await import('../../src/pages/flow-save.ts');
const page = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'pages', 'FlowDefinition.tsx'), 'utf8');

const FORM = { sourceSystem: 'SAP', destinations: ['A', ' ', 'B'], interfaceName: 'Orders', businessKeyRule: '$.id', ordering: 'PER_KEY', sourceSchema: '' };
const ok = (data) => ({ ok: true, data });
const fail = (status, error) => ({ ok: false, status, error });
const field = (error) => (error ? 'source-system' : 'request');
const deps = (over = {}) => ({
  createFlow: async () => ok({ id: 'new-1' }),
  saveFlowDraft: async () => ok({}),
  saveSourceSchema: async () => ok({}),
  validationFieldOf: field,
  ...over
});

test('failureText: every status says what failed and what to do, never a bare code', () => {
  for (const status of [0, 401, 403, 429, 500, 502, 418]) {
    const text = failureText('save the draft', status);
    assert.match(text, /^Could not save the draft/, String(status));
    assert.doesNotMatch(text, /\b(HTTP|status)\b|\b\d{3}\b/, `no code in "${text}"`);
    assert.ok(text.length > 40, text);
  }
  assert.match(failureText('save the draft', 0), /reached/);
  assert.match(failureText('save the draft', 403), /permission/);
  assert.match(failureText('save the draft', 429), /Wait/);
});

test('A1: a 500, 403 or 429 on the draft save is a worded failure, not a silent return', async () => {
  for (const status of [500, 403, 429]) {
    const out = await saveFlowDefinition(deps({ createFlow: async () => fail(status) }), null, FORM);
    assert.equal(out.kind, 'failed', String(status));
    assert.match(out.text, /Could not save the draft/);
  }
});

test('A1: a thrown network error is a worded failure too', async () => {
  const out = await saveFlowDefinition(deps({ createFlow: async () => { throw new TypeError('Failed to fetch'); } }), null, FORM);
  assert.equal(out.kind, 'failed');
  assert.match(out.text, /reached/);
});

test('400 stays a field validation and 404 stays not-found', async () => {
  const bad = await saveFlowDefinition(deps({ saveFlowDraft: async () => fail(400, 'sourceSystem is unknown') }), 'f1', FORM);
  assert.deepEqual(bad, { kind: 'validation', field: 'source-system', text: 'sourceSystem is unknown' });
  assert.deepEqual(await saveFlowDefinition(deps({ saveFlowDraft: async () => fail(404) }), 'f1', FORM), { kind: 'not-found' });
});

test('success: create sends trimmed destinations and returns the new id; update keeps the id', async () => {
  let sent;
  const created = await saveFlowDefinition(deps({ createFlow: async (b) => { sent = b; return ok({ id: 'n9' }); } }), null, FORM);
  assert.deepEqual(created, { kind: 'saved', id: 'n9' });
  assert.deepEqual(sent.destinations, ['A', 'B']);
  assert.deepEqual(await saveFlowDefinition(deps(), 'f1', FORM), { kind: 'saved', id: 'f1' });
});

test('A2: a schema save that fails with 500 is not "saved" - the draft id is kept, the schema failure worded', async () => {
  const withSchema = { ...FORM, sourceSchema: '{"type":"object"}' };
  const out = await saveFlowDefinition(deps({ saveSourceSchema: async () => fail(500) }), null, withSchema);
  assert.equal(out.kind, 'failed');
  assert.equal(out.id, 'new-1', 'the draft was created; the screen must remember its id');
  assert.match(out.text, /draft was saved, but the source schema could not be/i);
});

test('A2: a schema 400 is a field validation (with the draft id); bad JSON names the schema field', async () => {
  const withSchema = { ...FORM, sourceSchema: '{"type":"object"}' };
  const out = await saveFlowDefinition(deps({ saveSourceSchema: async () => fail(400, 'not a schema') }), 'f1', withSchema);
  assert.deepEqual(out, { kind: 'validation', field: 'source-system', text: 'not a schema', id: 'f1' });
  const json = await saveFlowDefinition(deps(), 'f1', { ...FORM, sourceSchema: '{nope' });
  assert.equal(json.kind, 'validation');
  assert.equal(json.field, 'source-schema');
});

test('A3: hydrate - loaded form, 404 not-found, and any other failure is a worded error with no form', async () => {
  const detail = { sourceSystem: 'SAP', destinations: ['A'], name: 'Orders', businessKeyRule: '$.id', ordering: 'NONE' };
  const loaded = await loadFlowForm(async () => ok(detail), 'f1');
  assert.equal(loaded.kind, 'loaded');
  assert.deepEqual(loaded.form.destinations, ['A', ''], 'padded to the two-row minimum');
  assert.equal(loaded.form.interfaceName, 'Orders');
  assert.deepEqual(await loadFlowForm(async () => fail(404), 'f1'), { kind: 'not-found' });
  for (const read of [async () => fail(500), async () => { throw new Error('offline'); }]) {
    const out = await loadFlowForm(read, 'f1');
    assert.equal(out.kind, 'failed');
    assert.match(out.text, /Could not open the flow/);
  }
});

test('A3 + A1/A2 wiring: the page shows the load error with Retry instead of the form, and the saved note needs a saved outcome', () => {
  assert.match(page, /data-load-error="flow"/);
  assert.match(page, /data-action="retry-load"/);
  assert.match(page, /data-save-error/);
  assert.match(page, /saveFlowDefinition\(/);
  assert.match(page, /loadFlowForm\(/);
});
