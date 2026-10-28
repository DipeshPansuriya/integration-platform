// Lane A - Dashboard screen. Presentational only: all data arrives as typed props, no api
// calls and no routing (the contract for the console build). Loading / empty / error states
// are props too, so the wiring screen can drive them from its own store.
import type { ReactElement } from 'react';
// Direct per-module ds imports (barrel pulls forms/* whose top-level forwardRef crashes the
// test-harness react stub; see lane hand-back notes).
import type { DataTableColumn } from '../../ds/components/data/DataTable.jsx';
import type { DonutSegment } from '../../ds/components/data/DonutChart.jsx';
import type { IconName } from '../../ds/components/brand/Icon.jsx';
import type { StatTileProps } from '../../ds/components/data/StatTile.jsx';
import type { Tone } from '../../ds/components/feedback/StatusBadge.jsx';
import { Button } from '../../ds/components/actions/Button.jsx';
import { Card } from '../../ds/components/surfaces/Card.jsx';
import { DataTable } from '../../ds/components/data/DataTable.jsx';
import { DonutChart } from '../../ds/components/data/DonutChart.jsx';
import { HealthRow } from '../../ds/components/data/HealthRow.jsx';
import { PageHeader } from '../../ds/components/shell/PageHeader.jsx';
import { Panel } from '../../ds/components/surfaces/Panel.jsx';
import { Segmented } from '../../ds/components/data/Segmented.jsx';
import { StatTile } from '../../ds/components/data/StatTile.jsx';
import { StatusBadge } from '../../ds/components/feedback/StatusBadge.jsx';
import { EmptyState, ErrorNote, LineChart, QuickAction, SkeletonBlock } from '../_shared/states.js';
import './dashboard.css';

export type DashboardStat = StatTileProps;
export type StatusMixSegment = DonutSegment;

export interface ThroughputSeries {
  label?: string;
  data: number[];
  max?: number;
  height?: number;
  xLabels?: { at: number; text: string }[];
}
export interface TopFlowRow { id: string; name: string; status: string; lastRun: string; messages: number; }
export interface DashboardSystem { name: string; latency: string; status?: 'Healthy' | 'Slow' | 'Down'; }
export interface QuickActionEntry { key: string; icon: IconName; title: string; description: string; tone?: Tone; }

export interface DashboardScreenProps {
  stats: DashboardStat[];
  period?: string;
  periods?: string[];
  onPeriodChange?: (period: string) => void;
  throughput: ThroughputSeries;
  topFlows: TopFlowRow[];
  statusMix: StatusMixSegment[];
  systems: DashboardSystem[];
  quickActions: QuickActionEntry[];
  loading?: boolean;
  error?: string | null;
  stale?: boolean;
  onReload?: () => void;
  onRefresh?: () => void;
  onNewFlow?: () => void;
  onQuickAction?: (key: string) => void;
  className?: string;
}

const DEFAULT_PERIODS: string[] = ['1H', '24H', '7D', '30D'];

const FLOW_COLUMNS: DataTableColumn<TopFlowRow>[] = [
  { key: 'name', header: 'Flow', mono: true },
  { key: 'status', header: 'Status', render: (row) => <StatusBadge status={row.status} size="xs" /> },
  { key: 'lastRun', header: 'Last run' },
  { key: 'messages', header: 'Messages', align: 'right', numeric: true },
];

function DashboardSkeleton(): ReactElement {
  return (
    <div data-test="skeleton">
      <div className="ip-grid-tiles">
        <SkeletonBlock height={112} /><SkeletonBlock height={112} />
        <SkeletonBlock height={112} /><SkeletonBlock height={112} />
      </div>
      <div className="ip-grid-panels">
        <Panel title="Throughput"><div className="scr-skel-chart"><SkeletonBlock height={190} /><SkeletonBlock height={150} /></div></Panel>
        <Panel title="Status Mix"><SkeletonBlock height={240} /></Panel>
      </div>
      <div className="ip-grid-2">
        <Card padded><SkeletonBlock height={170} /></Card>
        <Card padded><SkeletonBlock height={170} /></Card>
      </div>
    </div>
  );
}

function DashboardScreen(props: DashboardScreenProps): ReactElement {
  const periods = props.periods ?? DEFAULT_PERIODS;
  const period = props.period ?? periods[1] ?? periods[0] ?? '24H';

  return (
    <div className={['scr-page', 'scr-dashboard', props.className].filter(Boolean).join(' ')} data-test="dashboard">
      <PageHeader
        title="Dashboard"
        subtitle="Real-time integration overview"
        actions={(
          <>
            <Button variant="secondary" onClick={props.onRefresh}>Refresh</Button>
            <Button variant="primary" onClick={props.onNewFlow}>New Flow</Button>
          </>
        )}
      />
      {props.error != null ? (
        <ErrorNote what="the dashboard" message={props.error} stale={props.stale} onReload={props.onReload} />
      ) : props.loading ? (
        <DashboardSkeleton />
      ) : (
        <>
          <div className="ip-grid-tiles">
            {props.stats.map((s) => <StatTile key={s.label} {...s} />)}
          </div>
          <div className="ip-grid-panels">
            <Panel
              title="Throughput"
              aside={(
                <Segmented
                  options={periods}
                  value={period}
                  label="Period"
                  onChange={(v) => props.onPeriodChange?.(v)}
                />
              )}
            >
              {props.throughput?.data != null && props.throughput.data.length > 0 ? (
                <LineChart
                  data={props.throughput.data}
                  max={props.throughput.max}
                  height={props.throughput.height ?? 190}
                  xLabels={props.throughput.xLabels}
                  label={props.throughput.label ?? 'Messages per hour'}
                />
              ) : (
                <EmptyState title="No throughput data for this period." />
              )}
            </Panel>
            <Panel title="Top Flows">
              {props.topFlows?.length ? (
                <DataTable
                  columns={FLOW_COLUMNS}
                  rows={props.topFlows.slice(0, 5)}
                  rowKey="id"
                  caption="Top flows by messages today"
                />
              ) : (
                <EmptyState
                  title="No top flows yet."
                  action={<Button variant="secondary" onClick={props.onNewFlow}>New Flow</Button>}
                />
              )}
            </Panel>
            <Panel title="Status Mix" aside={<span className="scr-muted-note">Flow states</span>}>
              {props.statusMix?.length ? (
                <DonutChart segments={props.statusMix} />
              ) : (
                <EmptyState title="No flow statuses to chart." />
              )}
            </Panel>
          </div>
          <div className="ip-grid-2">
            <Card padded>
              <h2 className="scr-card-title">System Health</h2>
              {props.systems?.length ? (
                props.systems.map((sys) => (
                  <HealthRow key={sys.name} name={sys.name} latency={sys.latency} status={sys.status} />
                ))
              ) : (
                <EmptyState title="No monitored systems connected." />
              )}
            </Card>
            <Card padded>
              <h2 className="scr-card-title">Quick Actions</h2>
              {props.quickActions?.length ? (
                <div className="scr-quick-actions">
                  {props.quickActions.map((qa) => (
                    <QuickAction
                      key={qa.key}
                      icon={qa.icon}
                      title={qa.title}
                      description={qa.description}
                      tone={qa.tone}
                      onClick={() => props.onQuickAction?.(qa.key)}
                    />
                  ))}
                </div>
              ) : (
                <EmptyState title="No quick actions configured." />
              )}
            </Card>
          </div>
        </>
      )}
    </div>
  );
}

export default DashboardScreen;
export { DashboardScreen };