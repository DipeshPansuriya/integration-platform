// T131 (row 323, F21 BS-C) - the ONE registry of names BS-08 / BS-09 / BS-10 use that are not on main yet,
// FAILING FIRST. No docker: pure functions of tests/base-suite/lib/pending-names.mjs.
//
// Two kinds of entry, one line each, so the Tue 20 run swaps one line per name:
//   provisional  measured on a lane (t112 @21f6835 / T110 2a9c835), not on main - used as is, re-measured Tue 20
//   TBD(<item>)  not known until the item lands - the scenario FAILS on it with the item named, never guesses

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { NAMES, ON_MAIN, absentReason, fill, isPending, nameOf, pendingNames, waitsOn } from '../lib/pending-names.mjs';

test('the screens and reads measured on lane t112 / T110 are provisional values, not placeholders', () => {
  assert.equal(nameOf('detailRoute'), '#/messages/{trackingId}');
  assert.equal(nameOf('failuresRoute'), '#/failures');
  assert.equal(nameOf('timelineApi'), '/api/v1/admin/messages/{trackingId}/timeline');
  assert.equal(nameOf('failuresApi'), '/api/v1/admin/messages/failures');
});

test('the T107 log fields are on main efa7d1d (CorrelationContext.java:25-28)', () => {
  assert.equal(nameOf('logFieldTenant'), 'tenantId');
  assert.equal(nameOf('logFieldMessage'), 'messageId');
  assert.equal(nameOf('logFieldCorrelation'), 'correlationId');
});

test('a TBD name fails the step, naming the key and the item it waits for', () => {
  assert.throws(() => nameOf('replayApi'), /replayApi is still TBD\(T111\).*pending-names\.mjs/);
  assert.throws(() => nameOf('auditActor'), /TBD\(T111\)/, 'T109 is on main; the replay writer T111 sets the actor');
});

test('the Kafka harness names are set by T130: the boot names its broker container and the in-container consumer', () => {
  assert.equal(nameOf('kafkaContainerEnv'), 'BS_KAFKA_CONTAINER');
  assert.equal(nameOf('kafkaConsumer'), '/opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:19092');
});

test('the hop after platform.message.accepted on main efa7d1d is platform.delivery.due (OutboxTopic.java:13)', () => {
  assert.equal(nameOf('kafkaStageTopics'), 'platform.delivery.due');
});

test('a name T107 does not have is null with a reason - never a made-up name, never a TBD that waits forever', () => {
  for (const key of ['logFieldStage', 'logStageValues']) {
    assert.equal(NAMES[key], null, key);
    assert.match(absentReason(key), /no stage field.*CorrelationContext\.java:31.*efa7d1d/, key);
    assert.throws(() => nameOf(key), new RegExp(`${key} is not on main: no stage field`), key);
  }
  assert.equal(absentReason('logFieldTenant'), null, 'a present name has no absence reason');
  assert.throws(() => absentReason('nope'), /unknown name nope/);
});

test('ON_MAIN: one boolean per T-item, frozen, measured on main efa7d1d', () => {
  assert.equal(Object.isFrozen(ON_MAIN), true);
  for (const item of ['T105', 'T106', 'T107', 'T109', 'T110', 'T138']) assert.equal(ON_MAIN[item], true, item);
  for (const item of ['T111', 'T112', 'T113']) assert.equal(ON_MAIN[item], false, item);
});

test('waitsOn: null when every item is on main, else one reason naming each missing item and the flag to flip', () => {
  assert.equal(waitsOn('T110'), null);
  assert.equal(waitsOn('T106', 'T109'), null);
  assert.equal(waitsOn('T105', 'T107'), null, 'BS-09 runs once T105 + T107 are on main');
  const reason = waitsOn('T110', 'T112', 'T111');
  assert.match(reason, /waits on T112, T111/);
  assert.match(reason, /ON_MAIN.*pending-names\.mjs/);
  assert.throws(() => waitsOn('T999'), /unknown item T999/, 'a typo never reads as landed');
  assert.throws(() => waitsOn(), /at least one item/);
});

test('an unknown key is refused - a typo never reads as a name', () => {
  assert.throws(() => nameOf('replayAPI'), /unknown name replayAPI/);
});

test('isPending: any value reading TBD( - any case - is a placeholder', () => {
  assert.equal(isPending('TBD(T111)'), true);
  assert.equal(isPending('tbd(t111)'), true);
  assert.equal(isPending('#/failures'), false);
  assert.equal(isPending(''), true, 'an empty name is never a configuration');
});

test('pendingNames lists every placeholder still open - the handoff list comes from here', () => {
  const open = pendingNames();
  assert.ok(open.length >= 4, `at least the T111 names: ${JSON.stringify(open)}`);
  for (const [key, value] of open) {
    assert.ok(isPending(value), key);
    assert.match(value, /^TBD\(T\d{3}(\|T\d{3})*\)$/, `${key}: one item-named placeholder`);
  }
  assert.ok(open.some(([key]) => key === 'replayApi'));
  assert.ok(!open.some(([key]) => key === 'detailRoute'));
  assert.ok(!open.some(([key]) => key === 'logFieldStage' || key === 'kafkaStageTopics'), 'absent and landed names are not open');
});

test('the registry is frozen - a spec cannot patch a name at run time', () => {
  assert.ok(Object.keys(NAMES).length > 0, 'the registry has names');
  assert.equal(Object.isFrozen(NAMES), true);
});

test('fill: each {param} is replaced, URI-encoded; a missing param or a leftover brace fails', () => {
  assert.equal(fill('#/messages/{trackingId}', { trackingId: 'a b' }), '#/messages/a%20b');
  assert.equal(fill('/x/{a}/y/{b}', { a: '1', b: '2' }), '/x/1/y/2');
  assert.throws(() => fill('/x/{a}', {}), /missing a/);
  assert.throws(() => fill('TBD(T111)', {}), /still TBD/);
});
