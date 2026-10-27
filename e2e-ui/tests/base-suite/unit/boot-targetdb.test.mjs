// F3 same-user DB approval (row 523, lane G; ADR-H-IPASS-03 option A) - the target database sidecar, FAILING FIRST. No docker: pure.
//
// Approving a DB table dials the target's REAL Postgres with the restricted account (DbAccountWidth); a WireMock
// port cannot answer that and no seam skips it. The boot therefore declares ONE more sidecar, `targetDb`: the
// platform's own Postgres image (reuse, no new image), published on 127.0.0.1 on the caller's port only, with the
// target database and the declared tables created by its owner. The restricted accounts are created by the worker
// (a narrow role per tenant), never here. Started by the boot inside its docker-suite lock, removed before the
// lock is released.

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { PG_IMAGE, servicePlan } from '../../../scripts/boot-backend.mjs';

const DECLARED = Object.freeze({ image: PG_IMAGE, hostPort: 41235, database: 'f3orders', tables: Object.freeze(['f3_orders', 'f3_self_approved']) });
const plan = (over = {}) => servicePlan({ targetDb: { ...DECLARED, ...over } }, '0a1b2c3d')[0];

test('targetDb: the platform postgres image, --rm, 5432 published on 127.0.0.1:<hostPort> only, owner-only, no volume', () => {
  const p = plan();
  assert.equal(p.key, 'targetDb');
  assert.equal(p.name, 'ipass-ui-bs-tdb-0a1b2c3d');
  assert.equal(p.containerPort, 5432);
  assert.equal(p.database, 'f3orders');
  assert.deepEqual(p.runArgs.slice(0, 7), ['run', '-d', '--rm', '--name', 'ipass-ui-bs-tdb-0a1b2c3d', '-p', '127.0.0.1:41235:5432']);
  assert.equal(p.runArgs[p.runArgs.length - 1], PG_IMAGE);
  assert.ok(p.runArgs.includes('POSTGRES_USER=owner') && p.runArgs.includes('POSTGRES_DB=f3orders'));
  const password = p.runArgs.find((a) => a.startsWith('POSTGRES_PASSWORD='));
  assert.match(password, /^POSTGRES_PASSWORD=[A-Za-z0-9_-]{20,}$/, 'a random owner password, held in the boot only');
  assert.ok(!p.runArgs.includes('-v') && !p.runArgs.includes('--volume'), 'no named volume: --rm removes the anonymous one');
  assert.notEqual(plan().runArgs.find((a) => a.startsWith('POSTGRES_PASSWORD=')), password, 'a fresh owner password per plan');
});

test('targetDb: the owner creates exactly the declared tables - no role, no grant (the worker creates the narrow accounts)', () => {
  assert.deepEqual(plan().initSql, [
    'CREATE TABLE public.f3_orders (delivery_id uuid UNIQUE, payload jsonb)',
    'CREATE TABLE public.f3_self_approved (delivery_id uuid UNIQUE, payload jsonb)'
  ]);
});

test('targetDb together with the others: one plan each, declared order, one suffix', () => {
  const kafka = { image: 'apache/kafka:4.3.1@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837', topics: ['platform.poison'] };
  const plans = servicePlan({ kafka, targetDb: DECLARED }, '0a1b2c3d', { kafkaPort: 41234 });
  assert.deepEqual(plans.map((x) => x.name), ['ipass-ui-bs-kafka-0a1b2c3d', 'ipass-ui-bs-tdb-0a1b2c3d']);
});

test('targetDb refused before anything starts: other image, bad port, bad names, no tables', () => {
  const bad = (over, pattern) => assert.throws(() => plan(over), pattern, JSON.stringify(over).slice(0, 80));
  for (const image of ['postgres:latest', 'postgres:17.11-alpine@sha256:' + 'a'.repeat(64), '', undefined]) bad({ image }, /image/);
  for (const hostPort of [undefined, 0, 65536, 1.5, '41235']) bad({ hostPort }, /hostPort/);
  for (const database of ['', 'F3 orders', "x'; drop", undefined]) bad({ database }, /database/);
  for (const tables of [undefined, [], ['f3 orders'], ['f3_orders', 'f3_orders'], ['x; drop'], 'f3_orders']) bad({ tables }, /tables/);
});
