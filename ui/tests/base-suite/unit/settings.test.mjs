// T130 (row 323, F21 BS-B) - the dev-only outbound exception as the base suite reads it, FAILING FIRST.
// No docker, no JVM: pure functions of tests/base-suite/lib/settings.mjs.
//
// Dipesh "Option 1" (2026-10-16 20:38 IST): BS-07 / BS-10 / BS-12 reach the one local WireMock only
// through row 278's dev-only exception - a named dev/showcase profile, one exact host, the app's real
// host resolver. Its profile, host and app-side variable name are row 278's to set, so the suite reads
// them from three environment names and nowhere else; unset means the scenarios FAIL with a named
// reason (never a skip, never a fallback to 127.0.0.1 or a test resolver).

import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  EXCEPTION_ENV,
  NOT_CONFIGURED,
  PLACEHOLDER,
  assertRealResolver,
  exceptionSettings,
  requireException,
  targetUrl
} from '../lib/settings.mjs';

// Fixture values only: row 278's real profile, host and app setting name are TBD(row278) until row 278's build lands.
const VALID = Object.freeze({
  IPASS_BS_EXCEPTION_PROFILE: 'showcase',
  IPASS_BS_EXCEPTION_HOST: 'target.showcase.test',
  IPASS_BS_EXCEPTION_HOST_VAR: 'SHOWCASE_TARGET_HOST',
  IPASS_BS_EXCEPTION_PORT: '32768'
});
const withEnv = (overrides) => ({ ...VALID, ...overrides });

test('the exception is read from exactly four environment names', () => {
  assert.deepEqual({ ...EXCEPTION_ENV }, {
    profile: 'IPASS_BS_EXCEPTION_PROFILE',
    host: 'IPASS_BS_EXCEPTION_HOST',
    hostVar: 'IPASS_BS_EXCEPTION_HOST_VAR',
    port: 'IPASS_BS_EXCEPTION_PORT'
  });
  assert.equal(NOT_CONFIGURED, 'dev-only exception not configured - waits for row 278');
});

test('unset: not configured, the reason waits for row 278 and names every missing variable', () => {
  const settings = exceptionSettings({});
  assert.equal(settings.configured, false);
  assert.ok(settings.reason.startsWith(NOT_CONFIGURED), settings.reason);
  for (const name of Object.values(EXCEPTION_ENV)) assert.ok(settings.reason.includes(name), `${name} named`);
  assert.equal(settings.appEnv, undefined, 'no app environment when not configured');
});

test('blank values count as unset', () => {
  const settings = exceptionSettings({ IPASS_BS_EXCEPTION_PROFILE: '  ', IPASS_BS_EXCEPTION_HOST: '', IPASS_BS_EXCEPTION_HOST_VAR: '\t', IPASS_BS_EXCEPTION_PORT: ' ' });
  assert.equal(settings.configured, false);
  assert.ok(settings.reason.startsWith(NOT_CONFIGURED));
});

test('partly set: only the missing names are listed', () => {
  const settings = exceptionSettings({ IPASS_BS_EXCEPTION_PROFILE: 'showcase' });
  assert.equal(settings.configured, false);
  assert.deepEqual(settings.missing, ['IPASS_BS_EXCEPTION_HOST', 'IPASS_BS_EXCEPTION_HOST_VAR', 'IPASS_BS_EXCEPTION_PORT']);
  assert.ok(!settings.reason.includes('IPASS_BS_EXCEPTION_PROFILE'));
});

test('the TBD(row278) placeholder is never a configuration: a still-TBD value fails, naming row 278', () => {
  assert.equal(PLACEHOLDER, 'TBD(row278)');
  const allTbd = exceptionSettings({
    IPASS_BS_EXCEPTION_PROFILE: PLACEHOLDER, IPASS_BS_EXCEPTION_HOST: PLACEHOLDER, IPASS_BS_EXCEPTION_HOST_VAR: PLACEHOLDER,
    IPASS_BS_EXCEPTION_PORT: PLACEHOLDER
  });
  const named = (reason) => reason.match(/IPASS_BS_EXCEPTION_[A-Z_]+/g);
  assert.equal(allTbd.configured, false);
  assert.match(allTbd.reason, /still TBD\(row278\)/);
  assert.deepEqual(named(allTbd.reason), Object.values(EXCEPTION_ENV));
  assert.equal(allTbd.appEnv, undefined, 'no app environment from a placeholder');
  for (const [name, value] of [['IPASS_BS_EXCEPTION_PROFILE', 'tbd'], ['IPASS_BS_EXCEPTION_HOST', 'tbd.showcase.test'],
    ['IPASS_BS_EXCEPTION_HOST_VAR', 'TBD_ROW278_HOST'], ['IPASS_BS_EXCEPTION_PORT', 'tbd']]) {
    const settings = exceptionSettings(withEnv({ [name]: value }));
    assert.equal(settings.configured, false, `${name}=${value}`);
    assert.deepEqual(settings.missing, [], 'a TBD value is set - still TBD, not missing');
    assert.deepEqual(named(settings.reason), [name], settings.reason);
  }
});

