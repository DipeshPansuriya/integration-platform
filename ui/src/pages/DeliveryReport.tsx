// T139 (E13, lane e13-t139ui): the delivery-report page - one UTC day, one row per interface/target.
// Look and feel: the console's own elements only - base.css element defaults.
import { useEffect, useState } from 'react';
import { deliveryReport } from '../api/reports';
import { reportColumns, reportQuery, reportRows, todayUtc, type ReportRow } from './delivery-report-view';

type Load = 'loading' | 'ready' | 'refused' | 'error';

const REFUSAL_COPY: Record<Exclude<Load, 'loading' | 'ready'>, string> = {
  refused: 'You do not have permission to read the message timeline, so the delivery report is refused.',
  error: 'The delivery report could not be loaded.',
};

export default function DeliveryReport() {
  const [rows, setRows] = useState<ReportRow[]>([]);
  const [state, setState] = useState<Load>('loading');
  const [status, setStatus] = useState(0);
  const [day, setDay] = useState(() => todayUtc(new Date()));

  useEffect(() => {
    const query = reportQuery({ day });
    if (query === undefined) {
      setState('error');
      return;
    }
    setState('loading');
    deliveryReport(query).then((result) => {
      if (result.ok) {
        setRows(reportRows(result.data));
        setState('ready');
      } else {
        setStatus(result.status);
        setState(result.status === 401 || result.status === 403 ? 'refused' : 'error');
      }
    });
  }, [day]);

  return (
    <div data-page="delivery-report">
      <h1>Delivery report</h1>
      <label>
        Day (
        {day ? (
          <input type="date" value={day} onChange={(event) => setDay(event.target.value)} data-day-filter />
        ) : (
          <p role="alert" data-refusal="delivery-report-error">
            {REFUSAL_COPY.error}
          </p>
        )}
        )
      </label>
      {(state === 'refused' || state === 'error') && (
        <p role="alert" data-refusal={`delivery-report-${state}`}>
          {REFUSAL_COPY[state]}
          {state === 'error' && status > 0 && ` (HTTP ${status})`}
        </p>
      )}
      {state === 'loading' && <p data-loading>Loading…</p>}
      {state === 'ready' && (
        <table data-delivery-report>
          <thead>
            <tr>
              {reportColumns().map((column) => (
                <th scope="col" key={column}>{column}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.key} data-report-row={row.key}>
                <td>{row.interfaceId}</td>
                <td>{row.targetId}</td>
                <td>{row.successes}</td>
                <td>{row.failures}</td>
                <td>{row.deliveries}</td>
                <td>{row.retriesMade}</td>
                <td>{row.retriedThenSucceeded}</td>
                <td>{row.stillStuck}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}