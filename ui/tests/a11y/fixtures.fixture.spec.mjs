// BS-14 (lane 4): the probes proven on inline pages, in real Chromium, with NO backend - so the a11y
// drivers are known to detect a defect before they are trusted on the console. A good page must pass
// every probe; each seeded defect must be caught by exactly the probe that owns it.
// Run: A11Y_FIXTURES_ONLY=1 npx playwright test -c playwright.a11y.config.mjs

import { expect, test } from '@playwright/test';
import { describeFailures, labelAudit, tabWalk, textContrast } from './lib/audit.mjs';

const GOOD = `
<style>
  body { background: #fff; color: #0f172a; font: 14px system-ui }
  a:focus-visible, button:focus-visible, input:focus-visible { outline: 2px solid #2563eb; outline-offset: 2px }
  th, td { text-align: left }
</style>
<main>
  <h1>Orders</h1>
  <a href="#/x">Go</a>
  <label>Filter <input type="text"></label>
  <button type="button">Save</button>
  <table><thead><tr><th>Code</th><th>Status</th></tr></thead><tbody><tr><td>A</td><td>ok</td></tr></tbody></table>
</main>`;

const BAD = `
<style>
  body { background: #fff; color: #0f172a; font: 14px system-ui }
  *:focus, *:focus-visible { outline: none !important; box-shadow: none !important }
  .grey { color: #aaaaaa }
</style>
<main>
  <h1>One</h1><h1>Two</h1>
  <input type="text">
  <button type="button"><svg width="10" height="10" role="img"><circle cx="5" cy="5" r="4"/></svg></button>
  <p class="grey">Pale helper text</p>
  <table><tbody><tr><td>no</td><td>headers</td></tr></tbody></table>
</main>`;

const TRAP = `
<style>body { background: #fff } button:focus-visible { outline: 2px solid #2563eb }</style>
<button id="out" type="button">Before</button>
<div id="box"><button id="b1" type="button">One</button><button id="b2" type="button">Two</button></div>
<button id="after" type="button">After</button>
<script>
  const box = document.getElementById('box');
  const pair = [document.getElementById('b1'), document.getElementById('b2')];
  box.addEventListener('keydown', (e) => {
    if (e.key !== 'Tab') return;
    e.preventDefault();
    pair[(pair.indexOf(document.activeElement) + 1) % 2].focus();
  });
</script>`;

const HEADING_FOCUSED = `
<style>button:focus-visible { outline: 2px solid #2563eb }</style>
<h2 id="h" tabindex="-1">Title</h2>
<button type="button">First</button><button type="button">Second</button>
<script>document.getElementById('h').focus();</script>`;

test('a good page passes every probe', async ({ page }) => {
  await page.setContent(GOOD);
  const walk = await tabWalk(page);
  expect(walk.walk).toEqual({ missing: [], extra: [], outOfOrder: [], trap: false, completed: true });
  expect(walk.expected.length).toBe(3);
  expect(walk.indicators.filter((i) => !i.passes), JSON.stringify(walk.indicators)).toEqual([]);

  const labels = await labelAudit(page);
  expect(labels.nameless).toEqual([]);
  expect(labels.h1Count).toBe(1);
  expect(labels.tablesWithoutHeaders).toBe(0);

  const contrast = await textContrast(page, ['body']);
  expect(contrast.failures, describeFailures(contrast.failures)).toEqual([]);
  expect(contrast.checked).toBeGreaterThan(3);
});

test('a bad page: every seeded defect is caught by its own probe', async ({ page }) => {
  await page.setContent(BAD);

  const labels = await labelAudit(page);
  expect(labels.nameless.map((n) => n.role).sort(), labels.yaml).toEqual(['button', 'img', 'textbox']);
  expect(labels.h1Count).toBe(2);
  expect(labels.tablesWithoutHeaders).toBe(1);

  const contrast = await textContrast(page, ['body']);
  expect(contrast.failures.map((f) => f.selector)).toEqual(['p.grey']);

  const walk = await tabWalk(page);
  expect(walk.expected.length).toBe(2);
  expect(walk.indicators.length).toBe(2);
  expect(walk.indicators.every((i) => i.visible === false && i.passes === false)).toBe(true);
});

test('a keyboard trap is detected, the control behind it reported missing', async ({ page }) => {
  await page.setContent(TRAP);
  const { walk } = await tabWalk(page);
  expect(walk.trap).toBe(true);
  expect(walk.completed).toBe(false);
  expect(walk.missing.length).toBeGreaterThan(0);
});

test('the walk starts at the top of the document even when a heading holds focus on load', async ({ page }) => {
  await page.setContent(HEADING_FOCUSED);
  const walk = await tabWalk(page);
  expect(walk.stops.map((s) => s.label)).toEqual(['First', 'Second']);
  expect(walk.walk.completed).toBe(true);
});
