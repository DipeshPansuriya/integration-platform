// Row 575 S4 (guided flow, spec 4.4): the setup stepper and the tabs of ONE flow, computed from the two reads the workspace makes
// (the flow and its routes) - no list-wide fan-out. Honest: a step the data cannot show (contracts, the sample run, go live)
// never ticks; it says "Not tracked here" / "Checked when you publish". UX only; the server stays the gate. No DOM, no fetch.

import { OPERATION_ROLES } from '../nav-model';

export type FlowStepState = 'done' | 'next' | 'todo' | 'info';
export type FlowStep = {
  n: number;
  key: 'systems' | 'contracts' | 'mapping' | 'test' | 'approve' | 'golive';
  label: string;
  state: FlowStepState;
  mark: string;
  stateWord: string;
  note: string;
  action?: { label: string; href: string };
};

export type WorkspaceRoles = { canApprove: boolean; isTenantAdmin: boolean; canReadMessages: boolean };
type FlowLike = { id: string; status?: string | null; sourceSystem?: string | null; destinations?: readonly string[] | null };
type RouteLike = { id: string; mappingId?: string | null };

const MARK: Record<FlowStepState, string> = { done: '✓', next: '●', todo: '○', info: '–' };
const WORD: Record<FlowStepState, string> = { done: 'Done', next: 'Next', todo: 'To do', info: 'Not tracked here' };

/** Roles not loaded (null): approve and messages shown (the server's 403 still guards); tenant admin is never assumed. */
export function workspaceRoles(roles: readonly string[] | null | undefined): WorkspaceRoles {
  if (!roles) return { canApprove: true, isTenantAdmin: false, canReadMessages: true };
  const any = (allowed: readonly string[]) => roles.some((r) => allowed.includes(r));
  return {
    canApprove: any(OPERATION_ROLES.APPROVE_VERSION),
    isTenantAdmin: roles.includes('TENANT_ADMIN'),
    canReadMessages: any(OPERATION_ROLES.READ_MESSAGE_TIMELINE)
  };
}

type Draft = { done: boolean; info?: string; note: string; action?: { label: string; href: string } };

const mappingHref = (routeId: string) => `#/mappings/${encodeURIComponent(routeId)}`;
const versionsHref = (flowId: string) => `#/versions/${encodeURIComponent(flowId)}`;

export function flowStepper(input: { flow: FlowLike; routes: readonly RouteLike[]; roles: WorkspaceRoles }): { steps: FlowStep[]; next: FlowStep | null } {
  const { flow, routes, roles } = input;
  const destinations = flow.destinations ?? [];

  const systems: Draft = flow.sourceSystem && destinations.length > 0
    ? { done: true, note: `Source ${flow.sourceSystem}, ${destinations.length} ${destinations.length === 1 ? 'destination' : 'destinations'}.` }
    : { done: false, note: 'Name the source system and at least one destination, then save.' };

  const contracts: Draft = { done: false, info: 'Not tracked here', note: 'Check the contracts on the Systems page.', action: { label: 'Open Systems', href: '#/systems' } };

  const unmapped = routes.filter((r) => !r.mappingId);
  const mapping: Draft = routes.length === 0
    ? { done: false, note: 'No routes listed yet.' }
    : unmapped.length === 0
      ? { done: true, note: `${routes.length} of ${routes.length} routes have a mapping.` }
      : { done: false, note: `${routes.length - unmapped.length} of ${routes.length} routes have a mapping.`, action: { label: 'Open mapping', href: mappingHref(unmapped[0].id) } };

  // The lists do not say whether a sample run passed (the publish check is the only gate the UI knows), so no tick.
  const test: Draft = { done: false, info: 'Checked when you publish', note: 'Run the sample on the mapping before you publish.' };

  const status = flow.status ?? 'DRAFT';
  const open = { label: 'Open versions', href: versionsHref(flow.id) };
  const approve: Draft = status === 'PUBLISHED'
    ? { done: true, note: 'Published.' }
    : status === 'SUBMITTED'
      ? roles.canApprove
        ? { done: false, note: 'Submitted - waits for approval.', action: open }
        : { done: false, note: 'Submitted - waits for an approver.' }
      : status === 'APPROVED'
        ? roles.canApprove
          ? { done: false, note: 'Approved - publish it.', action: open }
          : { done: false, note: 'Approved - waits for someone to publish it.' }
        : { done: false, note: 'Submit the flow for approval.', action: open };

  const golive: Draft = roles.isTenantAdmin
    ? { done: false, info: 'Not tracked here', note: 'Issue the source key on the system Go live tab if you have not.', action: { label: 'Open Systems', href: '#/systems' } }
    : { done: false, info: 'Not tracked here', note: 'Ask your tenant admin to issue the source key.' };

  const table: Array<[FlowStep['key'], string, Draft]> = [
    ['systems', 'Systems', systems],
    ['contracts', 'Contracts', contracts],
    ['mapping', 'Mapping', mapping],
    ['test', 'Test', test],
    ['approve', 'Approve and publish', approve],
    ['golive', 'Go live', golive]
  ];
  let taken = false;
  const steps = table.map(([key, label, d], index): FlowStep => {
    const state: FlowStepState = d.done ? 'done' : d.info ? 'info' : taken ? 'todo' : 'next';
    if (state === 'next') taken = true;
    const step: FlowStep = { n: index + 1, key, label, state, mark: MARK[state], stateWord: d.info ?? WORD[state], note: state === 'todo' ? '' : d.note };
    if (state !== 'todo' && d.action) step.action = d.action;
    return step;
  });
  return { steps, next: steps.find((s) => s.state === 'next') ?? null };
}

export type FlowTab = { key: 'setup' | 'mapping' | 'rules' | 'versions' | 'messages'; label: string; href: string; current: boolean };

/** Tabs are links (spec section 5). Messages only for roles that read messages; Setup is the page you are on. */
export function flowTabs(input: { flowId: string; routes: readonly RouteLike[]; roles: WorkspaceRoles }): FlowTab[] {
  const id = encodeURIComponent(input.flowId);
  const tabs: FlowTab[] = [
    { key: 'setup', label: 'Setup', href: `#/flow/${id}`, current: true },
    { key: 'mapping', label: 'Mapping', href: input.routes[0] ? mappingHref(input.routes[0].id) : '#/mappings', current: false },
    { key: 'rules', label: 'Rules', href: `#/rules?flow=${id}`, current: false },
    { key: 'versions', label: 'Versions', href: versionsHref(input.flowId), current: false }
  ];
  if (input.roles.canReadMessages) tabs.push({ key: 'messages', label: 'Messages', href: `#/failures?flow=${id}`, current: false });
  return tabs;
}
