// T130 (row 323, F21 BS-B) step 5 - the base suite's boot options, FAILING FIRST. No docker: pure.
//
// The base suite boots the SAME harness as the screens suite plus four additive options and nothing else:
// tenant B (its own admin and approver), the DECLARED WireMock (started by the boot inside its lock, never
// here), the dev-only exception's app settings (through mergeAppEnv - their real names are TBD(row278) and
// arrive only through the environment) and source credentials (the realm's admin client secret by NAME,
// a random value in memory only). Not configured, or still TBD(row278) -> no options at all: the run
// stops before any build, lock or container.

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { HARNESS_APP_ENV, HARNESS_LOGINS, extraTenantPlan, mergeAppEnv, servicePlan } from '../../../scripts/boot-backend.mjs';
import { BS_CONSOLE_LOGINS, BS_KAFKA, BS_WIREMOCK, KAFKA_IMAGE, baseSuiteBootOptions } from '../lib/boot-options.mjs';
import { NOT_CONFIGURED, PLACEHOLDER, exceptionSettings } from '../lib/settings.mjs';
import { TENANT_B_PLAN } from '../lib/tenants.mjs';
import { STUB_IDS, WIREMOCK_IMAGE, stubMapping } from '../lib/wiremock.mjs';

// Fixture values only: row 278's real profile, host and app setting name are TBD(row278).
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

test('not configured or still TBD(row278): no options at all - the run stops before any build, lock or container', () => {
  assert.throws(() => baseSuiteBootOptions(exceptionSettings({}), { lockHolder: 'x' }), (e) => e.message.startsWith(NOT_CONFIGURED));
  const tbd = exceptionSettings({
    IPASS_BS_EXCEPTION_PROFILE: PLACEHOLDER, IPASS_BS_EXCEPTION_HOST: PLACEHOLDER, IPASS_BS_EXCEPTION_HOST_VAR: PLACEHOLDER,
    IPASS_BS_EXCEPTION_PORT: PLACEHOLDER
  });
  assert.throws(() => baseSuiteBootOptions(tbd, { lockHolder: 'x' }), /TBD\(row278\)/);
});

test('configured: tenant B, the declared WireMock, the exception through mergeAppEnv, source credentials - nothing else', () => {
  const options = baseSuiteBootOptions(CONFIGURED, { lockHolder: 'aditya-qa-automation base-suite' });
  assert.deepEqual(options, {
    extraTenants: [TENANT_B_PLAN],
    services: { wiremock: BS_WIREMOCK, kafka: BS_KAFKA },
    appEnv: { SPRING_PROFILES_ACTIVE: 'showcase', SHOWCASE_TARGET_HOST: 'target.showcase.test' },
    sourceCredentials: true,
    lockHolder: 'aditya-qa-automation base-suite'
  });
  // the boot's own checks accept every part, and none takes a harness login or a harness-owned setting
  assert.equal(extraTenantPlan('lane-d-0a1b2c', options.extraTenants).length, 1);
  assert.deepEqual(mergeAppEnv({}, options.appEnv), options.appEnv);
  for (const name of Object.keys(options.appEnv)) assert.ok(!HARNESS_APP_ENV.includes(name), name);
  for (const login of Object.keys(TENANT_B_PLAN.logins)) assert.ok(!HARNESS_LOGINS.includes(login), login);
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
