// T112 (E13 base N3, lane D): the message detail screen, #/messages/<trackingId>[?failed=<STAGE>].
// Every pipeline stage (INTAKE, ROUTE, TRANSFORM, DELIVER) with its UTC timestamps, its own duration,
// the gap since the previous stage, and the total - read LIVE through the ONE typed client
// (src/api/messages.ts, GET .../timeline, T110), no mock (E3-F3-S1). The failing stage is marked; a
// stage after it reads "Not reached", never a zero duration (message-timeline.ts holds the rules).
// Reached from the Failures screen (no menu entry). The failing stage is the server's `failingStage`
// only (row 328 M1); the link's ?failed=<STAGE> stays in the URL but never sets a status. The summary
// adds one "Target response" per delivery - the last attempt's sent-to-answered time (row 329 M3).
//
// Masked fields: this screen shows NO payload field - the timeline answer has none. Only ids, stage
// names, states and timestamps are rendered, all as React text children (escaped).
//
// Look and feel (B28, UI audit B0/B6/B8): the Flows pattern - header block, summary panel with a styled dl, the
// stage table in ONE card (operator.css, tokens only); state is a worded badge, the failure a role=alert block.

import { useEffect, useState } from 'react';
import { messageTimeline, type Timeline } from '../api/messages';
import { OperatorActionButton } from './OperatorActionButton';
import { deliveryAction, type OperatorAction } from './operator-actions';
import {
  NOT_FOUND_TEXT,
  detailStateOf,
  failingStageOf,
  isTrackingId,
  stageName,
  stateBadge,
  statusWord,
  targetResponses,
  timelineRows,
  totalText,
  utcText,
  type StageStatus
} from './message-timeline';
import './operator.css';

type Load = 'loading' | 'ready' | 'not-found' | 'refused' | 'error';

const TONES: Record<StageStatus, string> = {
  done: 'badge badge-success',
  running: 'badge badge-info',
  failed: 'badge badge-danger',
  'not-reached': 'badge badge-neutral',
  'not-recorded': 'badge badge-neutral'
};

