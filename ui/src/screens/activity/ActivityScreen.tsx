// Lane D (same-day build): Activity screen - presentational only. Typed props in, no fetch, no
// routes, no hooks. Items pass through newest-first in caller order; filter/export/retry clicks
// are emitted to the caller's handlers. DS Timeline carries the list (dots + times + tones).

import { Timeline } from '../../ds/components/data/Timeline.jsx';
import { PageHeader } from '../../ds/components/shell/PageHeader.jsx';
import { Button } from '../../ds/components/actions/Button.jsx';
import { Callout } from '../../ds/components/feedback/Callout.jsx';
import './activity.css';

export interface ActivityItem {
  time: string;
  text: string;
  tone?: 'ok' | 'warn' | 'err';
}

export interface ActivityScreenProps {
  items?: ActivityItem[];
  loading?: boolean;
  error?: string;
  onFilter?: () => void;
  onExport?: () => void;
  onRetry?: () => void;
  className?: string;
}

export function ActivityScreen({
  items = [],
  loading,
  error,
  onFilter,
  onExport,
  onRetry,
  className,
}: ActivityScreenProps) {
  const body = error ? (
    <Callout tone="err" title="Activity could not load">
      <p>{error}</p>
      <Button {...{ 'data-action': 'retry' }} onClick={onRetry}>
        Retry
      </Button>
    </Callout>
  ) : loading ? (
    <div className="scr-loading" aria-busy="true">
      <span className="scr-skeleton scr-skeleton-label" />
      <span className="scr-skeleton scr-skeleton-sub" />
    </div>
  ) : items.length > 0 ? (
    <Timeline items={items} label="Activity" />
  ) : (
    <div className="scr-empty" data-empty="activity">
      No activity yet in this period.
    </div>
  );

  return (
    <div className={'scr-activity' + (className ? ' ' + className : '')}>
      <PageHeader
        title="Activity"
        subtitle="Recent flow events"
        actions={
          <>
            <Button {...{ 'data-action': 'filter' }} onClick={onFilter}>
              Filter
            </Button>
            <Button variant="primary" {...{ 'data-action': 'export' }} onClick={onExport}>
              Export
            </Button>
          </>
        }
      />
      {body}
    </div>
  );
}
export default ActivityScreen;
