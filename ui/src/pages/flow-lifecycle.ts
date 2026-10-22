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

/** B12: what the open flow editor tells the shell - unsaved edits, and whether Leave was already chosen. */
export type EditorGuardState = { dirty: boolean; released: boolean };

export type HashMove = { kind: 'route' } | { kind: 'confirm'; restore: string; leaveTo: string };

/**
 * B12: the shell's decision for an address change (Back, Forward, a typed #/ address). A dirty editor
 * that the user has not released keeps the screen: the address goes back to `restore` (the editor's own
 * URL) and the in-page confirm offers `leaveTo`. Anything else routes.
 */
export function hashMove(oldUrl: string, newUrl: string, editor: EditorGuardState | null): HashMove {
  if (!editor || !editor.dirty || editor.released) return { kind: 'route' };
  return { kind: 'confirm', restore: oldUrl, leaveTo: new URL(newUrl).hash || '#/' };
}

// B13: the visible text per designed 400 field (validationFieldOf in api/flows.ts). 'request' is a
// refusal the console has no field for - it shows the server's own words.
const VALIDATION_TEXT: Record<string, string> = {
  'source-system': 'sourceSystem is required',
  'business-key-rule': 'The mandatory business-key rule is missing: businessKeyRule',
  'source-schema': 'sourceSchema must be a JSON Schema object',
  destinations: 'destinations must name at least one registered system'
};

export function validationText(field: string, serverError?: string | null): string {
  return VALIDATION_TEXT[field] ?? (serverError?.trim() || 'The request was refused');
}

export const validationErrorId = (field: string) => `flow-error-${field}`;

/** B13: the failing field is aria-invalid and described by its visible error text; other fields get nothing. */
export function fieldA11y(validationField: string | null, field: string): { 'aria-invalid'?: true; 'aria-describedby'?: string } {
  return validationField === field ? { 'aria-invalid': true, 'aria-describedby': validationErrorId(field) } : {};
}

/** A11: the ordering enum in words for a table cell. */
export function orderingWord(ordering: string): string {
  return ordering === 'PER_KEY' ? 'Per key' : ordering === 'NONE' ? 'None' : ordering;
}

/** A11: the sentence a polite live region announces after the flow filter changes ('' = nothing to say). */
export function filterCountText(shown: number, total: number, filter: string): string {
  if (!filter.trim()) return '';
  if (shown === 0) return 'No flow matches the filter.';
  return `${shown} of ${total} flows ${shown === 1 ? 'matches' : 'match'}.`;
}

/** A10: the row Versions opens on - the deep-linked version, else the first draft, else the first row. */
export function initialSelection<T extends { id: string; status: string }>(rows: T[], selectId: string | null): T | undefined {
  return (selectId ? rows.find((row) => row.id === selectId) : undefined) ?? rows.find((row) => row.status === 'DRAFT') ?? rows[0];
}

/** A8: the control focus goes to when this validation field fails (the 'request' bucket has none). */
export function focusSelectorFor(field: string | null): string | null {
  if (!field || field === 'request') return null;
  return field === 'destinations' ? '[data-field="destination-1"]' : `[data-field="${field}"]`;
}

/** A8: the next index when Tab (or Shift+Tab) is pressed inside a dialog of `count` controls: wraps, never leaves. */
export function trapTab(current: number, count: number, shift: boolean): number {
  if (count <= 0) return -1;
  return shift ? (current - 1 + count) % count : (current + 1) % count;
}
