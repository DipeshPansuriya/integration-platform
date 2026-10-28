import type { SystemDetailDrawerProps } from './types';
import { StatusBadge } from '../../ds/components/feedback/StatusBadge.jsx';
import { KeyValue } from '../../ds/components/data/KeyValue.jsx';
import { KpiCard } from '../../ds/components/data/KpiCard.jsx';
import { TagList } from '../../ds/components/data/TagList.jsx';
import { Timeline } from '../../ds/components/data/Timeline.jsx';
import { Drawer } from '../../ds/components/surfaces/Drawer.jsx';
import { Button } from '../../ds/components/actions/Button.jsx';
import './systems.css';

export default function SystemDetailDrawer({
  open = false,
  system = null,
  kpis = null,
  kpisLoading = false,
  usedBy = [],
  usedByLoading = false,
  events = null,
  eventsLoading = false,
  eventsError = '',
  onRetryEvents,
  onClose,
  onTestConnection,
  onEdit,
}: SystemDetailDrawerProps) {
  const hasKpis = Boolean(!kpisLoading && kpis);
  const eventState: 'loading' | 'error' | 'ready' | 'empty' = eventsLoading
    ? 'loading'
    : eventsError
      ? 'error'
      : events?.length
        ? 'ready'
        : 'empty';

  return (
    <Drawer
      open={open}
      title="System detail"
      onClose={onClose}
      footer={
        system ? (
          <div className="lb-foot">
            <Button data-action="close" onClick={onClose}>
              Close
            </Button>
            <Button
              variant="secondary"
              data-action="test-connection"
              onClick={onTestConnection ? () => onTestConnection(system) : undefined}
            >
              Test Connection
            </Button>
            <Button
              variant="primary"
              className="lb-foot-primary"
              data-action="edit-system"
              onClick={onEdit ? () => onEdit(system) : undefined}
            >
              Edit System
            </Button>
          </div>
        ) : (
          <div className="lb-foot">
            <Button variant="primary" data-action="close" onClick={onClose}>
              Close
            </Button>
          </div>
        )
      }
    >
      {!system && <p className="lb-muted">No system selected.</p>}
      {system && (
        <>
          <h3 className="ip-drawer-label">Overview</h3>
          <div className="lb-form">
            <div className="lb-row" data-field="status">
              <span className="lb-lab">Status</span>
              <StatusBadge status={system.status} />
            </div>
            <KeyValue label="Protocol" value={system.protocol ?? '—'} />
            <KeyValue label="Auth" value={system.auth ?? '—'} />
            <KeyValue
              label="Base URL"
              value={system.baseUrl ? <span className="lb-code">{system.baseUrl}</span> : '—'}
              mono
            />
            <KeyValue label="Timeout" value={system.timeoutMs != null ? `${system.timeoutMs} ms` : '—'} />
            <KeyValue label="Direction" value={system.direction ?? '—'} />
          </div>

          <h3 className="ip-drawer-label">Performance (Last 24h)</h3>
          {hasKpis ? (
            <div className="lb-kpis" data-kpis="ready">
              <div data-kpi="avg-latency">
                <KpiCard size="sm" label="Avg Latency" value={kpis?.avgLatency} />
              </div>
              <div data-kpi="uptime">
                <KpiCard size="sm" label="Uptime" value={kpis?.uptime} />
              </div>
              <div data-kpi="requests">
                <KpiCard size="sm" label="Requests" value={kpis?.requests} />
              </div>
              <div data-kpi="errors">
                <KpiCard size="sm" label="Errors" value={kpis?.errors} />
              </div>
            </div>
          ) : (
            <div className="lb-kpis" data-kpis="loading">
              {[0, 1, 2, 3].map((i) => (
                <div key={i} className="lb-skel lb-skel-row" />
              ))}
            </div>
          )}

          <h3 className="ip-drawer-label">Used by Flows</h3>
          {usedByLoading ? (
            <div className="lb-skel lb-skel-row" />
          ) : usedBy.length ? (
            <TagList tags={usedBy} mono label="Used by flows" />
          ) : (
            <p className="lb-muted">Not used by any flow yet.</p>
          )}

          <h3 className="ip-drawer-label">Recent Events</h3>
          <div data-region="recent-events" data-state={eventState}>
            {eventState === 'loading' && (
              <div>
                <div className="lb-skel lb-skel-row" />
                <div className="lb-skel lb-skel-row" />
              </div>
            )}
            {eventState === 'error' && (
              <div className="lb-errorbar" role="alert" data-error="events">
                <p className="lb-muted">{eventsError}</p>
                <Button data-action="retry-events" onClick={onRetryEvents}>
                  Retry
                </Button>
              </div>
            )}
            {eventState === 'empty' && <p className="lb-muted">No recent events.</p>}
            {eventState === 'ready' && (
              <Timeline
                compact
                label="Recent events"
                items={(events ?? []).map((e) => ({ time: e.time, text: e.text, tone: e.tone ?? 'info' }))}
              />
            )}
          </div>
        </>
      )}
    </Drawer>
  );
}