// Lane screens-gap, FAILING FIRST: tests/perf/pages.json lists every screen this lane gave a docker spec
// (activity, delivery report), in the entry shape the p95 gate reads (scripts/run-perf-suite.mjs), and
// each entry's `wait` selector names a data-page the console really renders.

import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const UI = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const { pages } = JSON.parse(fs.readFileSync(path.join(UI, 'tests', 'perf', 'pages.json'), 'utf8'));
const sources = fs.readdirSync(path.join(UI, 'src', 'pages')).filter((f) => f.endsWith('.tsx'))
  .map((f) => fs.readFileSync(path.join(UI, 'src', 'pages', f), 'utf8')).join('\n');

test('every entry has a unique name, a hash path and a wait selector', () => {
  assert.ok(pages.length >= 6);
  assert.equal(new Set(pages.map((p) => p.name)).size, pages.length, 'names are unique');
  for (const p of pages) {
    assert.match(p.name, /^[a-z-]+$/, p.name);
    assert.match(p.path, /^\/(#\/[a-z/]*)?$/, `${p.name} path`);
    assert.equal(typeof p.wait, 'string', `${p.name} wait`);
  }
});

test('the activity and delivery-report screens are measured', () => {
  const byName = Object.fromEntries(pages.map((p) => [p.name, p]));
  assert.equal(byName.activity?.path, '/#/activity');
  assert.equal(byName['delivery-report']?.path, '/#/reports');
});

test('every wait selector targets a data-page the console renders', () => {
  for (const p of pages) {
    const page = /data-page="([a-z-]+)"/.exec(p.wait)?.[1] ?? null;
    if (page === null) continue; // e.g. versions waits on a row attribute
    assert.ok(sources.includes(`data-page="${page}"`), `${p.name}: no component renders data-page="${page}"`);
  }
});
