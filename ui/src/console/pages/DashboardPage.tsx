// Console wiring: the Dashboard container (#/). Reads the same lists as pages/Dashboard.tsx (listFlows /
// listSystems / listContracts + today's failures, with the same 403 = block omitted rule) and maps them onto
// screens/dashboard/DashboardScreen. Stat tiles, Status Mix and Quick Actions are real; Throughput, Top Flows
// and System Health have no backend source, so they are hidden or left empty (no invented telemetry). The
// role-aware Home blocks (setup steps, approvals, find a message), the retry banner and Recent versions are
// rendered below the screen with the old page's markup and data-* hooks.

import { useCallback, useEffect, useState } from 'react';
import { listContracts, type Contract } from '../../api/contracts';
import { listFlows, type FlowSummary } from '../../api/flows';
import { listSystems, type SystemView } from '../../api/systems';
import { messageFailures, type Failure } from '../../api/messages';
import {
  contractsInDraft,
  flowCounts,
  greetingFor,
  recentVersions,
  retryBannerShown,
  statusBadge,
  systemsByDirection,
  tileName,
  type TileKind
} from '../../pages/dashboard-counts';
import {
  TRACKING_ID_HINT,
  approvalsWaiting,
  flowsInProgress,
  homeBlocks,
  setupSteps,
  trackingHash
} from '../../pages/home-steps';
import { todayUtc } from '../../pages/failures-view';
import {
  DashboardScreen,
  type DashboardStat,
  type QuickActionEntry,
  type StatusMixSegment
} from '../../screens/dashboard/DashboardScreen';
import { goTo } from './console-pages';
import '../../pages/dashboard.css';

type Lists = {
  flows: FlowSummary[] | null;
  systems: SystemView[] | null;
  contracts: Contract[] | null;
  denied: { flows: boolean; systems: boolean; contracts: boolean };
};
const NONE_DENIED = { flows: false, systems: false, contracts: false };
const isDenied = (answer: { ok: boolean; status?: number } | null): boolean => answer !== null && !answer.ok && answer.status === 403;

type Failures = { kind: 'rows'; rows: Failure[] } | { kind: 'off' } | { kind: 'hidden' } | { kind: 'error' };

type Tile = { kind: TileKind; label: string; count: number | null; href: string; sub: string };

function tilesOf(lists: Lists): Tile[] {
  const flows = lists.flows ? flowCounts(lists.flows) : null;
  const systems = lists.systems ? systemsByDirection(lists.systems) : null;
  const contracts = lists.contracts ? contractsInDraft(lists.contracts) : null;
  return [
    {
      kind: 'pending',
      label: 'Versions pending approval',
      count: flows?.pendingApproval ?? null,
      href: '#/versions',
      sub: flows?.pendingApproval === 0 ? 'Nothing waits for approval' : 'Across your tenant, any author'
    },
    {
      kind: 'drafts',
      label: 'Drafts to finish',
      count: flows?.drafts ?? null,
      href: '#/versions',
      sub: flows?.drafts === 0 ? 'No drafts open' : 'Flow versions still in draft'
    },
    {
      kind: 'systems',
      label: 'Systems registered',
      count: systems?.total ?? null,
      href: '#/systems',
      sub: systems
        ? systems.total === 0
          ? 'Register the first system'
          : `${systems.inbound} inbound · ${systems.outbound} outbound`
        : ''
    },
    {
      kind: 'contracts',
      label: 'Contracts in draft',
      count: contracts,
      href: '#/contracts',
      sub: contracts === 0 ? 'No contract drafts open' : 'With at least one draft version'
    }
  ];
}

const TILE_LOOK: Record<TileKind, Pick<DashboardStat, 'tone' | 'icon'>> = {
  pending: { tone: 'warn', icon: 'check' },
  drafts: { tone: 'accent', icon: 'document' },
  systems: { tone: 'ok', icon: 'systems' },
  contracts: { tone: 'accent', icon: 'contracts' }
};

/** The tile is not a link in the new design, so its sub-line carries the old tile's link and data-tile hook. */
function statsOf(lists: Lists): DashboardStat[] {
  return tilesOf(lists).map((tile) => ({
    ...TILE_LOOK[tile.kind],
    label: tile.label,
    value: tile.count ?? '-',
    sub: (
      <a className="cp-tile-link" href={tile.href} data-tile={tile.kind} aria-label={tileName(tile.kind, tile.count)}>
        {tile.count === null ? 'Count not available' : tile.sub}
      </a>
    )
  }));
}

const MIX: Array<{ status: string; tone: StatusMixSegment['tone'] }> = [
  { status: 'PUBLISHED', tone: 'ok' },
  { status: 'APPROVED', tone: 'accent' },
  { status: 'SUBMITTED', tone: 'info' },
  { status: 'DRAFT', tone: 'warn' }
];

