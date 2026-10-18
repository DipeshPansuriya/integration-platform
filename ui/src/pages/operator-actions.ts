// T112 (E13 base N3, row 278 lane B): the retry/replay buttons' pure view model over the T111 endpoints.
// Replay: a DEAD_LETTERED delivery (the failures screen lists exactly those) -> DUE. Retry now: a delivery
// waiting to retry (last outcome TRANSIENT or AMBIGUOUS, shown on the detail screen) -> DUE now. Both re-send
// with the delivery's pinned mapping and contract versions; the server decides, the screen only words it.
// No DOM, no fetch - unit-tested under node (tests/ui/operator-actions.test.mjs).

import type { DeliveryTime, Failure } from '../api/messages';
import type { OperatorAnswer } from '../api/operator';

export type ActionKind = 'replay' | 'retry';
export type OperatorAction = { kind: ActionKind; deliveryId: string; label: string };

const LABELS: Record<ActionKind, string> = { replay: 'Replay', retry: 'Retry now' };
const RETRYABLE = new Set(['TRANSIENT', 'AMBIGUOUS']);

export function failureAction(f: Pick<Failure, 'deliveryId' | 'failingStage'>): OperatorAction | null {
  if (f.failingStage !== 'DELIVER' || !f.deliveryId) return null;
  return { kind: 'replay', deliveryId: f.deliveryId, label: LABELS.replay };
}

export function deliveryAction(d: Pick<DeliveryTime, 'deliveryId' | 'outcome'>): OperatorAction | null {
  if (!RETRYABLE.has(d.outcome ?? '')) return null;
  return { kind: 'retry', deliveryId: d.deliveryId, label: LABELS.retry };
}

export function actionPath(kind: ActionKind): string {
  return `/api/v1/admin/messages/deliveries/{deliveryId}/${kind}`;
}

const DONE: Record<ActionKind, string> = { replay: 'Replayed', retry: 'Retried' };
const QUEUED: Record<ActionKind, string> = {
  replay: 'Replay queued: the delivery is due and will be sent again with its pinned versions.',
  retry: 'Retry queued: the delivery is due now and will be sent again with its pinned versions.'
};
const EXPECTED: Record<ActionKind, string> = { replay: 'dead-lettered', retry: 'waiting to retry' };

export function actionAnswerText(kind: ActionKind, answer: OperatorAnswer): string {
  const not = `Not ${DONE[kind].toLowerCase()}`;
  if (answer.ok) return QUEUED[kind];
  if (answer.status === 404) return 'This delivery was not found.';
  if (answer.status === 403) return 'You are not allowed to retry or replay deliveries.';
  if (answer.status === 409 && answer.error === 'not pinned') {
    return `${not}: the delivery is not pinned to a mapping and contract version.`;
  }
  if (answer.status === 409) {
    return `${not}: the delivery is ${answer.state ?? 'in another state'}, not ${EXPECTED[kind]}. Reload to see its current state.`;
  }
  return `${not}: the server answered ${answer.status}.`;
}
