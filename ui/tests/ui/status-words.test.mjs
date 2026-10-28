// Row 572 S0: ONE status vocabulary (spec 4.6). Every enum the console shows goes through statusWord(kind, value);
// no raw enum is rendered as text. The enum lists are read from the generated openapi.d.ts so a new server value
// that has no word FAILS here (it is never silently shown raw).
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/status-words.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { statusWord, isKnownStatus, STATUS_VALUES } from '../../src/ui/status-words.ts';

const src = (p) => readFileSync(new URL(`../../src/${p}`, import.meta.url), 'utf8');
const dts = src('api/openapi.d.ts');
const TONES = ['success', 'info', 'warn', 'danger', 'neutral'];

/** Every string-literal union (2+ members) declared for a field name in openapi.d.ts. */
function unionsFor(field) {
  const found = [];
  for (const m of dts.matchAll(new RegExp(`\\b${field}\\??: ((?:"[A-Z_]+"(?: \\| )?)+);`, 'g'))) {
    found.push([...m[1].matchAll(/"([A-Z_]+)"/g)].map((x) => x[1]));
  }
  return found.filter((u) => u.length > 1);
}

test('every contract-version status in openapi.d.ts has a word', () => {
  const lists = unionsFor('status').filter((u) => u.includes('PENDING_APPROVAL'));
  assert.ok(lists.length > 0, 'the contract status union is found');
  for (const value of lists.flat()) assert.ok(isKnownStatus('contractVersion', value), `contractVersion ${value}`);
});

test('every delivery state in openapi.d.ts has a word (state kind)', () => {
  const lists = unionsFor('state').filter((u) => u.includes('DEAD_LETTERED'));
  assert.ok(lists.length > 0, 'the delivery state union is found');
  for (const value of lists.flat()) assert.ok(isKnownStatus('state', value), `state ${value}`);
});

test('every contract role in openapi.d.ts has a word', () => {
  const lists = unionsFor('role').filter((u) => u.includes('TARGET_REQUEST'));
  assert.ok(lists.length > 0, 'the role union is found');
  for (const value of lists.flat()) assert.ok(isKnownStatus('role', value), `role ${value}`);
});

test('values the server stores as free text (no union in openapi.d.ts) are listed here and have words', () => {
  // flow version status, message state (V2_1_810 ACCEPTED/ROUTED/ROUTE_FAILED), ordering (ADR-011), outcome classes, stage statuses.
  const cases = {
    flowVersion: ['DRAFT', 'SUBMITTED', 'APPROVED', 'PUBLISHED'],
    state: ['ACCEPTED', 'ROUTED', 'ROUTE_FAILED'],
    ordering: ['PER_KEY', 'NONE'],
    outcome: ['SUCCESS', 'TRANSIENT', 'AMBIGUOUS', 'PERMANENT'],
    stage: ['done', 'running', 'failed', 'not-reached', 'not-recorded']
  };
  for (const [kind, values] of Object.entries(cases)) for (const v of values) assert.ok(isKnownStatus(kind, v), `${kind} ${v}`);
});

test('no mapped word is a raw enum (no underscore, not all capitals) and every tone is one of the five', () => {
  for (const [kind, values] of Object.entries(STATUS_VALUES)) {
    assert.ok(values.length > 0, `${kind} has values`);
    for (const value of values) {
      const { word, tone } = statusWord(kind, value);
      assert.doesNotMatch(word, /_/, `${kind} ${value}: ${word}`);
      assert.notEqual(word, word.toUpperCase(), `${kind} ${value}: ${word} is shouted`);
      assert.ok(TONES.includes(tone), `${kind} ${value}: tone ${tone}`);
    }
  }
});

test('the four enums the audit named never render as raw text', () => {
  assert.equal(statusWord('contractVersion', 'PENDING_APPROVAL').word, 'Waits for approval');
  assert.equal(statusWord('ordering', 'PER_KEY').word, 'Per key');
  assert.equal(statusWord('role', 'TARGET_REQUEST').word, 'Target request');
  assert.equal(statusWord('state', 'ROUTE_FAILED').word, 'Route failed');
});

test('tones follow spec 4.6: finished good = success, user has a next step = warn, something to fix = danger', () => {
  assert.equal(statusWord('flowVersion', 'PUBLISHED').tone, 'success');
  assert.equal(statusWord('flowVersion', 'DRAFT').tone, 'warn');
  assert.equal(statusWord('contractVersion', 'PENDING_APPROVAL').tone, 'warn');
  assert.equal(statusWord('state', 'DEAD_LETTERED').tone, 'danger');
  assert.equal(statusWord('state', 'RETRY_WAIT').tone, 'info');
  assert.equal(statusWord('stage', 'not-reached').tone, 'neutral');
});

test('an unknown value is worded safely (underscores to spaces, sentence case), neutral, and reported unknown', () => {
  assert.deepEqual(statusWord('state', 'WEIRD_THING'), { word: 'Weird thing', tone: 'neutral' });
  assert.equal(isKnownStatus('state', 'WEIRD_THING'), false);
  assert.deepEqual(statusWord('flowVersion', null), { word: '-', tone: 'neutral' });
  assert.deepEqual(statusWord('flowVersion', undefined), { word: '-', tone: 'neutral' });
});

test('the existing helpers read from the one table (no second vocabulary)', async () => {
  const { stateBadge, statusWord: stageWord, targetResponses } = await import('../../src/pages/message-timeline.ts');
  const { statusBadge } = await import('../../src/pages/dashboard-counts.ts');
  const { statusBadge: contractBadge, roleWord } = await import('../../src/pages/contracts/contracts-view.ts');
  const { orderingWord } = await import('../../src/pages/flow-lifecycle.ts');
  assert.deepEqual(stateBadge('ROUTE_FAILED'), statusWord('state', 'ROUTE_FAILED'));
  assert.equal(stageWord('not-reached'), statusWord('stage', 'not-reached').word);
  assert.deepEqual(statusBadge('SUBMITTED'), statusWord('flowVersion', 'SUBMITTED'));
  assert.deepEqual(contractBadge('PENDING_APPROVAL'), statusWord('contractVersion', 'PENDING_APPROVAL'));
  assert.equal(roleWord('TARGET_REQUEST'), statusWord('role', 'TARGET_REQUEST').word);
  assert.equal(orderingWord('PER_KEY'), statusWord('ordering', 'PER_KEY').word);
  assert.equal(targetResponses({ deliveries: [{ deliveryId: 'd', durationMs: 5, outcome: 'PERMANENT' }] })[0].text, '5 ms (Permanent)');
});

test('screens do not print raw enums: ordering options are worded, the state badge has no raw-enum tooltip', () => {
  const flow = src('pages/FlowDefinition.tsx');
  assert.doesNotMatch(flow, />PER_KEY<|>NONE</, 'ordering option labels');
  assert.match(flow, /<option value="PER_KEY">/, 'the option VALUE stays (e2e and the API use it)');
  assert.doesNotMatch(src('pages/MessageDetail.tsx'), /title=\{timeline\.state\}/, 'no raw-state tooltip');
  assert.match(src('pages/MessageDetail.tsx'), /data-state-raw=\{timeline\.state\}/, 'the data hook stays');
});
