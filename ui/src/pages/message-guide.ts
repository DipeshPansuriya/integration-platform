// Row 575 S3 (guided flow, spec 4.5): the plain-words layer over a message timeline - the stage strip (Received -> Routed -> Mapped
// -> Delivered, in pipeline order) and the "What to do next" table. Everything comes from fields the timeline already carries; a
// stage the data does not record reads "Not recorded", never a guess. UX only: Retry/Replay keep their server guards (409/403).
// No DOM, no fetch - unit-tested under node (tests/ui/message-guide.test.mjs).

import type { Timeline } from '../api/messages';
import { PIPELINE, failingStageOf, timelineRows, type StageStatus } from './message-timeline';

export type Guide = {
  tone: 'danger' | 'warn' | 'success' | 'info';
  words: string;
  links: Array<{ label: string; href: string }>;
};

export type GuideRoles = { canOpenFlow: boolean; canOperate: boolean };

/** REPLAY_DEAD_LETTER in RolePolicy.java: tenant admin and support. Roles not loaded (null): yes - the server's 409/403 still guards. */
export function canOperate(roles: readonly string[] | null | undefined): boolean {
  if (!roles) return true;
  return roles.some((r) => r === 'TENANT_ADMIN' || r === 'SUPPORT');
}

const NEEDS_OPERATOR = (verb: string): string => ` Only a tenant admin or support user can ${verb} it.`;

const DEAD: Record<string, { base: string; action: string }> = {
  PERMANENT: { base: 'The target refused the message. Fix the mapping or the target settings.', action: ' Then Replay.' },
  TRANSIENT: { base: 'Delivery kept failing for a temporary reason and the retries ran out.', action: ' When the cause is fixed, Replay.' },
  AMBIGUOUS: { base: 'The target may have received it. Check with the target owner first, to avoid a duplicate.', action: ' Then Replay.' }
};
const DEAD_UNKNOWN = { base: 'The platform gave up delivering this message. Check the target.', action: ' Then Replay.' };

/** Where the message stopped and what to do about it (spec 4.5 table), in plain words - no enum, no HTTP code. */
export function nextStepFor(timeline: Timeline, roles: GuideRoles): Guide {
  const openFlow = roles.canOpenFlow ? [{ label: 'Open the flow', href: `#/flow/${timeline.flowId}` }] : [];
  const failing = failingStageOf(timeline);
  const deliveries = timeline.deliveries ?? [];

  if (failing === 'ROUTE' || timeline.state === 'ROUTE_FAILED') {
    return { tone: 'danger', words: "No destination matched this message. Check the flow's routes, publish, then ask the source to resend.", links: openFlow };
  }
  if (failing === 'TRANSFORM') {
    return { tone: 'danger', words: 'The mapping could not convert this message. Run the sample with this case, fix the mapping, publish.', links: openFlow };
  }
  const dead = deliveries.find((d) => d.state === 'DEAD_LETTERED');
  if (dead) {
    const entry = (dead.outcome && DEAD[dead.outcome]) || DEAD_UNKNOWN;
    return { tone: 'danger', words: entry.base + (roles.canOperate ? entry.action : NEEDS_OPERATOR('replay')), links: openFlow };
  }
  if (deliveries.some((d) => d.state === 'RETRY_WAIT')) {
    // RETRY_WAIT also covers waits with no attempt at all (bulkhead full, circuit open, stale claim), so no claim about the target.
    const base = 'This delivery is waiting to be sent again. The platform retries by itself.';
    return { tone: 'warn', words: base + (roles.canOperate ? ' Use Retry now if you know the cause is fixed.' : NEEDS_OPERATOR('retry')), links: [] };
  }
  if (deliveries.length > 0 && deliveries.every((d) => d.state === 'DELIVERED')) {
    return { tone: 'success', words: 'This message was delivered. Nothing to do.', links: [] };
  }
  return { tone: 'info', words: 'This message is still being processed. Check again in a moment.', links: [] };
}

const STEP_LABEL: Record<string, string> = { INTAKE: 'Received', ROUTE: 'Routed', TRANSFORM: 'Mapped', DELIVER: 'Delivered' };
const MARK: Record<StageStatus, string> = { done: '✓', failed: '✗', running: '●', 'not-reached': '○', 'not-recorded': '–' };
const WORD: Record<StageStatus, string> = { done: 'Done', failed: 'Failed', running: 'Running', 'not-reached': 'Not reached', 'not-recorded': 'Not recorded' };

export type StripItem = { stage: string; label: string; status: StageStatus; mark: string; word: string; detail: string };

/** The four pipeline steps in order; `current` is the failing step, else the last one the data records (null when none). */
export function stageStrip(timeline: Timeline): { items: StripItem[]; current: number | null } {
  const byStage = new Map(timelineRows(timeline).map((r) => [r.stage, r]));
  const items = PIPELINE.map((stage): StripItem => {
    const row = byStage.get(stage)!;
    const seen = row.status === 'done' || row.status === 'running' || row.status === 'failed';
    return {
      stage,
      label: STEP_LABEL[stage],
      status: row.status,
      mark: MARK[row.status],
      word: WORD[row.status],
      detail: seen ? row.durationText : '' // an unseen step already says Not reached / Not recorded in its word
    };
  });
  const failed = items.findIndex((i) => i.status === 'failed');
  let current: number | null = failed >= 0 ? failed : null;
  if (current === null) {
    items.forEach((i, index) => {
      if (i.status === 'done' || i.status === 'running') current = index;
    });
  }
  return { items, current };
}
