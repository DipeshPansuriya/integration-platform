// Row 575 S2 (guided flow, spec 4.3): the pure logic behind Home "Next steps". Everything is computed from the list answers Home
// already fetches (listFlows / listSystems / listContracts / today's failures) - no new API. UX only: the server's 403 stays the
// gate, and a block whose API answers 403 is simply not rendered. No DOM, no fetch - unit-tested under node.

import { OPERATION_ROLES } from '../nav-model';
import { contractsInDraft, systemsByDirection } from './dashboard-counts';

type WithStatus = { status?: string | null };
type FlowLike = WithStatus & { id: string; code?: string; name?: string };
type ContractLike = { versions?: readonly WithStatus[] | null };
type SystemLike = { direction?: string | null };

/** `info` = a step this page cannot track (sample run, go live): it never claims done and never blocks the next step. */
export type StepState = 'done' | 'next' | 'todo' | 'info';
export type SetupStep = {
  n: number;
  key: 'systems' | 'contracts' | 'flow' | 'test' | 'approve' | 'golive';
  label: string;
  state: StepState;
  /** ✓ ● ○ - status is never colour alone. */
  mark: string;
  stateWord: string;
  note: string;
  action?: { label: string; href: string };
};

const MARK: Record<StepState, string> = { done: '✓', next: '●', todo: '○', info: '–' };
const WORD: Record<StepState, string> = { done: 'Done', next: 'Next', todo: 'To do', info: 'Not tracked here' };

const hasContractStatus = (contracts: readonly ContractLike[], status: string): number =>
  contracts.filter((c) => (c.versions ?? []).some((v) => v.status === status)).length;

const plural = (n: number, one: string, many: string): string => `${n} ${n === 1 ? one : many}`;

type Draft = { done: boolean; info?: string; note: string; action?: { label: string; href: string }; openHref: string };

/** The six setup steps (spec 4.3) from list data only; the first step not done is "next", the ones after it are "to do". */
export function setupSteps(lists: { systems: readonly SystemLike[]; contracts: readonly ContractLike[]; flows: readonly FlowLike[] }): SetupStep[] {
  const { systems, contracts, flows } = lists;
  const dir = systemsByDirection(systems);
  const statusOf = (s: string) => flows.filter((f) => f.status === s);
  const drafts = statusOf('DRAFT');
  const submitted = statusOf('SUBMITTED');
  const approved = statusOf('APPROVED');
  const published = statusOf('PUBLISHED');

  const step1: Draft = dir.inbound >= 1 && dir.outbound >= 1
    ? { done: true, note: `${dir.total} registered`, openHref: '#/systems' }
    : {
        done: false,
        note: dir.total === 0 ? 'No systems yet. Register the source system first.' : dir.inbound >= 1 ? 'Register a target system next.' : 'Register the source system next.',
        action: { label: 'Register a system', href: '#/systems' },
        openHref: '#/systems',
      };

  const approvedContracts = hasContractStatus(contracts, 'APPROVED');
  const pendingContracts = hasContractStatus(contracts, 'PENDING_APPROVAL');
  const draftContracts = contractsInDraft(contracts as { versions?: readonly WithStatus[] | null }[]);
  const step2: Draft = approvedContracts > 0
    ? { done: true, note: `${approvedContracts} approved`, openHref: '#/contracts' }
    : pendingContracts > 0
      ? { done: false, note: `${plural(pendingContracts, 'contract waits', 'contracts wait')} for approval`, action: { label: 'Open', href: '#/contracts' }, openHref: '#/contracts' }
      : draftContracts > 0
        ? { done: false, note: `${plural(draftContracts, 'draft', 'drafts')} - submit for approval`, action: { label: 'Open', href: '#/contracts' }, openHref: '#/contracts' }
        : { done: false, note: 'No approved contract yet.', action: { label: 'Import a contract', href: '#/contracts' }, openHref: '#/contracts' };

  const step3: Draft = flows.length > 0
    ? { done: true, note: `${flows.length} ${flows.length === 1 ? 'flow exists' : 'flows exist'}`, openHref: '#/flows' }
    : { done: false, note: 'No flow yet.', action: { label: 'Define a flow', href: '#/flows' }, openHref: '#/flows' };

  // Step 4: the lists do not say whether a sample run passed (the only gate the UI knows is the publish check), so no tick.
  const step4: Draft = { done: false, info: 'Checked when you publish', note: 'The sample run is checked when you publish the flow.', action: drafts[0] ? { label: 'Open the flow', href: `#/flow/${drafts[0].id}` } : undefined, openHref: '#/flows' };

  const waiting = submitted[0] ?? approved[0];
  const step5: Draft = published.length > 0
    ? { done: true, note: `${plural(published.length, 'flow', 'flows')} published`, openHref: '#/versions' }
    : {
        done: false,
        note: submitted.length > 0 ? 'Submitted - waits for approval.' : approved.length > 0 ? 'Approved - publish it.' : drafts.length > 0 ? 'Run the sample, then submit the flow for approval.' : 'Submit the flow for approval.',
        action: waiting ? { label: 'Open', href: `#/versions/${waiting.id}` } : drafts[0] ? { label: 'Open the flow', href: `#/flow/${drafts[0].id}` } : undefined,
        openHref: '#/versions',
      };

  // Step 6: whether the source key was issued is not in any list Home reads, so it is a pointer, never "next" (the checklist ends at step 5).
  const step6: Draft = { done: false, info: 'Not tracked here', note: "Issue the source key on the system's Go live tab if you have not.", action: { label: 'Open Systems', href: '#/systems' }, openHref: '#/systems' };

  const drafts6: Array<[SetupStep['key'], string, Draft]> = [
    ['systems', 'Connect systems', step1],
    ['contracts', 'Contracts', step2],
    ['flow', 'Flow', step3],
    ['test', 'Test', step4],
    ['approve', 'Approve and publish', step5],
    ['golive', 'Go live', step6],
  ];
  let nextTaken = false;
  return drafts6.map(([key, label, d], index) => {
    const state: StepState = d.done ? 'done' : d.info ? 'info' : nextTaken ? 'todo' : 'next';
    if (state === 'next') nextTaken = true;
    const step: SetupStep = { n: index + 1, key, label, state, mark: MARK[state], stateWord: d.info ?? WORD[state], note: state === 'todo' ? '' : d.note };
    if (state === 'next' && d.action) step.action = d.action;
    else if (state === 'info') step.action = d.action;
    else if (state === 'done') step.action = { label: 'Open', href: d.openHref };
    return step;
  });
}

