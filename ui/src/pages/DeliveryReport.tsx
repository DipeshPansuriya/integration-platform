// T139 (E13, lane e13-t139ui): the delivery-report page - one UTC day, one row per interface/target.
// B28 (UI audit B0/B3): header block + card table; an empty day is a worded prompt (the day control stays),
// the request has a .catch and a stale-response guard, and an empty day answer has its own state.
import { useEffect, useState } from 'react';
import { deliveryReport } from '../api/reports';
import { refusalWords } from '../ui/refusal-words';
import {
  NO_DAY_COPY,
  emptyReportText,
  countTone,
  reportColumns,
  reportQuery,
  reportRows,
  reportStatus,
  todayUtc,
  type ReportRow
} from './delivery-report-view';
import './operator.css';

type Load = 'loading' | 'ready' | 'refused' | 'error';

const REFUSAL_COPY: Record<'refused' | 'error', string> = {
  refused: 'You do not have permission to read the message timeline, so the delivery report is refused.',
  error: 'The delivery report could not be loaded.',
};

function Count({ value }: { value: number }) {
  return countTone(value) === 'danger' ? <span className="badge badge-danger">{value}</span> : <>{value}</>;
}

export default function DeliveryReport() {
  const [rows, setRows] = useState<ReportRow[]>([]);
  const [state, setState] = useState<Load>('loading');
  const [status, setStatus] = useState(0);
  const [day, setDay] = useState(() => todayUtc(new Date()));

  useEffect(() => {
    const query = reportQuery({ day });
    if (query === undefined) return; // an empty day is a prompt, not an error (the control stays)
    let current = true;
    setState('loading');
    deliveryReport(query)
      .then((result) => {
        if (!current) return;
        if (result.ok) {
          setRows(reportRows(result.data));
          setState('ready');
        } else {
          setRows([]);
          setStatus(result.status);
          setState(result.status === 401 || result.status === 403 ? 'refused' : 'error');
        }
      })
      .catch(() => {
        if (!current) return;
        setRows([]);
        setStatus(0);
        setState('error');
      });
    return () => {
      current = false;
    };
  }, [day]);

  const view = reportStatus(day, state, rows.length);

  return (
    <div data-page="delivery-report" className="operator-page">
      <header className="op-head">
        <div>
          <h1>Delivery report</h1>
          <p className="op-lead">One UTC day, one row per interface and target.</p>
        </div>
      </header>
      <div className="op-toolbar">
        <label htmlFor="report-day">
          Day (UTC)
          <input id="report-day" type="date" value={day} onChange={(event) => setDay(event.target.value)} data-day-filter />
        </label>
      </div>
      {view === 'prompt' && (
        <p role="status" className="op-empty" data-day-prompt>
          {NO_DAY_COPY}
        </p>
      )}
      {(view === 'refused' || view === 'error') && (
        <p role="alert" className="op-alert op-alert-danger" data-refusal={`delivery-report-${view}`}>
          {view === 'error' && status > 0 ? refusalWords(status, 'the delivery report') : REFUSAL_COPY[view]}
        </p>
      )}
      {view === 'loading' && (
        <p role="status" className="op-muted" data-loading>
          Loading…
        </p>
      )}
      {(view === 'ready' || view === 'empty') && (
        <div className="op-card">
          <table className="table op-table" data-delivery-report aria-label="Delivery report">
            <thead>
              <tr>
                {reportColumns().map((column, i) => (
                  <th scope="col" key={column} className={i >= 2 ? 'num' : undefined}>{column}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.key} data-report-row={row.key}>
                  <td className="op-mono">{row.interfaceId}</td>
                  <td className="op-mono">{row.targetId}</td>
                  <td className="num">{row.successes}</td>
                  <td className="num"><Count value={row.failures} /></td>
                  <td className="num">{row.deliveries}</td>
                  <td className="num">{row.retriesMade}</td>
                  <td className="num">{row.retriedThenSucceeded}</td>
                  <td className="num"><Count value={row.stillStuck} /></td>
                </tr>
              ))}
              {view === 'empty' && (
                <tr>
                  <td colSpan={8} className="op-empty-cell" data-empty="delivery-report-none">{emptyReportText(day)}</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
