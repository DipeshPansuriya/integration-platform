// Shared helpers for the lane-screens-gap docker specs (activity, delivery-report, systems-settings-fields).
// The same protocol as queue-dashboard / failures / message-detail: real API from the signed-in page
// (session + CSRF cookies), fixtures that no API can write go into the suite's own pinned postgres.
// Kept in a NEW file so the existing specs and harness files stay untouched.

import { execFileSync } from 'node:child_process';
import process from 'node:process';
import { expect } from '@playwright/test';
import { consoleUrl } from './boot-state.mjs';

/** One API call from inside the page: the browser's own session and CSRF cookies, like the console's client. */
export async function inBrowser(page, method, apiPath, body) {
  return page.evaluate(
    async ({ method, apiPath, body }) => {
      const xsrf = document.cookie.split('; ').filter((pair) => pair.startsWith('XSRF-TOKEN=')).pop();
      const headers = {};
      if (body !== undefined) headers['content-type'] = 'application/json';
      if (method !== 'GET' && xsrf) headers['X-XSRF-TOKEN'] = decodeURIComponent(xsrf.slice('XSRF-TOKEN='.length));
      const answer = await fetch(apiPath, { method, headers, credentials: 'include', body: body === undefined ? undefined : JSON.stringify(body) });
      return { status: answer.status, data: await answer.json().catch(() => null) };
    },
    { method, apiPath, body }
  );
}

/** The suite's own pinned postgres (boot-backend names it ipass-ui-t41-pg-*) - fixtures in, as owner. */
export function psql(sql) {
  const names = execFileSync('docker', ['ps', '--format', '{{.Names}}'], { encoding: 'utf8' })
    .split('\n').map((name) => name.trim()).filter((name) => name.startsWith('ipass-ui-t41-pg-'));
  if (names.length !== 1) throw new Error(`exactly one booted suite postgres expected, saw: ${names.join(', ')}`);
  return execFileSync('docker', ['exec', names[0], 'psql', '-U', 'owner', '-d', 'platform', '-t', '-A', '-c', sql],
    { encoding: 'utf8' }).trim();
}

/** The signed-in tenant's id, looked up by the harness tenant code. */
export function tenantIdOf() {
  const id = psql(`select id::text from public.tenant where code = '${process.env.CONSOLE_TENANT_CODE}'`);
  expect(id, 'the harness tenant row exists').not.toBe('');
  return id;
}

/** Onboards ONE fresh flow through the real onboarding API; the 201 carries only {id}. */
export async function freshFlow(page, scenario) {
  await page.goto(consoleUrl()); // the browser session must exist before the page-side fetch
  await expect(page.locator('nav')).toBeVisible();
  const created = await inBrowser(page, 'POST', '/api/v1/admin/flows',
    { sourceSystem: 'S', destinations: ['T1'], businessKeyRule: 'customer_id' });
  expect(created?.data?.id, `scenario ${scenario} onboards a flow: ${JSON.stringify(created?.data ?? created)}`).toBeTruthy();
  return { id: created.data.id, scenario };
}

/** GET /api/v1/me: the signed-in user (userId is the activity actor of every change this session makes). */
export async function meOf(page) {
  const me = await inBrowser(page, 'GET', '/api/v1/me');
  expect(me.data?.userId, `GET /api/v1/me names the user: ${JSON.stringify(me)}`).toBeTruthy();
  return me.data;
}
