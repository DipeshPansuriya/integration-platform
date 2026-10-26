// lane qa-release (2026-10-22): the front-half boot - the base suite's scenarios that need NO dev-only
// exception. Same harness boot as boot-options.mjs minus the exception, minus the WireMock; the only
// outbound setting is the normal allow-list with ONE exact host name.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { EXCEPTION_ENV } from '../lib/settings.mjs';
import { FRONT_TARGET_BASE, FRONT_TARGET_HOST, frontSuiteBootOptions } from '../lib/front-options.mjs';

const LOCK = 'aditya-qa-automation front-half test';

test('front boot: tenant B, a Kafka broker, source credentials - no WireMock', () => {
  const o = frontSuiteBootOptions({ lockHolder: LOCK });
  assert.equal(o.sourceCredentials, true);
  assert.equal(o.lockHolder, LOCK);
  assert.equal(o.extraTenants.length, 1);
  assert.ok(o.services.kafka, 'the outbox relay publishes to a real broker');
  assert.equal(o.services.wiremock, undefined, 'nothing listens behind the front half: no local target');
});

test('front boot: no profile switch, no showcase property, none of the four exception names', () => {
  const o = frontSuiteBootOptions({ lockHolder: LOCK });
  const names = Object.keys(o.appEnv).map((n) => n.toUpperCase());
  assert.ok(!names.includes('SPRING_PROFILES_ACTIVE'), 'the default profile stays');
  assert.deepEqual(names.filter((n) => n.includes('SHOWCASE')), []);
  for (const env of Object.values(EXCEPTION_ENV)) assert.ok(!names.includes(env));
  assert.equal(JSON.stringify(o).toLowerCase().includes('showcase'), false);
});

test('front boot: the normal allow-list names exactly one exact host - no wildcard, no IP, no port', () => {
  const o = frontSuiteBootOptions({ lockHolder: LOCK });
  assert.deepEqual(o.appEnv, { PLATFORM_CONFIG_DESTINATIONS_ALLOWED_HOSTS: FRONT_TARGET_HOST, ROUTING_CORE_ALLOWED_SCHEMES: 'https' });
  assert.match(FRONT_TARGET_HOST, /^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$/);
  assert.ok(!/^\d+(\.\d+){3}$/.test(FRONT_TARGET_HOST));
  assert.equal(FRONT_TARGET_BASE, `https://${FRONT_TARGET_HOST}`);
});

test('front boot: names the lock holder, like the exception boot', () => {
  assert.throws(() => frontSuiteBootOptions({}), /lock holder/);
  assert.throws(() => frontSuiteBootOptions({ lockHolder: '  ' }), /lock holder/);
});
