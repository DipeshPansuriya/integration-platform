// Row 575 (S1 of the guided flow, plan.md): the role-aware primary menu is ONE table whose role sets copy
// RolePolicy.java (READ_CONFIGURATION / READ_MESSAGE_TIMELINE / APPROVE_VERSION). UX only: the server check is the gate.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/nav-model.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const { navFor, moreFor, canOpen, showPlatformTools, platformTools, titleOf, OPERATION_ROLES, PRIMARY_NAV, LEGACY_NAV, PAGE_NEEDS, ALLOW_ALL_PAGES } = await import('../../src/nav-model.ts');
const { ROUTE_PAGES } = await import('../../src/route-types.ts');
const here = dirname(fileURLToPath(import.meta.url));
const labels = (items) => items.map((i) => i.label);
const navs = (items) => items.map((i) => i.nav);

test('navFor: item counts and entries per role (plan gate: TA 6, CF 3, AP 4, SU 3, RO 5)', () => {
  assert.deepEqual(labels(navFor(['TENANT_ADMIN'])), ['Home', 'Flows', 'Systems', 'Approvals', 'Messages', 'Reports']);
  assert.deepEqual(labels(navFor(['CONFIGURATOR'])), ['Home', 'Flows', 'Systems']);
  assert.deepEqual(labels(navFor(['APPROVER'])), ['Home', 'Flows', 'Systems', 'Approvals']);
  assert.deepEqual(labels(navFor(['SUPPORT'])), ['Home', 'Messages', 'Reports']);
  assert.deepEqual(labels(navFor(['READ_ONLY'])), ['Home', 'Flows', 'Systems', 'Messages', 'Reports']);
});

test('navFor: configurators do not see Messages in Release 1', () => {
  assert.ok(!navs(navFor(['CONFIGURATOR'])).includes('messages'));
});

test('navFor: roles NOT LOADED YET (null, undefined) show all six - the server 403 stays the gate', () => {
  for (const roles of [null, undefined]) assert.equal(navFor(roles).length, 6);
});

test('navFor: roles LOADED as an empty list (no grants) see Home only, never the full menu (Varun blocker, row 575)', () => {
  assert.deepEqual(labels(navFor([])), ['Home']);
  assert.deepEqual(navs(moreFor([])), []);
  assert.equal(canOpen([], 'flows'), false);
  assert.equal(canOpen([], 'messages'), false);
  assert.equal(canOpen([], 'home'), true);
  assert.equal(showPlatformTools([]), false);
});

test('navFor: several roles get the union; an unrecognised role name alone gets Home only', () => {
  assert.deepEqual(labels(navFor(['CONFIGURATOR', 'SUPPORT'])), ['Home', 'Flows', 'Systems', 'Messages', 'Reports']);
  assert.deepEqual(labels(navFor(['SOMETHING_NEW'])), ['Home']);
});

test('the six primary items keep the spec data-nav values and hash routes', () => {
  assert.deepEqual(PRIMARY_NAV.map((i) => [i.nav, i.hash]), [
    ['home', '#/'], ['flows', '#/flows'], ['systems', '#/systems'],
    ['approvals', '#/approvals'], ['messages', '#/messages'], ['reports', '#/reports'],
  ]);
});

test('each item\'s role set equals the RolePolicy operation it depends on', () => {
  const java = readFileSync(join(here, '..', '..', '..', 'platform-tenancy', 'src', 'main', 'java', 'io', 'integrationplatform', 'tenancy', 'RolePolicy.java'), 'utf8');
  for (const op of ['READ_CONFIGURATION', 'READ_MESSAGE_TIMELINE', 'APPROVE_VERSION']) {
    const line = new RegExp(String.raw`^\s*${op}\(Caller\.ROLE,([^)]*)\)`, 'm').exec(java);
    assert.ok(line, `${op} must still be declared in RolePolicy.java`);
    const fromJava = line[1].split(',').map((s) => s.trim()).sort();
    assert.deepEqual([...OPERATION_ROLES[op]].sort(), fromJava, `${op} role set drifted from RolePolicy.java`);
  }
});

test('moreFor: the temporary legacy group keeps every data-nav and is role-filtered', () => {
  assert.deepEqual(navs(moreFor(['TENANT_ADMIN'])), ['queue', 'failures', 'activity', 'versions', 'contracts', 'rules', 'mappings']);
  assert.deepEqual(navs(moreFor(['CONFIGURATOR'])), ['versions', 'contracts', 'rules', 'mappings']);
  assert.deepEqual(navs(moreFor(['SUPPORT'])), ['queue', 'failures', 'activity']);
  assert.deepEqual(navs(moreFor(['APPROVER'])), ['versions', 'contracts', 'rules', 'mappings']);
  assert.equal(moreFor(null).length, 7);
});

