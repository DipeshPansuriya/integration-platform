// T112 (E13 base N3, lane D): the failures screen's pure view model. GET
// /api/v1/admin/messages/failures (T110) answers the bound tenant's failed and dead-lettered work,
// optionally narrowed to one flow and one UTC day, newest first - the view keeps that order. Each row
// links to the detail screen with the failing stage (the timeline alone cannot tell a delivery failure).
// lastError stays plain text exactly as answered: the screen renders it as a React text child (escaped,
// never parsed as HTML); masking any sensitive value inside it is server-side (T108), not here.
// No DOM, no fetch - unit-tested under node.

import type { Failure } from '../api/messages';
import { messageHash, stageLabel, utcText } from './message-timeline';

export type FailureRow = {
  key: string;
  trackingId: string;
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

export function failureRows(failures: readonly Failure[]): FailureRow[] {
  return failures.map((f) => ({
    key: f.deliveryId ?? `${f.trackingId}|${f.failingStage}`,
    trackingId: f.trackingId,
    detailHash: messageHash(f.trackingId, f.failingStage),
    failingStage: f.failingStage,
    stageLabel: stageLabel(f.failingStage),
    errorClass: f.errorClass,
    lastErrorText: f.lastError ?? 'No error text recorded',
    hasLastError: f.lastError != null,
    origin: f.origin,
    originWord: originWord(f.origin),
    failedAt: f.failedAt,
    failedAtText: utcText(f.failedAt)
  }));
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
