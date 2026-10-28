// Lane A round 2 (B27): audit A10 (Dashboard user + deep links + Retry keeps focus, FlowsList Retry),
// A11 (Login pending, filter-count announcement, Ordering in words), A7 leftovers (publish note, sticky
// panel), mapping Save network throw. Pure helpers are called directly; page contracts are read from source.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/lane-a-polish.test.mjs

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const src = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src');
const read = (rel) => readFileSync(join(src, rel), 'utf8');
const { orderingWord, initialSelection, filterCountText } = await import('../../src/pages/flow-lifecycle.ts');
const { retryBannerShown } = await import('../../src/pages/dashboard-counts.ts');
const { saveThrownText } = await import('../../src/pages/mapping-load.ts');

test('A11: Ordering shows words, not the raw enum', () => {
  assert.equal(orderingWord('PER_KEY'), 'Per key');
  assert.equal(orderingWord('NONE'), 'None');
  assert.equal(orderingWord('SOMETHING'), 'SOMETHING');
});

test('A11: the filter result count is a sentence a live region can announce', () => {
  assert.equal(filterCountText(3, 10, 'ord'), '3 of 10 flows match.');
  assert.equal(filterCountText(1, 10, 'ord'), '1 of 10 flows matches.');
  assert.equal(filterCountText(0, 10, 'zzz'), 'No flow matches the filter.');
  assert.equal(filterCountText(10, 10, ''), '');
});

test('A10: a deep link selects that version, else the first draft, else the first row', () => {
  const rows = [{ id: 'a', status: 'PUBLISHED' }, { id: 'b', status: 'DRAFT' }, { id: 'c', status: 'APPROVED' }];
  assert.equal(initialSelection(rows, 'c').id, 'c');
  assert.equal(initialSelection(rows, 'nope').id, 'b');
  assert.equal(initialSelection(rows, null).id, 'b');
  assert.equal(initialSelection([{ id: 'a', status: 'PUBLISHED' }], null).id, 'a');
  assert.equal(initialSelection([], 'a'), undefined);
});

test('A10: the Dashboard banner stays up while a retry is running (focus is not dropped)', () => {
  assert.equal(retryBannerShown(true, false), true);
  assert.equal(retryBannerShown(false, true), true);
  assert.equal(retryBannerShown(false, false), false);
});

test('A10 wiring: deep links, aria-disabled retry, FlowsList Retry', () => {
  const app = read('App.tsx');
  // /me carries only a uuid userId (B10, dashboard-counts.test.mjs): the greeting stays neutral until the API returns a display name.
  assert.doesNotMatch(app, /user=\{\s*me\??\.userId/);
  assert.ok(app.includes('^versions\\/(.+)$'), 'the #/versions/<id> deep link is parsed');
  assert.match(app, /<Versions selectId=/);
  const dash = read('pages/Dashboard.tsx');
  assert.match(dash, /href=\{`#\/versions\/\$\{flow\.id\}`\}/);
  assert.match(dash, /retryBannerShown\(/);
  assert.match(dash, /aria-disabled=\{retrying\}/);
  const list = read('pages/FlowsList.tsx');
  assert.match(list, /data-action="retry-load"/);
  assert.doesNotMatch(list, /Reload the page to try again/);
  assert.match(list, /data-live-region/);
  assert.match(list, /orderingWord\(/);
});

test('A11 wiring: Login announces the redirect to sign-in (row 571: automatic, no SSO click)', () => {  const login = read('pages/Login.tsx');  assert.match(login, /Redirecting to sign-in/);  assert.match(login, /role="status"/);});

test('A7 wiring: publish carries a note saying what it does, the panel is sticky', () => {
  const versions = read('pages/Versions.tsx');
  assert.match(versions, /data-publish-note/);
  assert.match(versions, /aria-describedby="versions-publish-note"/);
  assert.match(read('pages/flows.css'), /\.versions-panel\s*\{[^}]*position:\s*sticky/);
});

test('mapping Save: a thrown network error is a worded failure and the PUT sits in a try', () => {
  assert.match(saveThrownText(), /^Could not save the mapping.*reached/);
  const page = read('pages/MappingDesigner.tsx');
  assert.match(page, /try \{\s*\n\s*const \{ data, error \} = await api\.PUT/);
  assert.match(page, /saveThrownText\(\)/);
});
