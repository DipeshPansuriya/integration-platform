import type { FlowsScreenProps, FlowRow } from './types';
import { PageHeader } from '../../ds/components/shell/PageHeader.jsx';
import { FlowCard } from '../../ds/components/data/FlowCard.jsx';
import { Button } from '../../ds/components/actions/Button.jsx';
import { Callout } from '../../ds/components/feedback/Callout.jsx';
import './flows.css';

export default function FlowsScreen({
  flows = null,
  loading = false,
  error = '',
  stale = false,
  onRetry,
  onOpenFlow,
  onNewFlow,
}: FlowsScreenProps) {
  const hasData = Boolean(flows?.length);
  return (
    <div className="lb-page" data-page="flows">
      <PageHeader
        title="Flows"
        subtitle={`${flows?.length ?? 0} integration flows configured`}
        actions={
          <>
            <Button data-action="filter-flows">Filter</Button>
            <Button variant="primary" data-action="new-flow" onClick={onNewFlow}>
              New Flow
            </Button>
          </>
        }
      />
      {Boolean(error) && (
        <div className="lb-errorbar" role="alert" data-error="flows">
          <Callout tone="err" title="Flows could not be loaded">
            {error}
          </Callout>
          <Button data-action="retry-flows" onClick={onRetry}>
            Retry
          </Button>
        </div>
      )}
      {stale && hasData && <p className="lb-stale">Showing the last loaded list — it may be out of date.</p>}
      {loading && !hasData ? (
        <div className="lb-grid" data-loading="flows">
          {[0, 1, 2, 3, 4, 5].map((i) => (
            <div key={i} className="lb-skel lb-skel-card" />
          ))}
        </div>
      ) : hasData ? (
        <div className="lb-grid">
          {flows.map((f) => (
            <FlowCard
              key={f.id}
              name={f.name}
              status={f.status}
              description={f.description}
              meta={metaOf(f)}
              onOpen={onOpenFlow ? () => onOpenFlow(f) : undefined}
            />
          ))}
        </div>
      ) : (
        <div className="lb-grid">
          <button type="button" className="lb-empty" onClick={onNewFlow}>
            No flows yet — create the first one with New Flow.
          </button>
        </div>
      )}
    </div>
  );
}

function metaOf(f: FlowRow): { label: string; value: string }[] {
  const meta: { label: string; value: string }[] = [];
  if (f.lastRun) meta.push({ label: 'Last run', value: f.lastRun });
  if (f.messagesToday != null) meta.push({ label: 'Messages today', value: String(f.messagesToday) });
  if (f.successRate) meta.push({ label: 'Success', value: f.successRate });
  return meta;
}