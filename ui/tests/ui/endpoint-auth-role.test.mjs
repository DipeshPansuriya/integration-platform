// Row 519: PUT /systems/{id}/endpoint-auth is TENANT_ADMIN-only (MANAGE_TARGET_CREDENTIAL, row 510 part 2). A CONFIGURATOR
// still reads the form data (GET is READ_CONFIGURATION) but its save answers 403: the form must then turn read-only with
// the reason, never leave a live Save button that can only fail, and never show the 403 as a generic refusal.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/endpoint-auth-role.test.mjs

import test from 'node:test';
import assert from 'node:assert/strict';
import { findAll, mount } from './hook-harness.mjs';

const AUTH = { endpointId: 'e1', authMethod: 'BEARER_TOKEN', authIdentity: null, authTokenUrl: null };
const json = (status, body) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });

/** A console that answers the three calls the form makes; `putStatus` is what the PUT endpoint-auth answers. */
function consoleOf(putStatus) {
  const calls = [];
  const consoleFetch = async (url, init) => {
    calls.push(`${init?.method ?? 'GET'} ${url}`);
    if (url.endsWith('/endpoint-auth')) return init?.method === 'PUT' ? json(putStatus, putStatus === 200 ? AUTH : { error: 'forbidden' }) : json(200, AUTH);
    return json(200, { fields: [] });
  };
  return { api: { consoleFetch, api: {} }, calls };
}

const mountForm = async (putStatus) => {
  const { api, calls } = consoleOf(putStatus);
  const form = await mount('pages/systems/EndpointAuthForm.tsx', 'EndpointAuthForm', { systemId: 's1' }, api, ['./client']);
  await form.settle();
  return { form, calls };
};
const submit = async (form) => {
  const element = findAll(form.tree(), (n) => n.type === 'form')[0];
  await element.props.onSubmit({ preventDefault() {} });
  await form.settle();
};
const saveButton = (form) => findAll(form.tree(), (n) => n.props?.['data-action'] === 'save-endpoint-auth')[0];
const notice = (form) => findAll(form.tree(), (n) => n.props?.['data-endpoint-auth-forbidden'] !== undefined);

test('a TENANT_ADMIN save works: Save stays live and no read-only notice appears', async () => {
  const { form, calls } = await mountForm(200);
  await submit(form);
  assert.ok(calls.includes('PUT /api/v1/admin/systems/s1/endpoint-auth'), calls.join(' | '));
  assert.equal(notice(form).length, 0);
  assert.equal(saveButton(form).props.disabled, false);
});

test('a CONFIGURATOR (PUT answers 403): the form turns read-only, says who may change it, and Save is disabled', async () => {
  const { form } = await mountForm(403);
  await submit(form);
  assert.equal(notice(form).length, 1, 'the read-only notice is shown');
  assert.match(JSON.stringify(notice(form)[0].props.children), /tenant admin/i);
  assert.equal(saveButton(form).props.disabled, true, 'Save is disabled');
  const fieldset = findAll(form.tree(), (n) => n.type === 'fieldset')[0];
  assert.equal(fieldset.props.disabled, true, 'the fields are disabled');
  assert.equal(findAll(form.tree(), (n) => n.props?.role === 'alert' && /HTTP 403/.test(JSON.stringify(n.props))).length, 0, 'not the generic "Save refused (HTTP 403)" error');
});
