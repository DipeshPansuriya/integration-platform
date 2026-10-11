// T-029 (E6-F3-S1, E6-C5, ADR-003 rule 3; E6 lane C, row 278) - the test panel shows what the
// runtime decides, proven on the SCREEN, FAILING FIRST.
//
//   Scenario: Panel and runtime agree
//     Given a rule set and 20 sample payloads
//     When each sample runs in the test panel and through the runtime evaluation port
//     Then every verdict is the same (20 of 20)
//
// The backend half (platform-config RulesPanelParityContainerTest) proves the evaluate endpoint
// and RuleEvaluationPort give the same verdict for each sample AND that both equal the committed
// expected-verdicts.json. This spec closes the loop on the console: the same rule set, the same
// 20 samples, run one by one in the always-visible test panel of #/rules/<ruleSetId> (lane D,
// T-028), and each [data-test-panel-verdict] must show exactly the committed verdict - the
// canonical JSON of {output, violations}, keys sorted, no whitespace (the panel contract).
// The fixture files are read from platform-config's test resources, never copied.
//
// The flow and rule set are seeded through the REAL API as the harness's signed-in TENANT_ADMIN
// (global-setup's session; a browser fetch carries its loopback session + CSRF cookies - E4
// T-097: a Playwright request-level context does not carry the Secure session cookie over http).
//
// RED on lane C's branch: the panel is lane D's (T-028) and #/rules/<id> does not exist until
// arjun merges lane D into row278-integration. The red run's failure names the missing page.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, test } from '@playwright/test';
import { consoleUrl } from './boot-state.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FIXTURE = path.resolve(HERE, '..', '..', '..', 'platform-config', 'src', 'test', 'resources', 'rule-parity');
const readFixture = (name) => JSON.parse(fs.readFileSync(path.join(FIXTURE, name), 'utf8'));

const RULE_SET = readFixture('validation-rule-set.json');
const SAMPLES = readFixture('samples.json');
const EXPECTED = new Map(readFixture('expected-verdicts.json').map((entry) => [entry.id, entry.verdict]));

/** Keys sorted at every depth, no whitespace - the panel's verdict text (and Parity.canonical). */
function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value !== null && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

/** One API call from the signed-in page (its session cookie and its CSRF token). */
async function api(page, method, apiPath, body) {
  return page.evaluate(async ({ url, method: m, apiPath: p, body: b }) => {
    const xsrf = document.cookie.split('; ').filter((pair) => pair.startsWith('XSRF-TOKEN=')).pop();
    const answer = await fetch(url + p, {
      method: m,
      headers: {
        ...(b ? { 'content-type': 'application/json' } : {}),
        ...(m !== 'GET' && xsrf ? { 'X-XSRF-TOKEN': decodeURIComponent(xsrf.split('=').pop()) } : {})
      },
      credentials: 'include',
      body: b ? JSON.stringify(b) : undefined
    });
    return { status: answer.status, data: await answer.json().catch(() => null) };
  }, { url: consoleUrl(), method, apiPath, body });
}

let ruleSetId = null;

test.describe.configure({ mode: 'serial' });

test.beforeAll(async ({ browser }) => {
  // the signed-in storage state applies to new contexts (playwright.config use.storageState)
  const context = await browser.newContext();
  const page = await context.newPage();
  try {
    await page.goto(consoleUrl());
    const marker = Date.now().toString(36);
    const flow = await api(page, 'POST', '/api/v1/admin/flows', {
      sourceSystem: `parity-src-${marker}`,
      destinations: [`parity-dst-${marker}`],
      businessKeyRule: 'reference'
    });
    if (flow.status !== 201 || !flow.data?.id) throw new Error(`seed flow: HTTP ${flow.status} ${JSON.stringify(flow.data)}`);
    const ruleSet = await api(page, 'POST', `/api/v1/admin/flows/${flow.data.id}/rule-sets`, {
      kind: 'VALIDATION',
      name: `parity ${marker}`,
      content: RULE_SET
    });
    if (ruleSet.status !== 201 || !ruleSet.data?.id) throw new Error(`seed rule set: HTTP ${ruleSet.status} ${JSON.stringify(ruleSet.data)}`);
    ruleSetId = ruleSet.data.id;
  } finally {
    await context.close();
  }
});

test('the seeded rule set answers every sample with its committed verdict through the real evaluate API (the runtime port behind it)', async ({ page }) => {
  await page.goto(consoleUrl());
  const mismatches = [];
  for (const sample of SAMPLES) {
    const answer = await api(page, 'POST', `/api/v1/admin/rule-sets/${ruleSetId}/evaluate`, { input: sample.input });
    const got = answer.status === 200 ? canonical({ output: answer.data.output, violations: answer.data.violations }) : `HTTP ${answer.status}`;
    if (got !== canonical(EXPECTED.get(sample.id))) mismatches.push(`${sample.id}: ${got}`);
  }
  expect(SAMPLES).toHaveLength(20);
  expect(mismatches, `API verdicts that differ from expected-verdicts.json (${20 - mismatches.length} of 20 agree)`).toEqual([]);
});

test('Panel and runtime agree: each of the 20 samples run in the test panel shows the committed verdict (20 of 20)', async ({ page }) => {
  test.setTimeout(120000); // 20 panel runs against the booted backend
  await page.goto(`${consoleUrl()}/#/rules/${ruleSetId}`);
  await expect(page.locator('[data-page="rules-editor"]')).toBeVisible();
  const panel = page.locator('[data-test-panel]');
  await expect(panel).toBeVisible();
  const verdict = panel.locator('[data-test-panel-verdict]');

  // no two consecutive samples share a verdict, so a stale verdict from the previous run can never
  // pass for the current one (the wait below is on THIS run's text)
  const order = SAMPLES.map((sample) => canonical(EXPECTED.get(sample.id)));
  expect(order.filter((text, i) => i > 0 && text === order[i - 1]), 'consecutive samples with the same verdict').toEqual([]);

  const shown = [];
  for (const sample of SAMPLES) {
    await panel.locator('[data-test-panel-input]').fill(JSON.stringify(sample.input));
    // wait for THIS run's evaluate call (the panel must call the runtime's endpoint)
    const evaluated = page.waitForResponse((r) => r.request().method() === 'POST'
      && r.url().endsWith(`/api/v1/admin/rule-sets/${ruleSetId}/evaluate`));
    await panel.locator('[data-test-panel-run]').click();
    expect((await evaluated).status(), `${sample.id}: the panel's evaluate call`).toBe(200);
    try {
      await expect(verdict).toHaveAttribute('data-state', 'done');
      await expect(verdict).toHaveText(canonical(EXPECTED.get(sample.id)), { timeout: 5000 });
    } catch {
      // recorded below with every other disagreement, so the report names all 20, not the first
    }
    shown.push({ id: sample.id, verdict: (await verdict.textContent()) ?? '' });
  }

  const mismatches = shown
    .filter((entry) => entry.verdict !== canonical(EXPECTED.get(entry.id)))
    .map((entry) => `${entry.id}\n  panel    ${entry.verdict}\n  expected ${canonical(EXPECTED.get(entry.id))}`);
  expect(shown).toHaveLength(20);
  expect(mismatches, `panel verdicts that differ from expected-verdicts.json (${20 - mismatches.length} of 20 agree)`).toEqual([]);
});
