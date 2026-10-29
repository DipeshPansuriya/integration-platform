// W6 mount adapter: Activity, Reports and Login screens bridged to the W4 data hooks, following the
// console adapter convention (thin glue, no App edits). Safe/optional props may override hook state.
import type { ReactElement } from 'react';

import { useActivity, useDeliveryReport } from '../../api/hooks';
import { reportRows, todayUtc } from '../../pages/delivery-report-view';

import ActivityScreen, { type ActivityItem } from '../../screens/activity/ActivityScreen';
import ReportsScreen from '../../screens/reports/ReportsScreen';
import type { ReportRow as ConsoleReportRow } from '../../screens/reports/reports-vm';
import LoginScreen, { type LoginView } from '../../screens/login/LoginScreen';

export interface ActivityRouteProps {
  query?: Record<string, string | number>;
  items?: ActivityItem[];
  loading?: boolean;
  error?: string;
  stale?: boolean;
  limit?: number;
  page?: number;
  onFilter?: () => void;
  onExport?: () => void;
  onRetry?: () => void;
  className?: string;
}

export function ActivityRoute(props: ActivityRouteProps = {}): ReactElement {
  const query: Record<string, string | number> = { page: 0, size: 50, ...props.query };
  if (props.page !== undefined) query.page = props.page;
  if (props.limit !== undefined) query.size = props.limit;

  const activity = useActivity(query);

  const mappedItems: ActivityItem[] = (activity.data?.items ?? []).map((raw: unknown) => {
    const item = raw as { time?: string; at?: string; text?: string; action?: string };
    return {
      time: item.time ?? item.at ?? '',
      text: item.text ?? item.action ?? '',
      tone: undefined,
    };
  });

  return (
    <ActivityScreen
      items={props.items ?? mappedItems}
      loading={props.loading ?? activity.loading}
      error={props.error ?? activity.error ?? activity.refusal ?? undefined}
      onFilter={props.onFilter}
      onExport={props.onExport}
      onRetry={props.onRetry ?? activity.reload}
      className={props.className}
    />
  );
}

export interface ReportsRouteProps {
  day?: string;
  range?: string;
  rows?: ConsoleReportRow[];
  loading?: boolean;
  error?: string;
  stale?: boolean;
  /** Optional flow-name passthrough for the report's `flow` column (overrides the interface/target key). */
  flowName?: string;
  onRangeChange?: (value: string) => void;
  onDownload?: () => void;
  onRetry?: () => void;
  className?: string;
}

export function ReportsRoute(props: ReportsRouteProps = {}): ReactElement {
  const day = props.day ?? todayUtc(new Date());
  const report = useDeliveryReport({ day });

  const aggregated = report.data ? reportRows(report.data) : [];
  const mappedRows: ConsoleReportRow[] = aggregated.map((row) => {
    const deliveries = row.deliveries;
    const successes = row.successes;
    return {
      flow: props.flowName ?? row.key,
      sent: deliveries,
      delivered: successes,
      failed: row.failures,
      successPct: deliveries > 0 ? (successes / deliveries) * 100 : 0,
      latencyMs: undefined,
    };
  });

  // ReportsScreen has no summary/count prop yet, so the number of rows returned (returnedRows.length)
  // is computed but only documented here; no broad screen edit is made. Flow names via useFlows() are
  // intentionally not fetched: DailyCounts carries interface/target ids, so a safe correlation is not
  // available from the current payload (too risky). TODO: add both once the screen/API supports them.
  const returnedRows = props.rows ?? mappedRows;
  void returnedRows.length;

  return (
    <ReportsScreen
      range={props.range}
      rows={returnedRows}
      loading={props.loading ?? report.loading}
      error={props.error ?? report.error ?? report.refusal ?? undefined}
      onRangeChange={props.onRangeChange}
      onDownload={props.onDownload}
      onRetry={props.onRetry ?? report.reload}
      className={props.className}
    />
  );
}

export interface LoginRouteProps {
  state?: 'checking' | 'signed-out' | 'error';
  view?: LoginView;
  onSSO?: () => void;
  onRetry?: () => void;
  className?: string;
}

export function LoginRoute(props: LoginRouteProps = {}): ReactElement {
  return (
    <LoginScreen
      state={props.state ?? 'checking'}
      view={props.view}
      onSSO={props.onSSO}
      onRetry={props.onRetry}
      className={props.className}
    />
  );
}

export default { ActivityRoute, ReportsRoute, LoginRoute };
