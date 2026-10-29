// W6 mount adapter: bridges the presentational lane-A screens (src/screens/*) to the W4 data
// hooks, so the shell can mount them without inventing fetch logic. Placeholders (TODO) mark
// fields the hooks do not carry yet (hourly throughput, top-flow run/message metadata, queue
// item lists, pause-all/retry-failed mutations, failure delivery ids and retry-all).
import type { ReactElement } from 'react';

import { useDashboardCounts, useFailures, useFlows, useQueue } from '../../api/hooks';
import { tileName } from '../../pages/dashboard-counts';

import DashboardScreen, {
  type DashboardScreenProps as _DS,
  type DashboardStat,
  type DashboardSystem,
  type StatusMixSegment,
  type ThroughputSeries,
  type TopFlowRow,
  type QuickActionEntry,
} from '../../screens/dashboard/DashboardScreen';
import QueueScreen, {
  type QueueKpi,
  type QueueItem,
} from '../../screens/queue/QueueScreen';
import FailuresScreen, {
  type FailureRow,
} from '../../screens/failures/FailuresScreen';
import type { Failure } from '../../api/messages';

const DASHBOARD_MIX_TONE: Record<string, StatusMixSegment['tone'] extends string ? StatusMixSegment['tone'] : never> = {
  Running: 'ok',
  Active: 'ok',
  Blocked: 'warn',
  Errored: 'err',
  Failed: 'err',
  Draft: 'muted',
};

export function DashboardRoute(): ReactElement {
  const counts = useDashboardCounts();
  const flows = useFlows();

  const pending = counts.counts.pendingApproval;
  const drafts = counts.counts.drafts;
  const sys = counts.counts.systems;
  const contractsDraft = counts.counts.contractsDraft;

  const stats: DashboardStat[] = [
    {
      label: 'Versions pending approval',
      value: pending === null ? '—' : pending,
      sub: tileName('pending', pending),
      tone: pending && pending > 0 ? 'warn' : 'ok',
    },
    {
      label: 'Drafts to finish',
      value: drafts === null ? '—' : drafts,
      sub: tileName('drafts', drafts),
      tone: drafts && drafts > 0 ? 'warn' : 'ok',
    },
    {
      label: 'Systems registered',
      value: sys.total === null ? '—' : sys.total,
      sub: tileName('systems', sys.total),
      tone: 'ok',
    },
    {
      label: 'Contracts in draft',
      value: contractsDraft === null ? '—' : contractsDraft,
      sub: tileName('contracts', contractsDraft),
      tone: contractsDraft && contractsDraft > 0 ? 'warn' : 'ok',
    },
  ];

  const flowList = flows.data ?? counts.flows.data ?? [];
  const statusCounts = flowList.reduce<Record<string, number>>((acc, f) => {
    const key = f.status || 'Unknown';
    acc[key] = (acc[key] ?? 0) + 1;
    return acc;
  }, {});
  const statusMix: StatusMixSegment[] = Object.entries(statusCounts).map(([label, value]) => ({
    label,
    value,
    tone: (DASHBOARD_MIX_TONE[label] as StatusMixSegment['tone']) ?? 'muted',
  }));

  const topFlows: TopFlowRow[] = flowList.slice(0, 5).map((f) => ({
    id: f.id,
    name: f.name,
    status: f.status,
    lastRun: '-', // TODO: top-flow last-run metadata is not in the flow list yet
    messages: 0, // TODO: top-flow message counts are not available from FlowSummary
  }));

  const systemList: DashboardSystem[] = (counts.systems.data ?? []).map((s) => ({
    name: s.name,
    latency: '-', // TODO: per-system latency is not exposed by listSystems
    status: 'Healthy',
  }));

  const throughput: ThroughputSeries = { data: [] }; // TODO: hourly throughput series unavailable

  const loading =
    counts.flows.loading || counts.systems.loading || counts.contracts.loading || flows.loading;
  const error =
    counts.flows.error ||
    counts.systems.error ||
    counts.contracts.error ||
    flows.error ||
    (counts.failed ? 'Some counts could not be loaded' : null);
  const stale = counts.flows.stale || counts.systems.stale || counts.contracts.stale || flows.stale;

  const quickActions: QuickActionEntry[] = [];

  return (
    <DashboardScreen
      stats={stats}
      throughput={throughput}
      topFlows={topFlows}
      statusMix={statusMix}
      systems={systemList}
      quickActions={quickActions}
      loading={loading}
      error={error}
      stale={stale}
      onReload={counts.reload}
      onRefresh={counts.reload}
    />
  );
}

export function QueueRoute(): ReactElement {
  const queue = useQueue();
  const rows = queue.data ?? [];

  const kpis: QueueKpi[] = rows.length
    ? [
        { value: rows.reduce((sum, r) => sum + r.queued, 0), label: 'Queued', tone: 'accent' },
        { value: rows.reduce((sum, r) => sum + r.processed, 0), label: 'Processed', tone: 'ok' },
        { value: rows.reduce((sum, r) => sum + r.failed, 0), label: 'Failed', tone: 'err' },
      ]
    : [];

  const items: QueueItem[] = []; // TODO: queue item (message-level) rows are not exposed by queueSummary

  return (
    <QueueScreen
      kpis={kpis}
      items={items}
      loading={queue.loading}
      error={queue.error ?? queue.refusal}
      stale={queue.stale}
      onReload={queue.reload}
      onRefresh={queue.reload}
      onPauseAll={() => {
        // TODO: pause-all mutation is not wired yet
      }}
      onRetryFailed={() => {
        // TODO: retry-failed mutation is not wired yet
      }}
    />
  );
}

function toFailureRow(f: Failure): FailureRow {
  return {
    id: f.trackingId,
    code: f.errorClass,
    message: f.lastError ?? '',
    flow: f.flowId,
    time: f.failedAt,
    retriesUsed: 0, // TODO: delivery retry counts are not in the Failure payload
    retriesMax: 1, // TODO: retry ceiling metadata unavailable
  };
}

export function FailuresRoute(): ReactElement {
  const failures = useFailures();

  const rows: FailureRow[] = (failures.data ?? []).map(toFailureRow);

  return (
    <FailuresScreen
      failures={rows}
      unresolvedCount={rows.length}
      canOperate={false} // TODO: role-gated canOperate is not passed into this adapter yet
      loading={failures.loading}
      error={failures.error ?? failures.refusal}
      stale={failures.stale}
      onReload={failures.reload}
      onRetry={() => {
        // TODO: per-failure retry needs delivery ids not carried by Failure today
      }}
      onEscalate={() => {
        // TODO: escalation needs delivery ids
      }}
      onRetryAll={() => {
        // TODO: retry-all mutation is not wired yet
      }}
    />
  );
}

export default { DashboardRoute, QueueRoute, FailuresRoute };