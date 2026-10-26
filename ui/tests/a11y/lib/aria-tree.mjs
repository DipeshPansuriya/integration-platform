// BS-14 (lane 4): a small parser + audit over Playwright's locator.ariaSnapshot() YAML, so the label
// checks (WCAG 4.1.2 name/role, 1.1.1 text alternatives, 1.3.1 table headers, 2.4.6 one h1) read the
// browser's own accessibility tree instead of re-implementing accessible-name computation.
// Unit: tests/a11y/unit/aria-tree.test.mjs.

const NEEDS_NAME = new Set([
  'button', 'link', 'textbox', 'combobox', 'checkbox', 'radio', 'switch', 'slider', 'spinbutton',
  'searchbox', 'menuitem', 'menuitemcheckbox', 'menuitemradio', 'tab', 'img', 'listbox', 'dialog',
  'alertdialog', 'tree',
]);
const TABLE_ROLES = new Set(['table', 'grid', 'treegrid']);
const HEADER_ROLES = new Set(['columnheader', 'rowheader']);
const LIVE_ROLES = new Set(['alert', 'status', 'log']);
const KEY = /^(\/?[\w-]+)(?:\s+"((?:[^"\\]|\\.)*)")?((?:\s+\[[^\]]+\])*)\s*$/;

const unescape = (s) => s.replace(/\\(["\\])/g, '$1');

/** Split `content` (after "- ") into the key and what follows its colon. */
function splitKey(content) {
  if (content.startsWith("'")) {
    let i = 1;
    for (; i < content.length; i += 1) {
      if (content[i] === "'" && content[i + 1] === "'") i += 1;
      else if (content[i] === "'") break;
    }
    return { key: content.slice(1, i).replace(/''/g, "'"), rest: content.slice(i + 1).replace(/^\s*:?\s*/, '') };
  }
  let quoted = false;
  for (let i = 0; i < content.length; i += 1) {
    const ch = content[i];
    if (ch === '\\' && quoted) i += 1;
    else if (ch === '"') quoted = !quoted;
    else if (ch === ':' && !quoted) return { key: content.slice(0, i), rest: content.slice(i + 1).trim() };
  }
  return { key: content, rest: '' };
}

/** YAML from locator.ariaSnapshot() -> [{role, name, attrs, text, children}]; name null = no accessible name. */
export function parseAriaSnapshot(yaml) {
  const roots = [];
  const stack = []; // {indent, node}
  for (const line of yaml.split('\n')) {
    const m = /^(\s*)- (.*)$/.exec(line);
    if (!m) continue;
    const indent = m[1].length;
    const { key, rest } = splitKey(m[2].trimEnd());
    const parsed = KEY.exec(key.trim());
    if (!parsed) continue;
    while (stack.length && stack[stack.length - 1].indent >= indent) stack.pop();
    const parent = stack.length ? stack[stack.length - 1].node : null;
    if (parsed[1].startsWith('/')) continue; // /url, /placeholder: properties, not nodes
    const attrs = {};
    for (const a of parsed[3].matchAll(/\[([^\]=]+)(?:=([^\]]*))?\]/g)) attrs[a[1]] = a[2] ?? true;
    const node = { role: parsed[1], name: parsed[2] === undefined ? null : unescape(parsed[2]), attrs, text: rest, children: [] };
    (parent ? parent.children : roots).push(node);
    stack.push({ indent, node });
  }
  return roots;
}

function* walk(nodes, path = []) {
  for (const node of nodes) {
    yield { node, path };
    yield* walk(node.children, [...path, node.role]);
  }
}

/** True when any node of `role` exists anywhere in the tree. */
export function hasRole(tree, role) {
  for (const { node } of walk(tree)) if (node.role === role) return true;
  return false;
}

/**
 * {nameless: [{role, path}], h1Count, tablesWithoutHeaders, hasLiveRegion}. `nameless` = controls and
 * images the browser exposes with no accessible name.
 */
export function auditTree(tree) {
  const nameless = [];
  let h1Count = 0;
  let tablesWithoutHeaders = 0;
  let hasLiveRegion = false;
  for (const { node, path } of walk(tree)) {
    if (NEEDS_NAME.has(node.role) && (node.name === null || node.name.trim() === '')) {
      nameless.push({ role: node.role, path: path.join(' > ') });
    }
    if (node.role === 'heading' && node.attrs.level === '1') h1Count += 1;
    if (LIVE_ROLES.has(node.role)) hasLiveRegion = true;
    if (TABLE_ROLES.has(node.role)) {
      let headed = false;
      for (const inner of walk(node.children)) if (HEADER_ROLES.has(inner.node.role)) headed = true;
      if (!headed) tablesWithoutHeaders += 1;
    }
  }
  return { nameless, h1Count, tablesWithoutHeaders, hasLiveRegion };
}