/** The first step that is not done (null when all are). */
export function nextStep(steps: readonly SetupStep[]): SetupStep | null {
  return steps.find((s) => s.state === 'next') ?? null;
}

const IN_PROGRESS: Record<string, string> = { DRAFT: 'Run the sample and submit', SUBMITTED: 'Waits for approval', APPROVED: 'Publish' };

/** Flows that are not published yet, each with the one next thing to do (from the list data only). */
export function flowsInProgress(flows: readonly FlowLike[]): Array<{ id: string; name: string; next: string; href: string }> {
  return flows
    .filter((f) => f.status !== undefined && f.status !== null && f.status in IN_PROGRESS)
    .map((f) => ({ id: f.id, name: f.name || f.code || f.id, next: IN_PROGRESS[f.status as string], href: `#/flow/${f.id}` }));
}

/** What waits for an approver: submitted flow versions and contract versions that wait for approval. */
export function approvalsWaiting(flows: readonly WithStatus[], contracts: readonly ContractLike[]): { flows: number; contracts: number; total: number } {
  const f = flows.filter((x) => x.status === 'SUBMITTED').length;
  const c = hasContractStatus(contracts, 'PENDING_APPROVAL');
  return { flows: f, contracts: c, total: f + c };
}

type Roles = readonly string[] | null | undefined;
const SETUP_ROLES: readonly string[] = ['TENANT_ADMIN', 'CONFIGURATOR']; // EDIT_CONFIGURATION in RolePolicy.java

/** Which Home blocks a role sees (spec 4.3). Roles not loaded (null / undefined): all three; the 403 rule hides what fails. */
export function homeBlocks(roles: Roles): { setup: boolean; approvals: boolean; messages: boolean } {
  if (!roles) return { setup: true, approvals: true, messages: true };
  const any = (allowed: readonly string[]) => roles.some((r) => allowed.includes(r));
  return { setup: any(SETUP_ROLES), approvals: any(OPERATION_ROLES.APPROVE_VERSION), messages: any(OPERATION_ROLES.READ_MESSAGE_TIMELINE) };
}

export const TRACKING_ID_HINT = 'Enter a tracking id (36 characters)';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** A valid tracking id opens its message; anything else returns null (the screen shows TRACKING_ID_HINT). */
export function trackingHash(input: string): string | null {
  const id = input.trim();
  return UUID.test(id) ? `#/messages/${id}` : null;
}

/** Today as yyyy-mm-dd (the failures call and its link take a day). */
export function dayOf(date: Date): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())}`;
}
