// Row 372 review F1-F4 (aditya's measurement): Failures table fits, hit targets >= 28px, error banners are alerts
// with a Retry. CSS and source are read as text (no browser here); the Failures banner role is rendered.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/review-layout.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { findAll, mount } from './hook-harness.mjs';

const src = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src');
const read = (p) => readFileSync(join(src, p), 'utf8');
/** The declaration block of the first rule whose selector list contains `selector`. */
const rule = (css, selector) => {
  const at = css.indexOf(selector);
  assert.ok(at >= 0, `${selector} has a rule`);
  return css.slice(css.indexOf('{', at), css.indexOf('}', at));
};
const operator = read('pages/operator.css');

test('F1 the tracking-id link is capped and ellipsised, so the 36-char id cannot widen the table past the card', () => {
  const css = rule(operator, '.operator-page .op-id a');
  assert.match(css, /max-width:\s*\d+(\.\d+)?(rem|ch|px)/);
  assert.match(css, /text-overflow:\s*ellipsis/);
  assert.match(css, /overflow:\s*hidden/);
  assert.match(read('pages/Failures.tsx'), /title=\{row\.trackingId\}/, 'the full id stays in the title');
  assert.match(rule(operator, '.operator-page .op-card {'), /overflow-x:\s*auto/, 'narrow cards scroll sideways, Action reachable');
});

test('F2 hit targets: tracking-id link, Copy, badge links and the mapping back link are at least 28px tall', () => {
  for (const [css, selector] of [
    [operator, '.operator-page .op-id a'],
    [operator, '.operator-page .op-copy'],
    [operator, '.operator-page a.badge::after'],
    [read('mapping/mapping.css'), '.mapping-page .mapping-head a']
  ]) {
    const block = rule(css, selector);
    const px = /(?:min-height|line-height):\s*(\d+)px/.exec(block);
    assert.ok(px && Number(px[1]) >= 28, `${selector} sets a >= 28px height`);
  }
});

test('F3 Failures: a refused or failed load is announced as an alert, a quiet one as a status', async () => {
  const roleFor = async (answer) => {
    const screen = await mount('pages/Failures.tsx', 'Failures', {}, {
      listFlows: async () => ({ ok: true, data: [] }),
      messageFailures: async () => answer
    }, ['../api/messages', '../api/flows']);
    await screen.settle();
    return findAll(screen.tree(), (n) => n.props?.['data-status'] === 'failures')[0].props.role;
  };
  assert.equal(await roleFor({ ok: false, status: 500, error: 'x' }), 'alert');
  assert.equal(await roleFor({ ok: true, data: [] }), 'status');
});

test('F4 Systems and Contracts list banners carry a Retry that re-reads the list', () => {
  const systems = read('pages/Systems.tsx');
  const contracts = read('pages/ContractVersions.tsx');
  assert.match(systems, /data-action="retry-load"[^>]*onClick=\{\(\) => void refresh\(\)\}/);
  assert.match(contracts, /data-action="retry-load"[^>]*onClick=\{\(\) => void refresh\(\)\}/);
});

test('F5 Contracts cards share one fixed column layout, so columns line up from card to card', () => {
  const css = read('pages/contracts/contracts.css');
  assert.match(rule(css, '.contracts-page .contracts-table-wrap table'), /table-layout:\s*fixed/);
  for (const n of [1, 2, 3, 4]) assert.match(css, new RegExp(`contracts-table-wrap th:nth-child\\(${n}\\)`), `column ${n} has a width`);
});
