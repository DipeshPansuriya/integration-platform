// Row 310 lane A (sanjay-developer, T87): the Dashboard - the signed-in landing (10-screens §1,
// 00-portal-ia §5, phase 1). Four stat tiles (numbers, not charts), the four create entry points and
// the five most recently listed flow versions. Every number comes from the list endpoints the
// screens already call (listFlows / listSystems / listContracts) - no new API, no invented telemetry.
// "Pending approval" is the TENANT's count (Q3 default, m-5517d7): the flow list has no author.
// States: loading = static skeletons, a 0 = the tile's worded empty copy, a failed list = one banner
// "Some counts could not be loaded - Retry" with that tile shown as not available.

import { useCallback, useEffect, useState } from 'react';
import { listContracts, type Contract } from '../api/contracts';
import { listFlows, type FlowSummary } from '../api/flows';
import { listSystems, type SystemView } from '../api/systems';
import { messageFailures, type Failure } from '../api/messages';
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
} from './dashboard-counts';
import {
  TRACKING_ID_HINT,
  approvalsWaiting,
  flowsInProgress,
  homeBlocks,
  setupSteps,
  trackingHash
} from './home-steps';
import { todayUtc } from './failures-view';
import './dashboard.css';

/** Each list's answer: the rows, or null when that list could not be loaded. */
type Lists = {
  flows: FlowSummary[] | null;
  systems: SystemView[] | null;
  contracts: Contract[] | null;
  /** A list that answered 403 is not an error: its blocks are omitted and no banner is raised. */
  denied: { flows: boolean; systems: boolean; contracts: boolean };
};
const NONE_DENIED = { flows: false, systems: false, contracts: false };
const isDenied = (answer: { ok: boolean; status?: number } | null): boolean => answer !== null && !answer.ok && answer.status === 403;

/** Today's failures for the Messages block: rows, off (role has no such block), hidden (403: block not rendered) or error. */
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
      // Honest copy (R3/Q3): the count is the tenant's, not filtered to the signed-in approver.
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

// Setup order (row 575 S2): systems first, then contracts, then the flow.
const QUICK_ACTIONS = [
  { label: 'Register a system', href: '#/systems', primary: true },
  { label: 'Import a contract', href: '#/contracts', primary: false },
  { label: 'Define a flow', href: '#/flows', primary: false },
  { label: 'Create a rule set', href: '#/rules', primary: false }
];

// Row 575 (S1, Karan Low 2): `readsConfiguration` is false only when the roles are KNOWN and lack READ_CONFIGURATION (Support, no
// grants): no list call is made (each would answer 403) and no "could not be loaded" banner is shown. UX only - the server stays the gate.
// Row 575 (S2): Home "Next steps" - role blocks (homeBlocks) over the lists Home already reads; `roles` null = not loaded (all blocks).
export function Dashboard({ user, readsConfiguration = true, roles = null }: { user?: string; readsConfiguration?: boolean; roles?: readonly string[] | null }) {
  const blocks = homeBlocks(roles);
  const today = todayUtc(new Date()); // the backend's days are UTC days - same default as the Failures screen
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

  const failed =
    readsConfiguration &&
    lists !== null &&
    ((lists.flows === null && !lists.denied.flows) || (lists.systems === null && !lists.denied.systems) || (lists.contracts === null && !lists.denied.contracts));
  const recent = lists?.flows ? recentVersions(lists.flows) : [];
  const steps = lists?.systems && lists.contracts && lists.flows ? setupSteps({ systems: lists.systems, contracts: lists.contracts, flows: lists.flows }) : null;
  const waiting = lists?.flows && lists.contracts ? approvalsWaiting(lists.flows, lists.contracts) : null;
  const inProgress = lists?.flows ? flowsInProgress(lists.flows) : [];

  return (
    <section className="dashboard" data-page="dashboard" data-dashboard-loaded={lists !== null}>
      <header className="dashboard-head">
        <div>
          <h1>Home</h1>
          <p className="dashboard-greeting">{greetingFor(new Date().getHours(), user)}. Here is what to do next.</p>
        </div>
      </header>

      {retryBannerShown(failed, retrying) && (
        <div className="dashboard-alert" role="alert">
          <span>{retrying ? 'Retrying...' : 'Some counts could not be loaded.'}</span>
          <button
            type="button"
            className="btn"
            data-action="retry-counts"
            aria-disabled={retrying}
            onClick={() => {
              if (retrying) return;
              setRetrying(true);
              void load().finally(() => setRetrying(false));
            }}
          >
            Retry
          </button>
        </div>
      )}

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
          <h2 id="home-approvals">Waiting for approval ({waiting.total})</h2>
          {/* Plain links: the Versions and Contracts screens do not read a status filter yet (S3/S5 add it); ?date= below does work. */}
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
              if (target !== null) window.location.hash = target;
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
          {/* The live region is always mounted (empty first) so the hint is announced when it appears. */}
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
        <>

      <div className="dashboard-tiles" aria-busy={lists === null}>
        {lists === null
          ? [0, 1, 2, 3].map((i) => (
              <div className="tile tile-skeleton" key={i} aria-hidden="true">
                <span className="skeleton skeleton-label" />
                <span className="skeleton skeleton-number" />
                <span className="skeleton skeleton-sub" />
              </div>
            ))
          /* V4 8: colour the tile's 3px spine - warm for the approval queue, green for systems, accent otherwise. */
          : tilesOf(lists).map((tile) => (
              <a
                key={tile.kind}
                className={
                  'tile' +
                  (tile.kind === 'pending' ? ' tile-bar-warn' : tile.kind === 'systems' ? ' tile-bar-success' : ' tile-bar-accent')
                }
                href={tile.href}
                data-tile={tile.kind}
                aria-label={tileName(tile.kind, tile.count)}
              >
                <span className="tile-label">{tile.label}</span>
                <span className="tile-number">{tile.count ?? '-'}</span>
                <span className="tile-sub">{tile.count === null ? 'Count not available' : tile.sub}</span>
              </a>
            ))}
      </div>

      {blocks.setup && (
      <section className="dashboard-panel" aria-labelledby="dashboard-quick">
        <h2 id="dashboard-quick">Quick actions</h2>
        <div className="dashboard-actions">
          {QUICK_ACTIONS.map((action) => (
            <a key={action.href} className={action.primary ? 'btn btn-primary' : 'btn'} href={action.href}>
              {action.label}
            </a>
          ))}
        </div>
      </section>
      )}

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
        </>
      )}
    </section>
  );
}
