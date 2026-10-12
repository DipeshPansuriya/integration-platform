// T-017/T-022 (E3 lane D): the ONE API seam every console screen calls through the typed
// client (src/api/client.ts) - no hand-written fetch to a backend route, no mock layer
// (E3-F3-S1). The bodies are the real platform-config answers: {error} for a designed
// refusal, {refusal: <code>} for the named approval refusals (ConfigApiException.java).

import { api } from './client';

export type FlowSummary = { id: string; code: string; name: string; ordering: string; status: string };
export type FlowDetail = FlowSummary & {
  sourceSystem: string;
  destinations: string[];
  businessKeyRule: string;
};

/** The harness-login marker (test protocol only): the console proxy swaps in the binding's token. */
export const HARNESS_LOGIN_HEADER = 'X-Harness-Login';

export type ApiAnswer<T> = { ok: true; data: T } | { ok: false; status: number; error?: string; refusal?: string };

function answer<T>(data: T | undefined, error: unknown, response: Response): ApiAnswer<T> {
  if (response.ok && data !== undefined) return { ok: true, data };
  const body = (error ?? {}) as { error?: string; refusal?: string };
  return { ok: false, status: response.status, error: body.error, refusal: body.refusal };
}

export async function listFlows(): Promise<ApiAnswer<FlowSummary[]>> {
  const { data, error, response } = await api.GET('/api/v1/admin/flows');
  return answer((data ?? []) as unknown as FlowSummary[], error, response);
}

export async function readFlow(flowId: string): Promise<ApiAnswer<FlowDetail>> {
  const { data, error, response } = await api.GET('/api/v1/admin/flows/{flowId}', {
    params: { path: { flowId } }
  });
  return answer(data as unknown as FlowDetail, error, response);
}

export type FlowDraftBody = {
  sourceSystem: string;
  destinations: string[];
  businessKeyRule: string;
  name?: string;
  ordering?: string;
};

export async function createFlow(body: FlowDraftBody): Promise<ApiAnswer<{ id: string }>> {
  const { data, error, response } = await api.POST('/api/v1/admin/flows', { body: body as never });
  return answer(data as unknown as { id: string }, error, response);
}

export async function saveFlowDraft(flowId: string, body: FlowDraftBody): Promise<ApiAnswer<unknown>> {
  const { data, error, response } = await api.PUT('/api/v1/admin/flows/{flowId}', {
    params: { path: { flowId } },
    body: body as never
  });
  return answer(data, error, response);
}

/** The draft-save's sourceSchema half (E3-F3-S1): stores it as a draft SOURCE_REQUEST contract version (200). */
export async function saveSourceSchema(system: string, name: string, sourceSchema: unknown): Promise<ApiAnswer<unknown>> {
  const { data, error, response } = await api.POST('/api/v1/admin/contracts/versions', {
    body: { system, role: 'SOURCE_REQUEST', name, sourceSchema } as never
  });
  return answer(data, error, response);
}

export async function submitFlow(flowId: string, changeNote: string): Promise<ApiAnswer<unknown>> {
  const { data, error, response } = await api.POST('/api/v1/admin/flows/{flowId}/submit', {
    params: { path: { flowId } },
    body: { changeNote } as never
  });
  return answer(data, error, response);
}

export async function approveFlow(flowId: string, asLogin?: string): Promise<ApiAnswer<unknown>> {
  const { data, error, response } = await api.POST('/api/v1/admin/flows/{flowId}/approve', {
    params: { path: { flowId } },
    headers: asLogin ? { [HARNESS_LOGIN_HEADER]: asLogin } : undefined
  });
  return answer(data, error, response);
}

export async function publishFlow(flowId: string): Promise<ApiAnswer<unknown>> {
  const { data, error, response } = await api.POST('/api/v1/admin/flows/{flowId}/publish', {
    params: { path: { flowId } }
  });
  return answer(data, error, response);
}

/** Maps a designed 400 answer to the data-validation-error attribute the screens show. */
export function validationFieldOf(error: string | undefined): string | null {
  if (!error) return null;
  if (error.includes('businessKeyRule')) return 'business-key-rule';
  if (error.includes('sourceSystem')) return 'source-system';
  if (error.includes('sourceSchema')) return 'source-schema';
  if (error.includes('destinations')) return 'destinations';
  return 'request';
}
