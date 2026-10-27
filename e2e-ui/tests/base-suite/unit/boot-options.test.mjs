// T130 (row 323, F21 BS-B) step 5 - the base suite's boot options, FAILING FIRST. No docker: pure.
//
// The base suite boots the SAME harness as the screens suite plus four additive options and nothing else:
// tenant B (its own admin and approver), the DECLARED WireMock (started by the boot inside its lock, never
// here), the dev-only exception's app settings (through mergeAppEnv - their names are DECIDED and
// arrive only through the environment) and source credentials (the realm's admin client secret by NAME,
// a random value in memory only). Not configured, or misconfigured -> no options at all: the run
// stops before any build, lock or container.

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { HARNESS_APP_ENV, HARNESS_LOGINS, PG_IMAGE, extraTenantPlan, mergeAppEnv, servicePlan } from '../../../scripts/boot-backend.mjs';
import { BS_CONSOLE_LOGINS, BS_KAFKA, BS_WIREMOCK, KAFKA_IMAGE, baseSuiteBootOptions } from '../lib/boot-options.mjs';
import { DB_NAME, DB_SELF_TABLE, DB_TABLE } from '../lib/approved-tables.mjs';
import { NOT_CONFIGURED, exceptionSettings } from '../lib/settings.mjs';
import { TENANT_B_PLAN } from '../lib/tenants.mjs';
import { STUB_IDS, WIREMOCK_IMAGE, stubMapping } from '../lib/wiremock.mjs';

// Fixture values for the unit tests (the host does not resolve); the decided real values are in settings.test.mjs.
const CONFIGURED = exceptionSettings({
  IPASS_BS_EXCEPTION_PROFILE: 'showcase',
  IPASS_BS_EXCEPTION_HOST: 'target.showcase.test',
  IPASS_BS_EXCEPTION_HOST_VAR: 'SHOWCASE_TARGET_HOST',
  IPASS_BS_EXCEPTION_PORT: '32768'
});

test('WireMock is DECLARED here, never started: the pinned image and one 200 stub per path', () => {
  assert.equal(BS_WIREMOCK.image, WIREMOCK_IMAGE);
  assert.deepEqual(BS_WIREMOCK.mappings.map((mapping) => mapping.request.urlPath), ['/tenant-a/orders', '/tenant-b/orders', '/tenant-a/loop']);
  assert.deepEqual(BS_WIREMOCK.mappings, Object.keys(STUB_IDS).map((stubPath) => stubMapping(stubPath, 200)));
  assert.ok(Object.isFrozen(BS_WIREMOCK) && Object.isFrozen(BS_WIREMOCK.mappings));
  const [plan] = servicePlan({ wiremock: BS_WIREMOCK }, '0a1b2c3d');
  assert.ok(plan.runArgs.includes('127.0.0.1::8080'), 'the boot publishes it on loopback only');
});

test('Kafka is DECLARED here, never started: the pinned broker image and the three main topics (OutboxTopic.java)', () => {
  assert.match(KAFKA_IMAGE, /^apache\/kafka:[0-9.]+@sha256:[0-9a-f]{64}$/);
  assert.equal(BS_KAFKA.image, KAFKA_IMAGE);
  assert.deepEqual([...BS_KAFKA.topics], ['platform.message.accepted', 'platform.delivery.due', 'platform.poison']);
  assert.ok(Object.isFrozen(BS_KAFKA) && Object.isFrozen(BS_KAFKA.topics));
  const [plan] = servicePlan({ kafka: BS_KAFKA }, '0a1b2c3d', { kafkaPort: 41234 });
  assert.ok(plan.runArgs.includes('127.0.0.1:41234:9092'), 'the boot publishes it on loopback only');
  for (const name of Object.keys(plan.appEnv)) assert.ok(HARNESS_APP_ENV.includes(name), `${name} is harness-owned - appEnv cannot replace it`);
});

