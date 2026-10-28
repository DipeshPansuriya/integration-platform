import type * as React from 'react';
import type { SystemsScreenProps, SystemRow, SystemStatus } from './types';
import { PageHeader } from '../../ds/components/shell/PageHeader.jsx';
import { EntityCard } from '../../ds/components/data/EntityCard.jsx';
import { StatusBadge } from '../../ds/components/feedback/StatusBadge.jsx';
import { Button } from '../../ds/components/actions/Button.jsx';
import { Callout } from '../../ds/components/feedback/Callout.jsx';
import './systems.css';

const statusTone = (status: SystemStatus) =>
  status === 'Healthy' ? 'ok' : status === 'Slow' ? 'warn' : status === 'Down' ? 'err' : 'muted';

export { SystemsScreen };
export default function SystemsScreen({
  systems = null,
  loading = false,
  error = '',
  stale = false,
  onRetry,
  onOpenSystem,
  onRegisterSystem,
}: SystemsScreenProps) {
  const hasData = Boolean(systems?.length);
  const systemRows = systems ?? [];
  return (
    <div className="lb-page" data-page="systems">
      <PageHeader
        title="Systems"
        subtitle={`${systems?.length ?? 0} registered endpoints`}
        actions={
          <Button variant="primary" data-action="register-system" onClick={onRegisterSystem}>
            Register System
          </Button>
        }
      />
      {Boolean(error) && (
        <div className="lb-errorbar" role="alert" data-error="systems">
          <Callout tone="err" title="Systems could not be loaded">
            {error}
          </Callout>
          <Button data-action="retry-systems" onClick={onRetry}>
            Retry
          </Button>
        </div>
      )}
      {stale && hasData && <p className="lb-stale">Showing the last loaded list — it may be out of date.</p>}
      {loading && !hasData ? (
        <div className="lb-grid" data-loading="systems">
          {[0, 1, 2, 3, 4, 5].map((i) => (
            <div key={i} className="lb-skel lb-skel-card" />
          ))}
        </div>
      ) : hasData ? (
        <div className="lb-grid">
          {systemRows.map((s) => (
            <EntityCard
              key={s.id}
              icon="systems"
              tone={statusTone(s.status)}
              name={s.name}
              typeLine={s.protocol}
              rows={rowsOf(s)}
              onOpen={onOpenSystem ? () => onOpenSystem(s) : undefined}
            />
          ))}
        </div>
      ) : (
        <div className="lb-grid">
          <button type="button" className="lb-empty" onClick={onRegisterSystem}>
            No systems yet — register the first one with Register System.
          </button>
        </div>
      )}
    </div>
  );
}

function rowsOf(s: SystemRow) {
  const rows: { label: string; value: React.ReactNode }[] = [
    { label: 'Status', value: <StatusBadge status={s.status} /> },
    { label: 'Latency', value: s.latencyMs != null ? `${s.latencyMs} ms` : '—' },
    { label: 'Last check', value: s.lastCheck ?? '—' },
  ];
  return rows;
}