/** Flow-version states counted from the flow list; a state with no flow is left out of the donut. */
function statusMixOf(flows: readonly FlowSummary[] | null): StatusMixSegment[] {
  if (!flows) return [];
  return MIX.map(({ status, tone }) => ({ label: statusBadge(status).word, value: flows.filter((f) => f.status === status).length, tone }))
    .filter((segment) => segment.value > 0);
}

// Setup order (row 575 S2): systems first, then contracts, then the flow.
const QUICK_ACTIONS: Array<QuickActionEntry & { href: string }> = [
  { key: 'systems', icon: 'systems', title: 'Register a system', description: 'Source or target endpoint', tone: 'accent', href: '#/systems' },
  { key: 'contracts', icon: 'contracts', title: 'Import a contract', description: 'Message schema and version', tone: 'ok', href: '#/contracts' },
  { key: 'flows', icon: 'flows', title: 'Define a flow', description: 'Route, map and deliver', tone: 'info', href: '#/flows' },
  { key: 'rules', icon: 'rules', title: 'Create a rule set', description: 'Decision table for routing', tone: 'warn', href: '#/rules' }
];

export function DashboardPage({ user, readsConfiguration = true, roles = null }: { user?: string; readsConfiguration?: boolean; roles?: readonly string[] | null }) {
  const blocks = homeBlocks(roles);
  const today = todayUtc(new Date());
  const [failures, setFailures] = useState<Failures>({ kind: 'off' });
  const [trackingInput, setTrackingInput] = useState('');
  const [trackingError, setTrackingError] = useState(false);
  const [lists, setLists] = useState<Lists | null>(null);
  const [retrying, setRetrying] = useState(false);

  const load = useCallback(async () => {
    setLists(null);
    const failuresCall = blocks.messages
      ? messageFailures({ date: today }).then(
          (answer): Failures => (answer.ok ? { kind: 'rows', rows: answer.data } : answer.status === 403 ? { kind: 'hidden' } : { kind: 'error' }),
          (): Failures => ({ kind: 'error' })
        )
      : Promise.resolve<Failures>({ kind: 'off' });
    if (!readsConfiguration) {
      setFailures(await failuresCall);
      setLists({ flows: null, systems: null, contracts: null, denied: NONE_DENIED });
      return;
    }
    const [flows, systems, contracts, failureAnswer] = await Promise.all([
      listFlows().catch(() => null),
      listSystems().catch(() => null),
      listContracts().catch(() => null),
      failuresCall
    ]);
    setFailures(failureAnswer);
    setLists({
      flows: flows?.ok ? flows.data : null,
      systems: systems?.ok ? systems.data : null,
      contracts: contracts?.ok ? contracts.data : null,
      denied: { flows: isDenied(flows), systems: isDenied(systems), contracts: isDenied(contracts) }
    });
  }, [readsConfiguration, blocks.messages, today]);

  useEffect(() => {
    void load();
  }, [load]);

  const retry = () => {
    if (retrying) return;
    setRetrying(true);
    void load().finally(() => setRetrying(false));
  };

  const failed =
    readsConfiguration &&
    lists !== null &&
    ((lists.flows === null && !lists.denied.flows) || (lists.systems === null && !lists.denied.systems) || (lists.contracts === null && !lists.denied.contracts));
  const recent = lists?.flows ? recentVersions(lists.flows) : [];
  const steps = lists?.systems && lists.contracts && lists.flows ? setupSteps({ systems: lists.systems, contracts: lists.contracts, flows: lists.flows }) : null;
  const waiting = lists?.flows && lists.contracts ? approvalsWaiting(lists.flows, lists.contracts) : null;
  const inProgress = lists?.flows ? flowsInProgress(lists.flows) : [];
  const loading = readsConfiguration && lists === null;

  const screenClass = [
    !loading && 'cp-dash-loaded',
    !blocks.setup && 'cp-dash-no-setup',
    !readsConfiguration && 'cp-dash-no-config'
  ].filter(Boolean).join(' ');

  return (
    <section className="dashboard" data-page="dashboard" data-dashboard-loaded={lists !== null}>
      {retryBannerShown(failed, retrying) && (
        <div className="dashboard-alert" role="alert">
          <span>{retrying ? 'Retrying...' : 'Some counts could not be loaded.'}</span>
          <button type="button" className="btn" data-action="retry-counts" aria-disabled={retrying} onClick={retry}>
            Retry
          </button>
        </div>
      )}

      <DashboardScreen
        title="Home"
        className={screenClass}
        stats={lists && readsConfiguration ? statsOf(lists) : []}
        throughput={{ data: [] }}
        topFlows={[]}
        statusMix={statusMixOf(lists?.flows ?? null)}
        systems={[]}
        quickActions={blocks.setup ? QUICK_ACTIONS : []}
        loading={loading}
        error={null}
        onReload={retry}
        onRefresh={() => void load()}
        onNewFlow={() => goTo('#/flows')}
        onQuickAction={(key) => {
          const action = QUICK_ACTIONS.find((qa) => qa.key === key);
          if (action) goTo(action.href);
        }}
      />

      <div className="cp-below">
        <p className="dashboard-greeting">{greetingFor(new Date().getHours(), user)}. Here is what to do next.</p>

        {lists !== null && blocks.setup && steps && (
          <section className="dashboard-panel home-block" data-home-block="setup" aria-labelledby="home-setup">
            <h2 id="home-setup">Set up an integration</h2>
            <ol className="home-steps">
              {steps.map((step) => (
                <li key={step.key} className={`home-step home-step-${step.state}`} data-step={step.key} aria-current={step.state === 'next' ? 'step' : undefined}>
                  <span className="home-step-mark" aria-hidden="true">{step.mark}</span>
                  <span className="home-step-label">{step.n} {step.label}</span>
                  <span className="home-step-state">{step.stateWord}</span>
                  <span className="home-step-note">{step.note}</span>
                  {step.action && (
                    <a className={step.state === 'next' ? 'btn btn-primary' : 'btn'} href={step.action.href}>{step.action.label}</a>
                  )}
                </li>
              ))}
            </ol>
            {inProgress.length > 0 && (
              <>
                <h3>Flows in progress</h3>
                <ul className="home-progress">
                  {inProgress.map((row) => (
                    <li key={row.id} data-progress-flow={row.id}>
                      <a href={row.href}>{row.name}</a> - next: {row.next}
                    </li>
                  ))}
                </ul>
              </>
            )}
          </section>
        )}

        {lists !== null && blocks.approvals && waiting && (
          <section className="dashboard-panel home-block" data-home-block="approvals" aria-labelledby="home-approvals">
            <h2 id="home-approvals">Waiting for your approval ({waiting.total})</h2>
            {waiting.total === 0 ? (
              <p className="dashboard-muted">Nothing waits for approval.</p>
            ) : (
              <ul className="home-progress">
                {waiting.flows > 0 && (
                  <li><a href="#/versions">{waiting.flows} {waiting.flows === 1 ? 'flow version' : 'flow versions'}</a> submitted</li>
                )}
                {waiting.contracts > 0 && (
                  <li><a href="#/contracts">{waiting.contracts} {waiting.contracts === 1 ? 'contract' : 'contracts'}</a> to approve</li>
                )}
              </ul>
            )}
            <a href="#/versions">Open versions</a>
          </section>
        )}

        {lists !== null && blocks.messages && failures.kind !== 'off' && failures.kind !== 'hidden' && (
          <section className="dashboard-panel home-block" data-home-block="messages" aria-labelledby="home-messages">
            <h2 id="home-messages">Find a message</h2>
            <form
              className="home-search"
              onSubmit={(event) => {
                event.preventDefault();
                const target = trackingHash(trackingInput);
                setTrackingError(target === null);
                if (target !== null) goTo(target);
              }}
            >
              <label htmlFor="home-tracking">Tracking id</label>
              <input
                id="home-tracking"
                className="input"
                value={trackingInput}
                aria-invalid={trackingError}
                aria-describedby={trackingError ? 'home-tracking-hint' : undefined}
                onChange={(event) => setTrackingInput(event.target.value)}
              />
              <button type="submit" className="btn btn-primary">Go</button>
            </form>
            <p id="home-tracking-hint" className="dashboard-muted" role="status">{trackingError ? TRACKING_ID_HINT : ''}</p>
            <h3>Needs attention today (UTC)</h3>
            {failures.kind === 'error' ? (
              <p className="dashboard-muted">Failed messages could not be loaded.</p>
            ) : failures.rows.length === 0 ? (
              <p className="dashboard-muted">Nothing failed today.</p>
            ) : (
              <p><a href={`#/failures?date=${today}`}>{failures.rows.length} failed</a></p>
            )}
          </section>
        )}

        {readsConfiguration && (
          <section className="dashboard-panel" aria-labelledby="dashboard-recent">
            <h2 id="dashboard-recent">Recent versions</h2>
            {lists === null ? (
              <p className="dashboard-muted">Loading versions...</p>
            ) : lists.flows === null ? (
              <p className="dashboard-muted">Versions could not be loaded.</p>
            ) : recent.length === 0 ? (
              <p className="dashboard-muted">
                No flows yet - <a href="#/flows">Define a flow</a> first.
              </p>
            ) : (
              <table className="table dashboard-recent">
                <thead>
                  <tr>
                    <th scope="col">Flow</th>
                    <th scope="col">Code</th>
                    <th scope="col">Status</th>
                  </tr>
                </thead>
                <tbody>
                  {recent.map((flow) => {
                    const badge = statusBadge(flow.status);
                    return (
                      <tr key={flow.id} data-recent-flow={flow.id}>
                        <td>
                          <a href={`#/versions/${flow.id}`}>{flow.name || flow.code}</a>
                        </td>
                        <td className="dashboard-code">{flow.code}</td>
                        <td>
                          <span className={`badge badge-${badge.tone}`}>{badge.word}</span>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            )}
          </section>
        )}
      </div>
    </section>
  );
}
