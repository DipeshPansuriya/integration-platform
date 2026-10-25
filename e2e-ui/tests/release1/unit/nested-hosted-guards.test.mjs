// T214: the hosted driver's guards - https only on every hop, only the app's own domain, an explicit timeout, no manual redirect
// following, and a failure line that is short, stackless and secret-free. Offline: a fake fetch stands in for the network.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { describeFailure, guardedFetch } from '../../../scripts/release1/hosted-guards.mjs';

const BASE = 'https://ipass.example.test';
const seen = [];
const fake = async (url, init) => { seen.push({ url: String(url), init }); return new Response('ok'); };

test('guardedFetch: https on the app host and its sibling auth host passes, redirects are never followed, a timeout signal is set', async () => {
  const guarded = guardedFetch(BASE, fake, 5000);
  await guarded('https://ipass.example.test/api/v1/me');
  await guarded('https://auth.example.test/realms/platform/protocol/openid-connect/auth');
  assert.equal(seen.length, 2);
  assert.ok(seen.every((s) => s.init.redirect === 'manual' && s.init.signal instanceof AbortSignal));
});

test('guardedFetch: a plain-http hop (login or token redirect) is refused before any request', async () => {
  const before = seen.length;
  await assert.rejects(guardedFetch(BASE, fake)('http://auth.example.test/realms/platform'), /not https/);
  assert.equal(seen.length, before);
});

test('guardedFetch: another domain is refused, even over https', async () => {
  await assert.rejects(guardedFetch(BASE, fake)('https://evil.example.org/x'), /not the app's domain/);
  await assert.rejects(guardedFetch(BASE, fake)('https://example.test.evil.org/x'), /not the app's domain/);
});

test('guardedFetch: a hung server is cut at the timeout', async () => {
  const hung = (url, init) => new Promise((resolve, reject) => init.signal.addEventListener('abort', () => reject(init.signal.reason)));
  await assert.rejects(guardedFetch(BASE, hung, 50)('https://ipass.example.test/api/v1/me'));
});

test('describeFailure: one short line, no stack, the secrets and the host paths redacted', () => {
  const error = new Error(`NESTED-APPOINTMENT source credential: HTTP 500 ${'x'.repeat(900)} pw=Sup3rSecret!`);
  const line = describeFailure(error, ['Sup3rSecret!', '']);
  assert.ok(!line.includes('\n') && !line.includes('    at '));
  assert.ok(line.length <= 320, String(line.length));
  assert.ok(!line.includes('Sup3rSecret!'));
  assert.match(line, /^seed-nested-appointment-hosted: NESTED-APPOINTMENT source credential: HTTP 500/);
});

test('the driver prints a failure through describeFailure, never the raw error', () => {
  const driver = readFileSync(new URL('../../../scripts/seed-nested-appointment-hosted.mjs', import.meta.url), 'utf8');
  assert.match(driver, /describeFailure\(/);
  assert.ok(!/console\.error\([a-z]+\)/.test(driver), 'no console.error(error)');
  assert.match(driver, /guardedFetch\(/);
});
