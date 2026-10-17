// T112 (E13 base N3, lane D): the failures screen, #/failures - the bound tenant's failed and
// dead-lettered messages per flow and UTC day, read LIVE through the ONE typed client
// (src/api/messages.ts, GET .../failures, T110), no mock (E3-F3-S1). Each row: tracking id (a link to
// the detail screen, carrying the failing stage), failing stage, error class, last error text, origin
// (PLATFORM = our side, TARGET = the target's answer) and failed-at. Newest first, as answered.
//
// Retry/replay is NOT here: the replay API (T111) is not built, so there is no button at all - not a
// dead or disabled one. FailureRowView is its own component so the one action cell can be added later.
//
// Masked fields: no payload field is shown. lastError is rendered as a React text child (escaped,
// never parsed as HTML); masking any sensitive value inside it is the backend's (T108), not this screen's.
//
// Look and feel: the console's own elements only - base.css element defaults (the QueueDashboard
// table style, label above its field); nothing new is styled here.

import { useEffect, useState } from 'react';
import { listFlows, type FlowSummary } from '../api/flows';
import { messageFailures } from '../api/messages';
import { emptyText, failureRows, failuresCaption, failuresQuery, todayUtc, type FailureRow } from './failures-view';

type Load = 'loading' | 'ready' | 'refused' | 'error';

/** One failure row; T111 adds its replay action as one more cell here. */
function FailureRowView({ row }: { row: FailureRow }) {
  return (
    <tr data-failure-row={row.trackingId} data-failing-stage={row.failingStage} data-origin={row.origin}>
      <td>
        <a href={row.detailHash} data-message-link={row.trackingId}>
          {row.trackingId}
        </a>
      </td>
      <td>{row.stageLabel}</td>
      <td data-error-class>{row.errorClass}</td>
      <td data-last-error={row.hasLastError ? 'text' : 'none'}>{row.lastErrorText}</td>
      <td>{row.originWord}</td>
      <td>
        <time dateTime={row.failedAt}>{row.failedAtText}</time>
      </td>
    </tr>
  );
}

export function Failures() {
  const [flows, setFlows] = useState<FlowSummary[]>([]);
  const [flowId, setFlowId] = useState('');
  const [date, setDate] = useState(() => todayUtc(new Date()));
  const [rows, setRows] = useState<FailureRow[]>([]);
  const [load, setLoad] = useState<Load>('loading');
  const [refusal, setRefusal] = useState('');
  const [reloads, setReloads] = useState(0);

  // The flow filter's choices: the tenant's own flows (a refusal leaves "All flows" only).
  useEffect(() => {
    void listFlows().then((answer) => {
      if (answer.ok) setFlows(answer.data);
    });
  }, []);

  useEffect(() => {
    let current = true;
    setLoad('loading');
    messageFailures(failuresQuery({ flowId, date }))
      .then((answer) => {
        if (!current) return;
        if (answer.ok) {
          setRows(failureRows(answer.data));
          setLoad('ready');
        } else {
          setRows([]);
          setRefusal(answer.refusal ?? answer.error ?? 'failures list refused');
          setLoad('refused');
        }
      })
      .catch(() => {
        if (!current) return;
        setRows([]);
        setLoad('error');
      });
    return () => {
      current = false;
    };
  }, [flowId, date, reloads]);

  const query = failuresQuery({ flowId, date });
  const flow = flows.find((f) => f.id === query.flowId);
  const flowLabel = query.flowId ? flow?.code ?? query.flowId : null;
  const day = query.date ?? null;

  const statusText =
    load === 'loading'
      ? 'Loading failures...'
      : load === 'refused'
        ? refusal
        : load === 'error'
          ? 'The failures list could not be loaded. Reload to try again.'
          : rows.length === 0
            ? emptyText(flowLabel, day)
            : `${rows.length} failed ${rows.length === 1 ? 'message' : 'messages'}.`;

  return (
    <div data-page="failures" data-failures-loaded={load === 'loading' ? 'false' : 'true'} data-load={load}>
      <h1>Failures</h1>
      <p>Failed and dead-lettered messages per flow and UTC day. Open a tracking id to see where it stopped.</p>
      <label htmlFor="failures-flow">
        Flow
        <select id="failures-flow" data-field="failures-flow" value={flowId} onChange={(e) => setFlowId(e.target.value)}>
          <option value="">All flows</option>
          {flows.map((f) => (
            <option key={f.id} value={f.id}>
              {f.name ? `${f.code} - ${f.name}` : f.code}
            </option>
          ))}
        </select>
      </label>
      <label htmlFor="failures-date">
        Day (UTC, empty for all days)
        <input id="failures-date" type="date" data-field="failures-date" value={date} onChange={(e) => setDate(e.target.value)} />
      </label>
      {/* One persistent live region: loading, empty, refused and error are announced from it. */}
      <p
        role="status"
        data-status="failures"
        data-empty={load === 'ready' && rows.length === 0 ? 'failures-none' : undefined}
        data-refusal={load === 'refused' ? 'failures-refused' : undefined}
      >
        {statusText}
      </p>
      <table data-failures>
        <caption>{failuresCaption(flowLabel, day)}</caption>
        <thead>
          <tr>
            <th scope="col">Tracking id</th>
            <th scope="col">Failing stage</th>
            <th scope="col">Error class</th>
            <th scope="col">Last error</th>
            <th scope="col">Origin</th>
            <th scope="col">Failed at (UTC)</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <FailureRowView key={row.key} row={row} />
          ))}
        </tbody>
      </table>
      <p>
        <button type="button" data-action="reload-failures" onClick={() => setReloads((n) => n + 1)}>
          Reload
        </button>
      </p>
    </div>
  );
}
