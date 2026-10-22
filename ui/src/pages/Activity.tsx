// Row 327 T119 (lane D, vijay-developer, E12-F2-S2 "I see only my own tenant's activity"): the activity
// view (#/activity) and one event (#/activity/<eventId>). The server scopes both reads to the bound
// tenant and orders newest first; this screen filters nothing itself and shows only the returned
// fields, before/after as plain text. Another tenant's event id answers 404 and shows the plain
// not-found state. A 403 shows the no-access state. Every read goes through the ONE client
// (src/api/activity.ts -> src/api/client.ts), no mock. B28 (UI audit B0/B5): Flows pattern - h1 header block,
// filter toolbar, table in ONE card, UTC timestamps, a loading state, a stale-response guard, scrolling mono pre.

import { useEffect, useState, type FormEvent } from 'react';
import { listActivity, readActivity, type ActivityPage } from '../api/activity';
import {
  activityQuery,
  activityRows,
  activityTone,
  eventIdFromHash,
  pagerState,
  refusalState,
  REFUSAL_COPY,
  type ActivityRow,
  type RefusalState
} from './activity-view';
import { utcText } from './message-timeline';
import './operator.css';

const PAGE_SIZE = 50;

type Filter = { action: string; from: string; to: string };
const NO_FILTER: Filter = { action: '', from: '', to: '' };

function Refusal({ state, status }: { state: RefusalState; status: number }) {
  return (
    <p role="alert" className="op-alert op-alert-danger" data-refusal={`activity-${state}`}>
      {REFUSAL_COPY[state]}
      {state === 'failed' && ` (HTTP ${status})`}
    </p>
  );
}

function EventDetail({ eventId }: { eventId: string }) {
  const [row, setRow] = useState<ActivityRow | null>(null);
  const [refusal, setRefusal] = useState<{ state: RefusalState; status: number } | null>(null);

  useEffect(() => {
    let current = true;
    setRow(null);
    setRefusal(null);
    readActivity(eventId)
      .then((result) => {
        if (!current) return;
        if (result.ok) setRow(activityRows([result.data])[0]);
        else setRefusal({ state: refusalState(result.status), status: result.status });
      })
      .catch(() => {
        if (current) setRefusal({ state: 'failed', status: 0 });
      });
    return () => {
      current = false;
    };
  }, [eventId]);

  return (
    <div data-activity-event={eventId}>
      <p>
        <a className="btn" href="#/activity" data-action="back-to-activity">Back to activity</a>
      </p>
      {refusal !== null && <Refusal state={refusal.state} status={refusal.status} />}
      {row !== null && (
        <div className="op-panel">
        <dl className="op-dl">
          <dt>When</dt>
          <dd><time dateTime={row.at} className="op-mono">{utcText(row.at)}</time></dd>
          <dt>Actor</dt>
          <dd data-actor-cell>{row.actor}</dd>
          <dt>Action</dt>
          <dd><span className={`badge badge-${activityTone(row.action)}`} data-activity-action>{row.action}</span></dd>
          <dt>Targets</dt>
          <dd>{row.targets}</dd>
          <dt>Before</dt>
          <dd><pre className="op-pre">{row.before}</pre></dd>
          <dt>After</dt>
          <dd><pre className="op-pre">{row.after}</pre></dd>
        </dl>
        </div>
      )}
    </div>
  );
}

export function Activity() {
  const [eventId, setEventId] = useState<string | null>(eventIdFromHash(window.location.hash));
  const [draft, setDraft] = useState<Filter>(NO_FILTER);
  const [applied, setApplied] = useState<Filter>(NO_FILTER);
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<ActivityPage | null>(null);
  const [refusal, setRefusal] = useState<{ state: RefusalState; status: number } | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const onHashChange = () => setEventId(eventIdFromHash(window.location.hash));
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  useEffect(() => {
    if (eventId !== null) return;
    let current = true; // a slower, older page/filter answer never overwrites the newer one
    setLoading(true);
    listActivity(activityQuery({ ...applied, page, size: PAGE_SIZE }))
      .then((answer) => {
        if (!current) return;
        if (answer.ok) {
          setResult(answer.data);
          setRefusal(null);
        } else {
          setRefusal({ state: refusalState(answer.status), status: answer.status });
        }
      })
      .catch(() => {
        if (current) setRefusal({ state: 'failed', status: 0 });
      })
      .finally(() => {
        if (current) setLoading(false);
      });
    return () => {
      current = false;
    };
  }, [eventId, applied, page]);

  const apply = (event: FormEvent) => {
    event.preventDefault();
    setApplied(draft);
    setPage(0);
  };

  if (eventId !== null) {
    return (
      <div data-page="activity" className="operator-page">
        <header className="op-head">
          <h1>Activity event</h1>
        </header>
        <EventDetail eventId={eventId} />
      </div>
    );
  }

  const rows = result ? activityRows(result.items) : [];
  const pager = pagerState(page, result?.hasMore ?? false);

  return (
    <div data-page="activity" data-activity-loaded={result !== null || refusal !== null ? 'true' : 'false'} className="operator-page">
      <header className="op-head">
        <div>
          <h1>Activity</h1>
          <p className="op-lead">Configuration changes in your tenant, newest first.</p>
        </div>
      </header>
      <form data-activity-filter onSubmit={apply} className="op-toolbar">
        <label htmlFor="activity-action">Action
          <input id="activity-action" type="text" value={draft.action}
            onChange={(e) => setDraft({ ...draft, action: e.target.value })} />
        </label>
        <label htmlFor="activity-from">From
          <input id="activity-from" type="date" value={draft.from}
            onChange={(e) => setDraft({ ...draft, from: e.target.value })} />
        </label>
        <label htmlFor="activity-to">To
          <input id="activity-to" type="date" value={draft.to}
            onChange={(e) => setDraft({ ...draft, to: e.target.value })} />
        </label>
        <button type="submit" className="btn" data-action="apply-activity-filter">Apply</button>
      </form>
      {refusal !== null && <Refusal state={refusal.state} status={refusal.status} />}
      {loading && refusal === null && <p role="status" className="op-muted" data-loading="activity">Loading activity…</p>}
      <div className="op-card">
      <table className="table op-table" aria-label="Activity">
        <thead>
          <tr>
            <th scope="col">When</th>
            <th scope="col">Actor</th>
            <th scope="col">Action</th>
            <th scope="col">Targets</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.id} data-activity-row={row.id}>
              <td>
                <a href={`#/activity/${encodeURIComponent(row.id)}`}><time dateTime={row.at} className="op-mono">{utcText(row.at)}</time></a>
              </td>
              <td data-actor-cell>{row.actor}</td>
              <td><span className={`badge badge-${activityTone(row.action)}`} data-activity-action>{row.action}</span></td>
              <td>{row.targets}</td>
            </tr>
          ))}
          {result !== null && !loading && rows.length === 0 && refusal === null && (
            <tr>
              <td colSpan={4} className="op-empty-cell" data-empty="activity-none">No activity matches the filter.</td>
            </tr>
          )}
        </tbody>
      </table>
      </div>
      <p className="op-pager">
        <button type="button" className="btn" data-action="activity-previous" disabled={!pager.previous}
          onClick={() => setPage(page - 1)}>Previous</button>{' '}
        <button type="button" className="btn" data-action="activity-next" disabled={!pager.next}
          onClick={() => setPage(page + 1)}>Next</button>
      </p>
    </div>
  );
}