test('not configured or misconfigured: no options at all - the run stops before any build, lock or container', () => {
  assert.throws(() => baseSuiteBootOptions(exceptionSettings({}), { lockHolder: 'x' }), (e) => e.message.startsWith(NOT_CONFIGURED));
  const wrongProfile = exceptionSettings({
    IPASS_BS_EXCEPTION_PROFILE: 'dev-showcase', IPASS_BS_EXCEPTION_HOST: 'localhost',
    IPASS_BS_EXCEPTION_HOST_VAR: 'PLATFORM_CONFIG_DESTINATIONS_SHOWCASE_LOCAL_HOSTS', IPASS_BS_EXCEPTION_PORT: '8089'
  });
  assert.throws(() => baseSuiteBootOptions(wrongProfile, { lockHolder: 'x' }), /dev-only exception misconfigured.*showcase/);
});

test('configured: tenant B, the declared WireMock, the exception through mergeAppEnv, source credentials - nothing else', () => {
  const options = baseSuiteBootOptions(CONFIGURED, { lockHolder: 'aditya-qa-automation base-suite' });
  assert.deepEqual(options, {
    extraTenants: [TENANT_B_PLAN],
    services: { wiremock: { ...BS_WIREMOCK, hostPort: 32768 }, kafka: BS_KAFKA },
    appEnv: { SPRING_PROFILES_ACTIVE: 'showcase', SHOWCASE_TARGET_HOST: 'target.showcase.test',
      PLATFORM_CONFIG_DESTINATIONS_SHOWCASE_LOCAL_PORTS: '32768', PLATFORM_CONFIG_DESTINATIONS_ALLOWED_HOSTS: 'target.showcase.test', ROUTING_CORE_ALLOWED_SCHEMES: 'http,https',
      IPASS_DB_TARGET_ENABLED: 'true' },   // the DB target adapter beans are conditional on it (DbAdapterConfiguration.java:32)
    sourceCredentials: true,
    lockHolder: 'aditya-qa-automation base-suite'
  });
  // the boot's own checks accept every part, and none takes a harness login or a harness-owned setting
  assert.equal(extraTenantPlan('lane-d-0a1b2c', options.extraTenants).length, 1);
  assert.deepEqual(mergeAppEnv({}, options.appEnv), options.appEnv);
  for (const name of Object.keys(options.appEnv)) assert.ok(!HARNESS_APP_ENV.includes(name), name);
  for (const login of Object.keys(TENANT_B_PLAN.logins)) assert.ok(!HARNESS_LOGINS.includes(login), login);
  // F1: WireMock listens on the exception port itself - the one port the guard's showcase-local-ports lifts
  assert.ok(servicePlan({ wiremock: options.services.wiremock }, '0a1b2c3d')[0].runArgs.includes('127.0.0.1:32768:8080'));
  assert.ok(!('loginSeed' in options), 'the base suite onboards its own flows - the login seed stays off');
});

test('the lock holder is named - the lock note says who holds it', () => {
  assert.throws(() => baseSuiteBootOptions(CONFIGURED, {}), /lock holder/);
  assert.throws(() => baseSuiteBootOptions(CONFIGURED, { lockHolder: '  ' }), /lock holder/);
});

test("the console declares only the logins the base suite drives: tenant A's approver and tenant B's two", () => {
  assert.deepEqual([...BS_CONSOLE_LOGINS], ['approver', 'bs-b-admin', 'bs-b-approver']);
  assert.ok(Object.isFrozen(BS_CONSOLE_LOGINS));
});

// F3 (row 523, ADR-H-IPASS-03): the target database a DB table approval dials. Declared only when the caller asks.
const KEK = Buffer.alloc(32, 7).toString('base64');
const TARGET = Object.freeze({ port: 41235, kek: KEK });
const withTarget = (over = {}) => baseSuiteBootOptions(CONFIGURED, { lockHolder: 'x', targetDb: { ...TARGET, ...over } });

test('targetDb declared: the sidecar on the caller\'s port, the owner-created tables, nothing else changes in the services', () => {
  const options = withTarget();
  assert.deepEqual(options.services.targetDb, { image: PG_IMAGE, hostPort: 41235, database: DB_NAME, tables: [DB_TABLE, DB_SELF_TABLE] });
  assert.deepEqual(options.services.wiremock, { ...BS_WIREMOCK, hostPort: 32768 });
  assert.equal(options.services.kafka, BS_KAFKA);
  assert.equal(servicePlan({ targetDb: options.services.targetDb }, '0a1b2c3d')[0].runArgs.includes('127.0.0.1:41235:5432'), true);
});

