// lane qa-release (2026-10-22) BS-17a / BS-07 negative leg WITHOUT the exception - the default profile refuses
// every outbound destination that is not the allow-listed name, whatever its address, at configuration: an
// unnamed loopback, a private and a link-local (metadata) literal, a name that is not on the allow-list.
// The refusal persists nothing: the same code saves afterwards on the allow-listed host. The showcase
// exception itself (the lifted refusal) is bs-07.docker.spec.mjs and needs the dev-only exception settings.

import { expect, test } from '@playwright/test';
import { FRONT_TARGET_BASE } from '../lib/front-options.mjs';
import { TARGET_CONTRACT, TENANTS } from '../lib/tenants.mjs';
import { inBrowser } from '../support/api.mjs';
import { bsEnv } from '../support/boot-state.mjs';

test.describe.configure({ mode: 'serial' });

const REFUSED_TARGETS = [
  ['target-loop-127', 'http://127.0.0.1:8089/tenant-a/loop', 'unnamed loopback literal'],
  ['target-loop-name', 'http://localhost:8089/tenant-a/loop', 'loopback by name, not on the allow-list'],
  ['target-private', 'http://10.0.0.5/orders', 'private literal'],
  ['target-metadata', 'http://169.254.169.254/latest/meta-data', 'link-local metadata literal'],
  ['target-unlisted', 'https://not-listed.partner.test/orders', 'a name that is not on the allow-list']
];

const outbound = (code, baseUrl) => ({ code, name: code, direction: 'OUTBOUND', type: 'API', settings: { baseUrl } });

test('BS-17a: the default profile refuses loopback, private, link-local and unlisted targets at configuration', async ({ page }) => {
  await page.goto(`${bsEnv('consoleUrl')}/`);
  const problems = [];
  for (const [code, baseUrl, what] of REFUSED_TARGETS) {
    const answer = await inBrowser(page, 'POST', '/api/v1/admin/systems', outbound(code, baseUrl), TENANTS.A.admin);
    if (answer.status < 400 || answer.status >= 500) problems.push(`${what}: ${baseUrl} answered ${answer.status} ${JSON.stringify(answer.data).slice(0, 160)}`);
  }
  expect(problems, 'each refused with a 4xx').toEqual([]);
});

test('BS-17a: a refused target left nothing behind - the same code saves on the allow-listed host', async ({ page }) => {
  await page.goto(`${bsEnv('consoleUrl')}/`);
  const [code] = REFUSED_TARGETS[0];
  const saved = await inBrowser(page, 'POST', '/api/v1/admin/systems', outbound(code, `${FRONT_TARGET_BASE}/tenant-a/orders`), TENANTS.A.admin);
  expect([200, 201], `the allow-listed host saves: ${JSON.stringify(saved.data)}`).toContain(saved.status);
  expect(TARGET_CONTRACT.system, 'the seeded target is a different system').not.toBe(code);
});
