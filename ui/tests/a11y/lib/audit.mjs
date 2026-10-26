// BS-14 (lane 4): the Playwright-side drivers that join the in-page probes to the pure judges.
// Works against any Page - the real console (routes spec) or an inline fixture (fixtures spec).

import { evaluateTextSamples, effectiveBackground } from './color.mjs';
import { focusIndicator } from './focus.mjs';
import { analyseTabWalk } from './tab-walk.mjs';
import { auditTree, parseAriaSnapshot } from './aria-tree.mjs';
import { markTabbables, resetFocusStart, focusSnapshot, sampleText } from './probes.mjs';

const MAX_STOPS = 200;

/**
 * Press Tab through the page. Returns {expected, stops[], walk, indicators[], positiveTabindex[], truncated}.
 * `scopeToDialog` keeps only dialog stops as expected (used while a modal is open).
 */
export async function tabWalk(page) {
  const all = await page.evaluate(markTabbables);
  const truncated = all.length > MAX_STOPS;
  const expectedInfo = truncated ? all.slice(0, MAX_STOPS) : all;
  await page.evaluate(resetFocusStart);

  const visited = [];
  const stops = [];
  const presses = Math.min(all.length + 3, MAX_STOPS + 3);
  for (let i = 0; i < presses; i += 1) {
    await page.keyboard.press('Tab');
    const snap = await page.evaluate(focusSnapshot);
    visited.push(snap === null ? null : snap.id);
    if (snap === null) break;
    stops.push(snap);
    if (visited.indexOf(snap.id) !== visited.length - 1) break; // repeated: walk judged below
  }

  const fullIds = new Set(all.map((c) => c.id));
  const walk = analyseTabWalk({ expected: expectedInfo.map((c) => c.id), visited });
  if (truncated) walk.extra = walk.extra.filter((id) => !fullIds.has(id)); // later real stops are not "extra"

  const indicators = stops.map((stop) => {
    const bg = effectiveBackground(stop.layers);
    const verdict = bg.indeterminate
      ? { kind: 'unknown', visible: stop.focusVisible, measured: false, ratio: null, passes: false }
      : focusIndicator(stop.style, bg.color);
    return { id: stop.id, label: stop.label, tag: stop.tag, ...verdict };
  });

  return {
    expected: expectedInfo,
    stops,
    walk,
    indicators,
    positiveTabindex: all.filter((c) => c.positiveTabindex).map((c) => `${c.tag}:${c.label}`),
    truncated,
  };
}

/** Text contrast over the given root selectors -> {checked, skipped, failures[]}. */
export async function textContrast(page, roots) {
  const samples = await page.evaluate(sampleText, roots);
  return evaluateTextSamples(samples);
}

/** The browser's own accessibility tree for the whole page, audited. */
export async function labelAudit(page) {
  const yaml = await page.locator('body').ariaSnapshot();
  const tree = parseAriaSnapshot(yaml);
  return { ...auditTree(tree), tree, yaml };
}

/** One-line human summaries for assertion messages. */
export const describeStops = (items) => items.map((i) => `${i.tag ?? ''}:${i.label ?? i.id ?? i}`).join(', ');
export const describeFailures = (items) =>
  items.map((f) => `${f.selector} "${f.text}" ${f.ratio}:1 < ${f.required}:1 (${f.color}, ${f.fontSize}, x${f.count})`).join('\n  ');
