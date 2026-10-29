// Row 310 lane B (T88, 10-screens §3): the Flows list landing (#/flows). Lists the tenant's flows
// from the typed client (GET /api/v1/admin/flows); a row opens the editor (#/flow/<id>), "Define a
// flow" opens it in create mode (#/flow/new). Replaces the old last-edited localStorage hydration.
// ponytail: the list answer carries no source/destinations/updated fields, so the table shows what
// it has (name, code, ordering, status) instead of one read per row.

import { useEffect, useState } from 'react';
import { listFlows, type FlowSummary } from '../api/flows';
import { statusBadge } from './dashboard-counts';
import { filterCountText, filterFlows, flowHash, NEW_FLOW_HASH, orderingWord } from './flow-lifecycle';
import './flows.css';

type Load = 'loading' | 'ready' | 'error';

export function FlowsList() {
  const [rows, setRows] = useState<FlowSummary[]>([]);
  const [load, setLoad] = useState<Load>('loading');
  const [filter, setFilter] = useState('');

  const loadRows = () => {
    setLoad('loading');
    void listFlows()
      .then((answer) => {
        if (!answer.ok) {
          setLoad('error');
          return;
        }
        setRows(answer.data);
        setLoad('ready');
      })
      .catch(() => setLoad('error'));
  };

  useEffect(loadRows, []);

  const shown = filterFlows(rows, filter);
  const open = (id: string) => {
    window.location.hash = flowHash(id);
  };

  return (
    <div data-page="flows-list" data-flows-loaded={load === 'ready'} className="flows-page">
      <div className="flows-head">
        <h1>Flows</h1>
        <a className="btn btn-primary" href={NEW_FLOW_HASH} data-action="define-flow">
          Define a flow
        </a>
        <a className="btn" href="#/flows?start-from-template=1" data-action="start-from-template" data-template-entry="true">
          Start from template
        </a>
      </div>

      {load === 'loading' && <p className="flows-muted" aria-live="polite">Loading flows...</p>}
      {load === 'error' && (
        <p role="alert" className="flows-alert flows-alert-danger" data-load-error="flows">
          The flow list could not be loaded. Check your connection and try again.{' '}
          <button type="button" className="btn" data-action="retry-load" onClick={loadRows}>
            Retry
          </button>
        </p>
      )}
      {load === 'ready' && rows.length === 0 && (
        <div className="flows-empty" data-empty="flows">
          <p>No flows yet. Define the first one to start.</p>
        </div>
      )}

      {rows.length > 0 && (
        <>
          <div className="flows-filter">
            <label>
              <span className="flows-sr-only">Filter flows</span>
              <input
                type="search"
                data-field="flows-filter"
                placeholder="Filter by name or code"
                value={filter}
                onChange={(e) => setFilter(e.target.value)}
              />
            </label>
            <button type="button" className="btn" data-action="clear-filter" onClick={() => setFilter('')} disabled={!filter}>
              Clear
            </button>
          </div>
          <p className="flows-sr-only" role="status" aria-live="polite" data-live-region="true">
            {filterCountText(shown.length, rows.length, filter)}
          </p>
          <div className="flows-grid">
            <table className="table" aria-label="Flows">
              <thead>
                <tr>
                  <th scope="col">Name</th>
                  <th scope="col">Code</th>
                  <th scope="col">Ordering</th>
                  <th scope="col">Latest version</th>
                </tr>
              </thead>
              <tbody>
                {shown.map((row) => {
                  const badge = statusBadge(row.status);
                  return (
                    <tr key={row.id} className="flows-row" onClick={() => open(row.id)}>
                      <td>
                        <a href={flowHash(row.id)} data-flow-link={row.id} onClick={(e) => e.stopPropagation()}>
                          {row.name || row.code}
                        </a>
                      </td>
                      <td className="flows-mono">{row.code}</td>
                      <td>{orderingWord(row.ordering)}</td>
                      <td>
                        <span className={`badge badge-${badge.tone}`}>{badge.word}</span>
                      </td>
                    </tr>
                  );
                })}
                {shown.length === 0 && (
                  <tr>
                    <td colSpan={4} className="flows-muted">No flow matches the filter.</td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  );
}
