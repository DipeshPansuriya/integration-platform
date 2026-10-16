// T130 (row 323, F21 BS-B) step 5 - the boot's sidecar services and an extra tenant's rows, FAILING FIRST.
// No docker: pure functions exported by scripts/boot-backend.mjs.
//
// services: a sidecar (only WireMock today) is DECLARED by the caller and started by the boot INSIDE its own
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

test('refused before anything starts: an unpinned image, an unknown service, a bad stub or name suffix', () => {
  for (const image of ['wiremock/wiremock:3.13.1', `wiremock/wiremock@sha256:${'a'.repeat(64)}`, 'wiremock/wiremock:latest', '', undefined]) {
    assert.throws(() => servicePlan({ wiremock: { image, mappings: [] } }, '0a1b2c3d'), /pinned by tag AND digest/, String(image));
  }
  assert.throws(() => servicePlan({ kafka: { image: IMAGE, mappings: [] } }, '0a1b2c3d'), /unknown service kafka/);
  assert.throws(() => servicePlan({ wiremock: { image: IMAGE } }, '0a1b2c3d'), /mappings/);
  for (const bad of [{}, { ...STUB, id: 'stub-1' }, { ...STUB, request: { method: 'POST', urlPath: 'tenant-a/orders' } },
    { ...STUB, response: {} }, { ...STUB, response: { status: 700 } }]) {
    assert.throws(() => servicePlan({ wiremock: { image: IMAGE, mappings: [bad] } }, '0a1b2c3d'), /not a stub mapping/, JSON.stringify(bad));
  }
  for (const suffix of ['', 'ABCD1234', '0a1b2c3', 'x; rm -rf']) {
    assert.throws(() => servicePlan({ wiremock: { image: IMAGE, mappings: [] } }, suffix), /suffix/, suffix);
  }
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
