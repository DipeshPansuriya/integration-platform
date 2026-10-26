// Lane ui-edit-merge (Varun blocker, 2026-10-22): saving an edit of an existing system must not erase the settings the
// form does not show (Kafka securityProtocol/saslMechanism/username/truststore, DB sslmode, FILE fileNamePattern,
// halfOpenTrialCalls on every type, API errorDictionary). The edit payload = the stored settings object with the form's
// own fields merged over it: an untouched save is a no-op on the wire; a key goes only when the user cleared a field
// the form owns. Required-field checks are unchanged.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/systems-edit-merge.test.mjs

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const src = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src');
const read = (path) => readFileSync(join(src, path), 'utf8');
const systems = await import('../../src/api/systems.ts');

/** One stored system per type, each carrying keys the form does not own. */
const STORED = {
  KAFKA: {
    bootstrapServers: 'b1:9092,b2:9093', topic: 'orders.out',
    securityProtocol: 'SASL_SSL', saslMechanism: 'SCRAM-SHA-512', username: 'svc-orders', truststore: '/etc/ssl/kafka.jks', halfOpenTrialCalls: 3
  },
  DB: {
    host: 'db.partner.test', port: 5432, database: 'orders', user: 'etl', table: 'orders',
    sslmode: 'verify-full', halfOpenTrialCalls: 2
  },
  FILE: { rootPath: '/data/inbound', fileNamePattern: '*.csv', halfOpenTrialCalls: 1 },
  API: { baseUrl: 'https://partner.example.test/orders', timeoutMs: 5000, followRedirects: true, errorDictionary: { '404': 'NOT_FOUND' }, halfOpenTrialCalls: 4 },
  MQ: { host: 'mq.partner.test', port: 5672, queue: 'orders.in', halfOpenTrialCalls: 5 }
};

/** What the edit form shows for a stored system, then sends back untouched. */
const untouched = (type) => systems.formFromSettings(STORED[type]);
const edit = (type, values, stored = STORED[type]) => systems.settingsForEdit(type, values, stored, type);

for (const type of Object.keys(STORED)) {
  test(`${type}: a no-change save sends the stored settings exactly (keys the form does not own survive)`, () => {
    assert.deepEqual(edit(type, untouched(type)), STORED[type]);
  });
}

test('KAFKA: changing an owned field keeps securityProtocol, saslMechanism, username, truststore', () => {
  const sent = edit('KAFKA', { ...untouched('KAFKA'), topic: 'orders.v2' });
  assert.deepEqual(sent, { ...STORED.KAFKA, topic: 'orders.v2' });
  for (const key of ['securityProtocol', 'saslMechanism', 'username', 'truststore', 'halfOpenTrialCalls']) assert.equal(sent[key], STORED.KAFKA[key], key);
});

test('DB: sslmode is never dropped, so a save cannot silently weaken TLS', () => {
  const sent = edit('DB', { ...untouched('DB'), host: 'db-new.partner.test', port: '6432' });
  assert.equal(sent.sslmode, 'verify-full');
  assert.equal(sent.host, 'db-new.partner.test');
  assert.equal(sent.port, 6432);
});

test('FILE keeps fileNamePattern; API keeps errorDictionary (an object) intact', () => {
  assert.equal(edit('FILE', { ...untouched('FILE'), rootPath: '/data/other' }).fileNamePattern, '*.csv');
  assert.deepEqual(edit('API', { ...untouched('API'), timeoutMs: '7000' }).errorDictionary, { '404': 'NOT_FOUND' });
});

test('a cleared owned optional field is removed; a non-owned key beside it stays', () => {
  const db = edit('DB', { ...untouched('DB'), user: '', table: '  ' });
  assert.deepEqual(db, { host: 'db.partner.test', port: 5432, database: 'orders', sslmode: 'verify-full', halfOpenTrialCalls: 2 });
  const api = edit('API', { ...untouched('API'), timeoutMs: '', followRedirects: false });
  assert.deepEqual(api, { baseUrl: 'https://partner.example.test/orders', errorDictionary: { '404': 'NOT_FOUND' }, halfOpenTrialCalls: 4 });
});

test('an unticked box the stored settings never had stays absent; a stored false stays false', () => {
  assert.equal('followRedirects' in edit('API', { baseUrl: 'https://x.test', followRedirects: false }, { baseUrl: 'https://x.test' }), false);
  const sent = edit('API', { baseUrl: 'https://x.test', followRedirects: false }, { baseUrl: 'https://x.test', followRedirects: false });
  assert.equal(sent.followRedirects, false);
});

