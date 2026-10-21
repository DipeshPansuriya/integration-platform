// F10-narrow (row278 release-base plan step 8, lane D): the queue dashboard - live counts per
// flow and day, derived from the delivery/message state tables through the ONE typed client
// (src/api/queue.ts), no mock (E3-F3-S1). No hand-maintained counters, no chart library: a plain
// table in the console's own style. The four figures are the state registers' funnel: queued
// (an ACCEPTED message or a non-final delivery: PENDING/HELD/SENDING/RETRY_WAIT/DUE), processed
// (a ROUTED message), converted (a DELIVERED delivery - the contract-translated output reached
// the target), failed (ROUTE_FAILED + DEAD_LETTERED). The dashboard never invents a figure: a
// (flow, day) group shows only when work exists. Reload is the refresh - the counts are read
// live on every mount.

import { useCallback, useEffect, useState } from 'react';
import { queueSummary, type QueueRow } from '../api/queue';

export function QueueDashboard() {
  const [rows, setRows] = useState<QueueRow[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);

  const load = useCallback(() => {
    queueSummary()
      .then((answer) => {
        if (answer.ok) {
          setRows(answer.data);
          setRefusal(null);
        } else {
          setRefusal(answer.refusal ?? answer.error ?? 'queue summary refused');
        }
        setLoaded(true);
      })
      .catch(() => {
        setRefusal('queue summary refused');
        setLoaded(true);
      });
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <div data-page="queue-dashboard" data-queue-loaded={loaded ? 'true' : 'false'}>
      <h1>Queue</h1>
      <p>Live counts per flow and day, read from the message and delivery state registers.</p>
      {refusal !== null && <p data-refusal="queue-summary-refused">{refusal}</p>}
      <table>
        <thead>
          <tr>
            <th scope="col">Flow</th>
            <th scope="col">Day</th>
            <th scope="col">Queued</th>
            <th scope="col">Processed</th>
            <th scope="col">Converted</th>
            <th scope="col">Failed</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={`${row.flowId}|${row.day}`} data-queue-row={row.flowId} data-day={row.day}
              data-count-queued={String(row.queued)} data-count-processed={String(row.processed)}
              data-count-converted={String(row.converted)} data-count-failed={String(row.failed)}>
              <td>{row.flowCode}</td>
              <td data-day-cell>{row.day}</td>
              <td>{row.queued}</td>
              <td>{row.processed}</td>
              <td>{row.converted}</td>
              <td>{row.failed}</td>
            </tr>
          ))}
          {rows.length === 0 && loaded && refusal === null && (
            <tr>
              <td colSpan={6} data-empty="queue-no-work">No work on the queue.</td>
            </tr>
          )}
        </tbody>
      </table>
      <p>
        <button type="button" data-action="reload-queue" onClick={load} hidden={!loaded}>Reload</button>
      </p>
    </div>
  );
}