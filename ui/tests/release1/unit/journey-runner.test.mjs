// L28 (row 372): the browser journey's offline guards - no docker, no browser, no backend.
// Run: node --test tests/release1/unit/
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { JOURNEY_ENV_NAMES, journeyEnv } from '../../../scripts/release1/journey-env.mjs';
import { journeyVerdict } from '../../../scripts/release1/journey-verdict.mjs';
import { REF } from '../../../scripts/release1/seed-ref-order.mjs';
import { FAIL_STUB_ID, deliveriesOfOrder, deliveriesUnder, targetAStub, utcDay } from '../lib/journey-lib.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const UI = path.resolve(HERE, '..', '..', '..');
const read = (...parts) => fs.readFileSync(path.join(UI, ...parts), 'utf8');
const SPEC = read('tests', 'release1', 'journey.docker.spec.mjs');
const RUNNER = path.join(UI, 'scripts', 'run-release1-journey.mjs');

const boot = {
  ui: UI, consoleUrl: 'http://127.0.0.1:4173',
  backend: { baseUrl: 'http://127.0.0.1:9000', users: { default: { username: 'admin', password: 'pw' } } },
  wiremock: { url: 'http://127.0.0.1:8089', adminUrl: 'http://127.0.0.1:8089/__admin' },
  seeded: { interfaceCode: 'IF-1', flowId: 'flow-1', routeIds: ['route-a', 'route-b'] },
  tokens: { CUSTOMER_A: 'jwt' }
};

/** Every source file under ui/src as one string. */
function allSource(dir = path.join(UI, 'src')) {
  return fs.readdirSync(dir, { withFileTypes: true })
    .map((e) => (e.isDirectory() ? allSource(path.join(dir, e.name)) : /\.(tsx?|css)$/.test(e.name) ? fs.readFileSync(path.join(dir, e.name), 'utf8') : ''))
    .join('\n');
}

test('wiring: every name the runner promises is set from the boot output, route A first', () => {
  const wired = journeyEnv(boot);
  assert.deepEqual(Object.keys(wired).sort(), [...JOURNEY_ENV_NAMES].sort());
  assert.equal(wired.E2E_ROUTE_A_ID, 'route-a');
  assert.equal(wired.E2E_WIREMOCK_ADMIN_URL, 'http://127.0.0.1:8089/__admin');
  assert.ok(fs.existsSync(path.join(wired.E2E_BASE_SUITE_LIB, 'wiremock.mjs')), 'the base-suite oracle is where the spec expects it');
});

test('wiring: a boot that lacks a value fails by name instead of starting a journey that cannot pass', () => {
  assert.throws(() => journeyEnv({ ...boot, tokens: {} }), /E2E_BEARER_CUSTOMER_A/);
  assert.throws(() => journeyEnv({ ...boot, seeded: { ...boot.seeded, routeIds: [] } }), /E2E_ROUTE_A_ID/);
});

test('spec: every setting it asks for is one the runner sets', () => {
  const asked = new Set([...SPEC.matchAll(/setting\('([A-Z0-9_]+)'\)/g)].map((m) => m[1]));
  assert.ok(asked.size >= 6);
  for (const name of asked) assert.ok(JOURNEY_ENV_NAMES.includes(name), `${name} is asked by the spec but not wired by journey-env.mjs`);
});

test('spec: every data-action / data-field / data-page it drives exists in ui/src', () => {
  const source = allSource();
  const drives = [...SPEC.matchAll(/\[data-(action|field|page)="([a-z-]+)"\]/g)];
  assert.ok(drives.length >= 10);
  for (const [, kind, value] of drives) {
    // the retry/replay button writes data-action={action.kind}; its kinds are 'replay' / 'retry' in operator-actions
    const present = source.includes(`data-${kind}="${value}"`) || (kind === 'action' && source.includes(`'${value}'`) && source.includes('data-action={action.kind}'));
    assert.ok(present, `data-${kind}="${value}" is driven by the spec but not found in ui/src`);
  }
});

