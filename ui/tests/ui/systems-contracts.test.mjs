// Row 310 lane C (rohit-developer, T101): Systems worklist + the shared 480px Drawer + the
// test-connection result in its row cell; Contracts grouped list, side-by-side compare panes and
// the import form inside the same Drawer (10-screens §5/§6, 20-build-plan s3 row C + s5).
// Pure helpers (ui/drawer-keys.ts, systems/systems-view.ts, contracts/contracts-view.ts) are exercised
// directly; the page contracts the screens specs key off (data-* hooks, verbatim refusals,
// aria-labels, drawer semantics) are read from source.
// Run: node --test tests/ui/systems-contracts.test.mjs   (helpers are imported with their .ts extension)

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const src = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src');
const read = (path) => (existsSync(join(src, path)) ? readFileSync(join(src, path), 'utf8') : '');
const load = async (path) => (existsSync(join(src, path)) ? import(`../../src/${path}`) : {});

const drawer = await load('ui/drawer-keys.ts');
const systemsView = await load('pages/systems/systems-view.ts');
const contractsView = await load('pages/contracts/contracts-view.ts');

// ---------- the shared Drawer ----------

test('C drawer: one shared component, 480px, built in ui/ and used by both screens', () => {
  const component = read('ui/Drawer.tsx');
  assert.ok(component, 'ui/src/ui/Drawer.tsx exists');
  assert.equal(drawer.DRAWER_WIDTH_PX, 480);
  assert.match(read('ui/drawer.css'), /480px/);
  assert.match(read('pages/Systems.tsx'), /from '\.\.\/ui\/Drawer'/);
  assert.match(read('pages/ContractVersions.tsx'), /from '\.\.\/ui\/Drawer'/);
});

