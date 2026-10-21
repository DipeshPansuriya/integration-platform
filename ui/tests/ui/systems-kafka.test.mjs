// E16 UI (lane ui-kafka): the Systems screen can create a KAFKA system, and DB / FILE carry the fields the server
// registers. The server (SystemSettings.java) stays the one authority: only the KAFKA shape is mirrored client-side
// (required fields, the bootstrap-servers list); the platform-topic refusal is the server's text, shown as-is.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/systems-kafka.test.mjs

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const src = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src');
const read = (path) => readFileSync(join(src, path), 'utf8');
const systems = await import('../../src/api/systems.ts');
const view = await import('../../src/pages/systems/systems-view.ts');

test('KAFKA is a system type, in the server order, with a label', () => {
  assert.deepEqual([...systems.SYSTEM_TYPES], ['API', 'DB', 'MQ', 'FILE', 'KAFKA']);
  assert.equal(systems.TYPE_LABELS.KAFKA, 'Kafka');
});

test('KAFKA settings fields: bootstrapServers and topic, both required text; no secret field', () => {
  const fields = systems.settingsFieldsOf('KAFKA');
  assert.deepEqual(fields.map((f) => [f.name, f.kind, f.required]), [['bootstrapServers', 'text', true], ['topic', 'text', true]]);
  assert.ok(fields.every((f) => !/password|secret|token|sasl/i.test(f.name)), 'a credential is a reference, never a form field');
  assert.match(systems.settingsNoteOf('KAFKA') ?? '', /KAFKA_TOPIC/, 'the form says where the credential lives');
});

test('KAFKA settings are sent as typed text, trimmed, blanks left out', () => {
  assert.deepEqual(systems.settingsFromForm('KAFKA', { bootstrapServers: ' b1:9092,b2:9093 ', topic: 'orders.out', junk: 'x' }),
    { bootstrapServers: 'b1:9092,b2:9093', topic: 'orders.out' });
  assert.deepEqual(systems.settingsFromForm('KAFKA', { bootstrapServers: '', topic: '  ' }), {});
});

test('client validation mirrors the server for KAFKA: required fields, with the server words', () => {
  assert.deepEqual(systems.clientSettingsErrors('KAFKA', {}), [
    'settings.bootstrapServers is required for type KAFKA',
    'settings.topic is required for type KAFKA'
  ]);
});

test('client validation mirrors the server bootstrap-servers rule (host:port list, port 1 to 65535)', () => {
  const words = 'settings.bootstrapServers must be a comma-separated list of host:port, port 1 to 65535';
  const check = (value) => systems.clientSettingsErrors('KAFKA', { bootstrapServers: value, topic: 'orders.out' });
  assert.deepEqual(check('b1:9092'), []);
  assert.deepEqual(check('b1:9092, b2.example.test:9093'), []);
  for (const bad of ['b1', 'b1:', ':9092', 'b1:0', 'b1:65536', 'b1:abc', 'b1:9092,', 'b 1:9092', 'http://b1:9092', 'u@b1:9092', 'b1:9092,,b2:9092']) {
    assert.deepEqual(check(bad), [words], `refused: ${bad}`);
  }
  assert.deepEqual(check('x'.repeat(2049) + ':9092'), [words], 'more than 2048 characters');
});

test('the platform-topic refusal is NOT duplicated client-side: the server text is shown as-is', () => {
  // a platform topic passes the client check so the server answers with its own words
  assert.deepEqual(systems.clientSettingsErrors('KAFKA', { bootstrapServers: 'b1:9092', topic: 'platform.poison' }), []);
  const serverWords = "settings.topic platform.poison is a platform topic - the platform's own register is never a tenant destination (T-184 SF-2)";
  assert.equal(view.errorAnchor(serverWords), 'sys-setting-topic', 'the server error still links to the topic field');
  assert.match(read('pages/systems/SystemFields.tsx'), /\{error\}/, 'each error text is rendered verbatim');
  assert.doesNotMatch(read('api/systems.ts') + read('pages/systems/systems-view.ts'), /platform\.(message|delivery|poison)/);
});

test('other types get no client-side rule: the server stays the authority', () => {
  for (const type of ['API', 'DB', 'MQ', 'FILE', 'FTP', '']) {
    assert.deepEqual(systems.clientSettingsErrors(type, {}), [], type);
  }
});

test('DB form carries the server fields: host, port, database required; user, table, approvedTables optional', () => {
  const fields = systems.settingsFieldsOf('DB');
  assert.deepEqual(fields.map((f) => [f.name, f.required]), [
    ['host', true], ['port', true], ['database', true], ['user', false], ['table', false], ['approvedTables', false]
  ]);
});

test('approvedTables is a comma list on screen and an array on the wire, and back', () => {
  const sent = systems.settingsFromForm('DB', { host: 'h', port: '5432', database: 'd', approvedTables: ' orders , lines,, ' });
  assert.deepEqual(sent, { host: 'h', port: 5432, database: 'd', approvedTables: ['orders', 'lines'] });
  assert.equal(systems.formFromSettings({ approvedTables: ['orders', 'lines'], port: 5432 }).approvedTables, 'orders, lines');
  assert.deepEqual(systems.settingsFromForm('DB', { host: 'h', port: '1', database: 'd', approvedTables: ' ' }), { host: 'h', port: 1, database: 'd' });
});

test('FILE keeps its one required field; the form adds no rule for it', () => {
  assert.deepEqual(systems.settingsFieldsOf('FILE').map((f) => [f.name, f.required]), [['rootPath', true]]);
});

test('a field with a server error is marked invalid; others are not', () => {
  const errors = ['settings.topic a topic is required', 'name is required'];
  assert.equal(view.fieldHasError?.(errors, 'topic'), true);
  assert.equal(view.fieldHasError?.(errors, 'bootstrapServers'), false);
});

test('@a11y settings fields: label wraps the input, hint and invalid state are associated, errors live in an alert', () => {
  const fields = read('pages/systems/SystemFields.tsx');
  assert.match(fields, /aria-invalid=/);
  assert.match(fields, /aria-describedby=/);
  assert.match(fields, /aria-required=/);
  assert.match(fields, /role="alert"/);
  assert.match(fields, /<label/);
  assert.match(fields, /fieldHasError/);
});

test('both forms run the client check before calling the API and show its errors in the same list', () => {
  for (const form of ['pages/systems/RegisterSystemForm.tsx', 'pages/systems/EditSystemForm.tsx']) {
    const text = read(form);
    assert.match(text, /clientSettingsErrors\(/, form);
    assert.match(text, /errors=\{errors\}/, `${form} passes the errors to the fields`);
  }
});
