// Lane A (B27, audit A1-A3): the flow-definition save and open outcomes as pure functions, so every
// failure is a worded sentence and "saved" is only ever reported for a save that really completed.
// The API calls arrive as arguments (the screen passes the typed client), so this file is testable
// without a DOM or a network.

import type { FlowDraftBody, FlowDetail, ApiAnswer } from '../api/flows';
import type { FlowForm } from './flow-lifecycle';

const MIN_DESTINATIONS = 2;

/** One plain sentence: what failed and what to do. Never a bare HTTP code. status 0 = no answer at all. */
export function failureText(task: string, status: number): string {
  if (status === 0) return `Could not ${task}: the server could not be reached. Check your connection and try again.`;
  if (status === 401) return `Could not ${task}: your session has expired. Sign in again, then retry.`;
  if (status === 403) return `Could not ${task}: you do not have permission for this action. Ask an administrator if you need it.`;
  if (status === 429) return `Could not ${task}: too many requests just now. Wait a moment and try again.`;
  if (status >= 500) return `Could not ${task}: the server had a problem. Try again in a moment.`;
  return `Could not ${task}. Try again, or reload the page if it keeps failing.`;
}

export type SaveDeps = {
  createFlow: (body: FlowDraftBody & { ordering: string }) => Promise<ApiAnswer<unknown>>;
  saveFlowDraft: (id: string, body: FlowDraftBody & { ordering: string }) => Promise<ApiAnswer<unknown>>;
  saveSourceSchema: (system: string, name: string, schema: unknown) => Promise<ApiAnswer<unknown>>;
  validationFieldOf: (error: string | undefined) => string | null;
};

export type SaveOutcome =
  | { kind: 'saved'; id: string }
  | { kind: 'validation'; field: string | null; text: string | null; id?: string }
  | { kind: 'not-found' }
  | { kind: 'failed'; text: string; id?: string };

export async function saveFlowDefinition(deps: SaveDeps, flowId: string | null, form: FlowForm): Promise<SaveOutcome> {
  let id = flowId ?? undefined;
  try {
    const draft: FlowDraftBody & { ordering: string } = {
      sourceSystem: form.sourceSystem,
      destinations: form.destinations.map((row) => row.trim()).filter((row) => row.length > 0),
      businessKeyRule: form.businessKeyRule,
      name: form.interfaceName.trim(), // F-1: the interface name typed by the user rides on the create
      ordering: form.ordering
    };
    const result = flowId ? await deps.saveFlowDraft(flowId, draft) : await deps.createFlow(draft);
    if (!result.ok) {
      if (result.status === 400) return { kind: 'validation', field: deps.validationFieldOf(result.error), text: result.error ?? null };
      if (result.status === 404) return { kind: 'not-found' };
      return { kind: 'failed', text: failureText('save the draft', result.status) };
    }
    id = flowId ?? (result.data as { id: string }).id;

    if (form.sourceSchema.trim().length > 0) {
      let schema: unknown;
      try {
        schema = JSON.parse(form.sourceSchema);
      } catch {
        return { kind: 'validation', field: 'source-schema', text: null, id };
      }
      const stored = await deps.saveSourceSchema(form.sourceSystem, form.interfaceName || 'interface', schema);
      if (!stored.ok) {
        if (stored.status === 400) return { kind: 'validation', field: deps.validationFieldOf(stored.error), text: stored.error ?? null, id };
        // A2: the draft is saved but the schema is not - never "Draft saved".
        return { kind: 'failed', id, text: `The draft was saved, but the source schema could not be saved. ${failureText('save the source schema', stored.status)}` };
      }
    }
    return { kind: 'saved', id };
  } catch {
    return { kind: 'failed', id, text: failureText('save the draft', 0) };
  }
}

export type OpenOutcome =
  | { kind: 'loaded'; form: FlowForm }
  | { kind: 'not-found' }
  | { kind: 'failed'; text: string };

export async function loadFlowForm(read: (id: string) => Promise<ApiAnswer<FlowDetail>>, id: string): Promise<OpenOutcome> {
  try {
    const answer = await read(id);
    if (!answer.ok) {
      // 404 is the tenant-isolation answer (ADR-011 Decision 4): never a hint of whose row it is.
      if (answer.status === 404) return { kind: 'not-found' };
      return { kind: 'failed', text: failureText('open the flow', answer.status) };
    }
    const rows = [...answer.data.destinations];
    while (rows.length < MIN_DESTINATIONS) rows.push('');
    return {
      kind: 'loaded',
      form: {
        sourceSystem: answer.data.sourceSystem,
        destinations: rows,
        interfaceName: answer.data.name,
        businessKeyRule: answer.data.businessKeyRule,
        ordering: answer.data.ordering,
        sourceSchema: ''
      }
    };
  } catch {
    return { kind: 'failed', text: failureText('open the flow', 0) };
  }
}
