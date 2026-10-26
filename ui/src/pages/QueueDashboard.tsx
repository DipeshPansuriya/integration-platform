// F10-narrow (row278 release-base plan step 8, lane D): the queue dashboard - live counts per
// flow and day, derived from the delivery/message state tables through the ONE typed client
// (src/api/queue.ts), no mock (E3-F3-S1). No hand-maintained counters, no chart library.
// The four figures are the state registers' funnel: queued (an ACCEPTED message or a non-final delivery:
// PENDING/HELD/SENDING/RETRY_WAIT/DUE), processed (a ROUTED message), converted (a DELIVERED delivery),
// failed (ROUTE_FAILED + DEAD_LETTERED). A (flow, day) group shows only when work exists.
// B28 (UI audit B4): loading state, role=alert refusal with no stale rows beside it, Reload with busy
// feedback, and a non-zero Failed count is a danger badge linking to #/failures for that flow and day.

import { useCallback, useEffect, useState } from 'react';
import { queueSummary, type QueueRow } from '../api/queue';
import { failuresHash } from './failures-view';
import { queueStatus } from './queue-view';
import './operator.css';

let latestRequest = 0; // a slower, older Reload never overwrites a newer answer

export function QueueDashboard() {
  const [rows, setRows] = useState<QueueRow[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [busy, setBusy] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);

  const load = useCallback(() => {
    const mine = ++latestRequest;
    setBusy(true);
    queueSummary()
      .then((answer) => {
        if (mine !== latestRequest) return;
        if (answer.ok) {
          setRows(answer.data);
          setRefusal(null);
        } else {
          setRows([]);
          setRefusal(answer.refusal ?? answer.error ?? 'queue summary refused');
        }
      })
      .catch(() => {
        if (mine !== latestRequest) return;
        setRows([]);
        setRefusal('queue summary refused');
      })
      .finally(() => {
        if (mine !== latestRequest) return;
        setLoaded(true);
        setBusy(false);
      });
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const status = queueStatus({ loaded, refusal, rowCount: rows.length });

  return (
    <div data-page="queue-dashboard" data-queue-loaded={loaded ? 'true' : 'false'} className="operator-page">
      <header className="op-head">
        <div>
          <h1>Queue</h1>
          <p className="op-lead">Live counts per flow and day, read from the message and delivery state registers.</p>
        </div>
        <button type="button" data-action="reload-queue" data-state={busy ? 'busy' : 'ready'} aria-disabled={busy ? 'true' : undefined}
          aria-busy={busy} onClick={() => { if (!busy) load(); }} hidden={!loaded}>
          {busy ? 'Reloading…' : 'Reload'}
        </button>
      </header>
      {status === 'loading' && (
        <p role="status" className="op-muted" data-loading="queue">Loading the queue…</p>
      )}
      {status === 'refused' && (
        <p role="alert" className="op-alert op-alert-danger" data-refusal="queue-summary-refused">{refusal}</p>
      )}
      {(status === 'ready' || status === 'empty') && (
        <div className="op-card">
          <table className="table op-table" aria-label="Queue by flow and day">
            <thead>
              <tr>
                <th scope="col">Flow</th>
                <th scope="col">Day</th>
                <th scope="col" className="num">Queued</th>
                <th scope="col" className="num">Processed</th>
                <th scope="col" className="num">Converted</th>
                <th scope="col" className="num">Failed</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={`${row.flowId}|${row.day}`} data-queue-row={row.flowId} data-day={row.day}
                  data-count-queued={String(row.queued)} data-count-processed={String(row.processed)}
                  data-count-converted={String(row.converted)} data-count-failed={String(row.failed)}>
                  <td className="op-mono">{row.flowCode}</td>
                  <td data-day-cell className="op-mono">{row.day}</td>
                  <td className="num">{row.queued}</td>
                  <td className="num">{row.processed}</td>
                  <td className="num">{row.converted}</td>
                  <td className="num">
                    {row.failed > 0 ? (
                      <a className="badge badge-danger" href={failuresHash(row.flowId, row.day)} data-failed-link
                        aria-label={`${row.failed} failed, open the failures for ${row.flowCode} on ${row.day}`}>
                        {row.failed}
                      </a>
                    ) : (
                      row.failed
                    )}
                  </td>
                </tr>
              ))}
              {status === 'empty' && (
                <tr>
                  <td colSpan={6} className="op-empty-cell" data-empty="queue-no-work">No work on the queue.</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
