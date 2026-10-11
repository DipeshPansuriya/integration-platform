// T-028 (E6-F3-S1, lane D): the test panel's logic. A sample runs on the RUNTIME evaluation port only -
// POST /api/v1/admin/rule-sets/{ruleSetId}/evaluate through the one typed client - so what the panel
// shows is what production decides (ADR-003 rule 3); nothing evaluates rules in the browser. The verdict
// is the canonical JSON of {output, violations}: keys sorted at every depth, no whitespace, so the
// parity test (lane C, T-029) compares it byte for byte with the port's own answer.

import type { ConsoleClient } from '../api/client';

export function canonicalJson(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  if (value !== null && typeof value === 'object') {
    const record = value as Record<string, unknown>;
    return `{${Object.keys(record)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonicalJson(record[key])}`)
      .join(',')}}`;
  }
  return JSON.stringify(value ?? null);
}

export function verdictText(answer: { output?: unknown; violations?: unknown }): string {
  return canonicalJson({ output: answer.output ?? null, violations: answer.violations ?? [] });
}

export type PanelResult = { state: 'done' | 'error'; text: string };

export async function runSample(client: Pick<ConsoleClient, 'POST'>, ruleSetId: string, inputText: string): Promise<PanelResult> {
  let input: unknown;
  try {
    input = JSON.parse(inputText);
  } catch {
    return { state: 'error', text: 'the sample is not valid JSON' };
  }
  const { data, error, response } = await client.POST('/api/v1/admin/rule-sets/{ruleSetId}/evaluate', {
    params: { path: { ruleSetId } },
    body: { input } as never
  });
  if (error !== undefined || data === undefined) {
    const body = (error ?? {}) as { error?: string; refusal?: string };
    return { state: 'error', text: `refused ${response.status}: ${body.error ?? body.refusal ?? 'no answer'}` };
  }
  return { state: 'done', text: verdictText(data as { output?: unknown; violations?: unknown }) };
}
