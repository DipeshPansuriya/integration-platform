// T133 (row 323, F21 BS-11): the pure checks behind the masked-field probe (lib/mask-probe.mjs).
// node --test tests/base-suite/unit/mask-probe.test.mjs - no browser, no docker, no lock.

import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  MASKED_FIELD, MASK_NAMES, PROBE, acceptanceProblems, bodyProblems, contractRefusalProblems, leakingAnswers, declareCall, echoStubMapping, isPendingName, leakingLines,
  maskName, probeForms, probeHits, probeRecord, redact, waitsFor
} from '../lib/mask-probe.mjs';
import { LINKS, SOURCE_CONTRACT, TENANTS, expectedTargetBody } from '../lib/tenants.mjs';
import { stubMapping } from '../lib/wiremock.mjs';

const b64 = (s) => Buffer.from(s, 'utf8').toString('base64');

test('the probe is the agreed value and sits in the masked field', () => {
  assert.equal(PROBE, 'MASK-PROBE-7Q3Z');
  assert.equal(MASKED_FIELD, 'cardNumber', 'T206: not customerId - the identity field the platform checks against the credential');
  assert.deepEqual(probeRecord(11), { customerId: 'A-CUST-0001', cardNumber: PROBE, orderId: 'A-ORD-0011', amount: 111.25 });
  assert.notEqual(probeRecord(11).customerId, PROBE, 'B36: the probe never sits in the identity field');
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

test('the T108 names are measured on main; the screen leg is dropped (Dipesh, option 1): no screen mask names', () => {
  assert.equal(maskName('maskApiMarker'), '[MASKED]', 'MaskedFields.java:33');
  assert.equal(maskName('maskDeclareApi'), 'PUT /api/v1/admin/flows/{flowId}', 'FlowAdminController.java:112');
  for (const key of ['maskScreenField', 'maskScreenMarker']) {
    assert.equal(Object.hasOwn(MASK_NAMES, key), false, `${key} is gone: the T112 screens carry no payload field`);
    assert.throws(() => maskName(key), /unknown name/);
  }
  assert.throws(() => maskName('nope'), /unknown name/);
  assert.deepEqual(waitsFor(...Object.keys(MASK_NAMES)), [], 'no mask name is pending');
  assert.ok(isPendingName('') && isPendingName(undefined) && isPendingName('TBD(T108)') && !isPendingName('[MASKED]'));
});

test('declareCall: the flow draft-save carrying maskedFields, built from what the flow read answered', () => {
  const current = { code: 'flow-1', name: 'orders', sourceSystem: 'src', destinations: ['dst'], businessKeyRule: 'orderId', ordering: 'NONE', maskedFields: [], status: 'PUBLISHED', id: 'x' };
  const [method, path, body] = declareCall('f/1', current);
  assert.equal(method, 'PUT');
  assert.equal(path, '/api/v1/admin/flows/f%2F1');
  assert.deepEqual(body, { name: 'orders', sourceSystem: 'src', destinations: ['dst'], businessKeyRule: 'orderId', ordering: 'NONE', maskedFields: [MASKED_FIELD] });
  assert.throws(() => declareCall('', current), /flowId/);
  assert.throws(() => declareCall('f1', { name: 'orders' }), /sourceSystem/);
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

// ---- T206 (B36): the probe must reach intake, and every leak assertion must be able to fail ----

test('B36: the masked field is a non-identity contract field that reaches the target; the probe never sits in customerId', () => {
  assert.ok(Object.hasOwn(SOURCE_CONTRACT.document.properties, MASKED_FIELD), 'the masked field is in the seeded source contract');
  assert.ok(!['customerId', 'clientId'].includes(MASKED_FIELD), 'not an identity field - the platform checks customerId against the credential');
  const target = LINKS.find(([from]) => from === MASKED_FIELD)?.[1];
  assert.ok(target, 'the masked field is linked, so the delivery carries the real value');
  const record = probeRecord(11);
  assert.equal(record.customerId, TENANTS.A.customerId, 'the credential\'s own customer, so SourceCustomerFilter lets it through');
  assert.equal(expectedTargetBody(record)[target], PROBE, 'positive control: the target gets the real probe');
  assert.equal(Object.values(record).filter((v) => v === PROBE).length, 1);
});

test('acceptanceProblems: only a 202 with a trackingId passes - a 403 can never look like a pass (B36)', () => {
  assert.deepEqual(acceptanceProblems({ status: 202, data: { trackingId: 'abc' } }), []);
  const refused = acceptanceProblems({ status: 403, data: { code: 'BODY_CUSTOMER' } });
  assert.equal(refused.length, 2, 'the status and the missing tracking id');
  assert.match(refused[0], /403, not 202/);
  assert.equal(acceptanceProblems({ status: 202, data: {} }).length, 1);
  assert.equal(acceptanceProblems(undefined).length, 2);
  for (const problem of acceptanceProblems({ status: 422, data: { message: `bad ${PROBE}` } })) assert.deepEqual(probeHits(problem), [], 'a report never re-prints the probe');
});

test('contractRefusalProblems: 400/422 naming the field passes; the identity gate, a 202, a 5xx and a nameless body fail', () => {
  const good = { status: 422, data: { code: 'CONTRACT_INVALID', message: 'amount: must be a number' } };
  assert.deepEqual(contractRefusalProblems(good, 'amount'), []);
  assert.deepEqual(contractRefusalProblems({ status: 400, data: { code: 'SCHEMA_TYPE', violations: [{ field: 'amount' }] } }, 'amount'), []);
  assert.ok(contractRefusalProblems({ status: 403, data: { code: 'BODY_CUSTOMER' } }, 'amount').length >= 2, 'the B36 answer is not a contract refusal');
  assert.match(contractRefusalProblems({ status: 403, data: { code: 'BODY_CUSTOMER' } }, 'amount').join('|'), /identity gate/);
  assert.ok(contractRefusalProblems({ status: 202, data: { trackingId: 'x' } }, 'amount').length > 0, 'an accepted record is no refusal');
  assert.ok(contractRefusalProblems({ status: 503, data: { code: 'INTAKE_UNAVAILABLE', message: 'amount' } }, 'amount').length > 0);
  assert.ok(contractRefusalProblems({ status: 422, data: { code: 'X' } }, 'amount').some((p) => /does not name/.test(p)));
  assert.ok(contractRefusalProblems({ status: 422, data: {} }, 'amount').some((p) => /no reason/.test(p)));
});

test('negative controls: the BS-11.2 / 11.4 leak assertions each FAIL on a planted probe and pass on a masked body', () => {
  const marker = maskName('maskApiMarker');
  // 11.2: failures API (last error) and detail API - the echoed probe must be replaced by the marker
  const leaking = [{ trackingId: 't', lastError: `target 403 {"echo":{"cardNumber":"${PROBE}"}}` }];
  const masked = [{ trackingId: 't', lastError: `target 403 {"echo":{"cardNumber":"${marker}"}}` }];
  assert.ok(bodyProblems(leaking, { marker }).length >= 1, '11.2 fails on a leaked probe');
  assert.deepEqual(bodyProblems(masked, { marker }), [], '11.2 passes on a masked error text');
  assert.ok(bodyProblems([{ trackingId: 't', lastError: 'target 403' }], { marker }).length === 1, '11.2 also fails when the mask marker is absent (nothing was masked)');
  assert.ok(bodyProblems({ timeline: [{ stage: 'DELIVER', detail: b64(`{"cardNumber":"${PROBE}"}`) }] }).length >= 1, '11.2 detail: a base64 probe fails too');
  // 11.4: every API answer a screen loaded
  const answers = [{ url: '/api/v1/admin/messages/failures', text: JSON.stringify(masked) }, { url: '/api/v1/admin/messages/1/timeline', text: `x ${PROBE.toLowerCase()} y` }];
  assert.deepEqual(leakingAnswers(answers), ['/api/v1/admin/messages/1/timeline'], '11.4 names the leaking answer');
  assert.deepEqual(leakingAnswers([answers[0]]), [], '11.4 passes when none leaks');
  assert.deepEqual(leakingAnswers(undefined), []);
  // the log slice leg shared by 11.1-11.3
  assert.equal(leakingLines(`ok\nintake accepted ${PROBE}\n`).length, 1);
  assert.deepEqual(leakingLines('ok\nintake accepted [MASKED]\n'), []);
});
