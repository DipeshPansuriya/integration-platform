// B29 lane C batch 2 (audit C6 leftovers): masked settings summary, keyboard-reachable settings text,
// required-fields legend, aria-invalid on code/name/type/direction errors.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/lane-c-systems-forms.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const src = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src');
const read = (p) => readFileSync(join(src, p), 'utf8');
const view = await import('../../src/pages/systems/systems-view.ts');

test('settingsSummary masks credential-named keys, in the same way as the edit form', () => {
  const summary = view.settingsSummary({ host: 'h', password: 'hunter2', apiKey: 'k-123', clientSecret: 's', port: 1, authToken: 't', api_key: 'x', privateKey: 'p', credentials: 'c', passwd: 'q' });
  assert.equal(summary, 'host: h, password: (hidden), apiKey: (hidden), clientSecret: (hidden), port: 1, authToken: (hidden), api_key: (hidden), privateKey: (hidden), credentials: (hidden), passwd: (hidden)');
  for (const leaked of ['hunter2', 'k-123', 'p', 'x']) assert.ok(!summary.split(', ').some((part) => part.endsWith(`: ${leaked}`) && /pass|key|secret|token|cred/i.test(part)), leaked);
  assert.equal(view.settingsSummary({ host: 'h', port: 1 }), 'host: h, port: 1');
  assert.equal(view.settingsSummary(null), '-');
});

test('the credential key test is the very expression the edit form uses', () => {
  const api = read('api/systems.ts').match(/const CREDENTIAL_NAME = (\/.+\/i);/)?.[1];
  const mine = read('pages/systems/systems-view.ts').match(/const CREDENTIAL_NAME = (\/.+\/i);/)?.[1];
  assert.ok(api && mine);
  assert.equal(mine, api);
});

test('top-level field errors are found by field (aria-invalid source)', () => {
  const errors = ['name is required', 'code must match ^[A-Za-z0-9_-]+$ and be 3 to 64 characters', 'settings.baseUrl is required for type API'];
  assert.equal(view.topFieldHasError(errors, 'name'), true);
  assert.equal(view.topFieldHasError(errors, 'code'), true);
  assert.equal(view.topFieldHasError(errors, 'type'), false);
  assert.equal(view.topFieldHasError([], 'name'), false);
});

test('settings text is reachable by keyboard, not title-only', () => {
  const page = read('pages/Systems.tsx');
  assert.match(page, /title=\{summary\}/, 'tooltip kept');
  assert.match(page, /tabIndex=\{0\}/);
  assert.match(page, /aria-label=\{`Settings of \$\{system\.code\}: \$\{summary\}`\}/);
  assert.match(read('pages/systems/systems.css'), /\.systems-settings-text:focus/);
});

test('both forms carry the legend, mark required fields and set aria-invalid on code/name/type/direction', () => {
  const register = read('pages/systems/RegisterSystemForm.tsx');
  const edit = read('pages/systems/EditSystemForm.tsx');
  const fields = read('pages/systems/SystemFields.tsx');
  for (const form of [register, edit]) {
    assert.match(form, /Required fields are marked \*/);
    assert.match(form, /topFieldHasError\(errors, 'name'\)/);
    assert.match(form, /aria-required/);
  }
  assert.match(register, /topFieldHasError\(errors, 'code'\)/);
  assert.match(register, /topFieldHasError\(errors, 'direction'\)/);
  assert.match(fields, /topFieldHasError\(errors, 'type'\)/);
  assert.match(fields, /export const ERRORS_ID/);
  for (const hook of ['data-field="system-code"', 'data-field="system-name"', 'data-form="register-system"']) assert.ok(register.includes(hook), hook);
});

test('the DB table hint says the table must be approved, without requiring a second person (single-user approval)', () => {
  const hint = read('api/systems.ts').match(/name: 'table', label: 'Table'[^\n]*hint: '([^']+)'/)?.[1];
  assert.ok(hint, 'the DB table field has a hint');
  assert.equal(hint, 'A table must be approved (approved-tables API) before the first send.');
  assert.ok(!/second person|another person|different (user|person)/i.test(hint));
});
