// T-096 (E3-F1-S2, E3 lane D): the admin page-load p95 measurement.
//
// Scenario "Page load p95 under 1.5 s": seeded configuration for 50 interfaces and 200 targets,
// each admin page loaded 100 times, the 95th percentile under 1500 ms.
// Scenario @negative "A slow page fails the build": a page whose p95 is >= 1500 ms FAILS the run
// and the failure NAMES the page (also proved by tests/slow-page-negative.test.mjs).
//
// Engine: headless Chromium (Playwright) timing a full page load (navigation complete) against
// the running system; the page list is tests/perf/pages.json. The seeded run starts the console
// via the same boot-backend.mjs protocol (container-tagged, one suite at a time); the negative
// probe needs no database seeding.

import { chromium } from 'playwright';

export const P95_BUDGET_MS = 1500;

/** One measured page load: full load time in ms after navigation + settle. */
async function loadOnce(browser, url, settle, wait) {
  const context = await browser.newContext();
  const page = await context.newPage();
  const start = performance.now();
  await page.goto(url, { waitUntil: 'load', timeout: 30000 });
  await page.waitForLoadState('load');
  // A page entry may wait for a selector INSIDE the timed window (e.g. the row a seeded
  // listing renders): then the measurement covers the real data load, not just the shell.
  if (wait) await page.waitForSelector(wait, { timeout: 30000 });
  if (settle) await page.waitForTimeout(settle);
  const ms = performance.now() - start;
  await context.close();
  return ms;
}

/** The p95 (nearest-rank) of `samples`. */
export function p95(samples) {
  if (!samples.length) return NaN;
  const sorted = [...samples].sort((a, b) => a - b);
  const rank = Math.ceil(sorted.length * 0.95); // nearest-rank definition: 95th percentile
  return sorted[Math.min(rank, sorted.length) - 1];
}

/**
 * Measures one page `loads` times; returns { p95, samples }.
 * Exposed for the negative test (the runner and the test use the SAME measurement).
 */
export async function measurePage(browser, url, loads, settle = 0, wait = null) {
  const samples = [];
  for (let i = 0; i < loads; i++) {
    samples.push(await loadOnce(browser, url, settle, wait));
  }
  return { p95: p95(samples), samples };
}

/**
 * The runner: every page of the config is loaded `loads` times against the `base` of the
 * running system; a page whose p95 is at or above the budget fails the run and is named.
 * Returns { failures: [...], pages: [...] }.
 */
export async function runP95Check(config, { base, loads = 100, headless = true } = {}) {
  if (!base) throw new Error('runP95Check needs the base URL of the running system (--base)');
  const browser = await chromium.launch({ headless });
  try {
    const pages = [];
    for (const entry of config.pages ?? []) {
      const url = new URL(entry.path, base);
      const result = await measurePage(browser, url.toString(), entry.loads ?? loads, entry.settle ?? 0, entry.wait ?? null);
      pages.push({ name: entry.name, url: url.toString(), p95: result.p95, loads: result.samples.length });
    }
    const failures = pages.filter((p) => !(p.p95 < P95_BUDGET_MS));
    return { pages, failures };
  } finally {
    await browser.close();
  }
}

export function formatReport(result) {
  const lines = result.pages.map((p) => `${p.name}: p95 ${p.p95.toFixed(0)} ms over ${p.loads} loads`);
  if (!result.failures.length) return `${lines.join('\n')}\nPASS: all pages under ${P95_BUDGET_MS} ms`;
  return [
    ...lines,
    ...result.failures.map((f) => `FAIL: ${f.name} (p95 ${f.p95.toFixed(0)} ms >= ${P95_BUDGET_MS} ms)`),
    `FAIL: ${result.failures.length} page(s) at or above the ${P95_BUDGET_MS} ms budget: ${result.failures.map((f) => f.name).join(', ')}`
  ].join('\n');
}

// --- CLI: node scripts/measure-p95.mjs --config tests/perf/pages.json [--loads 100] --------

if (process.argv[1] && process.argv[1].endsWith('measure-p95.mjs')) {
  const baseArg = process.argv.indexOf('--base');
  const configArg = process.argv.indexOf('--config');
  const loadsArg = process.argv.indexOf('--loads');
  const loads = loadsArg >= 0 ? Number(process.argv[loadsArg + 1]) : 100;
  if (baseArg < 0) {
    console.error('usage: node scripts/measure-p95.mjs --base <running system URL> [--config tests/perf/pages.json] [--loads 100]');
    process.exit(2);
  }
  const configPath = configArg >= 0 ? process.argv[configArg + 1] : 'tests/perf/pages.json';
  const { default: fsSync } = await import('node:fs');
  const { default: pathMod } = await import('node:path');
  const { fileURLToPath } = await import('node:url');
  const ui = pathMod.resolve(pathMod.dirname(fileURLToPath(import.meta.url)), '..');
  const config = JSON.parse(fsSync.readFileSync(pathMod.resolve(ui, configPath), 'utf8'));
  const result = await runP95Check(config, { base: process.argv[baseArg + 1], loads });
  console.log(formatReport(result));
  process.exitCode = result.failures.length ? 1 : 0;
}