test('C drawer: role=dialog + aria-modal=true, labelled by its title', () => {
  const component = read('ui/Drawer.tsx');
  assert.match(component, /role="dialog"/);
  assert.match(component, /aria-modal="true"/);
  assert.match(component, /aria-labelledby=\{/);
});

test('C drawer: closed by default - renders nothing until open', () => {
  assert.match(read('ui/Drawer.tsx'), /if \(!open\) return null;/);
});

test('C drawer: Esc closes, Tab is kept inside, other keys pass through', () => {
  assert.equal(drawer.drawerKeyAction?.('Escape'), 'close');
  assert.equal(drawer.drawerKeyAction?.('Esc'), 'close');
  assert.equal(drawer.drawerKeyAction?.('Tab'), 'trap');
  assert.equal(drawer.drawerKeyAction?.('a'), null);
  assert.equal(drawer.drawerKeyAction?.('Enter'), null);
});

test('C drawer: focus trap wraps at both ends', () => {
  assert.equal(drawer.nextFocusIndex?.(3, 2, false), 0, 'Tab on the last goes to the first');
  assert.equal(drawer.nextFocusIndex?.(3, 0, true), 2, 'Shift+Tab on the first goes to the last');
  assert.equal(drawer.nextFocusIndex?.(3, 1, false), 2);
  assert.equal(drawer.nextFocusIndex?.(3, -1, false), 0, 'focus outside the list starts at the first');
  assert.equal(drawer.nextFocusIndex?.(0, 0, false), -1, 'nothing focusable: no move');
});

test('C drawer: focus moves in on open and is restored to the opener on close', () => {
  const component = read('ui/Drawer.tsx');
  assert.match(component, /document\.activeElement/, 'remembers the element that had focus');
  assert.match(component, /opener\.focus\(\)/, 'restores focus to it on close');
  assert.match(component, /onKeyDown=\{/, 'keyboard handler on the panel');
});

// ---------- Systems ----------

test('§5 systems: register drawer closed by default; one primary opens it', () => {
  const page = read('pages/Systems.tsx');
  assert.match(page, /useState\(false\)/, 'register drawer state starts closed');
  assert.match(page, /data-action="open-register"/);
  assert.match(page, /<Drawer/);
  assert.equal((page.match(/btn-primary/g) ?? []).length, 1, 'Register system is the single primary');
});

test('§5 systems: drawer mode - an open id edits, otherwise register when asked, else closed', () => {
  assert.equal(systemsView.drawerMode?.('id-1', false), 'edit');
  assert.equal(systemsView.drawerMode?.('id-1', true), 'edit', 'Open replaces the drawer content in place');
  assert.equal(systemsView.drawerMode?.(null, true), 'register');
  assert.equal(systemsView.drawerMode?.(null, false), null);
});

test('§5 systems: the test-connection result sits in the row cell, role=status, row-named button', () => {
  const page = read('pages/Systems.tsx');
  const row = page.slice(page.indexOf('data-system-row='), page.indexOf('</tr>', page.indexOf('data-system-row=')));
  assert.match(row, /data-test-connection-result=/, 'the answer renders inside the row');
  assert.match(row, /role="status"/, 'the answer is a non-blocking status');
  assert.match(row, /data-refusal=\{`test-connection-refused-/, 'a refused test renders inside the row');
  assert.match(row, /aria-label=\{`Test connection for \$\{system\.code\}`\}/);
});

test('§5 systems: test cell words - result word is the carrier, 429 worded, never colour alone', () => {
  const ok = systemsView.testCell?.({ kind: 'answer', result: 'SUCCESS', message: null });
  assert.deepEqual(ok, { tone: 'success', word: 'SUCCESS', detail: '' });
  const refused = systemsView.testCell?.({ kind: 'answer', result: 'REFUSED', message: 'destination not allowed' });
  assert.deepEqual(refused, { tone: 'danger', word: 'REFUSED', detail: 'destination not allowed' });
  const limited = systemsView.testCell?.({ kind: 'refusal', status: 429, reason: 'TEST_CONNECTION_RATE_LIMITED' });
  assert.equal(limited?.tone, 'danger');
  assert.match(limited?.word ?? '', /Too many connection tests, wait a moment/);
  assert.match(limited?.detail ?? '', /TEST_CONNECTION_RATE_LIMITED/);
  const forbidden = systemsView.testCell?.({ kind: 'refusal', status: 403, reason: '' });
  assert.match(forbidden?.word ?? '', /HTTP 403/);
});

test('§5 systems: filter over code, name and type, case-insensitive; blank keeps all', () => {
  const rows = [
    { id: '1', code: 'CRM_MAIN', name: 'Customer', direction: 'OUTBOUND', type: 'API', settings: null },
    { id: '2', code: 'ERP', name: 'Ledger', direction: 'INBOUND', type: 'DB', settings: null }
  ];
  assert.equal(systemsView.filterSystems?.(rows, '')?.length, 2);
  assert.deepEqual(systemsView.filterSystems?.(rows, 'crm')?.map((r) => r.id), ['1']);
  assert.deepEqual(systemsView.filterSystems?.(rows, 'ledger')?.map((r) => r.id), ['2']);
  assert.deepEqual(systemsView.filterSystems?.(rows, ' db ')?.map((r) => r.id), ['2']);
});

test('§5 systems: settings summary kept as one joined string (shown truncated with a tooltip)', () => {
  assert.equal(systemsView.settingsSummary?.(null), '-');
  assert.equal(systemsView.settingsSummary?.({ host: 'h', port: 1 }), 'host: h, port: 1');
  assert.match(read('pages/Systems.tsx'), /title=\{summary\}/);
});

test('§5 systems: every validation error gets an anchor to its field', () => {
  assert.equal(systemsView.errorAnchor?.('settings.baseUrl is required for type API'), 'sys-setting-baseUrl');
  assert.equal(systemsView.errorAnchor?.('name is required'), 'sys-field-name');
  assert.equal(systemsView.errorAnchor?.('type FTP is not one of API, DB, MQ, FILE'), 'sys-field-type');
  assert.equal(systemsView.errorAnchor?.('The API refused the save (HTTP 500).'), null);
  const fields = read('pages/systems/SystemFields.tsx');
  assert.match(fields, /href=\{`#\$\{anchor\}`\}/, 'the anchor links to the field');
  assert.match(fields, /data-validation-error=\{index\}/, 'hook kept');
});

test('§5 systems: 404 refusal kept verbatim (tenant isolation) and every hook kept', () => {
  const page = read('pages/Systems.tsx');
  assert.match(page, /data-refusal="system-not-found"/);
  assert.match(page, /This system does not exist for your tenant\./);
  for (const hook of ['data-page="systems"', 'data-systems-loaded=', 'data-systems-table', 'data-system-row=', 'data-system-type=',
    'data-action="open-system"', 'data-action="test-connection"', 'data-systems-empty']) {
    assert.ok(page.includes(hook), `Systems.tsx keeps ${hook}`);
  }
  const forms = read('pages/systems/RegisterSystemForm.tsx') + read('pages/systems/EditSystemForm.tsx');
  for (const hook of ['data-form="register-system"', 'data-form="edit-system"', 'data-action="register-system"',
    'data-action="save-system"', 'data-saved="true"', 'data-field="system-code"', 'data-field="system-direction"']) {
    assert.ok(forms.includes(hook), `system forms keep ${hook}`);
  }
});

// ---------- Contracts ----------

test('§6 contracts: grouped list - one card per system/role, sorted, versions kept in order', () => {
  const contracts = [
    { contractId: 'b', system: 'TGT_B', role: 'TARGET_REQUEST', name: 'B', versions: [{ id: 'b1' }] },
    { contractId: 'a2', system: 'TGT_A', role: 'TARGET_RESPONSE', name: 'A resp', versions: [] },
    { contractId: 'a1', system: 'TGT_A', role: 'TARGET_REQUEST', name: 'A', versions: [{ id: 'a1-1' }, { id: 'a1-2' }] }
  ];
  const groups = contractsView.contractGroups?.(contracts) ?? [];
  assert.deepEqual(groups.map((g) => g.key), ['TGT_A/TARGET_REQUEST', 'TGT_A/TARGET_RESPONSE', 'TGT_B/TARGET_REQUEST']);
  assert.deepEqual(groups[0].contract.versions.map((v) => v.id), ['a1-1', 'a1-2']);
});

test('§6 contracts: status badge carries its word, tone repeats it', () => {
  assert.deepEqual(contractsView.statusBadge?.('DRAFT'), { tone: 'warn', word: 'Draft' });
  assert.deepEqual(contractsView.statusBadge?.('PENDING_APPROVAL'), { tone: 'info', word: 'Pending approval' });
  assert.deepEqual(contractsView.statusBadge?.('APPROVED'), { tone: 'success', word: 'Approved' });
  assert.deepEqual(contractsView.statusBadge?.('RETIRED'), { tone: 'neutral', word: 'RETIRED' });
});

test('§6 compare panes: fields changed between the two versions are marked with a word', () => {
  const a = [{ name: 'id', type: 'string', required: true }, { name: 'qty', type: 'integer', required: false }, { name: 'old', type: 'string', required: false }];
  const b = [{ name: 'id', type: 'string', required: true }, { name: 'qty', type: 'number', required: false }, { name: 'new', type: 'string', required: true }];
  assert.deepEqual(contractsView.fieldDiff?.(a, b), { qty: 'changed', old: 'only-here' });
  assert.deepEqual(contractsView.fieldDiff?.(b, a), { qty: 'changed', new: 'only-here' });
  assert.deepEqual(contractsView.fieldDiff?.(a, undefined), {}, 'one pane alone: nothing to compare');
  const panes = read('pages/contracts/SideBySide.tsx');
  assert.match(panes, /data-field-diff=/);
  assert.match(panes, /<caption/, 'pane tables keep captions');
  assert.match(panes, /MAX_COMPARED|compared\.map/);
});

test('§6 contracts: import lives in the shared Drawer behind one primary action', () => {
  const page = read('pages/ContractVersions.tsx');
  assert.match(page, /data-action="open-import"/);
  assert.match(page, /Import a contract version/);
  const drawerAt = page.indexOf('<Drawer');
  assert.ok(drawerAt > 0 && page.indexOf('<ImportContractForm') > drawerAt, 'the import form renders inside the Drawer');
  assert.equal((page.match(/btn-primary/g) ?? []).length, 1, 'Import is the screen\'s single primary');
});

test('§6 contracts: parser errors - every line, verbatim wording, inside the danger alert', () => {
  const form = read('pages/contracts/ImportContractForm.tsx');
  assert.ok(form.includes('<p>Refused: {refusal.error} Nothing was stored.</p>'), 'refusal sentence verbatim');
  assert.ok(form.includes('data-refusal="import-refused"'));
  assert.ok(form.includes('refusal.parserErrors.map((line, index)'), 'every parser line rendered');
  assert.ok(form.includes('data-parser-error={index}'));
  assert.ok(form.includes('{line}'), 'the line is rendered as the parser wrote it');
  assert.ok(form.includes('(result.errors ?? []).filter((line) => line !== result.error)'), 'no line dropped or rewritten');
});

test('§6 contracts: aria-labels kept verbatim; the change note moves behind its action', () => {
  const list = read('pages/contracts/ContractList.tsx');
  assert.ok(list.includes('aria-label={`Compare ${key}`}'));
  assert.ok(list.includes('aria-label={`Change note for ${key}`}'));
  assert.ok(list.includes('data-action="compose-submit"'), 'one action reveals the note group');
  assert.ok(list.includes('aria-expanded={composing}'));
  assert.ok(/composing && \(/.test(list), 'the note field renders only while composing');
});

test('§6 contracts: refusals keep their words and render at the top of the affected card', () => {
  const list = read('pages/contracts/ContractList.tsx') + read('pages/ContractVersions.tsx');
  assert.ok(list.includes('Refused: only a submitted version can be approved (NOT_PENDING_APPROVAL).'));
  // single-user workflow (V2_4_1420): the author may approve, so the screen has no author-cannot-approve refusal
  assert.ok(!list.includes('AUTHOR_IS_APPROVER'), 'no AUTHOR_IS_APPROVER branch');
  assert.ok(!list.includes('author-cannot-approve'), 'no author-cannot-approve alert');
  const cardList = read('pages/contracts/ContractList.tsx');
  assert.match(cardList, /refusal\?\.contractKey === group\.key/, 'the refusal renders in its own card');
});

// ---------- floors ----------

test('floors: lane css uses tokens only (no hex, rgb or hsl), 2px focus-visible ring, 12.5px floor', () => {
  for (const file of ['ui/drawer.css', 'pages/systems/systems.css', 'pages/contracts/contracts.css']) {
    const css = read(file);
    assert.ok(css, `${file} exists`);
    assert.ok(!/#[0-9a-fA-F]{3,8}\b|rgba?\(|hsla?\(/.test(css), `${file}: every colour is a --color-* token`);
    const sizes = [...css.matchAll(/font-size:\s*([\d.]+)px/g)].map((m) => Number(m[1]));
    assert.ok(sizes.every((size) => size >= 12.5), `${file}: 12.5px type floor (${sizes.join(',')})`);
  }
  assert.match(read('ui/drawer.css'), /:focus-visible[^}]*outline:\s*2px solid var\(--color-focus\)/s);
  assert.match(read('ui/drawer.css'), /prefers-reduced-motion/);
});

test('floors: no inline style colours left in the lane TSX', () => {
  for (const file of ['ui/Drawer.tsx', 'pages/Systems.tsx', 'pages/ContractVersions.tsx', 'pages/contracts/SideBySide.tsx', 'pages/contracts/ContractList.tsx']) {
    assert.ok(!/style=\{\{/.test(read(file)), `${file}: layout lives in the lane css, not inline styles`);
  }
});
