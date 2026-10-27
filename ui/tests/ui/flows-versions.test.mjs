// Row 310 lane B (vijay-developer, T88): Flows (list landing + 2-pane editor) and Versions (table +
// status-contextual lifecycle panel), 10-screens §3/§4, 01-tokens status map, 20-build-plan lane B.
// Pure helpers (flow-lifecycle.ts) are exercised directly; the page contracts the screens specs key
// off (data-* hooks, verbatim refusals, no silent create, no localStorage hydration) are read from source.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/flows-versions.test.mjs

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const pages = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'pages');
const source = (name) => readFileSync(join(pages, name), 'utf8');

const {
  lifecycleActions,
  lifecycleSteps,
  isImmutable,
  harnessApproveOffered,
  filterFlows,
  editorFlowId,
  isDirty,
  NEW_FLOW_HASH,
  flowHash,
  hashMove,
  validationText,
  fieldA11y
} = await import('../../src/pages/flow-lifecycle.ts');

test('§4 lifecycle panel: only the actions valid for the selected version status', () => {
  assert.deepEqual(lifecycleActions('DRAFT'), ['save-draft', 'submit-for-approval']);
  assert.deepEqual(lifecycleActions('SUBMITTED'), ['approve', 'new-draft']);
  assert.deepEqual(lifecycleActions('APPROVED'), ['publish', 'new-draft']);
  assert.deepEqual(lifecycleActions('PUBLISHED'), ['new-draft']);
  assert.deepEqual(lifecycleActions('SOMETHING_ELSE'), []);
});

test('§4 publish is offered only for APPROVED (Versions.tsx:227 today)', () => {
  for (const status of ['DRAFT', 'SUBMITTED', 'PUBLISHED']) assert.ok(!lifecycleActions(status).includes('publish'), status);
});

test('§4 submitted and approved versions are immutable; drafts and published are not', () => {
  assert.equal(isImmutable('SUBMITTED'), true);
  assert.equal(isImmutable('APPROVED'), true);
  assert.equal(isImmutable('DRAFT'), false);
  assert.equal(isImmutable('PUBLISHED'), false);
});

test('§4 the harness approver action shows only for a SUBMITTED version with the harness binding', () => {
  assert.equal(harnessApproveOffered('SUBMITTED', true), true);
  assert.equal(harnessApproveOffered('SUBMITTED', false), false);
  assert.equal(harnessApproveOffered('DRAFT', true), false);
  assert.equal(harnessApproveOffered('APPROVED', true), false);
});

test('§4 stepper: worded steps, the current one marked, earlier ones done', () => {
  const steps = lifecycleSteps('APPROVED');
  assert.deepEqual(steps.map((s) => s.word), ['Draft', 'Submitted', 'Approved', 'Published']);
  assert.deepEqual(steps.map((s) => s.state), ['done', 'done', 'current', 'todo']);
  assert.deepEqual(lifecycleSteps('DRAFT').map((s) => s.state), ['current', 'todo', 'todo', 'todo']);
});

test('§3 list filter: one text filter over name and code, case-insensitive; blank keeps all', () => {
  const rows = [
    { id: '1', code: 'ORD', name: 'Orders inbound', ordering: 'PER_KEY', status: 'DRAFT' },
    { id: '2', code: 'INV', name: 'Invoices', ordering: 'NONE', status: 'PUBLISHED' }
  ];
  assert.deepEqual(filterFlows(rows, '  ').map((r) => r.id), ['1', '2']);
  assert.deepEqual(filterFlows(rows, 'orders').map((r) => r.id), ['1']);
  assert.deepEqual(filterFlows(rows, 'inv').map((r) => r.id), ['2']);
  assert.deepEqual(filterFlows(rows, 'zzz'), []);
});

test('§3 editor route: #/flow/new is create mode, #/flow/<id> opens that flow', () => {
  assert.equal(NEW_FLOW_HASH, '#/flow/new');
  assert.equal(editorFlowId('new'), null);
  assert.equal(editorFlowId(null), null);
  assert.equal(editorFlowId('abc-1'), 'abc-1');
  assert.equal(flowHash('a b'), '#/flow/a%20b');
});

test('§3 unsaved changes: dirty only when a field differs from the loaded baseline', () => {
  const base = { sourceSystem: 'S', destinations: ['T1', ''], interfaceName: 'o', businessKeyRule: 'k', ordering: 'PER_KEY', sourceSchema: '' };
  assert.equal(isDirty(base, { ...base, destinations: ['T1', ''] }), false);
  assert.equal(isDirty(base, { ...base, businessKeyRule: 'k2' }), true);
  assert.equal(isDirty(base, { ...base, destinations: ['T1', 'T2'] }), true);
});

