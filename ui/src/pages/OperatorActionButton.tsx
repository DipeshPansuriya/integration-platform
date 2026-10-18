// T112 (row 278 lane B): one retry/replay button over the T111 POSTs. The server decides (200 moved to DUE,
// 404, 409 wrong state / not pinned, 403); the answer is worded by operator-actions.ts and announced in a
// live region beside the button. The button is disabled only while its own POST is in flight.

import { useState } from 'react';
import { replayDelivery, retryDelivery } from '../api/operator';
import { actionAnswerText, type OperatorAction } from './operator-actions';

export function OperatorActionButton({ action }: { action: OperatorAction }) {
  const [busy, setBusy] = useState(false);
  const [text, setText] = useState('');

  async function run() {
    setBusy(true);
    const send = action.kind === 'replay' ? replayDelivery : retryDelivery;
    try {
      setText(actionAnswerText(action.kind, await send(action.deliveryId)));
    } catch {
      setText(actionAnswerText(action.kind, { ok: false, status: 0 }));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <button type="button" data-action={action.kind} data-delivery-id={action.deliveryId} disabled={busy} onClick={run}>
        {action.label}
      </button>{' '}
      <span role="status" data-action-answer={action.deliveryId}>
        {text}
      </span>
    </>
  );
}
