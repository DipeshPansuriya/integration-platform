// T112 (E13 base N3, lane D): the message detail and failures screens' API reads (T110). Every call
// through the ONE typed client (src/api/client.ts) against the real API, no mock (E3-F3-S1).
//   GET /api/v1/admin/messages/{trackingId}/timeline - one message's recorded stages; 404 {"error":
//     "not found"} when the bound tenant cannot see it; 403 NO_BOUND_USER / ROLE_NOT_GRANTED.
//   GET /api/v1/admin/messages/failures?flowId=&date= - failed + dead-lettered work, newest first.
// Neither answer carries a payload field; lastError is the backend's text (masking is T108's, server side).

import { api } from './client';
import type { ApiAnswer } from './flows';

export type StageTime = {
  stage: string;
  startedAt: string;
  finishedAt: string | null;
  durationMs: number | null;
  sincePreviousMs: number | null;
};

/**
 * One delivery of the message: the last attempt's sent-to-answered time and outcome (null: no attempt yet).
 * Row 328 V2: deliberately the 4-field subset of the row 329 contract ({state, attemptCount, lastAttempt{...}});
 * Mira decision 17-Oct - kept for the showcase, the seam's contract regeneration uses it, mahesh-architect widens it.
 */
export type DeliveryTime = {
  deliveryId: string;
  targetId: string | null;
  durationMs: number | null;
  outcome: string | null;
};

export type Timeline = {
  trackingId: string;
  flowId: string;
  state: string;
  acceptedAt: string | null;
  stages: StageTime[];
  totalMs: number | null;
  /** Server-side (row 328 M1): ROUTE for ROUTE_FAILED, DELIVER for a DEAD_LETTERED delivery, else null. */
  failingStage: string | null;
  /** Row 329 M3: one entry per delivery of the message. */
  deliveries: DeliveryTime[];
};

export type Failure = {
  trackingId: string;
  deliveryId: string | null;
  flowId: string;
  targetId: string | null;
  failingStage: string;
  errorClass: string;
  lastError: string | null;
  origin: string;
  failedAt: string;
};

// The live-regenerated schemas (openapi.d.ts Timeline/Failure/DeliveryTime) now carry failingStage and
// deliveries, but springdoc marks every record field optional and never nullable, so the two reads below
// keep one cast to the documented contract (tsc refuses the bare assignment: 'string | undefined' vs 'string').

/** flows.ts's answer() pattern, keeping the backend's error/refusal wording. */
function answer<T>(data: T | undefined, error: unknown, response: Response): ApiAnswer<T> {
  if (response.ok && data !== undefined) return { ok: true, data };
  const body = (error ?? {}) as { error?: string; refusal?: string };
  return { ok: false, status: response.status, error: body.error, refusal: body.refusal };
}

export async function messageTimeline(trackingId: string): Promise<ApiAnswer<Timeline>> {
  const { data, error, response } = await api.GET('/api/v1/admin/messages/{trackingId}/timeline', {
    params: { path: { trackingId } }
  });
  return answer(data as unknown as Timeline | undefined, error, response);
}

export async function messageFailures(params: { flowId?: string; date?: string }): Promise<ApiAnswer<Failure[]>> {
  const { data, error, response } = await api.GET('/api/v1/admin/messages/failures', { params: { query: params } });
  return answer((data ?? []) as unknown as Failure[], error, response);
}

