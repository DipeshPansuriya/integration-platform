// T130 (row 323, F21 BS-B) step 5 - the boot's sidecar services and an extra tenant's rows, FAILING FIRST.
// No docker: pure functions exported by scripts/boot-backend.mjs.
//
// services: a sidecar (WireMock and, for BS-09, one Kafka broker) is DECLARED by the caller and started by the boot INSIDE its own
// docker-suite lock, then removed before that lock is released - never on its own, never by default. Its
// image is pinned by tag AND digest (R05), it publishes on 127.0.0.1 only (a random host port), runs --rm.
// extraTenantSql: an extra tenant's rows follow the harness's own pattern - the tenant row and, per login,
// an app_user and a role_grant row (the harness's bindings in bootBackend) - and refuse any value that
// could leave its SQL literal (values come from the plan, the realm issuer and Keycloak's Location header).

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { extraTenantSql, servicePlan } from '../../../scripts/boot-backend.mjs';

const IMAGE = 'wiremock/wiremock:3.13.1@sha256:d61e7720f89483fdef5366843b58d1dfd06bcce5828179c9f2f54de5c28354b0';
const STUB = Object.freeze({
  id: '6a1e0a01-3130-4b5e-9a01-000000000a01',
  request: { method: 'POST', urlPath: '/tenant-a/orders' },
  response: { status: 200 }
});

test('no services: nothing is planned - the boot stays exactly today', () => {
  assert.deepEqual(servicePlan(undefined, '0a1b2c3d'), []);
  assert.deepEqual(servicePlan({}, '0a1b2c3d'), []);
});

test('wiremock: the pinned image, --rm, 8080 published on 127.0.0.1 only (random host port), its stubs', () => {
  assert.deepEqual(servicePlan({ wiremock: { image: IMAGE, mappings: [STUB] } }, '0a1b2c3d'), [{
    key: 'wiremock',
    name: 'ipass-ui-bs-wm-0a1b2c3d',
    containerPort: 8080,
    readyPath: '/__admin/health',
    mappings: [STUB],
    runArgs: ['run', '-d', '--rm', '--name', 'ipass-ui-bs-wm-0a1b2c3d', '-p', '127.0.0.1::8080', IMAGE]
  }]);
});

test('wiremock with a hostPort: published on exactly that loopback port (F1); a non-port is refused', () => {
  const [plan] = servicePlan({ wiremock: { image: IMAGE, mappings: [STUB], hostPort: 32768 } }, '0a1b2c3d');
  assert.ok(plan.runArgs.includes('127.0.0.1:32768:8080'));
  for (const hostPort of [0, 65536, 1.5, '32768', null]) {
    assert.throws(() => servicePlan({ wiremock: { image: IMAGE, mappings: [STUB], hostPort } }, '0a1b2c3d'), /hostPort/, String(hostPort));
  }
});

test('refused before anything starts: an unpinned image, an unknown service, a bad stub or name suffix', () => {
  for (const image of ['wiremock/wiremock:3.13.1', `wiremock/wiremock@sha256:${'a'.repeat(64)}`, 'wiremock/wiremock:latest', '', undefined]) {
    assert.throws(() => servicePlan({ wiremock: { image, mappings: [] } }, '0a1b2c3d'), /pinned by tag AND digest/, String(image));
  }
  assert.throws(() => servicePlan({ redis: { image: IMAGE } }, '0a1b2c3d'), /unknown service redis/);
  assert.throws(() => servicePlan({ wiremock: { image: IMAGE } }, '0a1b2c3d'), /mappings/);
  for (const bad of [{}, { ...STUB, id: 'stub-1' }, { ...STUB, request: { method: 'POST', urlPath: 'tenant-a/orders' } },
    { ...STUB, response: {} }, { ...STUB, response: { status: 700 } }]) {
    assert.throws(() => servicePlan({ wiremock: { image: IMAGE, mappings: [bad] } }, '0a1b2c3d'), /not a stub mapping/, JSON.stringify(bad));
  }
  for (const suffix of ['', 'ABCD1234', '0a1b2c3', 'x; rm -rf']) {
    assert.throws(() => servicePlan({ wiremock: { image: IMAGE, mappings: [] } }, suffix), /suffix/, suffix);
  }
});

const KAFKA_IMAGE = 'apache/kafka:4.3.1@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837';
const TOPICS = Object.freeze(['platform.message.accepted', 'platform.delivery.due', 'platform.poison']);
const TOPICS_CLI = ['exec', 'ipass-ui-bs-kafka-0a1b2c3d', '/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server', 'localhost:19092'];

test('kafka: the pinned image, --rm, ONE broker+controller on 127.0.0.1:<kafkaPort>, auto-create off, its topics', () => {
  const [plan] = servicePlan({ kafka: { image: KAFKA_IMAGE, topics: TOPICS } }, '0a1b2c3d', { kafkaPort: 41234 });
  assert.deepEqual(plan, {
    key: 'kafka',
    name: 'ipass-ui-bs-kafka-0a1b2c3d',
    containerPort: 9092,
    port: 41234,
    topics: [...TOPICS],
    runArgs: ['run', '-d', '--rm', '--name', 'ipass-ui-bs-kafka-0a1b2c3d', '-p', '127.0.0.1:41234:9092',
      '-e', 'KAFKA_NODE_ID=1',
      '-e', 'KAFKA_PROCESS_ROLES=broker,controller',
      '-e', 'KAFKA_LISTENERS=PLAINTEXT://:9092,INTERNAL://:19092,CONTROLLER://:9093',
      '-e', 'KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://127.0.0.1:41234,INTERNAL://localhost:19092',
      '-e', 'KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=PLAINTEXT:PLAINTEXT,INTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT',
      '-e', 'KAFKA_INTER_BROKER_LISTENER_NAME=INTERNAL',
      '-e', 'KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER',
      '-e', 'KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093',
      '-e', 'KAFKA_AUTO_CREATE_TOPICS_ENABLE=false',
      '-e', 'KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1',
      '-e', 'KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1',
      '-e', 'KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1',
      '-e', 'KAFKA_MIN_INSYNC_REPLICAS=1',
      '-e', 'KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0',
      '-e', 'TZ=Etc/UTC',
      KAFKA_IMAGE],
    readyArgs: [...TOPICS_CLI, '--list'],
    topicArgs: TOPICS.map((topic) => [...TOPICS_CLI, '--create', '--if-not-exists', '--topic', topic, '--partitions', '3', '--replication-factor', '1']),
    appEnv: { IPASS_KAFKA_BOOTSTRAP_SERVERS: '127.0.0.1:41234', IPASS_KAFKA_PUBLISH_CLIENT_ID: 'ipass-ui-bs' }
  });
});

