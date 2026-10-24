// L28 (row 372): the browser journey's offline guards - no docker, no browser, no backend.
// Run: node --test tests/release1/unit/
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { JOURNEY_ENV_NAMES, journeyEnv } from '../../../scripts/release1/journey-env.mjs';
import { journeyNames, makeCleanup, installSignalCleanup, readStats } from '../../../scripts/release1/journey-run.mjs';
import { journeyVerdict } from '../../../scripts/release1/journey-verdict.mjs';
import { REF } from '../../../scripts/release1/seed-ref-order.mjs';
import { FAIL_STUB_ID, deliveriesOfOrder, deliveriesUnder, targetAStub, upsertStub, utcDay } from '../lib/journey-lib.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const UI = path.resolve(HERE, '..', '..', '..');
const CONSOLE = path.join(UI, '..', 'ui'); // L29: ui/src stays in the console project
const read = (...parts) => fs.readFileSync(path.join(parts[0] === 'src' ? CONSOLE : UI, ...parts), 'utf8');
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
function allSource(dir = path.join(CONSOLE, 'src')) {
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

test('verdict (V1): green needs exit 0 AND a report with >=1 passed and nothing skipped, failed or flaky', () => {
  const ok = { expected: 1, skipped: 0, unexpected: 0, flaky: 0 };
  assert.deepEqual(journeyVerdict(0, ok), { exitCode: 0, line: 'RELEASE 1 BROWSER JOURNEY: PASS (1 passed, playwright exit 0)' });
  for (const bad of [1, 2, null]) assert.equal(journeyVerdict(bad, ok).exitCode, 1);
  assert.match(journeyVerdict(0, { ...ok, expected: 0, skipped: 1 }).line, /FAIL \(zero tests passed\)/, 'a fully fixme-d journey is never PASS');
  assert.match(journeyVerdict(0, { ...ok, skipped: 1 }).line, /FAIL \(skipped 1/);
  assert.match(journeyVerdict(0, null).line, /FAIL \(no test report\)/);
  assert.equal(readStats(path.join(UI, 'does-not-exist.json')), null);
});

test('names (R2): container, MQ port and inner lock follow the slot, so two runners cannot collide', () => {
  const two = journeyNames('/x/.docker-suite.lock-2', '/tmp');
  const three = journeyNames('/x/.docker-suite.lock-3', '/tmp');
  assert.deepEqual([two.artemisName, two.mqPort], ['l28-artemis-2', 61618]);
  for (const key of ['artemisName', 'mqPort', 'innerLock']) assert.notEqual(two[key], three[key]);
  assert.equal(journeyNames('/x/.docker-suite.lock', '/tmp').n, 0);
});

test('cleanup (R1): newest step first, a failing step never strands the rest, a second run is a no-op', async () => {
  const order = [];
  const logged = [];
  const cleanup = makeCleanup((m) => logged.push(m));
  cleanup.add('slot', () => order.push('slot'));
  cleanup.add('artemis', () => order.push('artemis'));
  cleanup.add('backend', () => { throw new Error('boom'); });
  cleanup.add('console', () => order.push('console'));
  await Promise.all([cleanup.run(), cleanup.run()]);
  assert.deepEqual(order, ['console', 'artemis', 'slot']);
  assert.match(logged.join('|'), /cleanup backend: boom/);
  await cleanup.run();
  assert.equal(order.length, 3);
});

test('signals (R1): SIGINT and SIGTERM run the cleanup, then exit 130 / 143; removing the handlers leaves none', async () => {
  const { EventEmitter } = await import('node:events');
  for (const [signal, code] of [['SIGINT', 130], ['SIGTERM', 143]]) {
    const proc = new EventEmitter();
    const ran = [];
    const exited = new Promise((resolve) => {
      const cleanup = makeCleanup();
      cleanup.add('slot', () => ran.push('slot released'));
      installSignalCleanup(cleanup, { proc, exit: resolve });
    });
    proc.emit(signal);
    assert.equal(await exited, code);
    assert.deepEqual(ran, ['slot released']);
  }
  const proc = new EventEmitter();
  installSignalCleanup(makeCleanup(), { proc })();
  assert.equal(proc.listenerCount('SIGINT') + proc.listenerCount('SIGTERM'), 0);
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

test('upsertStub: PUT first; WireMock answers 404 to an unknown id, so it POSTs; any other refusal throws', async () => {
  const calls = [];
  const fake = (statuses) => async (url, init) => { calls.push(`${init.method} ${url}`); return { status: statuses.shift() }; };
  const stub = targetAStub(403);
  assert.equal(await upsertStub('http://w/__admin', stub, fake([404, 201])), 201);
  assert.deepEqual(calls, [`PUT http://w/__admin/mappings/${stub.id}`, 'POST http://w/__admin/mappings']);
  calls.length = 0;
  assert.equal(await upsertStub('http://w/__admin', stub, fake([200])), 200);
  assert.equal(calls.length, 1, 'a known id is replaced in place');
  await assert.rejects(upsertStub('http://w/__admin', stub, fake([500])), /answered 500/);
  await assert.rejects(upsertStub('http://w/__admin', stub, fake([404, 400])), /answered 400/);
});

test('A1: the designer renders a persistent polite live region for the Auto-map result', () => {
  const designer = read('src', 'pages', 'MappingDesigner.tsx');
  assert.match(designer, /<p role="status" aria-live="polite"[^>]*data-auto-map-result>\s*\{autoMapNote\}/);
  assert.match(designer, /setAutoMapNote\(autoMapMessage\(matched\.added\)\)/);
});

test('S1: reports and traces are gitignored and a trace is kept only for a failed run', () => {
  assert.match(read('.gitignore'), /^playwright-report\/$/m);
  assert.match(read('.gitignore'), /^_reports\/$/m);
  assert.doesNotMatch(read('playwright.release1.config.mjs'), /trace: 'on'/);
  assert.match(read('playwright.release1.config.mjs'), /retain-on-failure/);
});

test('runner wiring: signals, slot-derived names and the json report that V1 reads', () => {
  const runner = fs.readFileSync(RUNNER, 'utf8');
  for (const piece of ['installSignalCleanup(cleanup', 'journeyNames(slot.path)', '--reporter=line,html,json', 'readStats(jsonReport)', "slotNames: ['.docker-suite.lock-2', '.docker-suite.lock-3']"]) assert.ok(runner.includes(piece), piece);
});
