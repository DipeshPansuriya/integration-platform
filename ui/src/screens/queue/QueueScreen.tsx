// Lane A - Queue screen. Presentational only: rows arrive as typed props; pause-all and
// retry-failed are confirmed through the lane-local ConfirmDialog before the callbacks run.
import { useState } from 'react';
import type { ReactElement, ReactNode } from 'react';
// Direct per-module ds imports (barrel pulls forms/* whose top-level forwardRef crashes the
// test-harness react stub; see lane hand-back notes).
import type { DataTableColumn } from '../../ds/components/data/DataTable.jsx';
import type { Tone } from '../../ds/components/feedback/StatusBadge.jsx';
import { Button } from '../../ds/components/actions/Button.jsx';
import { DataTable } from '../../ds/components/data/DataTable.jsx';
import { KpiCard } from '../../ds/components/data/KpiCard.jsx';
import { PageHeader } from '../../ds/components/shell/PageHeader.jsx';
import { Panel } from '../../ds/components/surfaces/Panel.jsx';
import { StatusBadge } from '../../ds/components/feedback/StatusBadge.jsx';
import { ConfirmDialog, EmptyState, ErrorNote, IconButton, SkeletonBlock } from '../_shared/states.js';
import './queue.css';

export interface QueueKpi { value: ReactNode; label: string; tone?: Tone; }
export interface QueueItem { id: string; flow: string; priority: string; queuedAt: string; status: string; }

export interface QueueScreenProps {
  kpis: QueueKpi[];
  items: QueueItem[];
  autoRefresh?: boolean;
  onAutoRefreshChange?: (next: boolean) => void;
  loading?: boolean;
  error?: string | null;
  stale?: boolean;
  onReload?: () => void;
  onRefresh?: () => void;
  onPauseAll?: () => void;
  onRetryFailed?: () => void;
  className?: string;
}

type ConfirmKind = 'pauseAll' | 'retryFailed';

const CONFIRMATIONS: Record<ConfirmKind, { title: string; body: string; label: string; variant: 'danger' | 'primary' }> = {
  pauseAll: {
    title: 'Pause all queues',
    body: 'Processing stops for every flow queue until an operator resumes it. Messages already queued wait in place.',
    label: 'Pause All',
    variant: 'danger',
  },
  retryFailed: {
    title: 'Retry failed messages',
    body: 'Every failed message with retries left goes back to the queue now. Exhausted messages stay for escalation.',
    label: 'Retry Failed',
    variant: 'primary',
  },
};

const ITEMS_COLUMNS: DataTableColumn<QueueItem>[] = [
  { key: 'id', header: 'Message', mono: true },
  { key: 'flow', header: 'Flow', mono: true },
  { key: 'priority', header: 'Priority', render: (row) => <StatusBadge status={row.priority} size="xs" /> },
  { key: 'queuedAt', header: 'Queued at' },
  { key: 'status', header: 'Status', render: (row) => <StatusBadge status={row.status} size="xs" /> },
];

function QueueSkeleton(): ReactElement {
  return (
    <div data-test="skeleton">
      <div className="ip-grid-stats">
        <SkeletonBlock height={120} /><SkeletonBlock height={120} /><SkeletonBlock height={120} />
      </div>
      <Panel title="Queue Items" padded>
        <div className="scr-skel-stack"><SkeletonBlock height={48} /><SkeletonBlock height={260} /></div>
      </Panel>
    </div>
  );
}

function QueueScreen(props: QueueScreenProps): ReactElement {
  const [pending, setPending] = useState<ConfirmKind | null>(null);
  const count = props.items.length;

  return (
    <div className={['scr-page', 'scr-queue', props.className].filter(Boolean).join(' ')} data-test="queue">
      <PageHeader
        title="Queue"
        subtitle={count === 0 ? 'No messages waiting' : `${count} messages waiting`}
        actions={(
          <>
            <Button variant="secondary" onClick={() => setPending('pauseAll')}>Pause All</Button>
            <Button variant="primary" onClick={() => setPending('retryFailed')}>Retry Failed</Button>
          </>
        )}
      />
      {props.error != null ? (
        <ErrorNote what="the queue" message={props.error} stale={props.stale} onReload={props.onReload} />
      ) : props.loading ? (
        <QueueSkeleton />
      ) : (
        <>
          <div className="ip-grid-stats">
            {props.kpis.map((k, i) => (
              <KpiCard key={`${k.label}-${i}`} value={k.value} label={k.label} tone={k.tone} size="lg" />
            ))}
          </div>
          <Panel
            title="Queue Items"
            padded
            aside={(
              <div className="scr-aside-cluster">
                <IconButton icon="refresh" label="Refresh queue" onClick={props.onRefresh} />
                <Button
                  variant="secondary"
                  size="sm"
                  aria-pressed={props.autoRefresh ?? false}
                  onClick={() => props.onAutoRefreshChange?.(!(props.autoRefresh ?? false))}
                >
                  Auto-refresh: {props.autoRefresh ? 'ON' : 'OFF'}
                </Button>
              </div>
            )}
          >
            {count ? (
              <DataTable
                columns={ITEMS_COLUMNS}
                rows={props.items}
                rowKey="id"
                caption={`${count} messages in the queue`}
              />
            ) : (
              <EmptyState
                title="The queue is empty."
                action={<Button variant="secondary" onClick={props.onRefresh}>Refresh</Button>}
              />
            )}
          </Panel>
        </>
      )}
      <ConfirmDialog
        open={pending !== null}
        title={pending ? CONFIRMATIONS[pending].title : ''}
        body={pending ? CONFIRMATIONS[pending].body : ''}
        confirmLabel={pending ? CONFIRMATIONS[pending].label : ''}
        confirmVariant={pending ? CONFIRMATIONS[pending].variant : 'primary'}
        onConfirm={() => {
          if (pending === 'pauseAll') props.onPauseAll?.();
          if (pending === 'retryFailed') props.onRetryFailed?.();
          setPending(null);
        }}
        onCancel={() => setPending(null)}
      />
    </div>
  );
}

export default QueueScreen;
export { QueueScreen };