test('a value the user did not touch is sent as stored, not re-typed; an edited whole number becomes a number', () => {
  const sent = edit('MQ', untouched('MQ'));
  assert.strictEqual(sent.port, 5672);
  assert.strictEqual(edit('MQ', { host: 'h', port: '5673', queue: 'q' }, STORED.MQ).port, 5673);
});

test('required fields are still validated on the edit values (KAFKA client check, other types left to the server)', () => {
  assert.deepEqual(systems.clientSettingsErrors('KAFKA', { ...untouched('KAFKA'), topic: '', bootstrapServers: '' }), [
    'settings.bootstrapServers is required for type KAFKA',
    'settings.topic is required for type KAFKA'
  ]);
  // a cleared required field is omitted from the payload so the server names it
  const sent = edit('MQ', { ...untouched('MQ'), host: '' });
  assert.equal('host' in sent, false);
  assert.equal(sent.halfOpenTrialCalls, 5);
});

test('changing the type drops the old type extra keys (they belong to the old type); same type with spacing keeps them', () => {
  const retyped = systems.settingsForEdit('MQ', { host: 'h', port: '1', queue: 'q' }, STORED.KAFKA, 'KAFKA');
  assert.deepEqual(retyped, { host: 'h', port: 1, queue: 'q' });
  assert.deepEqual(systems.settingsForEdit(' KAFKA ', untouched('KAFKA'), STORED.KAFKA, 'KAFKA'), STORED.KAFKA);
});

test('no stored settings: the edit payload is just the form (as settingsFromForm)', () => {
  assert.deepEqual(systems.settingsForEdit('FILE', { rootPath: '/r' }, null, null), { rootPath: '/r' });
  assert.deepEqual(systems.settingsForEdit('FILE', { rootPath: '/r' }, undefined, 'FILE'), systems.settingsFromForm('FILE', { rootPath: '/r' }));
});

test('the stored object is never mutated', () => {
  const stored = structuredClone(STORED.KAFKA);
  edit('KAFKA', { ...untouched('KAFKA'), topic: 'x.y' }, stored);
  assert.deepEqual(stored, STORED.KAFKA);
});

test('extraSettingsOf lists the stored keys the form does not own, read-only; credential-looking names are masked', () => {
  const extras = systems.extraSettingsOf('KAFKA', STORED.KAFKA, 'KAFKA');
  assert.deepEqual(extras.map((e) => e.name), ['securityProtocol', 'saslMechanism', 'username', 'truststore', 'halfOpenTrialCalls']);
  assert.deepEqual(extras.find((e) => e.name === 'halfOpenTrialCalls'), { name: 'halfOpenTrialCalls', value: '3' });
  assert.equal(systems.extraSettingsOf('API', STORED.API, 'API').find((e) => e.name === 'errorDictionary').value, '{"404":"NOT_FOUND"}');
  assert.equal(systems.extraSettingsOf('API', { baseUrl: 'u', apiToken: 'abc' }, 'API')[0].value, '(hidden)');
  assert.deepEqual(systems.extraSettingsOf('KAFKA', null, null), []);
  assert.deepEqual(systems.extraSettingsOf('MQ', STORED.KAFKA, 'KAFKA'), [], 'a retyped system shows none of the old extras');
});

test('the edit form sends the merged payload and shows the extras read-only (no input for them)', () => {
  const form = read('pages/systems/EditSystemForm.tsx');
  assert.match(form, /settingsForEdit\(/);
  assert.doesNotMatch(form, /settings: typed \? settingsFromForm\(/);
  assert.match(form, /extraSettingsOf\(/);
  assert.match(form, /data-extra-settings/);
  const from = form.indexOf('data-extra-settings');
  const block = form.slice(from, form.indexOf('</dl>', from));
  assert.ok(block.length > 0 && !/<input/.test(block), 'the extras block holds no input');
});

test('the register form still sends only the form fields (nothing stored to merge)', () => {
  assert.match(read('pages/systems/RegisterSystemForm.tsx'), /settingsFromForm\(/);
});

test('DB: a stale stored approvedTables key is dropped before sending (F3 refuses it with FIELD_NOT_ALLOWED); other keys stay', () => {
  const stale = { ...STORED.DB, approvedTables: ['orders'] };
  const sent = edit('DB', untouched('DB'), stale);
  assert.equal('approvedTables' in sent, false);
  assert.deepEqual(sent, STORED.DB);
  assert.deepEqual(stale.approvedTables, ['orders'], 'the stored object is never mutated');
});
