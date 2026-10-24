// T130 (row 323, F21 BS-B) - static guards over the base suite's own sources, FAILING FIRST. No
// docker: pure functions of tests/base-suite/lib/source-scan.mjs, then a scan of the real files.
//
// Hard limits of the dispatch: no psql INSERT into message, delivery, outbox or stage-timing rows (nor
// attempts, events, payloads or audit rows) anywhere in BS-07 / BS-10 / BS-12 - those rows must come
// from the real pipeline; tenants, users and roles may follow the existing role_grant pattern. Every
// direct read the oracle makes is ONE read-only statement. The exception settings name no IP literal.

import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { PIPELINE_TABLES, ipLiterals, isReadOnlySelect, pipelineWrites } from '../lib/source-scan.mjs';

const SUITE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

function suiteSources(dir = SUITE) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) return entry.name === 'unit' ? [] : suiteSources(full);
    return entry.name.endsWith('.mjs') ? [full] : [];
  });
}

test('the pipeline tables are the rows the real pipeline writes', () => {
  assert.deepEqual([...PIPELINE_TABLES].sort(), ['audit_event', 'delivery', 'delivery_attempt', 'delivery_event',
    'message', 'message_payload', 'message_stage_timing', 'outbox'].sort());
});

test('pipelineWrites finds every write into a pipeline table, and only those', () => {
  const hits = (text) => pipelineWrites(text).map((hit) => hit.table);
  assert.deepEqual(hits("psql(`insert into public.message (tenant_id, state) values ('t', 'ROUTED')`)"), ['message']);
  assert.deepEqual(hits('INSERT INTO delivery (id) VALUES (1)'), ['delivery']);
  assert.deepEqual(hits('update public.delivery set state = \'DUE\''), ['delivery']);
  assert.deepEqual(hits('delete from public.outbox where id = 1'), ['outbox']);
  assert.deepEqual(hits('insert into public.message_stage_timing (stage) values (1)'), ['message_stage_timing']);
  assert.deepEqual(hits('truncate public.delivery_attempt'), ['delivery_attempt']);
  assert.deepEqual(hits('insert\n  into public.audit_event (actor) values (1)'), ['audit_event']);
  assert.deepEqual(hits('select count(*) from public.message where state = \'ROUTED\''), []);
  assert.deepEqual(hits("insert into public.tenant (code, status) values ('x', 'ACTIVE')"), [], 'tenants may be seeded');
  assert.deepEqual(hits('insert into public.role_grant (tenant_id, user_id, role) select 1'), [], 'roles may be seeded');
  assert.deepEqual(hits('insert into public.messages_archive (x) values (1)'), [], 'a longer name is another table');
  const [first] = pipelineWrites('line one\nline two\ninsert into public.delivery (x) values (1)');
  assert.equal(first.line, 3);
});

test("the base suite's own sources write no pipeline row", () => {
  const files = suiteSources();
  assert.ok(files.length > 0, 'the scan found the suite sources');
  const writes = files.flatMap((file) => pipelineWrites(fs.readFileSync(file, 'utf8'))
    .map((hit) => `${path.relative(SUITE, file)}:${hit.line} ${hit.table}`));
  assert.deepEqual(writes, []);
});

test('isReadOnlySelect accepts ONE read and refuses any write or second statement', () => {
  for (const sql of ['select 1', ' SELECT id from public.message where tenant_id = $$x$$ ', 'with x as (select 1) select * from x',
    "select 'insert into public.message' as text", 'select 1;']) {
    assert.equal(isReadOnlySelect(sql), true, sql);
  }
  for (const sql of ['insert into public.message (x) values (1)', 'update public.delivery set state = 1',
    'select 1; delete from public.outbox', 'with d as (delete from public.message returning *) select * from d',
    'select * from x for update', 'copy public.message from stdin', 'set role owner', '', '   ']) {
    assert.equal(isReadOnlySelect(sql), false, sql);
  }
});

test('the exception settings module names no IP literal (no fallback to 127.0.0.1)', () => {
  assert.deepEqual(ipLiterals('baseUrl: http://127.0.0.1:8080/x and 10.0.0.1'), ['127.0.0.1', '10.0.0.1']);
  assert.deepEqual(ipLiterals('version 3.13.1 and 1.0'), []);
  const settings = fs.readFileSync(path.join(SUITE, 'lib', 'settings.mjs'), 'utf8');
  assert.deepEqual(ipLiterals(settings), []);
});
