// lane qa-release (2026-10-22): the front half's raw intake call - the variants support/api.mjs postRecord
// cannot send (a missing header, a wrong or missing Content-Type, a body that is not JSON, a body of an exact
// size, no token). Bounded by a timeout, never retried.

import { randomUUID } from 'node:crypto';
import { bsEnv } from './boot-state.mjs';

/**
 * POST to the common intake as a source system: { status, data }. A header whose value is `null` is left out.
 * `body` is sent as given (text), so a malformed or oversized body is possible; `token: null` sends none.
 */
export async function rawIntake(token, interfaceCode, {
  version = '1.0', idempotencyKey = randomUUID(), contentType = 'application/json', body = '{}', extraHeaders = {}
} = {}) {
  const headers = { ...extraHeaders };
  if (token !== null) headers.authorization = `Bearer ${token}`;
  if (contentType !== null) headers['content-type'] = contentType;
  if (version !== null) headers['X-Source-Contract-Version'] = version;
  if (idempotencyKey !== null) headers['Idempotency-Key'] = idempotencyKey;
  const answer = await fetch(`${bsEnv('consoleUrl')}/api/v1/interfaces/${encodeURIComponent(interfaceCode)}/messages`, {
    method: 'POST', headers, body, signal: AbortSignal.timeout(30000)
  });
  const text = await answer.text();
  let data = null;
  try {
    data = JSON.parse(text);
  } catch {
    data = text;
  }
  return { status: answer.status, data };
}
