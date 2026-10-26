// Row 327 T119 (lane D, vijay-developer, E12-F2-S2): the activity view's pure words and shapes. The
// server scopes every read to the bound tenant and orders newest first; nothing here filters by tenant
// or re-sorts. No DOM, no fetch - unit-tested under node (tests/ui/activity-view.test.mjs).

import type { Activity, ActivityFilter } from '../api/activity';

/** ConfigAuditActions.SYSTEM_ACTOR: the nil UUID the backend records for system-made changes. */
export const SYSTEM_ACTOR = '00000000-0000-0000-0000-000000000000';

/** A null or blank actor is worded, never a blank cell. */
export const UNKNOWN_ACTOR = 'Unknown';

export function actorLabel(actor: string | null | undefined): string {
  if (actor === SYSTEM_ACTOR) return 'System';
  if (actor == null || actor.trim() === '') return UNKNOWN_ACTOR;
  return actor;
}

export type ActivityRow = {
  id: string;
  actor: string;
  action: string;
  targets: string;
  before: string;
  after: string;
  at: string;
};

/** One row per returned event, in the server's order; before/after stay the text the API returned. */
export function activityRows(items: readonly Activity[]): ActivityRow[] {
  return items.map((item) => ({
    id: item.id,
    actor: actorLabel(item.actor),
    action: item.action,
    targets: (item.targetIds ?? []).join(', '),
    before: item.before ?? '',
    after: item.after ?? '',
    at: item.at
  }));
}

/** GET /api/v1/activity query: page and size always, action/from/to only when filled. */
export function activityQuery(filter: ActivityFilter): Record<string, string | number> {
  const query: Record<string, string | number> = {};
  const action = filter.action?.trim();
  if (action) query.action = action;
  if (filter.from) query.from = filter.from;
  if (filter.to) query.to = filter.to;
  query.page = filter.page;
  query.size = filter.size;
  return query;
}

export type ActivityTone = 'success' | 'danger' | 'neutral';

/**
 * The badge tone for an action name. The action set is open-ended (ConfigAuditActions grows), so only a
 * completed lifecycle step (..._APPROVED / ..._PUBLISHED) is success, a failure or revocation
 * (..._FAILED / ..._REJECTED / ..._REVOKED / ..._DENIED) is danger, and anything else - including a
 * value never seen before - is neutral.
 */
export function activityTone(action: string): ActivityTone {
  if (/(?:FAILED|REJECTED|REVOKED|DENIED)$/i.test(action)) return 'danger';
  if (/(?:APPROVED|PUBLISHED)$/i.test(action)) return 'success';
  return 'neutral';
}

export function pagerState(page: number, hasMore: boolean): { previous: boolean; next: boolean } {
  return { previous: page > 0, next: hasMore };
}

export type RefusalState = 'not-found' | 'no-access' | 'failed';

/** 404 = not found (another tenant's event looks exactly like a missing one); 403 = no access. */
export function refusalState(status: number): RefusalState {
  if (status === 404) return 'not-found';
  if (status === 403) return 'no-access';
  return 'failed';
}

export const REFUSAL_COPY: Record<RefusalState, string> = {
  'not-found': 'Activity event not found.',
  'no-access': 'You do not have access to the activity view.',
  failed: 'The activity could not be read.'
};

/** #/activity/<eventId> opens one event; #/activity is the list. */
export function eventIdFromHash(hash: string): string | null {
  const match = /^#\/activity\/(.+)$/.exec(hash);
  return match ? decodeURIComponent(match[1]) : null;
}