test('targetDb declared: the allow-lists name EXACTLY the new port; test (sslmode disable) joins showcase; an in-memory KEK; no 5432 / 9092', () => {
  const env = withTarget().appEnv;
  assert.equal(env.PLATFORM_CONFIG_DESTINATIONS_DB_ALLOWED_PORTS, '41235', 'the WireMock port stays off the DB list (scenario 9 keeps its meaning)');
  assert.equal(env.PLATFORM_CONFIG_DESTINATIONS_SHOWCASE_LOCAL_PORTS, '32768,41235');
  assert.equal(env.SPRING_PROFILES_ACTIVE, 'showcase,test');
  assert.equal(env.PLATFORM_CONFIG_DESTINATIONS_DB_SSLMODE, 'disable');
  assert.equal(env.PLATFORM_CREDENTIAL_KEK_T1, KEK);
  assert.equal(env.PLATFORM_CREDENTIAL_KEK_ACTIVE, 'T1');
  assert.equal(env.PLATFORM_CONFIG_DESTINATIONS_ALLOWED_HOSTS, 'target.showcase.test', 'allowed-hosts is unchanged: still the one exact exception host');
  for (const port of [env.PLATFORM_CONFIG_DESTINATIONS_DB_ALLOWED_PORTS, ...env.PLATFORM_CONFIG_DESTINATIONS_SHOWCASE_LOCAL_PORTS.split(',')]) {
    assert.ok(!['5432', '9092'].includes(port), port);
  }
  assert.deepEqual(mergeAppEnv({}, env), env, 'the boot accepts every name; none is harness-owned');
});

test('F2: the FINAL composed appEnv - profiles exactly {showcase,test}, showcase-local ports exactly {exception,target}, DB ports exactly {target}', () => {
  const env = withTarget().appEnv;
  assert.deepEqual(env.SPRING_PROFILES_ACTIVE.split(',').sort(), ['showcase', 'test']);
  assert.deepEqual(env.PLATFORM_CONFIG_DESTINATIONS_SHOWCASE_LOCAL_PORTS.split(',').map(Number).sort((a, b) => a - b), [32768, 41235]);
  assert.deepEqual(env.PLATFORM_CONFIG_DESTINATIONS_DB_ALLOWED_PORTS.split(','), ['41235']);
  assert.deepEqual(env.PLATFORM_CONFIG_DESTINATIONS_ALLOWED_HOSTS.split(','), ['target.showcase.test']);
  // nothing the exception sets is silently replaced except the two names the target extends, on purpose
  const replaced = Object.keys(CONFIGURED.appEnv).filter((name) => env[name] !== CONFIGURED.appEnv[name]).sort();
  assert.deepEqual(replaced, ['PLATFORM_CONFIG_DESTINATIONS_SHOWCASE_LOCAL_PORTS', 'SPRING_PROFILES_ACTIVE']);
});

test('targetDb NOT declared: exactly today - no test profile, no sslmode, no KEK, no port list', () => {
  const env = baseSuiteBootOptions(CONFIGURED, { lockHolder: 'x' }).appEnv;
  assert.equal(env.SPRING_PROFILES_ACTIVE, 'showcase');
  for (const name of ['PLATFORM_CONFIG_DESTINATIONS_DB_ALLOWED_PORTS', 'PLATFORM_CONFIG_DESTINATIONS_DB_SSLMODE', 'PLATFORM_CREDENTIAL_KEK_T1', 'PLATFORM_CREDENTIAL_KEK_ACTIVE']) {
    assert.ok(!(name in env), name);
  }
  assert.ok(!('targetDb' in baseSuiteBootOptions(CONFIGURED, { lockHolder: 'x' }).services));
});

test('targetDb refused: a port that is not one TCP port, the WireMock port, 5432 / 9092, a KEK that is not 32 random-able bytes of base64', () => {
  for (const port of [undefined, 0, 65536, 1.5, '41235', 32768, 5432, 9092]) {
    assert.throws(() => withTarget({ port }), /targetDb port/, String(port));
  }
  for (const kek of [undefined, '', 'not base64!', Buffer.alloc(16).toString('base64'), Buffer.alloc(33).toString('base64')]) {
    assert.throws(() => withTarget({ kek }), /KEK/, String(kek));
  }
});
