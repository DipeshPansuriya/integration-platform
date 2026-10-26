// T178a-3 / T176-1 (row 372 L2): the console words the server's DB_ACCOUNT_* approval refusals in plain language and
// says what an MQ system without a contract sends. The server text stays the authority: a refusal that is not one of
// these is shown as-is, and a mapped one keeps the server's detail (the privileges, the account).
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/refusal-words.test.mjs

import test from 'node:test';
import assert from 'node:assert/strict';

const { refusalWords } = await import('../../src/api/refusal-words.ts');
const systems = await import('../../src/api/systems.ts');

const TOO_WIDE = "DB_ACCOUNT_TOO_WIDE: the account 'app' holds more than INSERT and SELECT on 'orders' (DELETE, UPDATE); grant the restricted account only what the send needs";
const unverified = (why) => `DB_ACCOUNT_UNVERIFIED: ${why}; an approval is refused when the account's privileges cannot be read`;

test('a too-wide account reads as plain words, names the extra rights and the way out', () => {
  const words = refusalWords(TOO_WIDE);
  assert.doesNotMatch(words, /DB_ACCOUNT/);
  assert.match(words, /more rights than INSERT and SELECT/i);
  assert.match(words, /DELETE, UPDATE/);
  assert.match(words, /narrow|only INSERT and SELECT/i);
});

test('an unverified account says which step is missing: no password stored, no account, no table, bad login, no answer', () => {
  assert.match(refusalWords(unverified('the probe was refused (NO_CREDENTIAL)')), /store the account password/i);
  assert.match(refusalWords(unverified('the probe was refused (NO_ACCOUNT)')), /account name/i);
  assert.match(refusalWords(unverified('the probe was refused (TABLE_NOT_FOUND)')), /table.*(does not exist|not found)/i);
  assert.match(refusalWords(unverified('the probe was refused (AUTH_REFUSED)')), /refused the (account|login)/i);
  assert.match(refusalWords(unverified('the target database did not answer')), /did not answer|reach/i);
  assert.match(refusalWords(unverified('the probe could not run (IllegalStateException)')), /could not read the account's rights/i);
  for (const why of ['the probe was refused (NO_CREDENTIAL)', 'x']) assert.doesNotMatch(refusalWords(unverified(why)), /DB_ACCOUNT_UNVERIFIED/);
});

test('every unverified answer tells the operator the order: store the password, grant narrow rights, then approve', () => {
  assert.match(refusalWords(unverified('the probe could not run (X)')), /store.*password.*(grant|narrow).*approve/i);
});

test('any other text passes through untouched', () => {
  for (const text of ['settings.host is required', 'FIELD_NOT_ALLOWED', '', 'the DB_ACCOUNT_TOO_WIDE code in the middle']) {
    assert.equal(refusalWords(text), text);
  }
});

test('listedAnswer words the refusal in error, errors and refusal; other bodies keep their text', () => {
  const refused = systems.listedAnswer(undefined, { error: TOO_WIDE, refusal: TOO_WIDE, errors: [TOO_WIDE, 'settings.table is required'] }, { ok: false, status: 409 });
  assert.equal(refused.ok, false);
  assert.doesNotMatch(String(refused.error), /DB_ACCOUNT/);
  assert.doesNotMatch(String(refused.refusal), /DB_ACCOUNT/);
  assert.match(refused.errors[0], /more rights than INSERT and SELECT/i);
  assert.equal(refused.errors[1], 'settings.table is required');
  const plain = systems.listedAnswer(undefined, { error: 'settings.host is required' }, { ok: false, status: 400 });
  assert.deepEqual(plain.errors, ['settings.host is required']);
});

test('an MQ system form says that without a contract the plain transformed body is sent', () => {
  const note = systems.settingsNoteOf('MQ') ?? '';
  assert.match(note, /no contract: sends the plain transformed body/i);
  assert.match(note, /contract/i);
});
