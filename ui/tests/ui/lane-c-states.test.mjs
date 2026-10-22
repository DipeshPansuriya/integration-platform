// B29 lane C (audit C1, C2, C3, C6): Systems / Contracts / Drawer states and plain words.
// Pure helpers are imported; the page contracts are read from source (screens specs cannot run here).
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/lane-c-states.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const src = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src');
const read = (p) => readFileSync(join(src, p), 'utf8');
const { refusalWords } = await import('../../src/ui/refusal-words.ts');
const systemsView = await import('../../src/pages/systems/systems-view.ts');
const contractsView = await import('../../src/pages/contracts/contracts-view.ts');
const keys = await import('../../src/ui/drawer-keys.ts');

test('C1/C2 plain-language refusals: no raw HTTP code, no internal token', () => {
  for (const status of [401, 403, 404, 429, 500, 503, 418]) {
    const words = refusalWords(status, 'the systems list');
    assert.ok(words.length > 20, String(status));
    assert.doesNotMatch(words, /HTTP|\b[45]\d\d\b|refused-/, `status ${status} stays out of the sentence`);
  }
  assert.match(refusalWords(403, 'the systems list'), /permission/);
  assert.match(refusalWords(500, 'the systems list'), /Try again/);
  assert.match(refusalWords(429, 'x'), /wait/i);
});

test('C1 systems: the pending test state blocks parallel clicks and says Testing', () => {
  assert.equal(systemsView.TESTING_WORD, 'Testing...');
  assert.equal(systemsView.canStartTest(new Set(), 'a'), true);
  assert.equal(systemsView.canStartTest(new Set(['a']), 'a'), false);
  assert.equal(systemsView.canStartTest(new Set(['a']), 'b'), true);
  const page = read('pages/Systems.tsx');
  assert.match(page, /canStartTest\(/);
  assert.match(page, /disabled=\{testing\.has\(system\.id\)\}/);
  assert.match(page, /TESTING_WORD/);
  assert.match(page, /data-test-connection-pending/);
  assert.match(page, /data-action="test-connection"/, 'hook kept');
});

test('C1 systems: loading state, empty state with a Register action, no header-only table, plain refusals', () => {
  const page = read('pages/Systems.tsx');
  assert.match(page, /data-systems-loading/);
  assert.match(page, /Loading systems/);
  assert.match(page, /data-systems-empty/);
  assert.match(page, /data-action="register-from-empty"/);
  assert.match(page, /\{systems\.length > 0 && \(\s*<div className="systems-grid">/, 'the table renders only with rows');
  assert.match(page, /refusalWords\(listRefusal/);
  assert.match(page, /refusalWords\(/);
  assert.doesNotMatch(page, /refused the systems list \(HTTP/);
  assert.doesNotMatch(page, /refused opening this system \(\{openRefusal\}\)/);
  assert.match(page, /data-refusal=\{`list-refused-\$\{listRefusal\}`\}/, 'hook kept');
});

test('C2 contracts: row-specific accessible names and a confirm step before Approve', () => {
  assert.equal(contractsView.rowActionLabel('Approve', 'CRM/TARGET_REQUEST/1.0'), 'Approve CRM/TARGET_REQUEST/1.0');
  const list = read('pages/contracts/ContractList.tsx');
  assert.match(list, /data-action="approve-version"/);
  assert.match(list, /data-action="confirm-approve"/);
  assert.match(list, /Approve this version\?/);
  assert.match(list, /rowActionLabel\('Approve', key\)/);
  assert.match(list, /rowActionLabel\('Submit for approval', key\)/);
  assert.match(list, /data-action="approve-as-approver"/, 'harness approver hook kept');
});

test('C2 contracts: empty copy, loading, list refusal words, import system-list failure, checkbox size, pane error', () => {
  const page = read('pages/ContractVersions.tsx');
  assert.match(page, /Import a contract version to start comparing/);
  assert.match(page, /data-action="import-from-empty"/);
  assert.match(page, /data-contracts-empty/);
  assert.match(page, /data-contracts-loading/);
  assert.match(page, /refusalWords\(listRefusal/);
  const form = read('pages/contracts/ImportContractForm.tsx');
  assert.match(form, /data-refusal="systems-list-failed"/);
  assert.match(form, /roleWord\(value\)/);
  assert.match(form, /value=\{value\}/, 'option values (API enums) unchanged');
  assert.equal(contractsView.roleWord('TARGET_REQUEST'), 'Target request');
  assert.equal(contractsView.roleWord('SOURCE_RESPONSE'), 'Source response');
  assert.equal(contractsView.roleWord('ODD'), 'ODD');
  const css = read('pages/contracts/contracts.css');
  assert.match(css, /\.contracts-check input\s*\{[^}]*width:\s*28px[^}]*height:\s*28px/s);
  assert.match(css, /\.contracts-pane-error/);
  const list = read('pages/contracts/ContractList.tsx');
  assert.match(list, /<label className="contracts-check-label"/, 'the checkbox sits in a wrapping label');
  const pane = read('pages/contracts/SideBySide.tsx');
  assert.match(pane, /className="contracts-pane-error"/);
  assert.match(pane, /refusalWords\(loaded\.refused/);
  assert.match(pane, /data-compare-loading/);
  assert.doesNotMatch(pane, /\{version\.status\}/, 'raw PENDING_APPROVAL enum not printed');
  assert.match(pane, /data-compare-status/);
});

test('C3 drawer: a dirty form asks before Esc / scrim / Close discards it', () => {
  assert.equal(keys.closeAction(false), 'close');
  assert.equal(keys.closeAction(true), 'confirm');
  const d = read('ui/Drawer.tsx');
  assert.match(d, /Discard changes\?/);
  assert.match(d, /closeAction\(/);
  assert.match(d, /data-action="discard-changes"/);
  assert.match(d, /data-action="keep-editing"/);
  assert.match(d, /onInput=/);
  assert.doesNotMatch(d, /onSubmit=/, 'row 372 M4: a submit event no longer clears the guard; useDrawerSaved does');
  assert.match(d, /useDrawerSaved/);
  assert.match(d, /data-action="close-drawer"/, 'hook kept');
  assert.match(read('ui/drawer.css'), /\.drawer-discard/);
});
