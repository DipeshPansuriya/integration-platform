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
import './dashboard.css';

/** Each list's answer: the rows, or null when that list could not be loaded. */
type Lists = { flows: FlowSummary[] | null; systems: SystemView[] | null; contracts: Contract[] | null };

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

const QUICK_ACTIONS = [
  { label: 'Define a flow', href: '#/flows', primary: true },
  { label: 'Register a system', href: '#/systems', primary: false },
  { label: 'Import a contract', href: '#/contracts', primary: false },
  { label: 'Create a rule set', href: '#/rules', primary: false }
];

export function Dashboard({ user }: { user?: string }) {
  const [lists, setLists] = useState<Lists | null>(null);
  const [retrying, setRetrying] = useState(false);

  const load = useCallback(async () => {
    setLists(null);
    const [flows, systems, contracts] = await Promise.all([
      listFlows().catch(() => null),
      listSystems().catch(() => null),
      listContracts().catch(() => null)
    ]);
    setLists({
      flows: flows?.ok ? flows.data : null,
      systems: systems?.ok ? systems.data : null,
      contracts: contracts?.ok ? contracts.data : null
    });
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const failed = lists !== null && (lists.flows === null || lists.systems === null || lists.contracts === null);
  const recent = lists?.flows ? recentVersions(lists.flows) : [];

  return (
    <section className="dashboard" data-page="dashboard" data-dashboard-loaded={lists !== null}>
      <header className="dashboard-head">
        <div>
          <h1>Dashboard</h1>
          <p className="dashboard-greeting">{greetingFor(new Date().getHours(), user)}</p>
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
    </section>
  );
}
