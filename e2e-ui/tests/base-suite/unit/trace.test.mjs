// T131 (row 323, F21 BS-C, BS-09) - the trace oracle, FAILING FIRST. No docker: pure functions of
// tests/base-suite/lib/trace.mjs.
//
// BS-09 / T107 criteria: one correlation id set at intake - taken from an inbound traceparent when present
// - on every log line and Kafka header for intake, route, transform and deliver of that message; log lines
// are structured JSON carrying tenant id, message id and correlation id. Kafka records carry the outbox
// row's stored W3C trace context as the ONE header "traceparent" (KafkaPublishPort.java:41-42 on main).
// The JSON field names are T107's (on main efa7d1d, pending-names.mjs; no stage field) - here they are parameters.

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { correlationOf, headerProblems, logProblems, parseConsumerOutput, traceIdOf, traceparentOf } from '../lib/trace.mjs';

const TRACE = '4bf92f3577b34da6a3ce929d0e0e4736';
const TP = `00-${TRACE}-00f067aa0ba902b7-01`;
const MSG = '5a4b3c2d-0000-4000-8000-0000000000a1';
const TEN = '5a4b3c2d-0000-4000-8000-0000000000aa';
const FIELDS = { tenant: 'tenantId', message: 'messageId', correlation: 'correlationId', stage: 'stage' };
const STAGES = ['INTAKE', 'ROUTE', 'TRANSFORM', 'DELIVER'];
const line = (stage, extra = {}) => JSON.stringify({ level: 'INFO', msg: `stage ${stage} of ${MSG}`, tenantId: TEN, messageId: MSG, correlationId: TRACE, stage, ...extra });
const goodLog = () => `${STAGES.map((s) => line(s)).join('\n')}\n${JSON.stringify({ level: 'INFO', msg: 'scheduler tick' })}\n`;
const want = { messageId: MSG, tenantId: TEN, correlationId: TRACE, fields: FIELDS, stages: STAGES };

test('traceparentOf builds a valid W3C traceparent from given bytes; all-zero ids are refused', () => {
  const tp = traceparentOf(Buffer.from(TRACE, 'hex'), Buffer.from('00f067aa0ba902b7', 'hex'));
  assert.equal(tp, TP);
  assert.throws(() => traceparentOf(Buffer.alloc(16), Buffer.from('00f067aa0ba902b7', 'hex')), /all-zero/);
  assert.throws(() => traceparentOf(Buffer.alloc(3), Buffer.alloc(8, 1)), /16 bytes/);
});

test('traceIdOf: the trace id of a valid traceparent; null for anything else', () => {
  assert.equal(traceIdOf(TP), TRACE);
  for (const bad of [null, '', 'x', `ff-${TRACE}-00f067aa0ba902b7-01`, `00-${'0'.repeat(32)}-00f067aa0ba902b7-01`,
    `00-${TRACE}-${'0'.repeat(16)}-01`, `00-${TRACE.toUpperCase()}-00f067aa0ba902b7-01`]) {
    assert.equal(traceIdOf(bad), null, String(bad));
  }
});

test('correlationOf accepts the bare trace id or a full traceparent carrying it - nothing else', () => {
  assert.equal(correlationOf(TRACE), TRACE);
  assert.equal(correlationOf(TP), TRACE);
  assert.equal(correlationOf('req-123'), 'req-123', 'any other value is compared as is');
  assert.equal(correlationOf(undefined), null);
});

test('one id on every line of the message at all four stages, JSON everywhere: no problem', () => {
  assert.deepEqual(logProblems(goodLog(), want), []);
});

test('a line that is not JSON is named - every log line in the window is structured', () => {
  const problems = logProblems(`${goodLog()}2026-10-20 10:00:00 INFO plain text line\n`, want);
  assert.deepEqual(problems, ['log line 6 is not a JSON object: 2026-10-20 10:00:00 INFO plain text line']);
});

