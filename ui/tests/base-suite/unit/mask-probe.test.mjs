// T133 (row 323, F21 BS-11): the pure checks behind the masked-field probe (lib/mask-probe.mjs).
// node --test tests/base-suite/unit/mask-probe.test.mjs - no browser, no docker, no lock.

import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  MASKED_FIELD, MASK_NAMES, PROBE, bodyProblems, declareCall, echoStubMapping, isPendingName, leakingLines,
  maskName, probeForms, probeHits, probeRecord, redact, waitsFor
} from '../lib/mask-probe.mjs';
import { stubMapping } from '../lib/wiremock.mjs';

const b64 = (s) => Buffer.from(s, 'utf8').toString('base64');

test('the probe is the agreed value and sits in the masked field', () => {
  assert.equal(PROBE, 'MASK-PROBE-7Q3Z');
  assert.equal(MASKED_FIELD, 'customerId');
  assert.deepEqual(probeRecord(11), { customerId: PROBE, orderId: 'A-ORD-0011', amount: 111.25 });
  assert.equal(probeRecord(13, { amount: 'not-a-number' }).amount, 'not-a-number');
  assert.throws(() => probeRecord(0), /outside/);
});

test('plain hits: any case, every occurrence', () => {
  assert.deepEqual(probeHits('nothing here'), []);
  assert.equal(probeHits(`a ${PROBE} b ${PROBE.toLowerCase()}`).length, 2);
  assert.equal(probeHits(JSON.stringify({ customerId: PROBE })).length, 1, 'JSON escaping leaves the probe as is');
  assert.equal(probeHits(encodeURIComponent(PROBE)).length, 1, 'URL encoding leaves the probe as is');
});

test('base64 hits at all three alignments, inside a longer encoded body', () => {
  for (const prefix of ['', 'x', 'xy', '{"customerId":"', 'abcd']) {
    const encoded = b64(`${prefix}${PROBE}","orderId":"A-ORD-0011"}`);
    assert.ok(probeHits(`body=${encoded}`).some((h) => h.form === 'base64'), `prefix length ${prefix.length}`);
  }
  assert.equal(probeForms().base64.length, 3);
  assert.deepEqual(probeHits(b64('{"customerId":"A-CUST-0001"}')), [], 'an unmasked body is not a hit');
});

test('reports never re-print the probe', () => {
  const log = `INFO ok\nWARN record ${PROBE} refused\nDEBUG raw=${b64(`{"c":"${PROBE}"}`)}`;
  const lines = leakingLines(log);
  assert.equal(lines.length, 2);
  for (const line of lines) assert.deepEqual(probeHits(line), [], line);
  assert.match(lines[0], /^line 2: .*<PROBE>/);
  assert.deepEqual(probeHits(redact(`${PROBE} ${PROBE.toLowerCase()}`)), []);
  for (const problem of bodyProblems({ lastError: `echo ${PROBE}` })) assert.deepEqual(probeHits(problem), [], problem);
});

test('bodyProblems: a leak and a missing mask marker are both problems', () => {
  assert.deepEqual(bodyProblems({ customerId: '***' }, { marker: '***' }), []);
  assert.equal(bodyProblems({ customerId: PROBE }).length, 1);
  assert.match(bodyProblems({ customerId: '' }, { marker: '***' })[0], /not shown as "\*\*\*"/);
  assert.deepEqual(bodyProblems([]), [], 'an empty list carries no leak');
});

test('the landed T108 / T112 mask names hold exactly, and the fail-closed rule stays', () => {
  assert.equal(maskName('maskApiMarker'), '[MASKED]');
  assert.equal(maskName('maskScreenLastCell'), '[data-last-error]');
  assert.deepEqual(declareCall(), ['PUT', '/api/v1/admin/flows/{flowId}',
    { businessKeyRule: '$.orderId', sourceSystem: 'customer-a', destinations: ['target-b'], maskedFields: ['customerId'] }]);
  assert.deepEqual(waitsFor(...Object.keys(MASK_NAMES)), [], 'no mask name is pending');
  assert.ok(isPendingName('TBD(T999)') && isPendingName('') && isPendingName(undefined) && !isPendingName('[data-masked]'));
  assert.throws(() => maskName('nope'), /unknown name/);
});

test('the echo stub reuses the fixed stub id so setStub(path, 200) restores it', () => {
  const echo = echoStubMapping('/tenant-a/orders');
  const plain = stubMapping('/tenant-a/orders', 403);
  assert.equal(echo.id, plain.id);
  assert.deepEqual(echo.request, plain.request);
  assert.equal(echo.priority, plain.priority);
  assert.equal(echo.response.status, 403);
  assert.deepEqual(echo.response.transformers, ['response-template']);
  assert.match(echo.response.body, /\{\{\{request\.body\}\}\}/);
  assert.equal(echo.response.jsonBody, undefined, 'body and jsonBody never both');
});