test('configured: the app gets the named profile and the one host through the variable row 278 names', () => {
  const settings = exceptionSettings(withEnv({ IPASS_BS_EXCEPTION_HOST: ' Target.Showcase.Test ' }));
  assert.equal(settings.configured, true);
  assert.equal(settings.profile, 'showcase');
  assert.equal(settings.host, 'target.showcase.test', 'trimmed and lower-cased like the guard normalises');
  assert.equal(settings.port, 32768, 'the exception port as a number');
  assert.deepEqual(settings.appEnv, { SPRING_PROFILES_ACTIVE: 'showcase', SHOWCASE_TARGET_HOST: 'target.showcase.test' });
});

test('the exception port is one integer 1-65535 - anything else is misconfigured, not unset', () => {
  for (const port of ['0', '65536', 'abc', '8080x', '-1', '1.5', '0x50', '80 80']) {
    const settings = exceptionSettings(withEnv({ IPASS_BS_EXCEPTION_PORT: port }));
    assert.equal(settings.configured, false, port);
    assert.match(settings.reason, /IPASS_BS_EXCEPTION_PORT/, port);
    assert.ok(!settings.reason.startsWith(NOT_CONFIGURED), `${port}: misconfigured, not unset`);
  }
  assert.equal(exceptionSettings(withEnv({ IPASS_BS_EXCEPTION_PORT: ' 1 ' })).port, 1);
  assert.equal(exceptionSettings(withEnv({ IPASS_BS_EXCEPTION_PORT: '65535' })).port, 65535);
});

test('an IP literal is never the exception host - the unnamed loopback must stay refused', () => {
  for (const host of ['127.0.0.1', '10.0.0.5', '::1', '[::1]', '2130706433', '0x7f000001', '127.1']) {
    const settings = exceptionSettings(withEnv({ IPASS_BS_EXCEPTION_HOST: host }));
    assert.equal(settings.configured, false, host);
    assert.match(settings.reason, /not a host name|IP literal/, host);
    assert.ok(!settings.reason.startsWith(NOT_CONFIGURED), `${host}: misconfigured, not unset`);
  }
});

test('one exact host: no wildcard, port, scheme, path, user info or space', () => {
  for (const host of ['*.showcase.test', 'target.showcase.test:8080', 'http://target.showcase.test',
    'target.showcase.test/orders', 'user@target.showcase.test', 'target showcase', 'target..test', '-target.test']) {
    assert.equal(exceptionSettings(withEnv({ IPASS_BS_EXCEPTION_HOST: host })).configured, false, host);
  }
  assert.equal(exceptionSettings(withEnv({ IPASS_BS_EXCEPTION_HOST: 'localhost' })).configured, true,
    'a single-label name the system resolver knows is a host name');
});

test('the profile is ONE named profile - never default, never a list', () => {
  for (const profile of ['default', 'DEFAULT', 'showcase,prod', 'show case', 'showcase;prod']) {
    const settings = exceptionSettings(withEnv({ IPASS_BS_EXCEPTION_PROFILE: profile }));
    assert.equal(settings.configured, false, profile);
  }
  assert.equal(exceptionSettings(withEnv({ IPASS_BS_EXCEPTION_PROFILE: 'dev-showcase' })).configured, true);
});

test('the host variable is a plain environment name - not the profile switch, not a JVM option', () => {
  for (const name of ['showcase_host', 'SHOWCASE-HOST', 'SPRING_PROFILES_ACTIVE', 'JAVA_TOOL_OPTIONS',
    'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JAVA_HOME', 'PATH']) {
    const settings = exceptionSettings(withEnv({ IPASS_BS_EXCEPTION_HOST_VAR: name }));
    assert.equal(settings.configured, false, name);
  }
});

test('requireException fails with the reason when not configured and returns the settings when configured', () => {
  assert.throws(() => requireException(exceptionSettings({})), (e) => e.message.startsWith(NOT_CONFIGURED));
  const settings = exceptionSettings(VALID);
  assert.equal(requireException(settings), settings);
});

test('targetUrl addresses the exception host only: http://<host>:<port><path>', () => {
  const settings = exceptionSettings(VALID);
  assert.equal(targetUrl(settings, 32768, '/tenant-a/orders'), 'http://target.showcase.test:32768/tenant-a/orders');
  for (const port of [0, 65536, '8080', 1.5, undefined]) {
    assert.throws(() => targetUrl(settings, port, '/tenant-a/orders'), /port/, String(port));
  }
  assert.throws(() => targetUrl(settings, 32768, 'tenant-a/orders'), /path/);
  assert.throws(() => targetUrl(exceptionSettings({}), 32768, '/tenant-a/orders'), (e) => e.message.startsWith(NOT_CONFIGURED));
});

test('assertRealResolver refuses a hosts-file or name-service override in any JVM option variable', () => {
  assert.throws(() => assertRealResolver({ JAVA_TOOL_OPTIONS: '-Duser.timezone=Etc/UTC -Djdk.net.hosts.file=C:/hosts' }),
    /real host resolver.*JAVA_TOOL_OPTIONS/);
  assert.throws(() => assertRealResolver({ JDK_JAVA_OPTIONS: '-Dsun.net.spi.nameservice.provider.1=dns,fake' }),
    /real host resolver.*JDK_JAVA_OPTIONS/);
  assert.throws(() => assertRealResolver({ _JAVA_OPTIONS: '-Djdk.net.hosts.file=/tmp/h' }), /_JAVA_OPTIONS/);
  assert.doesNotThrow(() => assertRealResolver({ JAVA_TOOL_OPTIONS: '-Duser.timezone=Etc/UTC', _JAVA_OPTIONS: '-Xmx1g' }));
  assert.doesNotThrow(() => assertRealResolver({}));
});