test('no data-nav is shown twice (primary vs legacy group)', () => {
  const all = [...navFor(null), ...moreFor(null)].map((i) => i.nav);
  assert.equal(new Set(all).size, all.length);
});

test('canOpen: a hidden screen is refused by role, an unknown role list opens everything', () => {
  assert.equal(canOpen(['SUPPORT'], 'flows'), false);
  assert.equal(canOpen(['SUPPORT'], 'systems'), false);
  assert.equal(canOpen(['SUPPORT'], 'failures'), true);
  assert.equal(canOpen(['CONFIGURATOR'], 'messages'), false);
  assert.equal(canOpen(['CONFIGURATOR'], 'queue'), false);
  // Mira, 28-Oct (row 575): Approvals is open only to Approver and Tenant admin - the menu and canOpen agree.
  assert.equal(canOpen(['CONFIGURATOR'], 'approvals'), false);
  assert.equal(canOpen(['READ_ONLY'], 'approvals'), false);
  assert.equal(canOpen(['APPROVER'], 'approvals'), true);
  assert.equal(canOpen(['TENANT_ADMIN'], 'approvals'), true);
  assert.equal(canOpen(['READ_ONLY'], 'home'), true);
  assert.equal(canOpen(['SUPPORT'], 'home'), true);
  assert.equal(canOpen(null, 'flows'), true);
});

test('Keycloak and Kafka UI are tenant-admin only; unknown roles do not get them', () => {
  assert.equal(showPlatformTools(['TENANT_ADMIN']), true);
  for (const roles of [['CONFIGURATOR'], ['APPROVER'], ['SUPPORT'], ['READ_ONLY'], null, []]) assert.equal(showPlatformTools(roles), false);
});

test('platformTools: local and hosted addresses keep the previous targets', () => {
  assert.deepEqual(platformTools('localhost', 'http:').map((t) => [t.nav, t.href]), [
    ['keycloak', 'http://localhost:8180/admin/master/console/'], ['kafbat', 'http://localhost:8090'],
  ]);
  assert.deepEqual(platformTools('app.example.test', 'https:').map((t) => [t.nav, t.href]), [
    ['keycloak', 'https://auth.example.test/admin/master/console/'], ['kafbat', 'https://ops.example.test/kafka/'],
  ]);
});

test('App.tsx: builds the menu from the table, no 15-entry NAV_GROUPS, tools in the account menu', () => {
  const app = readFileSync(join(here, '..', '..', 'src', 'App.tsx'), 'utf8');
  assert.ok(!app.includes('NAV_GROUPS'), 'the old group table is gone');
  assert.ok(app.includes('navFor('), 'App.tsx must use navFor');
  assert.ok(app.includes('showPlatformTools('), 'platform tools are gated by role');
  assert.ok(app.includes('data-no-access') || app.includes('<NoAccess'), 'a refused screen shows NoAccess');
});

test('every route page is mapped to a role need or listed as allow-all - a new page is never open by default', () => {
  const mapped = Object.keys(PAGE_NEEDS);
  for (const page of ROUTE_PAGES) {
    assert.ok(mapped.includes(page) || ALLOW_ALL_PAGES.includes(page), `route page "${page}" must be in PAGE_NEEDS or ALLOW_ALL_PAGES`);
  }
  assert.deepEqual([...ALLOW_ALL_PAGES].sort(), ['home', 'login']);
  assert.equal(canOpen(['SUPPORT'], 'not-a-page'), false, 'an unmapped page is refused for known roles');
});

test('every menu item points at a mapped page whose need equals the item need', () => {
  for (const item of [...PRIMARY_NAV, ...LEGACY_NAV]) {
    if (item.page === 'home') continue;
    assert.equal(PAGE_NEEDS[item.page], item.needs, `${item.nav}: menu need and canOpen need must match`);
  }
});

test('data-initial marks are unique across the menu (collapsed rail)', () => {
  const marks = [...PRIMARY_NAV, ...LEGACY_NAV].map((i) => i.initial);
  assert.equal(new Set(marks).size, marks.length, marks.join(','));
});

test('titleOf names the screen for the NoAccess heading', () => {
  assert.equal(titleOf('flows'), 'Flows');
  assert.equal(titleOf('messages'), 'Messages');
  assert.equal(titleOf('home'), 'Home');
});
