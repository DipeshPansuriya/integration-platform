// T112 (E13 base N3, lane D): the failures screen's pure view model. GET
// /api/v1/admin/messages/failures (T110) answers the bound tenant's failed and dead-lettered work,
// optionally narrowed to one flow and one UTC day, newest first - the view keeps that order. Each row
// links to the detail screen with the failing stage (the timeline alone cannot tell a delivery failure).
// lastError stays plain text exactly as answered: the screen renders it as a React text child (escaped,
// never parsed as HTML); masking any sensitive value inside it is server-side (T108), not here.
// No DOM, no fetch - unit-tested under node.

import type { Failure } from '../api/messages';
import { messageHash, stageName, utcText } from './message-timeline';

export type FailureRow = {
  key: string;
  trackingId: string;
  flowLabel: string;
  detailHash: string;
  failingStage: string;
  stageLabel: string;
  errorClass: string;
  lastErrorText: string;
  hasLastError: boolean;
  origin: string;
  originWord: string;
  failedAt: string;
  failedAtText: string;
};

const ORIGINS: Record<string, string> = { PLATFORM: 'Platform (our side)', TARGET: 'Target answer' };

export function originWord(origin: string): string {
  return ORIGINS[origin] ?? origin;
}

/** Flow id -> flow code, from the listFlows() answer the filter already holds. */
export function flowLabels(flows: readonly { id: string; code: string }[]): Record<string, string> {
  return Object.fromEntries(flows.map((f) => [f.id, f.code]));
}

/** `labels` names each row's flow (the All-flows view's Flow column); an unlisted flow shows its raw id. */
export function failureRows(failures: readonly Failure[], labels: Record<string, string> = {}): FailureRow[] {
  return failures.map((f) => ({
    key: f.deliveryId ?? `${f.trackingId}|${f.failingStage}`,
    trackingId: f.trackingId,
    flowLabel: labels[f.flowId] ?? f.flowId,
    detailHash: messageHash(f.trackingId, f.failingStage),
    failingStage: f.failingStage,
    stageLabel: stageName(f.failingStage),
    errorClass: f.errorClass,
    lastErrorText: f.lastError ?? 'No error text recorded',
    hasLastError: f.lastError != null,
    origin: f.origin,
    originWord: originWord(f.origin),
    failedAt: f.failedAt,
    failedAtText: utcText(f.failedAt)
  }));
}

/** The table's column headers; Flow only in the All-flows view (a selected flow is named in the caption). */
export function failureColumns(showFlow: boolean): string[] {
  const flow = showFlow ? ['Flow'] : [];
  return ['Tracking id', ...flow, 'Failing stage', 'Error class', 'Last error', 'Origin', 'Failed at (UTC)'];
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

/** Only valid filters reach the API (a junk value would be a 400, not "no failures"). */
export function failuresQuery(filters: { flowId: string; date: string }): { flowId?: string; date?: string } {
  const query: { flowId?: string; date?: string } = {};
  if (UUID.test(filters.flowId)) query.flowId = filters.flowId;
  if (ISO_DATE.test(filters.date)) query.date = filters.date;
  return query;
}

/** The backend's days are UTC days; the default filter is today's UTC date, not the browser's. */
export function todayUtc(now: Date): string {
  return now.toISOString().slice(0, 10);
}

function scope(flowLabel: string | null, date: string | null): string {
  return `${flowLabel ? ` for ${flowLabel}` : ''}${date ? ` on ${date} (UTC)` : ''}`;
}

export function emptyText(flowLabel: string | null, date: string | null): string {
  return `No failed messages${scope(flowLabel, date)}.`;
}

export function failuresCaption(flowLabel: string | null, date: string | null): string {
  const what = flowLabel || date ? scope(flowLabel, date) : ', all flows and days';
  return `Failed and dead-lettered messages${what}, newest first`;
}


/** The row key: the delivery id, else tracking id + stage (the same identity failureRows() uses). */
export function failureKey(f: Pick<Failure, 'deliveryId' | 'trackingId' | 'failingStage'>): string {
  return f.deliveryId ?? `${f.trackingId}|${f.failingStage}`;
}

/**
 * A silent refresh after Replay/Retry: rows the server no longer lists drop out, except the ones the operator
 * just acted on (they stay in place, showing their answer, until the next Reload); new rows follow.
 */
export function keepActed(previous: readonly Failure[], fresh: readonly Failure[], actedKeys: readonly string[]): Failure[] {
  const freshByKey = new Map(fresh.map((f) => [failureKey(f), f]));
  const out: Failure[] = [];
  const seen = new Set<string>();
  for (const old of previous) {
    const key = failureKey(old);
    const now = freshByKey.get(key);
    if (now) out.push(now);
    else if (actedKeys.includes(key)) out.push(old);
    else continue;
    seen.add(key);
  }
  for (const f of fresh) if (!seen.has(failureKey(f))) out.push(f);
  return out;
}

/** "5 min ago" beside the absolute time; blank for an unreadable instant. */
export function relativeAge(iso: string, now: Date): string {
  const then = Date.parse(iso);
  if (Number.isNaN(then)) return '';
  const seconds = Math.max(0, Math.round((now.getTime() - then) / 1000));
  if (seconds < 60) return 'just now';
  if (seconds < 3600) return `${Math.floor(seconds / 60)} min ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} h ago`;
  return `${Math.floor(seconds / 86400)} d ago`;
}

/** Platform = our side (warn), Target = the target's answer (info); the word still says which. */
export function originTone(origin: string): 'warn' | 'info' | 'neutral' {
  return origin === 'PLATFORM' ? 'warn' : origin === 'TARGET' ? 'info' : 'neutral';
}

/** The Queue's "Failed" link: #/failures narrowed to one flow and day. */
export function failuresHash(flowId: string, day: string): string {
  return `#/failures?flow=${encodeURIComponent(flowId)}&date=${encodeURIComponent(day)}`;
}

/** Reads `?flow=&date=` off a failures hash; junk values are ignored (failuresQuery's own rule). */
export function failuresParamsFromHash(hash: string): { flowId: string; date: string | null } {
  const query = hash.includes('?') ? hash.slice(hash.indexOf('?') + 1) : '';
  const params = new URLSearchParams(query);
  const checked = failuresQuery({ flowId: params.get('flow') ?? '', date: params.get('date') ?? '' });
  return { flowId: checked.flowId ?? '', date: checked.date ?? null };
}