test('§3 lane B: the editor never hydrates from localStorage and never creates a flow on open', () => {
  const editor = source('FlowDefinition.tsx');
  assert.ok(!/localStorage/.test(editor), 'FlowDefinition.tsx must not read or write localStorage');
  const effects = [...editor.matchAll(/useEffect\(\(\) => \{([\s\S]*?)\}, \[/g)].map((m) => m[1]);
  assert.ok(effects.length > 0, 'the open effect exists');
  for (const body of effects) assert.ok(!/createFlow|saveFlowDraft/.test(body), 'an effect must never create or save a flow');
});

test('§3 editor keeps every data-field hook and the verbatim tenant 404 refusal', () => {
  const editor = source('FlowDefinition.tsx');
  for (const hook of ['data-field="source-system"', 'data-field={`destination-${', 'data-field="interface-name"',
    'data-field="business-key-rule"', 'data-field="ordering-setting"', 'data-field="source-schema"',
    'data-action="save-draft"', 'data-saved="true"', "errorText('business-key-rule')", 'data-validation-error={field}',
    'data-refusal="flow-not-found"', 'This flow does not exist for your tenant.']) {
    assert.ok(editor.includes(hook), `FlowDefinition.tsx keeps ${hook}`);
  }
  assert.ok(/data-confirm="unsaved-changes"/.test(editor), 'the unsaved-changes confirm is rendered in-page');
});

test('§4 Versions never creates a flow by itself; the empty state points to the editor', () => {
  const versions = source('Versions.tsx');
  assert.ok(!/createFlow/.test(versions), 'Versions.tsx must not create a flow (silent auto-create removed)');
  assert.ok(versions.includes('No flows yet'), 'empty state copy');
  assert.ok(versions.includes('data-empty="flows"'), 'empty state hook');
});

test('§4 Versions keeps every hook the screens specs use', () => {
  const versions = source('Versions.tsx');
  for (const hook of ['data-field="change-note"', 'data-flow-row=', 'data-version-status=',
    'aria-current', 'data-refusal="submitted-version-immutable"',
    'data-refusal="not-pending-approval"', 'data-refusal="publish"', 'data-published="true"',
    'data-bundle-equals-snapshot=', 'approve-as-approver']) {
    assert.ok(versions.includes(hook), `Versions.tsx keeps ${hook}`);
  }
  // single-user workflow (V2_4_1420): the author may approve, so no author-cannot-approve refusal on the screen
  assert.ok(!versions.includes('author-cannot-approve') && !versions.includes('AUTHOR_IS_APPROVER'),
    'Versions.tsx has no author-cannot-approve refusal');
});

test('§3 list landing exists and App routes #/flows to it, #/flow/<id> to the editor', () => {
  assert.ok(existsSync(join(pages, 'FlowsList.tsx')), 'FlowsList.tsx exists');
  const list = source('FlowsList.tsx');
  for (const hook of ['data-page="flows-list"', 'data-action="define-flow"', 'data-flow-link=', 'data-empty="flows"']) {
    assert.ok(list.includes(hook), `FlowsList.tsx carries ${hook}`);
  }
  const app = readFileSync(join(pages, '..', 'App.tsx'), 'utf8');
  // B11: the editor is keyed per address change, so #/flow/A -> #/flow/new (or -> #/flow/B) remounts it empty.
  assert.match(app, /route\.page === 'flows' &&\s*\(route\.flowId \?\s*\(?\s*<FlowDefinition key=\{[^}]+\}[^>]*openFlowId=\{route\.flowId\}/);
});

// Row 310 B12 (vijay-developer): Back, Forward or a typed #/ address away from the editor is decided by
// one pure helper the shell's hashchange handler calls before it routes.
test('B12 an address change away from a dirty editor asks first and puts the address back; clean or released routes', () => {
  const from = 'http://127.0.0.1:5173/#/flow/A';
  const to = 'http://127.0.0.1:5173/#/flows';
  assert.deepEqual(hashMove(from, to, null), { kind: 'route' }, 'no editor open: the router routes');
  assert.deepEqual(hashMove(from, to, { dirty: false, released: false }), { kind: 'route' }, 'a clean form navigates without a confirm');
  assert.deepEqual(hashMove(from, to, { dirty: true, released: false }), { kind: 'confirm', restore: from, leaveTo: '#/flows' });
  assert.deepEqual(hashMove(from, to, { dirty: true, released: true }), { kind: 'route' }, 'after Leave the move goes through');
  assert.deepEqual(hashMove(from, 'http://127.0.0.1:5173/#/flow/new', { dirty: true, released: false }),
    { kind: 'confirm', restore: from, leaveTo: '#/flow/new' }, 'flow A -> create mode with edits asks too');
  assert.deepEqual(hashMove(from, 'http://127.0.0.1:5173/', { dirty: true, released: false }),
    { kind: 'confirm', restore: from, leaveTo: '#/' }, 'Back to the bare console address leaves to the Dashboard route');
});

// Row 310 B13: every validation field the API can name (validationFieldOf) has visible error text,
// and the failing input is marked aria-invalid and points at that text.
test('B13 every validation field has visible error text; an unknown refusal shows the server words', () => {
  for (const field of ['source-system', 'business-key-rule', 'source-schema', 'destinations']) {
    assert.ok(validationText(field, undefined).trim().length > 0, `${field} has error text`);
  }
  assert.equal(validationText('request', 'unknown source system ZZ'), 'unknown source system ZZ');
  assert.ok(validationText('request', undefined).trim().length > 0, 'a refusal with no body still says something');
});

test('B13 the failing field is aria-invalid and described by its error; other fields are untouched', () => {
  assert.deepEqual(fieldA11y('destinations', 'destinations'), { 'aria-invalid': true, 'aria-describedby': 'flow-error-destinations' });
  assert.deepEqual(fieldA11y('business-key-rule', 'business-key-rule'),
    { 'aria-invalid': true, 'aria-describedby': 'flow-error-business-key-rule' });
  assert.deepEqual(fieldA11y('destinations', 'source-system'), {});
  assert.deepEqual(fieldA11y(null, 'source-system'), {});
});
