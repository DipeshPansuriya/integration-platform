// Console wiring: #/flows on the new design - FlowsScreen cards + FlowDetailDrawer over the real flow
// list (useFlows -> GET /api/v1/admin/flows) and one flow's detail (useFlow) plus today's delivered and
// failed messages of that flow. Replaces pages/FlowsList 1:1 (no props) and keeps all it offered: the
// editor's create mode (#/flow/new - POST /flows needs destinations and a business-key rule the New
// Flow drawer does not ask for, so the drawer is not used), the name/code filter with Clear and its
// live count, Retry, worded version statuses, ordering. No telemetry exists: Last run, Messages today,
// Success rate and Workers stay empty ('—').

import { useRef, useState } from 'react';
import type { MouseEvent } from 'react';
import { useFlow, useFlows, useResource } from '../../api/hooks';
import type { ApiAnswer, FlowSummary } from '../../api/flows';
import { messageDelivered, messageFailures } from '../../api/messages';
import { FlowsScreen } from '../../screens/flows/FlowsScreen';
import { FlowDetailDrawer } from '../../screens/flows/FlowDetailDrawer';
import type { FlowMessageRow, FlowRow, FlowStatus } from '../../screens/flows/types';
import { Button } from '../../ds/components/actions/Button.jsx';
import { Input } from '../../ds/components/forms/Input.jsx';
import { statusBadge } from '../../pages/dashboard-counts';
import { filterCountText, filterFlows, flowHash, NEW_FLOW_HASH, orderingWord } from '../../pages/flow-lifecycle';
import { failureText } from '../../pages/flow-save';
import { todayUtc } from '../../pages/failures-view';
import { statusWord } from '../../ui/status-words';
import { goTo } from './console-pages';
import './list-pages.css';

const LOAD_FAILED = 'The flow list could not be loaded. Check your connection and try again.';
const RECENT_LIMIT = 10;
// e2e hook of the old list: the header create action is [data-action="define-flow"] (replaces "new-flow").
const DEFINE_FLOW = { 'data-action': 'define-flow' };

/** A list row as the card grid shows it: the version status word, and code + ordering as the fact line. */
function flowRow(flow: FlowSummary): FlowRow {
  return {
    id: flow.id,
    code: flow.code,
    name: flow.name || flow.code,
    // the word is the carrier (Draft / Submitted / Approved / Published); FlowCard shows it as given
    status: statusBadge(flow.status).word as FlowStatus,
    description: [flow.code, flow.ordering ? `Ordering: ${orderingWord(flow.ordering)}` : ''].filter(Boolean).join(' · ')
  };
}

const timeOf = (iso: string | null | undefined): string => (iso && iso.length >= 19 ? iso.slice(11, 19) : iso ?? '—');

/** Today's (UTC day, as the backend counts) delivered + failed messages of one flow, newest first. */
async function recentMessages(flowId: string): Promise<ApiAnswer<FlowMessageRow[]>> {
  const date = todayUtc(new Date());
  const [delivered, failed] = await Promise.all([messageDelivered({ flowId, date }), messageFailures({ flowId, date })]);
  if (!delivered.ok) return delivered;
  if (!failed.ok) return failed;
  const rows = [
    ...delivered.data.map((m) => ({ at: m.deliveredAt, row: { id: m.trackingId, time: timeOf(m.deliveredAt), status: statusWord('state', 'DELIVERED').word, latency: '—' } })),
    ...failed.data.map((m) => ({ at: m.failedAt, row: { id: m.trackingId, time: timeOf(m.failedAt), status: 'Failed', latency: '—' } }))
  ];
  rows.sort((a, b) => String(b.at).localeCompare(String(a.at)));
  return { ok: true, data: rows.slice(0, RECENT_LIMIT).map((r) => r.row) };
}

const NO_MESSAGES: ApiAnswer<FlowMessageRow[]> = { ok: true, data: [] };

export function FlowsPage() {
  const flows = useFlows();
  const [filter, setFilter] = useState('');
  const [openId, setOpenId] = useState<string | null>(null);
  const filterRef = useRef<HTMLInputElement | null>(null);
  const detail = useFlow(openId);
  const messages = useResource<FlowMessageRow[], ApiAnswer<FlowMessageRow[]>>(
    () => (openId === null ? Promise.resolve(NO_MESSAGES) : recentMessages(openId)),
    [openId]
  );

  const all = flows.data ?? [];
  const failed = flows.status !== null;
  const loaded = flows.data !== null && !failed;
  const shown = filterFlows(all, filter);
  const filteredOut = all.length > 0 && shown.length === 0;
  const countText = filterCountText(shown.length, all.length, filter);

  const summary = openId ? all.find((f) => f.id === openId) ?? null : null;
  const read = detail.data && detail.data.id === openId ? detail.data : null;
  const open: FlowRow | null = summary
    ? {
        ...flowRow(summary),
        sourceSystem: read?.sourceSystem || undefined,
        targetSystem: read?.destinations?.length ? read.destinations.join(', ') : undefined
      }
    : null;

  // The screen's Filter button has no handler prop: a click on it (bubbling here) moves focus to the filter.
  const onClick = (e: MouseEvent<HTMLDivElement>) => {
    const target = e.target as Element | null;
    if (target?.closest?.('[data-action="filter-flows"]')) filterRef.current?.focus();
  };

  return (
    <div
      data-page="flows-list"
      data-flows-loaded={loaded}
      data-empty={loaded && all.length === 0 ? 'flows' : undefined}
      data-load-error={failed ? 'flows' : undefined}
      className={`cp-list${filter.trim() ? ' cp-hide-ph-sub' : ''}${filteredOut ? ' cp-filtered-out' : ''}`}
      onClick={onClick}
    >
      {all.length > 0 && (
        <div className="cp-list-tools">
          <Input
            ref={filterRef}
            type="search"
            aria-label="Filter flows"
            data-field="flows-filter"
            placeholder="Filter by name or code"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
          />
          <Button data-action="clear-filter" onClick={() => setFilter('')} disabled={!filter}>
            Clear
          </Button>
          <p className="cp-muted" role="status" aria-live="polite" data-live-region="true">
            {countText}
          </p>
        </div>
      )}
      <FlowsScreen
        flows={shown.map(flowRow)}
        loading={flows.loading}
        error={failed ? LOAD_FAILED : ''}
        stale={flows.stale}
        onRetry={flows.reload}
        onOpenFlow={(flow) => setOpenId(flow.id)}
        onNewFlow={() => goTo(NEW_FLOW_HASH)}
        headerClassName="flows-head"
        newFlowProps={DEFINE_FLOW}
      />
      <FlowDetailDrawer
        open={openId !== null}
        flow={open}
        messages={messages.data}
        messagesLoading={messages.loading}
        messagesError={messages.status !== null ? failureText('load the recent messages', messages.status) : ''}
        onRetryMessages={messages.reload}
        onClose={() => setOpenId(null)}
        onEdit={(flow) => goTo(flowHash(flow.id))}
      />
    </div>
  );
}

export default FlowsPage;
