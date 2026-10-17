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
// Look and feel: the console's own elements only - base.css element defaults (the QueueDashboard
// table style) and base.css .badge tones; nothing new is styled here.

import { useEffect, useState } from 'react';
import { messageTimeline, type Timeline } from '../api/messages';
import {
  NOT_FOUND_TEXT,
  detailStateOf,
  failingStageOf,
  isTrackingId,
  stageName,
  statusWord,
  targetResponses,
  timelineRows,
  totalText,
  utcText,
  type StageStatus
} from './message-timeline';

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

  useEffect(() => {
    let current = true;
    setTimeline(null);
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
    <div data-page="message-detail" data-message-loaded={load === 'ready' ? 'true' : 'false'} data-load={load}>
      <h1>Message</h1>
      <p>
        <a href="#/failures" data-action="back-to-failures">Back to failures</a>
      </p>
      {/* One persistent live region: loading, not found, refused and error are announced from it. */}
      <p
        role="status"
        data-status="message-detail"
        data-empty={load === 'not-found' ? 'message-not-found' : undefined}
        data-refusal={load === 'refused' ? 'message-timeline-refused' : undefined}
      >
        {statusText}
      </p>
      {timeline && (
        <>
          <dl data-message-summary>
            <dt>Tracking id</dt>
            <dd data-tracking-id>{timeline.trackingId}</dd>
            <dt>Flow</dt>
            <dd data-flow-id>{timeline.flowId}</dd>
            <dt>State</dt>
            <dd data-message-state>{timeline.state}</dd>
            <dt>Accepted at</dt>
            <dd>{timeline.acceptedAt ? <time dateTime={timeline.acceptedAt}>{utcText(timeline.acceptedAt)}</time> : 'Not recorded'}</dd>
            <dt>Total</dt>
            <dd data-total-ms={timeline.totalMs === null ? '' : String(timeline.totalMs)}>{totalText(timeline.totalMs)}</dd>
            {responses.map((r) => (
              <div key={r.key} data-target-response={r.key}>
                <dt>{r.label}</dt>
                <dd data-response-ms={r.durationMs === null ? '' : String(r.durationMs)}>{r.text}</dd>
              </div>
            ))}
          </dl>
          {failing && (
            <p data-failing-stage={failing}>
              Failed at the {stageName(failing)} stage.
            </p>
          )}
          <h2>Pipeline stages</h2>
          <table data-timeline>
            <caption>Pipeline stages of message {timeline.trackingId}, in pipeline order (times in UTC)</caption>
            <thead>
              <tr>
                <th scope="col">Stage</th>
                <th scope="col">Status</th>
                <th scope="col">Started</th>
                <th scope="col">Finished</th>
                <th scope="col">Duration</th>
                <th scope="col">Since previous stage</th>
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
                  <td data-duration-cell>{row.durationText}</td>
                  <td data-since-cell>{row.sinceText}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </div>
  );
}
