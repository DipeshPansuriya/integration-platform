// Console wiring: the Queue container (#/queue). Reads GET /admin/queue/summary through useQueue (no poll,
// as pages/QueueDashboard.tsx) and maps the per-flow/day state-register counts onto screens/queue/QueueScreen:
// the KPI row is the four funnel totals. The backend has no per-message queue items, no priority and no
// pause / bulk-retry endpoint, so the screen's Queue Items panel, its "N messages waiting" subtitle and its
// Pause All / Retry Failed actions are hidden; the old per-flow/day table (Reload, refusal / no-access, empty
// row, Failed link to #/failures, data-* hooks) is rendered below the screen.

import type { ReactNode } from 'react';
import { useQueue } from '../../api/hooks';
import type { QueueRow } from '../../api/queue';
import { failuresHash } from '../../pages/failures-view';
import { queueStatus } from '../../pages/queue-view';
import { NoAccess } from '../../ui/NoAccess';
import { isNoAccess } from '../../ui/no-access';
import { QueueScreen, type QueueKpi } from '../../screens/queue/QueueScreen';
import './console-pages';
import '../../pages/operator.css';

const count = (n: number): ReactNode => n.toLocaleString('en-US');

function kpisOf(rows: readonly QueueRow[]): QueueKpi[] {
  const sum = (pick: (row: QueueRow) => number) => rows.reduce((total, row) => total + pick(row), 0);
  const failed = sum((r) => r.failed);
  return [
    { value: count(sum((r) => r.queued)), label: 'Queued', tone: 'accent' },
    { value: count(sum((r) => r.processed)), label: 'Processed', tone: 'info' },
    { value: count(sum((r) => r.converted)), label: 'Converted', tone: 'ok' },
    { value: count(failed), label: 'Failed', tone: failed > 0 ? 'err' : 'muted' }
  ];
}

export function QueuePage() {
  const queue = useQueue(undefined, 0);
  const loaded = !queue.loading;
  const failedRead = queue.status !== null;
  // A refusal never leaves stale rows beside it (B28), so a failed read shows no rows.
  const rows = failedRead ? [] : queue.data ?? [];
  const refusal = failedRead ? (queue.refusal ?? queue.error ?? 'queue summary refused') : null;
  const noAccess = failedRead && isNoAccess(queue.status ?? 0);
  const busy = queue.loading || queue.refreshing;
  const status = queueStatus({ loaded, refusal, rowCount: rows.length });

  return (
    <div data-page="queue-dashboard" data-queue-loaded={loaded ? 'true' : 'false'} className="operator-page">
      <QueueScreen
        className="cp-queue cp-hide-ph-actions cp-hide-ph-sub cp-hide-panel"
        kpis={status === 'refused' ? [] : kpisOf(rows)}
        items={[]}
        loading={status === 'loading'}
        error={null}
        onReload={queue.reload}
        onRefresh={queue.reload}
      />
      <div className="cp-below">
        <header className="op-head">
          <div>
            <p className="op-lead">Live counts per flow and day, read from the message and delivery state registers.</p>
          </div>
          <button type="button" data-action="reload-queue" data-state={busy ? 'busy' : 'ready'} aria-disabled={busy ? 'true' : undefined}
            aria-busy={busy} onClick={() => { if (!busy) queue.reload(); }} hidden={!loaded}>
            {busy ? 'Reloading…' : 'Reload'}
          </button>
        </header>
        {status === 'loading' && (
          <p role="status" className="op-muted" data-loading="queue">Loading the queue…</p>
        )}
        {status === 'refused' && noAccess && <NoAccess refusal="queue-summary-refused" />}
        {status === 'refused' && !noAccess && (
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
    </div>
  );
}
