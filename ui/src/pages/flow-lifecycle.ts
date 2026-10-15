// Row 310 lane B (T88): pure helpers for the Flows list/editor and the Versions lifecycle panel
// (10-screens §3/§4). No React, no API - unit-tested by tests/ui/flows-versions.test.mjs.

import type { FlowSummary } from '../api/flows';

export type LifecycleAction = 'save-draft' | 'submit-for-approval' | 'approve' | 'publish' | 'new-draft';

const ACTIONS: Record<string, LifecycleAction[]> = {
  DRAFT: ['save-draft', 'submit-for-approval'],
  SUBMITTED: ['approve', 'new-draft'],
  APPROVED: ['publish', 'new-draft'],
  PUBLISHED: ['new-draft']
};

/** §4: the action row is contextual - only what the selected version's status allows. */
export function lifecycleActions(status: string): LifecycleAction[] {
  return ACTIONS[status] ?? [];
}

/** A submitted (or approved) version's content cannot be edited; a new draft is the only way. */
export function isImmutable(status: string): boolean {
  return status === 'SUBMITTED' || status === 'APPROVED';
}

/** The harness-only approver is a quiet test affordance, offered only while a version waits for approval. */
export function harnessApproveOffered(status: string, harness: boolean): boolean {
  return harness && status === 'SUBMITTED';
}

const ORDER = ['DRAFT', 'SUBMITTED', 'APPROVED', 'PUBLISHED'] as const;
const WORDS = ['Draft', 'Submitted', 'Approved', 'Published'];

export type Step = { status: string; word: string; state: 'done' | 'current' | 'todo' };

/** §4 stepper: Draft -> Submitted -> Approved -> Published as worded steps. */
export function lifecycleSteps(status: string): Step[] {
  const at = ORDER.indexOf(status as (typeof ORDER)[number]);
  return ORDER.map((s, i) => ({ status: s, word: WORDS[i], state: i < at ? 'done' : i === at ? 'current' : 'todo' }));
}

/** §3 list: one text filter over name and code. */
export function filterFlows(rows: FlowSummary[], text: string): FlowSummary[] {
  const needle = text.trim().toLowerCase();
  if (!needle) return rows;
  return rows.filter((row) => `${row.name ?? ''} ${row.code ?? ''}`.toLowerCase().includes(needle));
}

export const NEW_FLOW_HASH = '#/flow/new';

export function flowHash(id: string): string {
  return `#/flow/${encodeURIComponent(id)}`;
}

/** #/flow/new (or no id) is create mode; anything else is a flow id to open. */
export function editorFlowId(openFlowId: string | null | undefined): string | null {
  return openFlowId && openFlowId !== 'new' ? openFlowId : null;
}

export type FlowForm = {
  sourceSystem: string;
  destinations: string[];
  interfaceName: string;
  businessKeyRule: string;
  ordering: string;
  sourceSchema: string;
};

/** §3 unsaved changes: the form differs from what was last loaded or saved. */
export function isDirty(baseline: FlowForm, current: FlowForm): boolean {
  return JSON.stringify(baseline) !== JSON.stringify(current);
}
