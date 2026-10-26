// T130 (row 323, F21 BS-B) - the harness's additive two-tenant options, FAILING FIRST. No docker:
// pure functions exported by scripts/boot-backend.mjs (the module's import has no side effect beyond
// resolving the lock path - tests/trial/jvm-lookup.test.mjs imports it the same way).
//
// ADD, never repurpose: with no extra tenant and no extra app setting the boot is exactly today's
// (boot-backend.mjs:351-373 untouched); an extra tenant is a NEW tenant code with NEW logins, and an
// extra app setting can never replace a name the harness itself owns (DB_*, OIDC_*, CONSOLE_*, the
// JVM's options - boot-backend.mjs:302-318) - the dev-only exception is ADDED to the app's
// environment, it never rewrites the harness's own wiring or the JVM's resolver.

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { HARNESS_APP_ENV, HARNESS_LOGINS, extraTenantPlan, mergeAppEnv } from '../../../scripts/boot-backend.mjs';

const TENANT_B = { key: 'bs-b', logins: { 'bs-b-admin': 'TENANT_ADMIN', 'bs-b-approver': 'APPROVER' } };

test("the harness's own logins and app settings are named, so an extra can never take one", () => {
  assert.deepEqual([...HARNESS_LOGINS], ['default', 'approver', 'other-tenant', 'empty-tenant']);
  for (const name of ['DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'DB_MIGRATION_USERNAME', 'DB_MIGRATION_PASSWORD',
    'OIDC_ISSUER_URI', 'OIDC_AUDIENCE', 'CONSOLE_OIDC_CLIENT_ID', 'CONSOLE_OIDC_CLIENT_SECRET',
    'CONSOLE_OIDC_REDIRECT_URI', 'CONSOLE_POST_LOGOUT_REDIRECT_URI', 'CONSOLE_URL', 'JAVA_TOOL_OPTIONS',
    'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JAVA_HOME', 'PATH', 'PLATFORM_ADMIN_CLIENT_SECRET']) {
    assert.ok(HARNESS_APP_ENV.includes(name), name);
  }
});

test('no extra tenant: nothing is planned - the boot stays exactly today', () => {
  assert.deepEqual(extraTenantPlan('lane-d-abc123', []), []);
  assert.deepEqual(extraTenantPlan('lane-d-abc123', undefined), []);
});

test("tenant B: a NEW tenant code with its own admin and approver, in the role_grant vocabulary", () => {
  assert.deepEqual(extraTenantPlan('lane-d-abc123', [TENANT_B]), [{
    key: 'bs-b',
    code: 'lane-d-abc123-bs-b',
    logins: [['bs-b-admin', 'TENANT_ADMIN'], ['bs-b-approver', 'APPROVER']]
  }]);
});

test('never repurposes a harness login or tenant, never collides, never takes an invalid name or role', () => {
  const bad = [
    [{ key: 'bs-b', logins: { 'other-tenant': 'TENANT_ADMIN' } }, /harness login other-tenant/],
    [{ key: 'bs-b', logins: { default: 'TENANT_ADMIN' } }, /harness login default/],
    [{ key: 'b', logins: { 'bs-x': 'TENANT_ADMIN' } }, /harness tenant/],
    [{ key: 'c', logins: { 'bs-x': 'TENANT_ADMIN' } }, /harness tenant/],
    [{ key: 'B!', logins: { 'bs-x': 'TENANT_ADMIN' } }, /invalid tenant key/],
    [{ key: 'bs-b', logins: {} }, /no logins/],
    [{ key: 'bs-b', logins: { 'BS ADMIN': 'TENANT_ADMIN' } }, /invalid login/],
    [{ key: 'bs-b', logins: { 'bs-x': 'tenant_admin' } }, /invalid role/],
    [{ key: 'bs-b', logins: { 'bs-x': "TENANT_ADMIN'; drop" } }, /invalid role/]
  ];
  for (const [plan, message] of bad) assert.throws(() => extraTenantPlan('lane-d-abc123', [plan]), message, JSON.stringify(plan));
  assert.throws(() => extraTenantPlan('lane-d-abc123', [TENANT_B, { key: 'bs-d', logins: { 'bs-b-admin': 'APPROVER' } }]), /twice/);
  assert.throws(() => extraTenantPlan('lane-d-abc123', [TENANT_B, { key: 'bs-b', logins: { 'bs-y': 'APPROVER' } }]), /twice/);
});

test('mergeAppEnv with no extras is the base, unchanged', () => {
  const base = { DB_URL: 'jdbc:postgresql://x/platform', PATH: '/bin' };
  assert.deepEqual(mergeAppEnv(base), base);
  assert.deepEqual(mergeAppEnv(base, {}), base);
});

test('mergeAppEnv adds extras and refuses to replace any harness-owned name, in any letter case', () => {
  const base = { DB_URL: 'jdbc:postgresql://x/platform', Path: 'C:/bin' };
  assert.deepEqual(mergeAppEnv(base, { SPRING_PROFILES_ACTIVE: 'showcase' }, { SHOWCASE_TARGET_HOST: 'target.showcase.test' }),
    { ...base, SPRING_PROFILES_ACTIVE: 'showcase', SHOWCASE_TARGET_HOST: 'target.showcase.test' });
  for (const extra of [{ DB_URL: 'jdbc:postgresql://evil/x' }, { OIDC_ISSUER_URI: 'http://evil' }, { JAVA_TOOL_OPTIONS: '-Djdk.net.hosts.file=x' },
    { jdk_java_options: '-Dx' }, { path: 'C:/evil' }, { PLATFORM_ADMIN_CLIENT_SECRET: 'x' }]) {
    assert.throws(() => mergeAppEnv(base, extra), /harness-owned/, JSON.stringify(extra));
  }
  assert.throws(() => mergeAppEnv(base, { A_B: '1' }, { A_B: '2' }), /twice/);
  assert.throws(() => mergeAppEnv(base, { 'A-B': '1' }), /not an environment name/);
  assert.throws(() => mergeAppEnv(base, { A_B: 1 }), /text/);
});
