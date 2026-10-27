// F3 same-user DB approval (row 523, lane G; ADR-H-IPASS-03 option A) - the target database sidecar, FAILING FIRST. No docker: pure.
//
// Approving a DB table dials the target's REAL Postgres with the restricted account (DbAccountWidth); a WireMock
// port cannot answer that and no seam skips it. The boot therefore declares ONE more sidecar, `targetDb`: the
// platform's own Postgres image (reuse, no new image), published on 127.0.0.1 on the caller's port only, with the
// target database and the declared tables created by its owner. The restricted accounts are created by the worker
// (a narrow role per tenant), never here. Started by the boot inside its docker-suite lock, removed before the
// lock is released.

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import * as boot from '../../../scripts/boot-backend.mjs';
import { PG_IMAGE, dockerFailure, servicePlan, transientPsqlFailure } from '../../../scripts/boot-backend.mjs';

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
  assert.match(p.env.POSTGRES_PASSWORD, /^[A-Za-z0-9_-]{20,}$/, 'a random owner password, held in the boot only');
  assert.ok(p.runArgs.includes('POSTGRES_PASSWORD'), 'docker is told the NAME; the value rides in the spawn environment, never in argv (F1)');
  assert.ok(!p.runArgs.some((a) => a.includes(p.env.POSTGRES_PASSWORD)), 'no copy of the password in argv');
  assert.ok(!p.runArgs.includes('-v') && !p.runArgs.includes('--volume'), 'no named volume: --rm removes the anonymous one');
  assert.notEqual(plan().env.POSTGRES_PASSWORD, p.env.POSTGRES_PASSWORD, 'a fresh owner password per plan');
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

test('psql retries the start-up window of a fresh cluster, including the database not created yet (measured: first targetDb boot)', () => {
  for (const out of ['FATAL:  the database system is shutting down', 'FATAL:  the database system is starting up', 'connection to server on socket failed: No such file or directory',
    'connection to server failed: Connection refused', 'FATAL:  database "f3orders" does not exist']) {
    assert.equal(transientPsqlFailure(out), true, out);
  }
  for (const out of ['ERROR:  syntax error at or near "CREAT"', 'ERROR:  relation "f3_orders" already exists', 'ERROR:  role "x" does not exist', '']) {
    assert.equal(transientPsqlFailure(out), false, out);
  }
});

test('F1: a failed docker call never prints a secret - a -e NAME=value for a password, secret, key or token is masked, a bare -e NAME stays', () => {
  const argv = ['run', '-d', '--name', 'x', '-e', 'POSTGRES_USER=owner', '-e', 'POSTGRES_PASSWORD=S3cretValue-0123456789', '-e', 'POSTGRES_PASSWORD', '-e', 'TOKEN=abc123', '-e', 'MY_SECRET=zzz', 'img'];
  const text = dockerFailure(argv, 125, ' port is already allocated \n');
  assert.doesNotMatch(text, /S3cretValue|abc123|zzz/);
  assert.match(text, /POSTGRES_PASSWORD=\*\*\*/);
  assert.match(text, /POSTGRES_USER=owner/, 'a plain setting stays readable');
  assert.match(text, /failed \(125\): port is already allocated$/);
});

// F4 (row 554, CWE-1357): the targetDb sidecar and the platform postgres were the only images pinned by tag alone.
test('F4: PG_IMAGE is pinned by tag AND digest (the PINNED_IMAGE rule WireMock and Kafka already obey)', () => {
  assert.ok(boot.PINNED_IMAGE instanceof RegExp, 'the pin rule is exported so this test uses the real one');
  assert.match(PG_IMAGE, boot.PINNED_IMAGE);
  assert.match(PG_IMAGE, /^postgres:17\.11-alpine@sha256:[0-9a-f]{64}$/);
});

test('F4: a tag-only image for targetDb is refused with the pin message, even though it names the same tag', () => {
  assert.throws(() => plan({ image: 'postgres:17.11-alpine' }), /pinned by tag AND digest/);
  assert.throws(() => plan({ image: 'postgres:17.11-alpine@sha256:' + 'a'.repeat(64) }), /image/, 'a pinned image that is not PG_IMAGE is still refused');
});

// Row 558: the harness must test the build production runs - every postgres line in the Hostinger compose carries PG_IMAGE's digest.
test('row 558: PG_IMAGE is the exact postgres image deploy/hostinger/compose.yaml pins (harness tests the production build)', () => {
  const compose = readFileSync(new URL('../../../../deploy/hostinger/compose.yaml', import.meta.url), 'utf8');
  const images = [...compose.matchAll(/^\s*image:\s*(postgres:\S+)\s*$/gm)].map((m) => m[1]);
  assert.ok(images.length >= 1, 'the Hostinger compose names at least one postgres image');
  for (const image of images) assert.equal(image, PG_IMAGE);
});
