// Lane A - Failures screen. Presentational only. Retry buttons are visible only when the
// operator can operate (row-582); an exhausted failure offers Escalate instead of Retry and
// escalates only after the confirm dialog.
import { useState } from 'react';
import type { ReactElement } from 'react';
// Direct per-module ds imports (harness-bundle safety: the barrel pulls forms/*, whose
// top-level forwardRef calls crash the react-stub bundling; see lane hand-back notes).
import type { DataTableColumn } from '../../ds/components/data/DataTable.jsx';
import { Button } from '../../ds/components/actions/Button.jsx';
import { DataTable } from '../../ds/components/data/DataTable.jsx';
import { PageHeader } from '../../ds/components/shell/PageHeader.jsx';
import { Panel } from '../../ds/components/surfaces/Panel.jsx';
import { ConfirmDialog, EmptyState, ErrorNote, SkeletonBlock } from '../_shared/states.js';
import type { FailureRow } from './failures-vm.js';
import { failuresSubtitle, isExhausted, retriesLabel, unresolvedCount } from './failures-vm.js';
import './failures.css';

export type { FailureRow };

export interface FailuresScreenProps {
  failures: FailureRow[];
  /** Sidebar danger-count total; defaults to the rows actually shown. */
  unresolvedCount?: number;
  canOperate?: boolean;
  loading?: boolean;
  error?: string | null;
  stale?: boolean;
  onReload?: () => void;
  onRetry?: (id: string) => void;
  onEscalate?: (id: string) => void;
  onRetryAll?: () => void;
  className?: string;
}

type ConfirmPending = { kind: 'retryAll' } | { kind: 'escalate'; row: FailureRow };

function FailuresScreen(props: FailuresScreenProps): ReactElement {
  const count = props.unresolvedCount ?? unresolvedCount(props.failures);
  const [pending, setPending] = useState<ConfirmPending | null>(null);

  const errorColumns: DataTableColumn<FailureRow>[] = [
    {
      key: 'code',
      header: 'Error',
      render: (row) => (
        <div className="scr-error-cell">
          <span className="err-t">{row.code}</span>
          <span className="scr-error-message">{row.message}</span>
        </div>
      ),
    },
    { key: 'flow', header: 'Flow', mono: true },
    { key: 'time', header: 'Time' },
    { key: 'retries', header: 'Retries', align: 'right', numeric: true, render: (row) => retriesLabel(row) },
  ];

  const operateColumns: DataTableColumn<FailureRow>[] = [
    ...errorColumns,
    {
      key: 'action',
      header: 'Action',
      render: (row) => (isExhausted(row)
        ? (
          <Button variant="danger" size="sm" onClick={() => setPending({ kind: 'escalate', row })}>Escalate</Button>
        )
        : (
          <Button variant="secondary" size="sm" onClick={() => props.onRetry?.(row.id)}>Retry</Button>
        )),
    },
  ];

  return (
    <div className={['scr-page', 'scr-failures', props.className].filter(Boolean).join(' ')} data-test="failures">
      <PageHeader
        title="Failures"
        subtitle={failuresSubtitle(count)}
        actions={(
          <Button
            variant="primary"
            disabled={props.failures.length === 0}
            onClick={() => setPending({ kind: 'retryAll' })}
          >
            Retry All
          </Button>
        )}
      />
      {props.error != null ? (
        <ErrorNote what="failures" message={props.error} stale={props.stale} onReload={props.onReload} />
      ) : props.loading ? (
        <Panel title="Failures" padded>
          <div data-test="skeleton" className="scr-skel-stack">
            <SkeletonBlock height={40} /><SkeletonBlock height={40} /><SkeletonBlock height={40} />
            <SkeletonBlock height={40} /><SkeletonBlock height={40} /><SkeletonBlock height={40} />
          </div>
        </Panel>
      ) : props.failures.length === 0 ? (
        <Panel title="Failures" padded>
          <EmptyState title="No unresolved failures - messages routed straight through." />
        </Panel>
      ) : (
        <Panel title="Failures" padded>
          <DataTable
            columns={props.canOperate ? operateColumns : errorColumns}
            rows={props.failures}
            rowKey="id"
            caption={`${count} unresolved failures`}
          />
        </Panel>
      )}
      <ConfirmDialog
        open={pending !== null}
        title={pending?.kind === 'escalate' ? 'Escalate this failure' : 'Retry all failures'}
        body={pending?.kind === 'escalate'
          ? `Failure ${pending.row.code} is marked for manual escalation - retries are exhausted and no automatic attempt runs again.`
          : 'Every currently unresolved failure goes back to the queue now.'}
        confirmLabel={pending?.kind === 'escalate' ? 'Escalate' : 'Retry All'}
        confirmVariant={pending?.kind === 'escalate' ? 'danger' : 'primary'}
        onConfirm={() => {
          if (pending?.kind === 'escalate') props.onEscalate?.(pending.row.id);
          if (pending?.kind === 'retryAll') props.onRetryAll?.();
          setPending(null);
        }}
        onCancel={() => setPending(null)}
      />
    </div>
  );
}

export default FailuresScreen;
export { FailuresScreen };