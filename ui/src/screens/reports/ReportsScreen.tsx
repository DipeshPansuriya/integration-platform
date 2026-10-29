// Lane D (same-day build): Reports screen - presentational only. Typed props in, no fetch, no
// routes, no hooks. Range changes, downloads and retries are emitted to the caller's handlers;
// rows are the last good data the caller holds (marked stale when shown beside an error).

import { KpiCard } from '../../ds/components/data/KpiCard.jsx';
import { Segmented } from '../../ds/components/data/Segmented.jsx';
import { DataTable, type DataTableColumn } from '../../ds/components/data/DataTable.jsx';
import { PageHeader } from '../../ds/components/shell/PageHeader.jsx';
import { Panel } from '../../ds/components/surfaces/Panel.jsx';
import { Callout } from '../../ds/components/feedback/Callout.jsx';
import { Button } from '../../ds/components/actions/Button.jsx';
import { successBand, type ReportRow } from './reports-vm';
import './reports.css';

const RANGES = ['1H', '24H', '7D', '30D'];

export interface ReportsScreenProps {
  /** Optional page title (default "Reports"); the console passes the shipped "Delivery report" h1 the e2e suite reads. */
  title?: string;
  range?: string;
  rows?: ReportRow[];
  loading?: boolean;
  error?: string;
  onRangeChange?: (v: string) => void;
  onDownload?: () => void;
  onRetry?: () => void;
  className?: string;
}

const columns: DataTableColumn<ReportRow>[] = [
  { key: 'flow', header: 'Flow', mono: true },
  { key: 'sent', header: 'Sent', align: 'right' },
  { key: 'delivered', header: 'Delivered', align: 'right' },
  { key: 'failed', header: 'Failed', align: 'right' },
  {
    key: 'successPct',
    header: 'Success %',
    align: 'right',
    numeric: true,
    render: (r) => <span className={'scr-band scr-band-' + successBand(r.successPct)}>{r.successPct}</span>,
  },
];

export function ReportsScreen({
  title = 'Reports',
  range,
  rows = [],
  loading,
  error,
  onRangeChange,
  onDownload,
  onRetry,
  className,
}: ReportsScreenProps) {
  const sentTotal = rows.reduce((sum, r) => sum + r.sent, 0);
  const deliveredTotal = rows.reduce((sum, r) => sum + r.delivered, 0);
  const failedTotal = rows.reduce((sum, r) => sum + r.failed, 0);
  const latencies = rows.flatMap((r) => (r.latencyMs != null ? [r.latencyMs] : []));
  const avgLatency = latencies.length > 0 ? Math.round(latencies.reduce((s, l) => s + l, 0) / latencies.length) : null;
  const successRate = sentTotal > 0 ? ((deliveredTotal / sentTotal) * 100).toFixed(1) + '%' : '—';
  // Interim: the KPI is toned by the best flow's band until Decision 6 (which rate is the rule) settles.
  const bestBand = rows.length > 0 ? successBand(Math.max(...rows.map((r) => r.successPct))) : undefined;

  const segmented = range !== undefined && (
    <Segmented options={RANGES} value={range} onChange={onRangeChange} label="Time range" />
  );

  const body = loading ? (
    <div className="scr-loading" aria-busy="true">
      <span className="scr-skeleton scr-skeleton-label" />
      <span className="scr-skeleton scr-skeleton-number" />
      <span className="scr-skeleton scr-skeleton-sub" />
    </div>
  ) : error ? (
    <>
      <Callout tone="err" title="Report could not load">
        <p>{error}</p>
        <Button {...{ 'data-action': 'retry' }} onClick={onRetry}>
          Retry
        </Button>
      </Callout>
      {rows.length > 0 && (
        <div data-stale="true">
          {/* Last good rows stay visible but are marked stale. */}
          <Panel title="Delivery History">
            <DataTable columns={columns} rows={rows} rowKey="flow" />
          </Panel>
        </div>
      )}
    </>
  ) : rows.length === 0 ? (
    <>
      {segmented}
      <div className="scr-empty" data-empty="report">
        No delivery data in this range.
      </div>
    </>
  ) : (
    <>
      <div className="scr-kpis">
        <KpiCard label="Success Rate" value={successRate} tone={bestBand} />
        <KpiCard label="Delivered" value={deliveredTotal} tone="ok" />
        <KpiCard label="Avg latency" value={avgLatency == null ? '—' : avgLatency + ' ms'} tone="accent" />
        <KpiCard label="Failed" value={failedTotal} tone="err" />
      </div>
      {segmented}
      <Panel title="Delivery History">
        <DataTable columns={columns} rows={rows} rowKey="flow" />
      </Panel>
    </>
  );

  return (
    <div className={'scr-reports' + (className ? ' ' + className : '')}>
      <PageHeader
        title={title}
        subtitle="Delivery performance across flows"
        actions={
          error ? undefined : (
            <Button variant="primary" {...{ 'data-action': 'download' }} onClick={onDownload}>
              Download
            </Button>
          )
        }
      />
      {body}
    </div>
  );
}
export default ReportsScreen;