test('kafka and wiremock together: one plan each, in declared order, the same suffix', () => {
  const plans = servicePlan({ wiremock: { image: IMAGE, mappings: [STUB] }, kafka: { image: KAFKA_IMAGE, topics: TOPICS } },
    '0a1b2c3d', { kafkaPort: 41234 });
  assert.deepEqual(plans.map((plan) => plan.name), ['ipass-ui-bs-wm-0a1b2c3d', 'ipass-ui-bs-kafka-0a1b2c3d']);
});

test('kafka refused before anything starts: unpinned image, bad or duplicate topics, no host port', () => {
  for (const image of ['apache/kafka:4.3.1', 'apache/kafka:latest', '', undefined]) {
    assert.throws(() => servicePlan({ kafka: { image, topics: TOPICS } }, '0a1b2c3d', { kafkaPort: 41234 }), /pinned by tag AND digest/, String(image));
  }
  for (const topics of [undefined, [], ['orders'], ['platform.Message'], ['platform.poison', 'platform.poison'], ['platform.x; rm -rf'], 'platform.poison']) {
    assert.throws(() => servicePlan({ kafka: { image: KAFKA_IMAGE, topics } }, '0a1b2c3d', { kafkaPort: 41234 }), /topics/, JSON.stringify(topics));
  }
  for (const kafkaPort of [undefined, 0, 65536, 1.5, '41234']) {
    assert.throws(() => servicePlan({ kafka: { image: KAFKA_IMAGE, topics: TOPICS } }, '0a1b2c3d', { kafkaPort }), /kafkaPort/, String(kafkaPort));
  }
  assert.throws(() => servicePlan({ kafka: { image: KAFKA_IMAGE, topics: TOPICS } }, '0a1b2c3d'), /kafkaPort/);
});

const ISSUER = 'http://localhost:51234/realms/platform';
const TENANT = Object.freeze({
  key: 'bs-b',
  code: 'lane-d-0a1b2c-bs-b',
  logins: Object.freeze([['bs-b-admin', 'TENANT_ADMIN'], ['bs-b-approver', 'APPROVER']])
});
const SUBJECTS = Object.freeze({
  'bs-b-admin': '0f8fad5b-d9cb-469f-a165-70867728950e',
  'bs-b-approver': '7c9e6679-7425-40de-944b-e07fc1f90ae7'
});

test("extraTenantSql: the harness's own tenant, app_user and role_grant rows for each login, in plan order", () => {
  const [admin, approver] = [SUBJECTS['bs-b-admin'], SUBJECTS['bs-b-approver']];
  assert.deepEqual(extraTenantSql(TENANT, ISSUER, SUBJECTS), [
    "INSERT INTO public.tenant (code, status) VALUES ('lane-d-0a1b2c-bs-b', 'ACTIVE')",
    `INSERT INTO public.app_user (tenant_id, issuer, subject, status) SELECT id, '${ISSUER}', '${admin}', 'ACTIVE' FROM public.tenant WHERE code='lane-d-0a1b2c-bs-b'`,
    `INSERT INTO public.role_grant (tenant_id, user_id, role) SELECT tenant_id, id, 'TENANT_ADMIN' FROM public.app_user WHERE issuer='${ISSUER}' AND subject='${admin}'`,
    `INSERT INTO public.app_user (tenant_id, issuer, subject, status) SELECT id, '${ISSUER}', '${approver}', 'ACTIVE' FROM public.tenant WHERE code='lane-d-0a1b2c-bs-b'`,
    `INSERT INTO public.role_grant (tenant_id, user_id, role) SELECT tenant_id, id, 'APPROVER' FROM public.app_user WHERE issuer='${ISSUER}' AND subject='${approver}'`
  ]);
});

test('extraTenantSql refuses any value that could leave its SQL literal, and a login with no realm user', () => {
  const bad = [
    [{ ...TENANT, code: "lane-d-0a1b2c-bs-b'; drop table x; --" }, ISSUER, SUBJECTS, /tenant code/],
    [TENANT, `${ISSUER}'`, SUBJECTS, /issuer/],
    [TENANT, 'ftp://localhost/realms/platform', SUBJECTS, /issuer/],
    [TENANT, ISSUER, { ...SUBJECTS, 'bs-b-admin': "x' OR '1'='1" }, /subject/],
    [{ ...TENANT, logins: [['bs-b-admin', "TENANT_ADMIN'"]] }, ISSUER, SUBJECTS, /role/],
    [TENANT, ISSUER, { 'bs-b-admin': SUBJECTS['bs-b-admin'] }, /no realm user/]
  ];
  for (const [tenant, issuer, subjects, message] of bad) {
    assert.throws(() => extraTenantSql(tenant, issuer, subjects), message, JSON.stringify([tenant.code, issuer]));
  }
});
