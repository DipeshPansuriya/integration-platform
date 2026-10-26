// BS-14 (lane 4): the pure audit over Playwright's locator.ariaSnapshot() YAML - names, h1, table
// headers, text alternatives, live regions. node --test, no browser.

import test from 'node:test';
import assert from 'node:assert/strict';
import { parseAriaSnapshot, auditTree, hasRole } from '../lib/aria-tree.mjs';

const GOOD = `
- banner:
  - button "Collapse navigation"
  - text: Integration Platform
- navigation "Primary":
  - link "Dashboard":
    - /url: "#/"
- main:
  - heading "Flows" [level=1]
  - textbox "Filter flows"
  - combobox "Flow":
    - option "All"
  - table:
    - rowgroup:
      - row "Code Status":
        - columnheader "Code"
        - columnheader "Status"
    - rowgroup:
      - row "A ok":
        - cell "A"
        - cell "ok"
  - img "Logo"
  - alert: Something failed
`;

test('parseAriaSnapshot builds a role/name tree with attributes and nesting', () => {
  const tree = parseAriaSnapshot(GOOD);
  assert.deepEqual(tree.map((n) => n.role), ['banner', 'navigation', 'main']);
  const nav = tree[1];
  assert.equal(nav.name, 'Primary');
  assert.equal(nav.children[0].role, 'link');
  assert.equal(nav.children[0].name, 'Dashboard');
  const heading = tree[2].children[0];
  assert.equal(heading.role, 'heading');
  assert.equal(heading.attrs.level, '1');
});

test('parseAriaSnapshot reads single-quoted keys and escaped quotes in names', () => {
  const [a, b] = parseAriaSnapshot(`- 'button "Save: now"'\n- button "Say \\"hi\\""`);
  assert.equal(a.role, 'button');
  assert.equal(a.name, 'Save: now');
  assert.equal(b.name, 'Say "hi"');
});

test('auditTree on a clean page finds nothing wrong', () => {
  const audit = auditTree(parseAriaSnapshot(GOOD));
  assert.deepEqual(audit.nameless, []);
  assert.equal(audit.h1Count, 1);
  assert.equal(audit.tablesWithoutHeaders, 0);
  assert.equal(audit.hasLiveRegion, true);
});

test('auditTree flags nameless buttons, links, fields and images by role', () => {
  const audit = auditTree(parseAriaSnapshot(`
- main:
  - button
  - link:
    - /url: "#/x"
  - textbox
  - combobox:
    - option "A"
  - img
  - checkbox "Ok"
`));
  assert.deepEqual(audit.nameless.map((n) => n.role), ['button', 'link', 'textbox', 'combobox', 'img']);
});

test('auditTree counts h1s (none and two are both wrong) and ignores other levels', () => {
  assert.equal(auditTree(parseAriaSnapshot('- main:\n  - heading "A" [level=2]')).h1Count, 0);
  assert.equal(auditTree(parseAriaSnapshot('- main:\n  - heading "A" [level=1]\n  - heading "B" [level=1]')).h1Count, 2);
});

test('auditTree flags a table with no column or row headers', () => {
  const audit = auditTree(parseAriaSnapshot(`
- table:
  - rowgroup:
    - row "A":
      - cell "A"
- grid:
  - row "H":
    - columnheader "H"
`));
  assert.equal(audit.tablesWithoutHeaders, 1);
});

test('hasRole finds a role anywhere in the tree; live regions are alert / status / log', () => {
  const tree = parseAriaSnapshot('- main:\n  - status: Saved');
  assert.equal(hasRole(tree, 'status'), true);
  assert.equal(hasRole(tree, 'alert'), false);
  assert.equal(auditTree(tree).hasLiveRegion, true);
  assert.equal(auditTree(parseAriaSnapshot('- main:\n  - paragraph: hi')).hasLiveRegion, false);
});
