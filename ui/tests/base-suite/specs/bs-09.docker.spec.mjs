// T131 (row 323, F21 BS-C) BS-09 - follow one POST through the logs and the Kafka headers (WD9, trace).
// Tenant A posts ONE record WITH a fresh inbound traceparent; T107: the correlation id is taken from it,
// so the one id is known before the POST - its trace id. Then:
//   logs   every app log line written meanwhile is a JSON object; every line naming the message carries
//          its tenant, message and correlation ids. T107 on main efa7d1d has NO stage field
//          (CorrelationContext.java:31), so the per-stage log check is skipped with that reason
//          (annotation "skip") - the stages are proven by the T105 rows below, never by a made-up field
//   stages the message has ONE T105 timing row per stage (INTAKE, ROUTE, TRANSFORM, DELIVER), each
//          finished, in pipeline order (public.message_stage_timing, read-only, tenant-scoped)
//   Kafka  every record of the message carries the header "traceparent" (KafkaPublishPort.java:42) with the
//          same trace id: platform.message.accepted by message id, platform.delivery.due by its delivery
//          ids (keyed "delivery:<id>", JdbcDeliveryRepository.java:471-474)
//
// Status lives in the gate (waitsOn / lib/pending-names.mjs) and the run report _reports/2026-10-22-base-suite-run.md, not in this header.
// Read-only: logs, Kafka and SQL are only read.

import { randomBytes } from 'node:crypto';
import { expect, test } from '@playwright/test';
import { deliveryOfMessageSql, stageTimingsOfMessageSql, tenantIdSql } from '../lib/direct-sql.mjs';
import { absentReason, nameOf, waitsOn } from '../lib/pending-names.mjs';
import { timelineOfRows, timelineProblems } from '../lib/timeline.mjs';
import { FLOW, SOURCE_CONTRACT, TENANTS, orderRecord } from '../lib/tenants.mjs';
import { ACCEPTED_TOPIC, headerProblems, logProblems, traceIdOf, traceparentOf } from '../lib/trace.mjs';
import { appLogFrom, appLogSize, eventually, postRecord, sourceToken } from '../support/api.mjs';
import { bsEnv } from '../support/boot-state.mjs';
import { readOne, readRows } from '../support/db.mjs';
import { topicRecords } from '../support/kafka.mjs';
import { addTracking, readRun } from '../support/run-file.mjs';
import { intakeCode, rotatedSecret } from '../support/seed.mjs';

// BS-09 needs features not on main yet: ONE flag per T-item (ON_MAIN in pending-names.mjs). While one is
// missing the file is test.fixme - reported as fixme with the item named, never a silent pass.
const WAITS = waitsOn('T105', 'T107');
test.fixme(WAITS !== null, WAITS ?? '');

const PIPELINE_MS = 90000;
const csv = (value) => value.split(',').map((s) => s.trim()).filter(Boolean);

test('BS-09: one correlation id at intake, route, transform and deliver - JSON log lines and Kafka headers', async ({ page }) => {
  // fail closed BEFORE any post: every T107 / T106 name this scenario reads
  const fields = {
    tenant: nameOf('logFieldTenant'), message: nameOf('logFieldMessage'), correlation: nameOf('logFieldCorrelation')
  };
  // the stage leg of the log check runs only if main has a stage field; else it is skipped, saying why
  const noStage = absentReason('logFieldStage') ?? absentReason('logStageValues');
  let stages = [];
  if (noStage === null) {
    fields.stage = nameOf('logFieldStage');
    stages = csv(nameOf('logStageValues'));
    expect(stages, 'one stage value each for intake, route, transform, deliver').toHaveLength(4);
  } else {
    test.info().annotations.push({ type: 'skip', description: `log stage leg: ${noStage}; the stages are proven by the T105 timing rows` });
  }
  const topics = [ACCEPTED_TOPIC, ...csv(nameOf('kafkaStageTopics'))];
  nameOf('kafkaContainerEnv');
  nameOf('kafkaConsumer');

  await page.goto(`${bsEnv('consoleUrl')}/`);
  const code = bsEnv('tenantA');
  const { clientId, secretValue } = await rotatedSecret(page, TENANTS.A, readRun().seed.A.credentialId);
  const token = await sourceToken(clientId, secretValue);
  const traceparent = traceparentOf(randomBytes(16), randomBytes(8));
  const correlationId = traceIdOf(traceparent);

  const from = await appLogSize();
  const answer = await postRecord(token, intakeCode(TENANTS.A), SOURCE_CONTRACT.versionLabel, orderRecord(TENANTS.A, 3), { traceparent });
  expect(answer.status, JSON.stringify(answer.data)).toBe(202);
  const messageId = answer.data.trackingId;
  addTracking('A', [messageId]);

  // TBD(T106): the relay + consumers that take the message to its target
  await eventually(() => readRows(deliveryOfMessageSql(code, messageId)).some(([, state]) => state === 'DELIVERED'),
    `message ${messageId} delivered`, { timeoutMs: PIPELINE_MS });
  const deliveryIds = readRows(deliveryOfMessageSql(code, messageId)).map(([id]) => id);

  const want = { messageId, tenantId: readOne(tenantIdSql(code)), correlationId, fields, stages };
  let problems = ['not read yet'];
  await eventually(async () => {
    problems = logProblems(await appLogFrom(from), want);
    return problems.length === 0;
  }, 'the log lines of the message', { timeoutMs: 15000 }).catch(() => {}); // the assertion below reports what is missing
  expect(problems, `every log line JSON; the message's lines carry tenant, message and correlation ids${noStage === null ? ' at all four stages' : ''}`).toEqual([]);

  // T105: the DELIVER row is written by the final move, in its transaction - so it is there once DELIVERED is
  const timingRows = readRows(stageTimingsOfMessageSql(code, messageId));
  const timings = timelineOfRows(messageId, timingRows);
  expect(timelineProblems(timings, messageId), `one finished T105 timing row per stage, in pipeline order; rows (stage|started|finished): ${JSON.stringify(timingRows)}`).toEqual([]);

  for (const topic of topics) {
    const ids = topic === ACCEPTED_TOPIC ? [messageId] : [messageId, ...deliveryIds]; // delivery.due names the delivery
    expect(headerProblems(topicRecords(topic), { messageId, ids, correlationId, topic }), `${topic}: traceparent carries the one id`).toEqual([]);
  }
});
