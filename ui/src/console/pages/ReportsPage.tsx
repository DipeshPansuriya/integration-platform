// Console wiring: the Reports container (#/reports). Same read as pages/DeliveryReport.tsx - GET
// /reports/delivery for one UTC day (bounded day picker, a malformed day is a prompt, never a call) - mapped
// onto screens/reports/ReportsScreen: one row per interface → target (Sent = deliveries, Delivered =
// successes, Failed = all three failure classes). The API is per day, so the screen's 1H/24H/7D/30D range is
// not offered; it has no latency, so Avg latency shows the screen's dash. Download saves the day's rows as CSV
// in the browser. The old full table (retries made, retried then succeeded, still stuck) stays below.

import { useEffect, useState } from 'react';
import { deliveryReport } from '../../api/reports';
import { refusalWords } from '../../ui/refusal-words';
import { NoAccess } from '../../ui/NoAccess';
import {
  NO_DAY_COPY,
  emptyReportText,
  countTone,
  reportColumns,
  reportQuery,
  reportRows,
  reportStatus,
  reportDayBounds,
  todayUtc,
  type ReportRow
} from '../../pages/delivery-report-view';
import { ReportsScreen } from '../../screens/reports/ReportsScreen';
import type { ReportRow as ScreenReportRow } from '../../screens/reports/reports-vm';
import { downloadCsv } from './console-pages';
import '../../pages/operator.css';

type Load = 'loading' | 'ready' | 'refused' | 'error';

const ERROR_COPY = 'The delivery report could not be loaded.';

function Count({ value }: { value: number }) {
  return countTone(value) === 'danger' ? <span className="badge badge-danger">{value}</span> : <>{value}</>;
}

/** Success % to one decimal, from the day's own counts; a row with no delivery attempt has no rate (0). */
function screenRows(rows: readonly ReportRow[]): ScreenReportRow[] {
  return rows.map((row) => {
    // The row-data join: one flow label per interface/target pair, built from the ids the row itself
    // carries (no lookup, no invented names). An absent id is skipped; both absent falls back to the
    // row's own key, and the label never dangles on a stray arrow.
    const parts = [row.interfaceId, row.targetId].map((part) => part.trim()).filter((part) => part !== '');
    const flow = parts.length > 0 ? parts.join(' → ') : row.key;
    return {
      flow,
      sent: row.deliveries,
      delivered: row.successes,
      failed: row.failures,
      successPct: row.deliveries > 0 ? Math.round((row.successes / row.deliveries) * 1000) / 10 : 0
    };
  });
}

function download(day: string, rows: readonly ReportRow[]): void {
  downloadCsv(
    `delivery-report-${day}.csv`,
    reportColumns(),
    rows.map((r) => [r.interfaceId, r.targetId, r.successes, r.failures, r.deliveries, r.retriesMade, r.retriedThenSucceeded, r.stillStuck])
  );
}

export default function ReportsPage() {
  const [rows, setRows] = useState<ReportRow[]>([]);
  const [state, setState] = useState<Load>('loading');
  const [status, setStatus] = useState(0);
  const [day, setDay] = useState(() => todayUtc(new Date()));
  const [reloads, setReloads] = useState(0);
  const bounds = reportDayBounds(new Date());

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
  }, [day, reloads]);

  const view = reportStatus(day, state, rows.length);
  const denied = view === 'refused' && status === 403;
  const failed = view === 'error' || (view === 'refused' && status !== 403);
  const failText = status > 0 ? refusalWords(status, 'the delivery report') : ERROR_COPY;

  return (
    <div data-page="delivery-report" className="operator-page">
      <ReportsScreen
        title="Delivery report"
        className={view === 'prompt' || view === 'empty' || denied || failed ? 'cp-header-only' : undefined}
        rows={view === 'ready' ? screenRows(rows) : []}
        loading={view === 'loading'}
        onDownload={() => download(day, rows)}
      />
      <div className="cp-below">
        <div className="op-toolbar">
          <label htmlFor="report-day">
            Day (UTC)
            <input id="report-day" type="date" min={bounds.min} max={bounds.max} value={day} onChange={(event) => setDay(event.target.value)} data-day-filter />
          </label>
        </div>
        {view === 'prompt' && (
          <p role="status" className="op-empty" data-day-prompt>
            {NO_DAY_COPY}
          </p>
        )}
        {denied && <NoAccess refusal="delivery-report-refused" />}
        {failed && (
          <p role="alert" className="op-alert op-alert-danger" data-refusal={`delivery-report-${view}`}>
            {failText}{' '}
            <button type="button" className="btn" data-action="reload-report" onClick={() => setReloads((n) => n + 1)}>Retry</button>
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
    </div>
  );
}

export { ReportsPage };
