// Row 327 T119 (lane D, vijay-developer, E12-F2-S2): the activity view's API reads, through the ONE
// client (src/api/client.ts) against the real API, no mock. GET /api/v1/activity answers ActivityPage
// {items, page, size, hasMore} (size default 50, max 200), the bound tenant's events newest first;
// GET /api/v1/activity/{eventId} answers one Activity or 404 {"error":"not found"} - also for another
// tenant's id. Both need READ_MESSAGE_TIMELINE; a refusal answers flows.ts's ApiAnswer shape.

import { api } from './client';
import type { ApiAnswer } from './flows';

// ponytail: hand-typed from ActivityService.Activity / ActivityPage (platform-app ActivityService.java:35-41);
// replaced by the generated client (openapi.d.ts) when arjun regenerates it at the seam - then drop the cast below.
export type Activity = {
  id: string;
  actor: string | null;
  action: string;
  targetIds: string[] | null;
  before: string | null;
  after: string | null;
  at: string;
};

export type ActivityPage = { items: Activity[]; page: number; size: number; hasMore: boolean };

export type ActivityFilter = { action?: string; from?: string; to?: string; page: number; size: number };

type UntypedGet = {
  GET(
    path: string,
    init: { params: { query?: Record<string, string | number>; path?: Record<string, string> } }
  ): Promise<{ data?: unknown; error?: unknown; response: Response }>;
};
// ponytail: the activity paths are not in openapi.d.ts until the seam regeneration; same client, same fetch.
const client = api as unknown as UntypedGet;

/** flows.ts's answer() pattern, keeping the backend's error/refusal wording. */
function answer<T>(data: T | undefined, error: unknown, response: Response): ApiAnswer<T> {
  if (response.ok && data !== undefined) return { ok: true, data };
  const body = (error ?? {}) as { error?: string; refusal?: string };
  return { ok: false, status: response.status, error: body.error, refusal: body.refusal };
}

export async function listActivity(query: Record<string, string | number>): Promise<ApiAnswer<ActivityPage>> {
  const { data, error, response } = await client.GET('/api/v1/activity', { params: { query } });
  return answer(data as ActivityPage | undefined, error, response);
}

export async function readActivity(eventId: string): Promise<ApiAnswer<Activity>> {
  const { data, error, response } = await client.GET('/api/v1/activity/{eventId}', { params: { path: { eventId } } });
  return answer(data as Activity | undefined, error, response);
}
