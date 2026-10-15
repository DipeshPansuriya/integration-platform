// F10-narrow (row278 release-base plan step 8, lane D): the queue dashboard's only API read.
// Every call through the ONE typed client (src/api/client.ts) against the real API, no mock
// (E3-F3-S1). GET /api/v1/admin/queue/summary answers an array of {flowId, flowCode, flowName,
// day, queued, processed, converted, failed} - the four figures derived live from the
// message/delivery state tables, per flow and UTC day, ordered by flow code then day. A refusal
// (403 NO_BOUND_USER / ROLE_NOT_GRANTED) answers flows.ts's answer() shape; 400 names the bad
// query value.

import { api } from './client';
import type { ApiAnswer } from './flows';

export type QueueRow = {
  flowId: string;
  flowCode: string;
  flowName: string;
  day: string;
  queued: number;
  processed: number;
  converted: number;
  failed: number;
};

/** flows.ts's answer() pattern, keeping the backend's error/refusal wording. */
function answer<T>(data: T | undefined, error: unknown, response: Response): ApiAnswer<T> {
  if (response.ok && data !== undefined) return { ok: true, data };
  const body = (error ?? {}) as { error?: string; refusal?: string };
  return { ok: false, status: response.status, error: body.error, refusal: body.refusal };
}

const SUMMARY_PATH = '/api/v1/admin/queue/summary';

export async function queueSummary(params?: { flowId?: string; date?: string }): Promise<ApiAnswer<QueueRow[]>> {
  const { data, error, response } = await api.GET(SUMMARY_PATH, { params: { query: params } });
  return answer((data ?? []) as unknown as QueueRow[], error, response);
}