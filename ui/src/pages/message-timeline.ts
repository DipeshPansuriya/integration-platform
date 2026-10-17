// T112 (E13 base N3, lane D): the message detail screen's pure view model. The timeline answer
// (GET /api/v1/admin/messages/{trackingId}/timeline, T110) carries only the RECORDED stages
// (V2_1_1110 message_stage_timing: stage, started_at, finished_at - no outcome column); this module
// lays them over the whole pipeline, marks the failing stage and words every stage after it "Not
// reached" - never a zero duration (backlog negative scenario). No DOM, no fetch - unit-tested under node.
//
// The failing stage: the timeline carries the message state (ACCEPTED | ROUTED | ROUTE_FAILED) but no
// delivery state, and a stage row has no outcome. ROUTE_FAILED means ROUTE; for a ROUTED message the
// Failures screen's link passes ?failed=<STAGE> (DELIVER for a dead letter). The hint is display-only
// and accepted only where it is consistent with the state (a stage after ROUTE, on a routed message).

import type { StageTime, Timeline } from '../api/messages';

/** The pipeline's closed stage set in order (MessageTrackingService.PIPELINE, V2_1_1110). */
export const PIPELINE = ['INTAKE', 'ROUTE', 'TRANSFORM', 'DELIVER'] as const;

/** One wording for a 404 and a malformed id - never a hint that the id exists for another tenant. */
export const NOT_FOUND_TEXT = 'No message with this tracking id';

const LABELS: Record<string, string> = { INTAKE: 'Intake', ROUTE: 'Route', TRANSFORM: 'Transform', DELIVER: 'Deliver' };

export function stageLabel(stage: string): string {
  return LABELS[stage] ?? stage;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function isTrackingId(value: string): boolean {
  return UUID.test(value);
}

/** `<trackingId>[?failed=<STAGE>]` - the part of the hash after `#/messages/`. */
export function parseMessageRoute(rest: string): { trackingId: string; failedHint: string | null } {
  const [path, query = ''] = rest.split('?', 2);
  return { trackingId: decodeURIComponent(path), failedHint: new URLSearchParams(query).get('failed') };
}

export function messageHash(trackingId: string, failingStage: string | null): string {
  const base = `#/messages/${encodeURIComponent(trackingId)}`;
  return failingStage ? `${base}?failed=${encodeURIComponent(failingStage)}` : base;
}

/** ROUTE_FAILED is ROUTE; a ROUTED message takes the hint when it names a stage after ROUTE. */
export function failingStageOf(state: string, hint: string | null): string | null {
  if (state === 'ROUTE_FAILED') return 'ROUTE';
  if (state === 'ROUTED' && (hint === 'TRANSFORM' || hint === 'DELIVER')) return hint;
  return null;
}

export type StageStatus = 'done' | 'running' | 'failed' | 'not-reached' | 'not-recorded';

export type TimelineRow = {
  stage: string;
  label: string;
  status: StageStatus;
  startedAt: string | null;
  finishedAt: string | null;
  startedText: string;
  finishedText: string;
  durationText: string;
  sinceText: string;
  durationMs: number | null;
};

const STATUS_WORDS: Record<StageStatus, string> = {
  done: 'Done',
  running: 'Running',
  failed: 'Failed',
  'not-reached': 'Not reached',
  'not-recorded': 'Not recorded'
};

export function statusWord(status: StageStatus): string {
  return STATUS_WORDS[status];
}

export function formatMs(ms: number): string {
  if (Math.abs(ms) < 1000) return `${ms} ms`;
  if (Math.abs(ms) < 60000) return `${(ms / 1000).toFixed(3)} s`;
  const minutes = Math.trunc(ms / 60000);
  return `${minutes} min ${((ms - minutes * 60000) / 1000).toFixed(3)} s`;
}

export function totalText(totalMs: number | null): string {
  return totalMs === null ? 'Not available' : formatMs(totalMs);
}

/** The backend's ISO-8601 instant (always UTC, `Z`) as `yyyy-mm-dd hh:mm:ss[.fff] UTC`. */
export function utcText(iso: string): string {
  return iso.replace('T', ' ').replace(/Z$/, ' UTC');
}

function unrecorded(stage: string, status: StageStatus): TimelineRow {
  const word = status === 'not-reached' ? 'Not reached' : 'Not recorded';
  return {
    stage,
    label: stageLabel(stage),
    status,
    startedAt: null,
    finishedAt: null,
    startedText: word,
    finishedText: word,
    durationText: word,
    sinceText: word,
    durationMs: null
  };
}

function recorded(row: StageTime, failed: boolean): TimelineRow {
  const finished = row.finishedAt ?? null;
  const open = failed ? 'Did not finish' : 'Running';
  const durationMs = finished === null ? null : row.durationMs ?? null;
  return {
    stage: row.stage,
    label: stageLabel(row.stage),
    status: failed ? 'failed' : finished === null ? 'running' : 'done',
    startedAt: row.startedAt,
    finishedAt: finished,
    startedText: utcText(row.startedAt),
    finishedText: finished === null ? open : utcText(finished),
    durationText: durationMs === null ? open : formatMs(durationMs),
    sinceText: row.sincePreviousMs == null ? 'First stage' : formatMs(row.sincePreviousMs),
    durationMs
  };
}

/**
 * Every pipeline stage in order (plus any recorded stage the pipeline does not name, appended).
 * A recorded stage keeps the backend's own times; an unrecorded one is "Not reached" when it lies
 * after the failing stage (or no failure is known), "Not recorded" when the message got past it.
 */
export function timelineRows(timeline: Timeline, failingStage: string | null): TimelineRow[] {
  const byStage = new Map((timeline.stages ?? []).map((row) => [row.stage, row]));
  const order: string[] = [...PIPELINE, ...[...byStage.keys()].filter((s) => !(PIPELINE as readonly string[]).includes(s))];
  const failAt = failingStage === null ? -1 : order.indexOf(failingStage);
  return order.map((stage, index) => {
    const row = byStage.get(stage);
    const failed = index === failAt;
    if (row) return recorded(row, failed);
    if (failed) return { ...unrecorded(stage, 'not-recorded'), status: 'failed' };
    return unrecorded(stage, failAt >= 0 && index < failAt ? 'not-recorded' : 'not-reached');
  });
}

export type DetailRefusal = 'not-found' | 'refused' | 'error';

export function detailStateOf(status: number): DetailRefusal {
  if (status === 404) return 'not-found';
  if (status === 403) return 'refused';
  return 'error';
}
