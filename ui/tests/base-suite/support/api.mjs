// T130 (row 323, F21 BS-B): the base suite's calls - every one bounded by a timeout, none retried (a red
// is reported, never retried to green).
//   console API  through the page (the console proxy), as the signed-in tenant-A admin or - with a login
//                marker - as another harness login (X-Harness-Login; tests/screens/console-server.mjs:10-13)
//   intake       from Node as the SOURCE SYSTEM: a client-credentials token from the realm, then the one
//                common endpoint POST /api/v1/interfaces/{code}/messages (IntakeController.java:53)
//   WireMock     its admin API: the request journal (lib/wiremock.mjs reads it)

import { randomUUID } from 'node:crypto';
import { bsEnv } from './boot-state.mjs';

/** One console API call from the page, with the CSRF token on writes; `asLogin` names a harness login. */
export async function inBrowser(page, method, apiPath, body, asLogin) {
  return page.evaluate(
    async ({ method, apiPath, body, asLogin }) => {
      const xsrf = document.cookie.split('; ').filter((pair) => pair.startsWith('XSRF-TOKEN=')).pop();
      const headers = {};
      if (body !== undefined) headers['content-type'] = 'application/json';
      if (method !== 'GET' && xsrf) headers['X-XSRF-TOKEN'] = decodeURIComponent(xsrf.slice('XSRF-TOKEN='.length));
      if (asLogin) headers['X-Harness-Login'] = asLogin;
      const answer = await fetch(apiPath, {
        method, headers, credentials: 'include', signal: AbortSignal.timeout(15000),
        body: body === undefined ? undefined : JSON.stringify(body)
      });
      return { status: answer.status, data: await answer.json().catch(() => null) };
    },
    { method, apiPath, body, asLogin }
  );
}

/** The answer's data when 200/201; otherwise the step fails with its status and body. */
export function must(answer, what) {
  if (answer.status !== 200 && answer.status !== 201) {
    throw new Error(`${what}: HTTP ${answer.status} ${JSON.stringify(answer.data)}`);
  }
  return answer.data;
}

/** A source system's access token (client credentials at the realm's token endpoint); memory only. */
export async function sourceToken(clientId, clientSecret) {
  const answer = await fetch(`${bsEnv('issuer')}/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'client_credentials', client_id: clientId, client_secret: clientSecret }),
    signal: AbortSignal.timeout(5000)
  });
  const data = await answer.json().catch(() => null);
  if (answer.status !== 200 || typeof data?.access_token !== 'string') {
    throw new Error(`source token for ${clientId}: HTTP ${answer.status} ${data?.error ?? ''}`);
  }
  return data.access_token;
}

/** POST one record to the common intake as the source system: { status, data, idempotencyKey }. */
export async function postRecord(token, interfaceCode, versionLabel, record) {
  const idempotencyKey = randomUUID();
  const answer = await fetch(`${bsEnv('consoleUrl')}/api/v1/interfaces/${encodeURIComponent(interfaceCode)}/messages`, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${token}`,
      'content-type': 'application/json',
      'X-Source-Contract-Version': versionLabel,
      'Idempotency-Key': idempotencyKey
    },
    body: JSON.stringify(record),
    signal: AbortSignal.timeout(10000)
  });
  return { status: answer.status, data: await answer.json().catch(() => null), idempotencyKey };
}

/** WireMock's request journal (GET /__admin/requests). */
export async function journal() {
  const answer = await fetch(`${bsEnv('wiremockAdmin')}/requests`, { signal: AbortSignal.timeout(5000) });
  if (answer.status !== 200) throw new Error(`WireMock journal: HTTP ${answer.status}`);
  return answer.json();
}

/** Polls `probe` until it returns a truthy value or `timeoutMs` passes; the last value or error is reported. */
export async function eventually(probe, what, { timeoutMs = 60000, everyMs = 500 } = {}) {
  const until = Date.now() + timeoutMs;
  let last;
  for (;;) {
    try {
      last = await probe();
      if (last) return last;
    } catch (e) {
      last = e;
    }
    if (Date.now() > until) throw new Error(`${what}: not reached within ${timeoutMs} ms (last: ${last instanceof Error ? last.message : JSON.stringify(last)})`);
    await new Promise((resolve) => setTimeout(resolve, everyMs));
  }
}

/** The one app process still serves (BS-07 "0 deploys"): the pid the boot started is alive. */
export function sameAppProcess() {
  const pid = Number(bsEnv('appPid'));
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}
