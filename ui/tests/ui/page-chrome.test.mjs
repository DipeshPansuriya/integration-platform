// Row 572 S0: Breadcrumb and PageHeader (spec 4.7, section 5). Breadcrumb = <nav aria-label="Breadcrumb"><ol>, the last
// item aria-current="page" and not a link; PageHeader = the screen's ONE <h1>, the breadcrumb, an optional "Next" slot.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/page-chrome.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { findAll, mount } from './hook-harness.mjs';

const text = (node) => (Array.isArray(node) ? node.map(text).join('') : typeof node === 'string' || typeof node === 'number' ? String(node) : node && typeof node === 'object' ? text(node.props?.children) : '');
const ITEMS = [{ label: 'Flows', href: '#/flows' }, { label: 'Nested appointment', href: '#/flow/1' }, { label: 'Versions' }];

test('breadcrumb: nav landmark named Breadcrumb holding an ordered list', async () => {
  const screen = await mount('ui/Breadcrumb.tsx', 'Breadcrumb', { items: ITEMS }, {}, []);
  const [nav] = findAll(screen.tree(), (n) => n.type === 'nav');
  assert.equal(nav.props['aria-label'], 'Breadcrumb');
  assert.equal(findAll(nav, (n) => n.type === 'ol').length, 1);
  assert.equal(findAll(nav, (n) => n.type === 'li').length, 3);
});

test('breadcrumb: earlier items are links, the last is plain text with aria-current=page', async () => {
  const screen = await mount('ui/Breadcrumb.tsx', 'Breadcrumb', { items: ITEMS }, {}, []);
  const links = findAll(screen.tree(), (n) => n.type === 'a');
  assert.deepEqual(links.map((a) => a.props.href), ['#/flows', '#/flow/1']);
  const current = findAll(screen.tree(), (n) => n.props?.['aria-current'] === 'page');
  assert.equal(current.length, 1);
  assert.equal(text(current[0]), 'Versions');
  assert.notEqual(current[0].type, 'a', 'the current page is not a link to itself');
});

test('breadcrumb: a single item is just the current page; no items renders nothing', async () => {
  const one = await mount('ui/Breadcrumb.tsx', 'Breadcrumb', { items: [{ label: 'Home' }] }, {}, []);
  assert.equal(findAll(one.tree(), (n) => n.props?.['aria-current'] === 'page').length, 1);
  const none = await mount('ui/Breadcrumb.tsx', 'Breadcrumb', { items: [] }, {}, []);
  assert.equal(none.tree(), null);
});

test('breadcrumb: a label is text, never markup (escaped by React)', async () => {
  const screen = await mount('ui/Breadcrumb.tsx', 'Breadcrumb', { items: [{ label: '<b>x</b>', href: '#/a' }, { label: 'Y' }] }, {}, []);
  assert.equal(findAll(screen.tree(), (n) => n.type === 'b').length, 0);
});

test('page header: exactly one h1 with the title, the breadcrumb, and the Next slot only when given', async () => {
  const withNext = await mount('ui/PageHeader.tsx', 'PageHeader', { title: 'Messages', breadcrumb: ITEMS, next: 'Open the mapping' }, {}, []);
  const h1 = findAll(withNext.tree(), (n) => n.type === 'h1');
  assert.equal(h1.length, 1);
  assert.equal(text(h1[0]), 'Messages');
  assert.equal(findAll(withNext.tree(), (n) => n.type === 'nav' && n.props['aria-label'] === 'Breadcrumb').length, 1);
  assert.equal(findAll(withNext.tree(), (n) => n.props?.['data-page-next'] !== undefined).length, 1);

  const plain = await mount('ui/PageHeader.tsx', 'PageHeader', { title: 'Home' }, {}, []);
  assert.equal(findAll(plain.tree(), (n) => n.type === 'nav').length, 0, 'no breadcrumb given, none drawn');
  assert.equal(findAll(plain.tree(), (n) => n.props?.['data-page-next'] !== undefined).length, 0);
});

test('page header: the h1 can take focus on a route change (tabIndex -1) and carries the data hook', async () => {
  const screen = await mount('ui/PageHeader.tsx', 'PageHeader', { title: 'Home' }, {}, []);
  const [h1] = findAll(screen.tree(), (n) => n.type === 'h1');
  assert.equal(h1.props.tabIndex, -1);
  assert.equal(h1.props['data-page-title'], 'true');
});
