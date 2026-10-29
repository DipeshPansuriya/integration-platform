// Row 575 S5 (guided flow, plan S5): #/approvals - what waits for an approver. Open to Approver and Tenant admin (the shell shows
// NoAccess to everyone else; the server's 403 stays the gate and shows the same NoAccess here). Reads the two lists Home already
// reads; approving itself is unchanged and happens on the existing Versions / Contracts screens the rows link to.

import { useEffect, useRef, useState } from 'react';
import { listContracts, type Contract } from '../api/contracts';
import { listFlows, type FlowSummary } from '../api/flows';
import { NoAccess } from '../ui/NoAccess';
import { PageHeader } from '../ui/PageHeader';
import { approvalRows } from './approvals-view';
import './operator.css';

type Load = 'loading' | 'ready' | 'denied' | 'error';

export function Approvals() {
  const [load, setLoad] = useState<Load>('loading');
  const [flows, setFlows] = useState<FlowSummary[]>([]);
  const [contracts, setContracts] = useState<Contract[]>([]);
  const [attempt, setAttempt] = useState(0);
  // WCAG 2.4.3: Retry removes the button that had focus; once the re-read answers, focus goes to the new content (never on the first load).
  const focusTarget = useRef<HTMLElement | null>(null);

  useEffect(() => {
    let current = true;
    setLoad('loading');
    Promise.all([listFlows(), listContracts()])
      .then(([f, c]) => {
        if (!current) return;
        if ((!f.ok && f.status === 403) || (!c.ok && c.status === 403)) return void setLoad('denied');
        if (!f.ok || !c.ok) return void setLoad('error');
        setFlows(f.data);
        setContracts(c.data);
        setLoad('ready');
      })
      .catch(() => {
        if (current) setLoad('error');
      });
    return () => {
      current = false;
    };
  }, [attempt]);

  useEffect(() => {
    if (attempt > 0 && (load === 'ready' || load === 'error')) focusTarget.current?.focus();
  }, [load]);

  const rows = load === 'ready' ? approvalRows(flows, contracts) : [];

  return (
    <section data-page="approvals" data-approvals-load={load} className="operator-page">
      <PageHeader title="Approvals" breadcrumb={[{ label: 'Approvals' }]} next={load === 'ready' && rows.length > 0 ? <>open a row to approve it.</> : undefined} />
      {load === 'denied' && <NoAccess refusal="approvals" />}
      {load === 'loading' && <p role="status" className="op-status">Loading what waits for approval...</p>}
      {load === 'error' && (
        <div role="alert" className="op-alert op-alert-danger" tabIndex={-1} ref={focusTarget as React.RefObject<HTMLDivElement>}>
          <span>What waits for approval could not be loaded. </span>
          <button type="button" className="btn" data-action="retry-approvals" onClick={() => setAttempt((n) => n + 1)}>Retry</button>
        </div>
      )}
      {load === 'ready' && (
        <>
          <h2 className="op-section" tabIndex={-1} ref={focusTarget as React.RefObject<HTMLHeadingElement>}>Waiting for approval ({rows.length})</h2>
          {rows.length === 0 ? (
            <p className="op-empty">
              Nothing waits for approval. Submitted flow versions and contract versions appear here; draft ones are submitted from <a href="#/versions">Versions</a> and <a href="#/contracts">Contracts</a>.
            </p>
          ) : (
            <div className="op-card">
              <table className="table op-table">
                <caption className="op-sr-only">Flow versions and contracts that wait for approval</caption>
                <thead>
                  <tr>
                    <th scope="col">What</th>
                    <th scope="col">Name</th>
                    <th scope="col">Details</th>
                    <th scope="col">Status</th>
                    <th scope="col"><span className="op-sr-only">Open</span></th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={`${row.kind}-${row.key}`} data-approval-row={row.kind}>
                      <td>{row.what}</td>
                      <th scope="row">{row.name}</th>
                      <td>{row.detail}</td>
                      <td><span className="badge badge-warn">{row.statusWord}</span></td>
                      <td><a className="btn" href={row.href} aria-label={`Open ${row.name} to approve`}>Open</a></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </section>
  );
}
