// T112 (row 278 lane B): one retry/replay button over the T111 POSTs. The server decides (200 moved to DUE,
// 404, 409 wrong state / not pinned, 403); the answer is worded by operator-actions.ts and announced in a
// live region beside the button. While its POST is in flight and after the delivery moved, the button is
// aria-disabled and ignores presses (T165 M4): native disabled would drop the focused button out of the tab order.
// B28 (UI audit B2): data-state (ready/confirm/busy/done) drives a visible busy/done style (operator.css), and the
// opt-in `confirm` makes the first press ask "Confirm replay" (with Cancel) - only the next press sends.

import { useState } from 'react';
import { replayDelivery, retryDelivery } from '../api/operator';
import { actionAnswerText, confirmLabel, confirmPrompt, pressStep, type OperatorAction } from './operator-actions';
import './operator.css';

// onDone: called once, after the server accepted the action (200) - the detail screen re-reads its timeline there.
export function OperatorActionButton({ action, onDone, confirm = false }: { action: OperatorAction; onDone?: (action: OperatorAction) => void; confirm?: boolean }) {
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);
  const [text, setText] = useState('');
  const [asking, setAsking] = useState(false);

  async function run() {
    const step = pressStep({ confirm, asking, busy, done });
    if (step === 'ignore') return;
    if (step === 'ask') {
      setAsking(true);
      return;
    }
    setAsking(false);
    setBusy(true);
    const send = action.kind === 'replay' ? replayDelivery : retryDelivery;
    try {
      const answer = await send(action.deliveryId);
      if (answer.ok) setDone(true);
      setText(actionAnswerText(action.kind, answer));
      if (answer.ok) onDone?.(action);
    } catch {
      setText(actionAnswerText(action.kind, { ok: false, status: 0 }));
    } finally {
      setBusy(false);
    }
  }

  const state = done ? 'done' : busy ? 'busy' : asking ? 'confirm' : 'ready';
  return (
    <span className="op-action">
      <button type="button" data-action={action.kind} data-delivery-id={action.deliveryId} data-state={state} aria-disabled={busy || done ? 'true' : undefined} onClick={run}>
        {asking ? confirmLabel(action) : action.label}
      </button>
      {asking && (
        <>
          {' '}
          <button type="button" data-confirm-cancel={action.deliveryId} onClick={() => setAsking(false)}>
            Cancel
          </button>{' '}
          <span className="op-hint" data-confirm-prompt={action.deliveryId}>{confirmPrompt(action)}</span>
        </>
      )}{' '}
      <span role="status" className="op-hint" data-action-answer={action.deliveryId}>
        {text}
      </span>
    </span>
  );
}
