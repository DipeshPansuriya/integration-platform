// T131 (row 323, F21 BS-C) BS-09 - follow one POST through the logs and the Kafka headers (WD9, trace).
// Tenant A posts ONE record WITH a fresh inbound traceparent; T107: the correlation id is taken from it,
// so the one id is known before the POST - its trace id. Then:
//   logs   every app log line written meanwhile is a JSON object; every line naming the message carries
//          its tenant, message and correlation ids; intake, route, transform and deliver each have one
//   Kafka  every record of the message, on platform.message.accepted and the later hops, carries the
//          header "traceparent" (KafkaPublishPort.java:41-42) with the same trace id
//
// Expected RED today, in this order:
//   1. global-setup: "dev-only exception not configured - waits for row 278" (or "still TBD(row278)");
//   2. "logFieldTenant is still TBD(T107)" - the JSON field names land with T107; and the Kafka reads
//      "kafkaContainerEnv is still TBD(T106)" - the base-suite boot starts no Kafka (harness gap);
//   3. with the names swapped, before T106: the message is never delivered (nothing consumes
//      platform.message.accepted) - the DELIVERED wait times out;
//   4. before T107: the log lines are plain text, and no line carries the correlation id.
// Green needs T106, T107 and row 278's exception after T137. Read-only: logs, Kafka and SQL are only read.

import { randomBytes } from 'node:crypto';
import { expect, test } from '@playwright/test';
import { deliveryOfMessageSql, tenantIdSql } from '../lib/direct-sql.mjs';
import { nameOf } from '../lib/pending-names.mjs';
import { FLOW, SOURCE_CONTRACT, TENANTS, orderRecord } from '../lib/tenants.mjs';
import { ACCEPTED_TOPIC, headerProblems, logProblems, traceIdOf, traceparentOf } from '../lib/trace.mjs';
import { appLogFrom, appLogSize, eventually, postRecord, sourceToken } from '../support/api.mjs';
import { bsEnv } from '../support/boot-state.mjs';
import { readOne, readRows } from '../support/db.mjs';
import { topicRecords } from '../support/kafka.mjs';
import { addTracking, readRun } from '../support/run-file.mjs';
import { rotatedSecret } from '../support/seed.mjs';

const PIPELINE_MS = 90000;
const csv = (value) => value.split(',').map((s) => s.trim()).filter(Boolean);

test('BS-09: one correlation id at intake, route, transform and deliver - JSON log lines and Kafka headers', async ({ page }) => {
  // fail closed BEFORE any post: every T107 / T106 name this scenario reads
  const fields = {
    tenant: nameOf('logFieldTenant'), message: nameOf('logFieldMessage'),
    correlation: nameOf('logFieldCorrelation'), stage: nameOf('logFieldStage')
  };
  const stages = csv(nameOf('logStageValues'));
  expect(stages, 'one stage value each for intake, route, transform, deliver').toHaveLength(4);
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
  const answer = await postRecord(token, FLOW.code, SOURCE_CONTRACT.versionLabel, orderRecord(TENANTS.A, 3), { traceparent });
  expect(answer.status, JSON.stringify(answer.data)).toBe(202);
  const messageId = answer.data.trackingId;
  addTracking('A', [messageId]);

  // TBD(T106): the relay + consumers that take the message to its target
  await eventually(() => readRows(deliveryOfMessageSql(code, messageId)).some(([, state]) => state === 'DELIVERED'),
    `message ${messageId} delivered`, { timeoutMs: PIPELINE_MS });

  const want = { messageId, tenantId: readOne(tenantIdSql(code)), correlationId, fields, stages };
  let problems = ['not read yet'];
  await eventually(async () => {
    problems = logProblems(await appLogFrom(from), want);
    return problems.length === 0;
  }, 'the log lines of the message', { timeoutMs: 15000 }).catch(() => {}); // the assertion below reports what is missing
  expect(problems, 'every log line JSON; the message\'s lines carry tenant, message and correlation ids at all four stages').toEqual([]);

  for (const topic of topics) {
    expect(headerProblems(topicRecords(topic), { messageId, correlationId, topic }), `${topic}: traceparent carries the one id`).toEqual([]);
  }
});
