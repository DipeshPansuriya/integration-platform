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
  onFilter?: () => void;
  onExport?: () => void;
  onRetry?: () => void;
  className?: string;
}

export function ActivityRoute(props: ActivityRouteProps = {}): ReactElement {
  const activity = useActivity(props.query ?? { page: 0, size: 50 });

  const mappedItems: ActivityItem[] = (activity.data?.items ?? []).map((item: any) => ({
    time: item.time ?? item.at ?? '',
    text: item.text ?? item.action ?? '',
    tone: undefined,
  }));

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
      flow: row.key,
      sent: deliveries,
      delivered: successes,
      failed: row.failures,
      successPct: deliveries > 0 ? (successes / deliveries) * 100 : 0,
      latencyMs: undefined,
    };
  });

  return (
    <ReportsScreen
      range={props.range}
      rows={props.rows ?? mappedRows}
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
