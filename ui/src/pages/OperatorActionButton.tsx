// T112 (row 278 lane B): one retry/replay button over the T111 POSTs. The server decides (200 moved to DUE,
// 404, 409 wrong state / not pinned, 403); the answer is worded by operator-actions.ts and announced in a
// live region beside the button. While its POST is in flight and after the delivery moved, the button is
// aria-disabled and ignores presses (T165 M4): native disabled would drop the focused button out of the tab order.

import { useState } from 'react';
import { replayDelivery, retryDelivery } from '../api/operator';
import { actionAnswerText, type OperatorAction } from './operator-actions';

export function OperatorActionButton({ action }: { action: OperatorAction }) {
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);
  const [text, setText] = useState('');

  async function run() {
    if (busy || done) return;
    setBusy(true);
    const send = action.kind === 'replay' ? replayDelivery : retryDelivery;
    try {
      const answer = await send(action.deliveryId);
      if (answer.ok) setDone(true);
      setText(actionAnswerText(action.kind, answer));
    } catch {
      setText(actionAnswerText(action.kind, { ok: false, status: 0 }));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <button type="button" data-action={action.kind} data-delivery-id={action.deliveryId} aria-disabled={busy || done ? 'true' : undefined} onClick={run}>
        {action.label}
      </button>{' '}
      <span role="status" data-action-answer={action.deliveryId}>
        {text}
      </span>
    </>
  );
}
