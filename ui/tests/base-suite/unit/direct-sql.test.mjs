// T130 (row 323, F21 BS-B) step 6: the count oracle's direct reads are ONE read-only SELECT each, scoped
// to one tenant by its code, and no base-suite source ever writes a pipeline row (BS-12: no hand-seeded rows).

import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { adminUserIdSql, deliveryRowsSql, intakeTimingSql, messageRowsSql, outboxAggregatesSql, stageTimingsOfMessageSql, tenantIdSql } from '../lib/direct-sql.mjs';
import { isReadOnlySelect, pipelineWrites } from '../lib/source-scan.mjs';

const SUITE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CODE = 'lane-d-0a1b2c-bs-b';

test('every direct read is one read-only SELECT naming the tenant code', () => {
  for (const sql of [tenantIdSql(CODE), adminUserIdSql(CODE), messageRowsSql(CODE), deliveryRowsSql(CODE), outboxAggregatesSql(CODE), intakeTimingSql(CODE)]) {
    assert.ok(isReadOnlySelect(sql), sql);
    assert.ok(sql.includes(`'${CODE}'`), `scoped to ${CODE}: ${sql}`);
  }
});

test('the message and delivery reads mirror the queue summary columns (interface_id, state, UTC day)', () => {
  assert.match(messageRowsSql(CODE), /select m\.id::text, m\.interface_id::text, m\.state, to_char\(m\.accepted_at at time zone 'UTC', 'YYYY-MM-DD'\)/);
  assert.match(deliveryRowsSql(CODE), /select d\.message_id::text, d\.interface_id::text, d\.state, to_char\(d\.created_at at time zone 'UTC', 'YYYY-MM-DD'\)/);
  assert.match(outboxAggregatesSql(CODE), /topic = 'platform\.message\.accepted'/);
  assert.match(intakeTimingSql(CODE), /stage = 'INTAKE'/);
});

test('BS-10 actor: the tenant admin user id is one tenant-scoped read through app_user and role_grant', () => {
  const sql = adminUserIdSql(CODE);
  assert.match(sql, /^select u\.id::text from public\.app_user u join public\.role_grant g on g\.user_id = u\.id and g\.tenant_id = u\.tenant_id where u\.tenant_id = /);
  assert.match(sql, /g\.role = 'TENANT_ADMIN'$/);
  assert.throws(() => adminUserIdSql("x' or '1'='1"), /tenant code/);
});

const MESSAGE = '3c2b1a09-0000-4000-8000-0000000000d9';

test('BS-09 stage proof: ONE read-only SELECT of one message\'s T105 timing rows, tenant-scoped, UTC ISO millis, pipeline order', () => {
  const sql = stageTimingsOfMessageSql(CODE, MESSAGE);
  assert.ok(isReadOnlySelect(sql), sql);
  assert.ok(sql.includes(`'${CODE}'`) && sql.includes(`'${MESSAGE}'`), sql);
  assert.match(sql, /^select s\.stage, to_char\(s\.started_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS\.MS"Z"'\), coalesce\(to_char\(s\.finished_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS\.MS"Z"'\), ''\) from public\.message_stage_timing s /);
  assert.match(sql, /order by s\.started_at, s\.stage$/);
  for (const bad of ["x' or '1'='1", 'not-a-uuid', '', null]) assert.throws(() => stageTimingsOfMessageSql(CODE, bad), /uuid|id/i, String(bad));
  assert.throws(() => stageTimingsOfMessageSql("x' or '1'='1", MESSAGE), /tenant code/);
});

test('a tenant code that could leave its literal is refused', () => {
  for (const bad of ["x' or '1'='1", 'UPPER', '', null, 'a;b']) {
    assert.throws(() => messageRowsSql(bad), /tenant code/);
  }
});

test('no base-suite source writes a pipeline row (message, delivery, outbox, timing, attempt, event, payload, audit)', () => {
  const sources = [];
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.name.endsWith('.mjs') && !full.includes(`${path.sep}unit${path.sep}`)) sources.push(full);
    }
  };
  walk(SUITE);
  assert.ok(sources.some((file) => file.endsWith('bs-12.docker.spec.mjs')), 'the scan covers the specs');
  for (const file of sources) {
    assert.deepEqual(pipelineWrites(fs.readFileSync(file, 'utf8')), [], file);
  }
});
