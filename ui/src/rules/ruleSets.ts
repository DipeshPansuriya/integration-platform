// T-025 (E6-F1-S1, lane D): the rule-set calls of the rules editor, through the ONE typed client
// (src/api/client.ts - session cookie, CSRF header on writes) against lane B's real API (T-023):
// POST /api/v1/admin/flows/{flowId}/rule-sets, GET /api/v1/admin/flows/{flowId}/rule-sets,
// GET and PUT /api/v1/admin/rule-sets/{ruleSetId}. The answer is {id, flowId, kind, name, content};
// a malformed table is refused 400 RULE_TABLE_INVALID with the cell and the engine's reason.

import { api } from '../api/client';
import type { ZenJdm } from './DecisionTableEditorAdapter';

export type RuleSet = { id: string; flowId: string; kind: string; name: string; content: ZenJdm };

export type TableCell = { node?: string | null; row?: number | null; column?: string | null };

export type RuleSetAnswer<T> =
  | { ok: true; data: T }
  | { ok: false; status: number; error?: string; refusal?: string; cell?: TableCell | null; reason?: string };

function answer<T>(data: unknown, error: unknown, response: Response): RuleSetAnswer<T> {
  if (response.ok && data !== undefined) return { ok: true, data: data as T };
  const body = (error ?? {}) as { error?: string; refusal?: string; cell?: TableCell | null; reason?: string };
  return { ok: false, status: response.status, error: body.error, refusal: body.refusal, cell: body.cell, reason: body.reason };
}

export async function createRuleSet(flowId: string, kind: string, name: string, content: ZenJdm): Promise<RuleSetAnswer<RuleSet>> {
  const { data, error, response } = await api.POST('/api/v1/admin/flows/{flowId}/rule-sets', {
    params: { path: { flowId } },
    body: { kind, name, content } as never
  });
  return answer<RuleSet>(data, error, response);
}

export async function listRuleSets(flowId: string): Promise<RuleSetAnswer<RuleSet[]>> {
  const { data, error, response } = await api.GET('/api/v1/admin/flows/{flowId}/rule-sets', {
    params: { path: { flowId } }
  });
  return answer<RuleSet[]>(data, error, response);
}

export async function readRuleSet(ruleSetId: string): Promise<RuleSetAnswer<RuleSet>> {
  const { data, error, response } = await api.GET('/api/v1/admin/rule-sets/{ruleSetId}', {
    params: { path: { ruleSetId } }
  });
  return answer<RuleSet>(data, error, response);
}

export async function saveRuleSet(ruleSetId: string, name: string, content: ZenJdm): Promise<RuleSetAnswer<RuleSet>> {
  const { data, error, response } = await api.PUT('/api/v1/admin/rule-sets/{ruleSetId}', {
    params: { path: { ruleSetId } },
    body: { name, content } as never
  });
  return answer<RuleSet>(data, error, response);
}