test('a line of the message missing tenant, message or correlation id fails, naming the field', () => {
  const noTenant = goodLog().replace(line('ROUTE'), line('ROUTE', { tenantId: undefined }));
  assert.match(logProblems(noTenant, want).join(';'), /line 2 .*tenantId undefined, not/);
  const otherId = goodLog().replace(line('TRANSFORM'), line('TRANSFORM', { correlationId: 'ffffffffffffffffffffffffffffffff' }));
  assert.match(logProblems(otherId, want).join(';'), /line 3 .*correlationId ffff/);
});

test('a stage with no line of the message fails; a log with no line of the message fails', () => {
  const noDeliver = goodLog().replace(`${line('DELIVER')}\n`, '');
  assert.deepEqual(logProblems(noDeliver, want), [`no log line of message ${MSG} at stage DELIVER`]);
  assert.match(logProblems(`${JSON.stringify({ msg: 'x' })}\n`, want).join(';'), /no log line names message/);
});

test('a line naming the message only inside a text field still has to carry the three ids', () => {
  const loose = `${goodLog()}${JSON.stringify({ level: 'WARN', msg: `retrying ${MSG}` })}\n`;
  assert.match(logProblems(loose, want).join(';'), /line 6 .*tenantId/);
});

test('parseConsumerOutput reads print.headers + print.key lines: headers, key, value', () => {
  const out = `traceparent:${TP}\t${MSG}\t{"messageId":"${MSG}"}\nNO_HEADERS\tk2\tv2\n`;
  assert.deepEqual(parseConsumerOutput(out), [
    { headers: { traceparent: TP }, key: MSG, value: `{"messageId":"${MSG}"}` },
    { headers: {}, key: 'k2', value: 'v2' }
  ]);
  assert.deepEqual(parseConsumerOutput(''), []);
});

test('headerProblems: every record of the message carries traceparent with the one trace id', () => {
  const ok = [{ headers: { traceparent: TP }, key: MSG, value: '{}' }, { headers: {}, key: 'other', value: '{}' }];
  assert.deepEqual(headerProblems(ok, { messageId: MSG, correlationId: TRACE, topic: 'platform.message.accepted' }), []);
  assert.match(headerProblems([{ headers: {}, key: MSG, value: '{}' }], { messageId: MSG, correlationId: TRACE, topic: 't' }).join(';'), /t: a record of .* has no traceparent/);
  const other = [{ headers: { traceparent: `00-${'a'.repeat(32)}-00f067aa0ba902b7-01` }, key: MSG, value: '{}' }];
  assert.match(headerProblems(other, { messageId: MSG, correlationId: TRACE, topic: 't' }).join(';'), /trace id a{32}, not/);
  assert.match(headerProblems([], { messageId: MSG, correlationId: TRACE, topic: 't' }).join(';'), /t: no record of message/);
});

test('headerProblems: ids names the records of a hop keyed by another id (delivery.due is keyed "delivery:<id>")', () => {
  const DEL = '7d0c4b1e-0000-4000-8000-000000000001';
  const due = [{ headers: { traceparent: TP }, key: `delivery:${DEL}`, value: `{"v":1,"type":"delivery.due","id":"${DEL}"}` }];
  assert.match(headerProblems(due, { messageId: MSG, correlationId: TRACE, topic: 'd' }).join(';'), /d: no record of message/, 'by default only the message id names a record');
  assert.deepEqual(headerProblems(due, { messageId: MSG, ids: [DEL], correlationId: TRACE, topic: 'd' }), []);
  assert.match(headerProblems([{ ...due[0], headers: {} }], { messageId: MSG, ids: [DEL], correlationId: TRACE, topic: 'd' }).join(';'), /d: a record of .* has no traceparent/);
  assert.match(headerProblems(due, { messageId: MSG, ids: [], correlationId: TRACE, topic: 'd' }).join(';'), /d: no record of message/, 'an empty id list never passes');
});