// failedHint: still passed by the route (URLs unchanged), deliberately unread - the server decides (M1).
export function MessageDetail({ trackingId }: { trackingId: string; failedHint: string | null }) {
  const [timeline, setTimeline] = useState<Timeline | null>(null);
  const [load, setLoad] = useState<Load>('loading');
  const [refusal, setRefusal] = useState('');
  // Varun follow-up: an accepted retry/replay re-reads the timeline (reloads) WITHOUT blanking the screen, and keeps
  // the pressed action (acted) on screen - the delivery is DUE now, so deliveryAction() alone would drop the button
  // and with it the announced answer.
  const [reloads, setReloads] = useState(0);
  const [acted, setActed] = useState<Record<string, OperatorAction>>({});

  useEffect(() => {
    let current = true;
    setTimeline(null);
    setActed({});
    // A malformed id is answered like a 404 without a call: the same words, no hint either way.
    if (!isTrackingId(trackingId)) {
      setLoad('not-found');
      return;
    }
    setLoad('loading');
    messageTimeline(trackingId)
      .then((answer) => {
        if (!current) return;
        if (answer.ok) {
          setTimeline(answer.data);
          setLoad('ready');
          return;
        }
        const state = detailStateOf(answer.status);
        setRefusal(answer.refusal ?? answer.error ?? 'message timeline refused');
        setLoad(state);
      })
      .catch(() => {
        if (current) setLoad('error');
      });
    return () => {
      current = false;
    };
  }, [trackingId]);

  // The silent refresh: no loading state, no clearing; a failed re-read leaves the last good timeline in place.
  useEffect(() => {
    if (reloads === 0 || !isTrackingId(trackingId)) return;
    let current = true;
    messageTimeline(trackingId)
      .then((answer) => {
        if (current && answer.ok) setTimeline(answer.data);
      })
      .catch(() => undefined);
    return () => {
      current = false;
    };
  }, [reloads]);

  function actionDone(action: OperatorAction) {
    setActed((a) => ({ ...a, [action.deliveryId]: action }));
    setReloads((n) => n + 1);
  }

  const failing = timeline ? failingStageOf(timeline) : null;
  const rows = timeline ? timelineRows(timeline) : [];
  const responses = timeline ? targetResponses(timeline) : [];

  const statusText =
    load === 'loading'
      ? 'Loading the message timeline...'
      : load === 'not-found'
        ? NOT_FOUND_TEXT
        : load === 'refused'
          ? refusal
          : load === 'error'
            ? 'The message timeline could not be loaded. Reload the page to try again.'
            : '';

  return (
    <div data-page="message-detail" data-message-loaded={load === 'ready' ? 'true' : 'false'} data-load={load} className="operator-page">
      <header className="op-head">
        <div>
          <h1>Message</h1>
          <p className="op-lead">Where this message went through the pipeline, stage by stage.</p>
        </div>
        <a className="btn" href="#/failures" data-action="back-to-failures">Back to failures</a>
      </header>
      {/* One persistent live region: loading, not found, refused and error are announced from it. */}
      <p
        role={load === 'refused' || load === 'error' ? 'alert' : 'status'}
        className={load === 'refused' || load === 'error' ? 'op-alert op-alert-danger' : load === 'not-found' ? 'op-empty' : 'op-status'}
        data-status="message-detail"
        data-empty={load === 'not-found' ? 'message-not-found' : undefined}
        data-refusal={load === 'refused' ? 'message-timeline-refused' : undefined}
      >
        {statusText}
      </p>
      {timeline && (
        <>
          {/* Row 328 V1 (WCAG 1.3.1): every group has the same form - one <div> holding one dt and one dd. */}
          <div className="op-panel">
          <dl data-message-summary className="op-dl">
            <div>
              <dt>Tracking id</dt>
              <dd data-tracking-id className="op-mono">{timeline.trackingId}</dd>
            </div>
            <div>
              <dt>Flow</dt>
              <dd data-flow-id className="op-mono">
                <a href={`#/flow/${encodeURIComponent(timeline.flowId)}`}>{timeline.flowId}</a>
              </dd>
            </div>
            <div>
              <dt>State</dt>
              <dd data-message-state data-state-raw={timeline.state}>
                <span className={`badge badge-${stateBadge(timeline.state).tone}`} title={timeline.state}>{stateBadge(timeline.state).word}</span>
              </dd>
            </div>
            <div>
              <dt>Accepted at</dt>
              <dd>{timeline.acceptedAt ? <time dateTime={timeline.acceptedAt}>{utcText(timeline.acceptedAt)}</time> : 'Not recorded'}</dd>
            </div>
            <div>
              <dt>Total</dt>
              <dd data-total-ms={timeline.totalMs === null ? '' : String(timeline.totalMs)} className="op-mono">{totalText(timeline.totalMs)}</dd>
            </div>
            {responses.map((r) => (
              <div key={r.key} data-target-response={r.key}>
                <dt>{r.label}</dt>
                <dd data-response-ms={r.durationMs === null ? '' : String(r.durationMs)}>{r.text}</dd>
              </div>
            ))}
          </dl>
          </div>
          {(timeline.deliveries ?? []).map((d) => {
            // T165 M2: the action follows the delivery state - RETRY_WAIT offers Retry now, DEAD_LETTERED Replay; the 409 still guards.
            const action = deliveryAction(d) ?? acted[d.deliveryId] ?? null;
            return action ? (
              <p key={d.deliveryId} data-delivery-action={d.deliveryId} className="op-panel">
                Delivery to {d.targetId ?? d.deliveryId}: <OperatorActionButton action={action} onDone={actionDone} />
              </p>
            ) : null;
          })}
          {failing && (
            <div role="alert" className="op-alert op-alert-danger" data-failing-stage={failing}>
              Failed at the {stageName(failing)} stage.
            </div>
          )}
          <h2 className="op-section">Pipeline stages</h2>
          <div className="op-card">
          <table className="table op-table" data-timeline>
            <caption className="op-sr-only">Pipeline stages of message {timeline.trackingId}, in pipeline order (times in UTC)</caption>
            <thead>
              <tr>
                <th scope="col">Stage</th>
                <th scope="col">Status</th>
                <th scope="col">Started</th>
                <th scope="col">Finished</th>
                <th scope="col" className="num">Duration</th>
                <th scope="col" className="num">Since previous stage</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr
                  key={row.stage}
                  data-stage-row={row.stage}
                  data-stage-status={row.status}
                  data-duration-ms={row.durationMs === null ? '' : String(row.durationMs)}
                >
                  <th scope="row">{row.label}</th>
                  <td>
                    <span className={TONES[row.status]}>{statusWord(row.status)}</span>
                  </td>
                  <td>{row.startedAt ? <time dateTime={row.startedAt}>{row.startedText}</time> : row.startedText}</td>
                  <td>{row.finishedAt ? <time dateTime={row.finishedAt}>{row.finishedText}</time> : row.finishedText}</td>
                  <td data-duration-cell className="num op-mono">{row.durationText}</td>
                  <td data-since-cell className="num op-mono">{row.sinceText}</td>
                </tr>
              ))}
            </tbody>
          </table>
          </div>
        </>
      )}
    </div>
  );
}
