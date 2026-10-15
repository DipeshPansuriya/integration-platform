// Row 310 wave 4 lane E (vijay-developer, T103): Mapping designer - the #/mappings worklist landing
// (MappingRoutes.tsx), the designer header / canvas card / action bar (MappingDesigner.tsx) and the
// xyflow --xy-* overrides (01-tokens s4.2) in ui/src/mapping/mapping.css. 10-screens s8, 20-build-plan
// s5 floors 1-3 + lane E specifics. The page contracts the screens specs key off are read from source.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/mapping-designer.test.mjs

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const src = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src');
const read = (rel) => readFileSync(join(src, rel), 'utf8');
const CSS = 'mapping/mapping.css';
const css = () => (existsSync(join(src, CSS)) ? read(CSS) : '');
const stripComments = (text) => text.replace(/\/\*[\s\S]*?\*\//g, '');
const tokensDefined = new Set([...read('tokens.css').matchAll(/(--[a-z0-9-]+)\s*:/g)].map((m) => m[1]));

test('floor 1: mapping.css exists and is imported by the designer page', () => {
  assert.ok(existsSync(join(src, CSS)), 'ui/src/mapping/mapping.css');
  assert.match(read('pages/MappingDesigner.tsx'), /import ['"]\.\.\/mapping\/mapping\.css['"]/);
});

test('floor 1: mapping.css holds no colour literal - every colour is a --color-* token that tokens.css defines', () => {
  const body = stripComments(css());
  assert.ok(body.length > 0, 'mapping.css is empty or missing');
  assert.doesNotMatch(body, /#[0-9a-fA-F]{3,8}\b/, 'hex colour');
  assert.doesNotMatch(body, /\b(rgb|rgba|hsl|hsla)\(/, 'functional colour');
  assert.doesNotMatch(body, /:\s*(white|black|red|blue|green|gray|grey)\b/i, 'named colour');
  for (const [, name] of body.matchAll(/var\((--[a-z0-9-]+)/g)) {
    if (name.startsWith('--xy-')) continue; // xyflow's own variables, set in this file
    assert.ok(tokensDefined.has(name), `${name} is not a token in tokens.css`);
  }
});

test('floor 3 + 12.5px type floor: radii are --r-* tokens and no font-size is under 12.5px', () => {
  const body = stripComments(css());
  for (const [, value] of body.matchAll(/border-radius\s*:\s*([^;]+);/g)) {
    assert.match(value.trim(), /^(var\(--r-(1|2|3|pill)\)|0|50%)$/, `radius ${value}`);
  }
  const sizes = [...body.matchAll(/font-size\s*:\s*([\d.]+)px/g)].map((m) => Number(m[1]));
  assert.ok(sizes.length > 0, 'mapping.css sets the canvas type sizes');
  for (const size of sizes) assert.ok(size >= 12.5, `font-size ${size}px is under the 12.5px floor`);
});

test('01 s4.2: the xyflow override is scoped to .mapping-canvas and edge labels are mono ink', () => {
  const body = stripComments(css());
  assert.match(body, /\.mapping-canvas[^{]*\{[^}]*--xy-edge-label-color:\s*var\(--color-ink\)/);
  assert.match(body, /\.mapping-canvas[^{]*\{[^}]*--xy-background-pattern-color:\s*var\(--color-border\)/);
  const edgeText = body.match(/\.mapping-canvas[^{]*\.react-flow__edge-text[^{]*\{([^}]*)\}/);
  assert.ok(edgeText, 'a .mapping-canvas .react-flow__edge-text rule');
  assert.match(edgeText[1], /font-family:\s*var\(--font-mono\)/);
  assert.match(edgeText[1], /fill:\s*var\(--color-ink\)/);
});

test('canvas: no hard-coded colour in the node style; the node takes token classes', () => {
  const canvas = read('mapping/MappingCanvas.tsx');
  assert.doesNotMatch(canvas, /#[0-9a-fA-F]{3,8}\b/, 'hex colour in MappingCanvas.tsx');
  assert.doesNotMatch(canvas, /style=\{\{\s*border/, 'inline node border');
  assert.match(canvas, /className=\{?['"`]mapping-canvas/, 'the canvas root carries .mapping-canvas');
  assert.match(canvas, /mapping-node/);
});

test('canvas stays non-focusable decoration: no tabbable xyflow node/edge wrappers, no xyflow keyboard layer', () => {
  const canvas = read('mapping/MappingCanvas.tsx');
  assert.match(canvas, /nodesFocusable=\{false\}/);
  assert.match(canvas, /edgesFocusable=\{false\}/);
  assert.match(canvas, /disableKeyboardA11y/);
  // the keyboard twin: node buttons with aria-pressed (the selection contract)
  assert.match(canvas, /<button type="button" data-node=\{data\.path\}[^>]*aria-pressed=\{data\.selected\}/);
});

test('canvas: drawn links reach the canvas - edge ends use the node ids', () => {
  const canvas = read('mapping/MappingCanvas.tsx');
  const nodeId = canvas.match(/id:\s*`\$\{side\}:\$\{path\}`/);
  assert.ok(nodeId, 'node id is `${side}:${path}`');
  assert.match(canvas, /source:\s*`source:\$\{link\.source\}`/, 'edge source = the source node id');
  assert.match(canvas, /target:\s*`target:\$\{link\.target\}`/, 'edge target = the target node id');
  assert.match(canvas, /label:\s*`\$\{link\.source\} → \$\{link\.target\}`/, 'edges keep their path label');
});

test('designer: links table stays the accessible editable truth (caption, row hooks, editable target, delete)', () => {
  const page = read('pages/MappingDesigner.tsx');
  assert.match(page, /<caption>Drawn links \(the drawing, per interface-target pair\)<\/caption>/);
  for (const hook of ['data-link-row=', 'data-field="link-source"', 'data-field="link-target"', 'data-action="delete-link"']) {
    assert.ok(page.includes(hook), hook);
  }
  // the table sits directly below the canvas card + action bar, above the sample panel
  const order = ['<MappingCanvas', 'data-action="save"', '<caption>Drawn links', '<MappingSamplePanel'].map((s) => page.indexOf(s));
  assert.deepEqual([...order].sort((a, b) => a - b), order, 'canvas -> action bar -> links table -> sample panel');
});

test('designer: header selects, action bar and data-* hooks kept; Save is the primary', () => {
  const page = read('pages/MappingDesigner.tsx');
  for (const hook of ['data-page="mapping-designer"', 'data-mapping-loaded=', 'data-field="source-version"',
    'data-field="target-version"', 'data-action="add-link"', 'data-action="save"', 'data-refusal', 'data-saved="true"',
    'data-selection=', 'data-mapping-missing="true"', 'data-canvas-loading="true"', 'data-raw-loading="true"']) {
    assert.ok(page.includes(hook), hook);
  }
  assert.match(page, /<option value="">not bound<\/option>[\s\S]*<option value="">not bound<\/option>/);
  assert.match(page, /className="btn btn-primary"[^>]*data-action="save"|data-action="save"[^>]*className="btn btn-primary"/);
  assert.match(page, />\s*Link selected fields\s*</);
  // refusals are a worded danger alert near Save, never colour alone
  assert.match(page, /role="alert"[^>]*data-refusal|data-refusal[^>]*role="alert"/);
});

test('designer: the missing-mapping state is an empty state inside the canvas card (no leak either way)', () => {
  const page = read('pages/MappingDesigner.tsx');
  assert.match(page, /This route has no mapping yet\. Bind the two contract versions and draw the first link\./);
});

test('designer: the save contract is unchanged - same PUT, same body', () => {
  const page = read('pages/MappingDesigner.tsx');
  assert.match(page, /api\.PUT\('\/api\/v1\/admin\/routes\/\{routeId\}\/mapping', \{\s*params: \{ path: \{ routeId \} \},\s*body: toSaveRequest\(graph\) as never\s*\}\)/);
  assert.match(page, /'a mapping binds one source and one target contract version together - select both'/);
});

test('list landing: a worklist table - Route, Interface, Target system, Mapping (worded badge), Open', () => {
  const list = read('mapping/MappingRoutes.tsx');
  const head = list.match(/<thead>([\s\S]*?)<\/thead>/);
  assert.ok(head, 'routes table head');
  const columns = [...head[1].matchAll(/<th[^>]*>([\s\S]*?)<\/th>/g)].map((m) => m[1].replace(/<[^>]+>/g, '').trim());
  assert.deepEqual(columns, ['Route', 'Interface', 'Target system', 'Mapping', 'Open']);
  assert.match(list, /className="table"/);
  assert.match(list, /'Saved'/);
  assert.match(list, /'Not saved'/);
  assert.match(list, /badge badge-/);
});

test('list landing: contracts kept - flows picker, route rows, open-mapping link, one call per selection', () => {
  const list = read('mapping/MappingRoutes.tsx');
  for (const hook of ['data-mapping-flows', 'data-mapping-flow={flow.id}', 'aria-pressed={selected === flow.id}',
    'data-mapping-route={route.id}', 'data-action="open-mapping"', 'href={mappingHref(route.id)}',
    'data-load-error="mapping-flows"', 'data-load-error="mapping-routes"', 'data-empty="mapping-flows"', 'data-empty="mapping-routes"']) {
    assert.ok(list.includes(hook), hook);
  }
  assert.equal((list.match(/loadRoutes\(/g) ?? []).length, 1, 'routes read in one place (per selection)');
});

test('sample panel: layout moved to mapping.css - no inline styles, hooks kept', () => {
  const panel = read('mapping/MappingSamplePanel.tsx');
  assert.doesNotMatch(panel, /style=\{\{/);
  for (const hook of ['data-sample-panel', 'data-sample-input', 'data-sample-run', 'data-sample-result', 'data-sample-output', 'data-sample-status']) {
    assert.ok(panel.includes(hook), hook);
  }
});
