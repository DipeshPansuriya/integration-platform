// Row 310 lane A (sanjay-developer, T87): the Dashboard's pure counts and words (10-screens §1/§2,
// 00-portal-ia §4/§5). Everything here is computed from the list answers the console already
// fetches (listFlows / listSystems / listContracts) - no new API. "Pending approval" is
// tenant-scoped (Q3 default, m-5517d7): the flow list carries no author, so "my" is not
// expressible in phase 1 and the copy says so honestly. No DOM, no fetch - unit-tested under node.

type WithStatus = { status?: string | null };

/** SUBMITTED flows wait for an approver; DRAFT flows are drafts to finish. */
export function flowCounts(flows: readonly WithStatus[]): { pendingApproval: number; drafts: number } {
  let pendingApproval = 0;
  let drafts = 0;
  for (const flow of flows) {
    if (flow.status === 'SUBMITTED') pendingApproval += 1;
    else if (flow.status === 'DRAFT') drafts += 1;
  }
  return { pendingApproval, drafts };
}

/** A contract counts once when any of its versions is still a DRAFT. */
export function contractsInDraft(contracts: readonly { versions?: readonly WithStatus[] | null }[]): number {
  return contracts.filter((contract) => (contract.versions ?? []).some((v) => v.status === 'DRAFT')).length;
}

export function systemsByDirection(systems: readonly { direction?: string | null }[]): {
  total: number;
  inbound: number;
  outbound: number;
} {
  const inbound = systems.filter((s) => s.direction === 'INBOUND').length;
  const outbound = systems.filter((s) => s.direction === 'OUTBOUND').length;
  return { total: systems.length, inbound, outbound };
}

/** The last five listed, newest-listed first (the list carries no timestamp); the input is not mutated. */
export function recentVersions<T>(flows: readonly T[]): T[] {
  return flows.slice(-5).reverse();
}

export type TileKind = 'pending' | 'drafts' | 'systems' | 'contracts';

const TILE_WORDS: Record<TileKind, { one: string; many: string; rest: string }> = {
  pending: { one: 'version', many: 'versions', rest: 'pending approval' },
  drafts: { one: 'draft', many: 'drafts', rest: 'to finish' },
  systems: { one: 'system', many: 'systems', rest: 'registered' },
  contracts: { one: 'contract', many: 'contracts', rest: 'in draft' }
};

/** The tile link's programmatic name: count + words, singular at 1, worded zero, worded unknown. */
export function tileName(kind: TileKind, count: number | null): string {
  const { one, many, rest } = TILE_WORDS[kind];
  if (count === null) return `${many.charAt(0).toUpperCase()}${many.slice(1)} ${rest}: count not available`;
  if (count === 0) return `No ${many} ${rest}`;
  return `${count} ${count === 1 ? one : many} ${rest}`;
}

export type BadgeTone = 'success' | 'warn' | 'info' | 'danger' | 'neutral';

const BADGES: Record<string, { word: string; tone: BadgeTone }> = {
  DRAFT: { word: 'Draft', tone: 'warn' },
  SUBMITTED: { word: 'Submitted', tone: 'info' },
  APPROVED: { word: 'Approved', tone: 'success' },
  PUBLISHED: { word: 'Published', tone: 'success' }
};

/** 01-tokens status map: the badge always carries its word; an unknown status shows as named, neutral. */
export function statusBadge(status: string): { word: string; tone: BadgeTone } {
  return BADGES[status] ?? { word: status, tone: 'neutral' };
}

export function greetingFor(hour: number, user: string | undefined): string {
  const part = hour < 12 ? 'morning' : hour < 18 ? 'afternoon' : 'evening';
  return user ? `Good ${part}, ${user}` : `Good ${part}`;
}

export type SessionState = 'signed-in' | 'signed-out' | 'error';

/** GET /api/v1/me: 200 with a body = signed in, 401 = signed out, anything else (or no answer) = error. */
export function sessionStateOf(status: number | null, body: unknown): SessionState {
  if (status === 200 && body) return 'signed-in';
  if (status === 401) return 'signed-out';
  return 'error';
}

/** A10: the 'some counts could not be loaded' banner stays mounted while its Retry runs, so focus is not dropped. */
export const retryBannerShown = (failed: boolean, retrying: boolean): boolean => failed || retrying;