test('mapping designer: the auto-map control is a real button with a visible text label', () => {
  assert.match(read('src', 'pages', 'MappingDesigner.tsx'), /<button type="button" className="btn" data-action="auto-map-matching"[^>]*>\s*Auto-map matching names\s*<\/button>/);
});

test('fixtures: the REF documents are the seed\'s own; 1.1 is a compatible superset of 1.0', () => {
  const doc = (name) => JSON.parse(read('tests', 'release1', 'fixtures', `${name}.json`));
  assert.deepEqual(doc('ref-source-1.0'), REF.source.document);
  assert.deepEqual(doc('ref-target-a-1.0'), REF.targetA.document);
  assert.deepEqual(doc('ref-target-b-2.0'), REF.targetB.document);
  const v11 = doc('ref-source-1.1');
  assert.deepEqual(v11.required, REF.source.document.required);
  for (const key of Object.keys(REF.source.document.properties)) assert.deepEqual(v11.properties[key], REF.source.document.properties[key]);
});

test('the gate runner is untouched by this row: it still runs only Maven, never Playwright', () => {
  const gate = read('scripts', 'run-release1-gate.mjs');
  assert.match(gate, /Release1ScenariosContainerTest/);
  assert.doesNotMatch(gate, /playwright/i);
});

test('the journey config boots nothing (no globalSetup), one worker, no retries', () => {
  const config = read('playwright.release1.config.mjs');
  assert.doesNotMatch(config, /globalSetup\s*:/);
  assert.match(config, /workers: 1/);
  assert.match(config, /retries: 0/);
});

test('verdict: only a Playwright exit 0 is green', () => {
  assert.deepEqual(journeyVerdict(0), { exitCode: 0, line: 'RELEASE 1 BROWSER JOURNEY: PASS (playwright exit 0)' });
  for (const bad of [1, 2, null]) assert.equal(journeyVerdict(bad).exitCode, 1);
});

test('runner --print-plan starts nothing; with no log file it refuses before touching docker', () => {
  const plan = spawnSync(process.execPath, [RUNNER, '--print-plan'], { encoding: 'utf8', timeout: 20000 });
  assert.equal(plan.status, 0, plan.stderr);
  assert.match(plan.stdout, /E2E_BEARER_CUSTOMER_A/);
  const bare = spawnSync(process.execPath, [RUNNER], { encoding: 'utf8', timeout: 20000 });
  assert.notEqual(bare.status, 0);
  assert.match(bare.stderr, /usage/);
});

test('journal helpers: deliveries per order - only POSTs with an Idempotency-Key, only under /target-a/', () => {
  const entry = (url, method, key, body, at) => ({ request: { url, method, headers: key ? { 'Idempotency-Key': key } : {}, body: JSON.stringify(body), loggedDate: at } });
  const journal = { requests: [
    entry('/target-a/orders', 'POST', 'd1', { orderNo: 'X', contractVersion: '1.0' }, 1),
    entry('/target-a/orders', 'POST', 'd2', { orderNo: 'Y' }, 2),
    entry('/target-a/orders', 'POST', 'd2', { orderNo: 'Y' }, 3),
    entry('/target-a/orders', 'GET', null, {}, 4),
    entry('/ref-source/x', 'POST', 'z', { orderNo: 'X' }, 5)
  ] };
  assert.equal(deliveriesUnder(journal).length, 3);
  assert.equal(deliveriesOfOrder(journal, 'Y').length, 2);
  assert.equal(deliveriesOfOrder(journal, 'X')[0].body.contractVersion, '1.0');
  assert.equal(targetAStub(403).response.status, 403);
  assert.equal(targetAStub(200).id, FAIL_STUB_ID);
  assert.equal(utcDay(new Date('2026-10-24T23:59:59Z')), '2026-10-24');
});
