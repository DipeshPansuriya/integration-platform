// Row 575 (S1, Varun finding 6; plan S1 acceptance 5): a skip link and focus-to-h1 on route change. The hash router means a
// plain #main anchor would change the route, so the link is handled in script. Run:
// node --import ./tests/rules/ts-resolve.mjs --test tests/ui/route-focus.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const { focusMainHeading, skipToMain } = await import('../../src/route-focus.ts');
const fakeEl = (tag, attrs = {}) => ({
  tag, attrs, focused: null,
  focus(opts) { this.focused = opts ?? {}; },
  hasAttribute(name) { return name in this.attrs; },
  setAttribute(name, value) { this.attrs[name] = value; },
});

test('focusMainHeading: focuses the first h1 in main (no scroll jump) and says so', () => {
  const h1 = fakeEl('h1', { tabindex: '-1' });
  const main = { ...fakeEl('main'), querySelector: (sel) => (sel === 'h1' ? h1 : null) };
  assert.equal(focusMainHeading(main), true);
  assert.deepEqual(h1.focused, { preventScroll: true });
  assert.equal(h1.attrs.tabindex, '-1');
  assert.equal(main.focused, null);
});

test('focusMainHeading: no h1 yet -> the main landmark itself takes focus; no main -> false', () => {
  const main = { ...fakeEl('main'), querySelector: () => null };
  assert.equal(focusMainHeading(main), true);
  assert.deepEqual(main.focused, { preventScroll: true });
  assert.equal(focusMainHeading(null), false);
});

test('skipToMain: cancels the anchor jump (the hash is the route) and focuses main', () => {
  let prevented = false;
  const main = { ...fakeEl('main'), querySelector: () => null };
  skipToMain({ preventDefault: () => { prevented = true; } }, main);
  assert.equal(prevented, true);
  assert.deepEqual(main.focused, { preventScroll: false });
});

test('App.tsx: first control is the skip link, main has the target id and tabIndex -1, focus follows the route (not the first paint)', () => {
  const app = readFileSync(fileURLToPath(new URL('../../src/App.tsx', import.meta.url)), 'utf8');
  assert.ok(app.includes('className="skip-link"'), 'skip link present');
  assert.ok(app.indexOf('className="skip-link"') < app.indexOf('<header className="topbar">'), 'skip link comes before the top bar');
  assert.ok(app.includes('id="main-content"') && app.includes('tabIndex={-1}'), 'main is a focus target');
  assert.ok(app.includes('skipToMain('), 'the skip link is handled in script');
  assert.ok(app.includes('focusMainHeading('), 'route change moves focus to the heading');
  assert.ok(app.includes('routeSeq'), 'focus effect is keyed on the routed address change');
  assert.ok(/<NoAccess|titleOf\(/.test(app) && app.includes('titleOf('), 'NoAccess sits under a heading');
});

test('base.css: the skip link is hidden until focused', () => {
  const css = readFileSync(fileURLToPath(new URL('../../src/ui/base.css', import.meta.url)), 'utf8');
  assert.ok(/\.skip-link\s*\{/.test(css) && /\.skip-link:focus/.test(css));
});

test('focusMainHeading: a bare h1 (FlowsList, Systems render none with tabIndex) gets tabindex=-1 so focus() takes effect', () => {
  const h1 = fakeEl('h1');
  const main = { ...fakeEl('main'), querySelector: (sel) => (sel === 'h1' ? h1 : null) };
  assert.equal(focusMainHeading(main), true);
  assert.equal(h1.attrs.tabindex, '-1');
  assert.deepEqual(h1.focused, { preventScroll: true });
});

test('focusMainHeading: an h1 that already has a tabindex keeps it', () => {
  const h1 = fakeEl('h1', { tabindex: '0' });
  const main = { ...fakeEl('main'), querySelector: () => h1 };
  focusMainHeading(main);
  assert.equal(h1.attrs.tabindex, '0');
});
