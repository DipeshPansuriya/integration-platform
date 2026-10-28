// Row 575 S6 (guided flow, plan S6): the Reports hub (Queue | Daily delivery | Activity) and the Systems tabs (Systems | Contracts).
// Tabs are real links with aria-current="page"; the legacy routes and their data-nav values (queue, activity, contracts) stay, now on
// the tabs; the S1 "More" group loses those entries; the pages inside are unchanged. The primary items Reports and Systems keep
// their own data-nav, so a tab for the same route carries none (no duplicate selector on a page).
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/section-tabs.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { findAll, mount } from './hook-harness.mjs';

const { sectionTabs, sectionOf, REPORT_TABS, SYSTEMS_TABS } = await import('../../src/section-tabs.ts');
const { moreFor, navFor, titleOf, canOpen } = await import('../../src/nav-model.ts');
const { ROUTE_PAGES } = await import('../../src/route-types.ts');

const read = (p) => readFileSync(fileURLToPath(new URL(`../../src/${p}`, import.meta.url)), 'utf8');

test('reports hub: Queue, Daily delivery, Activity in that order, each a link to its legacy hash', () => {
  assert.deepEqual(REPORT_TABS.map((t) => [t.label, t.href]), [['Queue', '#/queue'], ['Daily delivery', '#/reports'], ['Activity', '#/activity']]);
});

test('systems tabs: Systems and Contracts, each a link to its legacy hash', () => {
  assert.deepEqual(SYSTEMS_TABS.map((t) => [t.label, t.href]), [['Systems', '#/systems'], ['Contracts', '#/contracts']]);
});

test('data-nav stays on the tabs for queue, activity and contracts; the tabs for reports and systems carry none (the menu item owns it)', () => {
  assert.deepEqual(REPORT_TABS.map((t) => t.nav ?? null), ['queue', null, 'activity']);
  assert.deepEqual(SYSTEMS_TABS.map((t) => t.nav ?? null), [null, 'contracts']);
});

test('sectionTabs: exactly one tab is current on each page, and only on hub pages', () => {
  for (const [page, current] of [['queue', 'Queue'], ['reports', 'Daily delivery'], ['activity', 'Activity'], ['systems', 'Systems'], ['contracts', 'Contracts']]) {
    const section = sectionTabs(page);
    assert.ok(section, page);
    const marked = section.tabs.filter((t) => t.current);
    assert.deepEqual(marked.map((t) => t.label), [current], page);
  }
  for (const page of ['home', 'flows', 'versions', 'messages', 'failures', 'rules', 'mappings', 'approvals', 'login']) assert.equal(sectionTabs(page), null, page);
});

test('sectionOf: the menu item that owns each page', () => {
  assert.deepEqual(['queue', 'activity', 'reports'].map(sectionOf), ['reports', 'reports', 'reports']);
  assert.deepEqual(['systems', 'contracts'].map(sectionOf), ['systems', 'systems']);
  assert.equal(sectionOf('flows'), null);
});

test('the S1 More group loses queue, activity and contracts; the other legacy entries stay', () => {
  assert.deepEqual(moreFor(['TENANT_ADMIN']).map((i) => i.nav), ['failures', 'versions', 'rules', 'mappings']);
  assert.deepEqual(moreFor(['SUPPORT']).map((i) => i.nav), ['failures']);
  assert.deepEqual(moreFor(['CONFIGURATOR']).map((i) => i.nav), ['versions', 'rules', 'mappings']);
});

test('every data-nav the specs click still has exactly one home: menu or tab, never both', () => {
  const menu = [...navFor(null), ...moreFor(null)].map((i) => i.nav);
  const tabs = [...REPORT_TABS, ...SYSTEMS_TABS].map((t) => t.nav).filter(Boolean);
  for (const nav of ['queue', 'activity', 'contracts']) assert.ok(!menu.includes(nav) && tabs.includes(nav), nav);
  for (const nav of ['reports', 'systems']) assert.ok(menu.includes(nav) && !tabs.includes(nav), nav);
});

test('roles are unchanged: the pages the tabs reach need the same role as before, and titles still resolve for NoAccess', () => {
  assert.equal(canOpen(['SUPPORT'], 'queue'), true);
  assert.equal(canOpen(['CONFIGURATOR'], 'queue'), false);
  assert.equal(canOpen(['CONFIGURATOR'], 'contracts'), true);
  assert.equal(canOpen(['SUPPORT'], 'contracts'), false);
  assert.equal(titleOf('queue'), 'Queue');
  assert.equal(titleOf('activity'), 'Activity');
  assert.equal(titleOf('contracts'), 'Contracts');
  for (const page of ROUTE_PAGES) assert.notEqual(titleOf(page), '', page);
});

// ---- the component -----------------------------------------------------------------------------------------------------
const where = (name, value) => (n) => n.props?.[name] !== undefined && (value === undefined || n.props[name] === value);

test('SectionTabs renders a labelled nav of links with aria-current="page" on the current tab', async () => {
  const screen = await mount('ui/SectionTabs.tsx', 'SectionTabs', { page: 'activity' }, {}, []);
  await screen.settle();
  const [nav] = findAll(screen.tree(), (n) => n.type === 'nav');
  assert.equal(nav.props['aria-label'], 'Reports sections');
  const links = findAll(nav, (n) => n.type === 'a');
  assert.deepEqual(links.map((a) => a.props.href), ['#/queue', '#/reports', '#/activity']);
  assert.deepEqual(links.map((a) => a.props['aria-current'] ?? null), [null, null, 'page']);
  assert.deepEqual(links.map((a) => a.props['data-nav'] ?? null), ['queue', null, 'activity']);
});

test('SectionTabs renders nothing outside a hub page', async () => {
  const screen = await mount('ui/SectionTabs.tsx', 'SectionTabs', { page: 'flows' }, {}, []);
  await screen.settle();
  assert.equal(findAll(screen.tree(), (n) => n.type === 'nav').length, 0);
});

// ---- the shell ---------------------------------------------------------------------------------------------------------
test('App.tsx puts the tabs above the unchanged pages and highlights the owning menu item', () => {
  const app = read('App.tsx');
  assert.ok(app.includes('<SectionTabs page={route.page}'));
  assert.ok(app.includes('sectionOf(route.page)'));
  for (const page of ['QueueDashboard', 'Activity', 'DeliveryReport', 'Systems', 'ContractVersions']) assert.ok(app.includes(`<${page} />`), page);
});
