// T112 (E13 base N3, lane D): the message detail screen's pure view model. The timeline answer
// (GET /api/v1/admin/messages/{trackingId}/timeline, T110) carries only the RECORDED stages
// (V2_1_1110 message_stage_timing: stage, started_at, finished_at - no outcome column); this module
// lays them over the whole pipeline, marks the failing stage and words every stage after it "Not
// reached" - never a zero duration (backlog negative scenario). No DOM, no fetch - unit-tested under node.
//
// The failing stage (row 328 M1) is the SERVER's `failingStage` field and nothing else: ROUTE for a
// ROUTE_FAILED message, DELIVER for one with a DEAD_LETTERED delivery, null otherwise. The Failures link's
// ?failed=<STAGE> parameter is still accepted so the URLs stay unchanged (row 329), but it is parsed and
// ignored for status - no address can mark a stage "Failed".
//
// Row 329 M3 (mahesh-architect, row329-e13-c2-stage-mapping): stage labels carry the spec words; a stage
// with no timing row is "Not recorded" ("Not reached" only after the server's failing stage); the target
// response is per delivery - the last attempt's sent-to-answered time and outcome, never the Deliver stage.

import type { StageTime, Timeline } from '../api/messages';

/** The pipeline's closed stage set in order (MessageTrackingService.PIPELINE, V2_1_1110). */
export const PIPELINE = ['INTAKE', 'ROUTE', 'TRANSFORM', 'DELIVER'] as const;

/** One wording for a 404 and a malformed id - never a hint that the id exists for another tenant. */
export const NOT_FOUND_TEXT = 'No message with this tracking id';

const LABELS: Record<string, string> = {
  INTAKE: 'Intake (source intake)',
  ROUTE: 'Route',
  TRANSFORM: 'Transform (conversion)',
  DELIVER: 'Deliver (all attempts)'
};

/** The detail screen's stage-table label (row 329 spec words). */
export function stageLabel(stage: string): string {
  return LABELS[stage] ?? stage;
}

const SHORT: Record<string, string> = { INTAKE: 'Intake', ROUTE: 'Route', TRANSFORM: 'Transform', DELIVER: 'Deliver' };

/** The plain stage name (the Failures list's "Failing stage" column and the "Failed at" line). */
export function stageName(stage: string): string {
  return SHORT[stage] ?? stage;
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

/** The server's failing stage when it names a pipeline stage; nothing else (no hint input) can set it. */
export function failingStageOf(timeline: Pick<Timeline, 'failingStage'>): string | null {
  const stage = timeline.failingStage ?? null;
  return stage !== null && (PIPELINE as readonly string[]).includes(stage) ? stage : null;
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
 * Every pipeline stage in order (plus any recorded stage the pipeline does not name, appended), the
 * failing stage taken from the server field only. A recorded stage keeps the backend's own times; an
 * unrecorded one is "Not reached" only when it lies after the server's failing stage, otherwise "Not
 * recorded" (only INTAKE has a production writer today - row 329 open 1).
 */
export function timelineRows(timeline: Timeline): TimelineRow[] {
  const failingStage = failingStageOf(timeline);
  const byStage = new Map((timeline.stages ?? []).map((row) => [row.stage, row]));
  const order: string[] = [...PIPELINE, ...[...byStage.keys()].filter((s) => !(PIPELINE as readonly string[]).includes(s))];
  const failAt = failingStage === null ? -1 : order.indexOf(failingStage);
  return order.map((stage, index) => {
    const row = byStage.get(stage);
    const failed = index === failAt;
    if (row) return recorded(row, failed);
    if (failed) return { ...unrecorded(stage, 'not-recorded'), status: 'failed' };
    return unrecorded(stage, failAt >= 0 && index > failAt ? 'not-reached' : 'not-recorded');
  });
}

const OUTCOMES: Record<string, string> = {
  SUCCESS: 'Success',
  TRANSIENT: 'Transient',
  AMBIGUOUS: 'Ambiguous',
  PERMANENT: 'Permanent'
};

export type TargetResponse = { key: string; label: string; text: string; durationMs: number | null };

/**
 * The summary's "Target response" entries (row 329 M3): one per delivery - the last attempt's
 * sent-to-answered time with its outcome class, e.g. "412 ms (Success)"; "Not reached" when the delivery
 * has no attempt (never 0 ms). Two or more deliveries name their target; no delivery is one "Not reached".
 */
export function targetResponses(timeline: Pick<Timeline, 'deliveries'>): TargetResponse[] {
  const deliveries = timeline.deliveries ?? [];
  if (deliveries.length === 0) return [{ key: 'none', label: 'Target response', text: 'Not reached', durationMs: null }];
  return deliveries.map((d) => ({
    key: d.deliveryId,
    label: deliveries.length === 1 ? 'Target response' : `Target response - ${d.targetId ?? d.deliveryId}`,
    text:
      d.durationMs === null || d.durationMs === undefined
        ? 'Not reached'
        : `${formatMs(d.durationMs)} (${OUTCOMES[d.outcome ?? ''] ?? d.outcome ?? 'no outcome'})`,
    durationMs: d.durationMs ?? null
  }));
}

export type DetailRefusal = 'not-found' | 'refused' | 'error';

export function detailStateOf(status: number): DetailRefusal {
  if (status === 404) return 'not-found';
  if (status === 403) return 'refused';
  return 'error';
}


/** The message state enum as a worded badge: "ROUTE_FAILED" -> "Route failed", tinted by outcome (the word carries it). */
export function stateBadge(state: string): { word: string; tone: 'success' | 'danger' | 'info' | 'neutral' } {
  const spaced = state.toLowerCase().replace(/_/g, ' ');
  const word = spaced.charAt(0).toUpperCase() + spaced.slice(1);
  if (/FAIL|DEAD/.test(state)) return { word, tone: 'danger' };
  if (state === 'ROUTED' || state === 'DELIVERED') return { word, tone: 'success' };
  if (state === 'ACCEPTED') return { word, tone: 'info' };
  return { word, tone: 'neutral' };
}
