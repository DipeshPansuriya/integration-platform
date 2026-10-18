// Row 327 T119 (lane D, vijay-developer, E12-F2-S2 "I see only my own tenant's activity"): the activity
// view (#/activity) and one event (#/activity/<eventId>). The server scopes both reads to the bound
// tenant and orders newest first; this screen filters nothing itself and shows only the returned
// fields, before/after as plain text. Another tenant's event id answers 404 and shows the plain
// not-found state. A 403 shows the no-access state. Every read goes through the ONE client
// (src/api/activity.ts -> src/api/client.ts), no mock. Styled with base.css (.table, .btn) only.

import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { listActivity, readActivity, type ActivityPage } from '../api/activity';
import {
  activityQuery,
  activityRows,
  eventIdFromHash,
  pagerState,
  refusalState,
  REFUSAL_COPY,
  type ActivityRow,
  type RefusalState
} from './activity-view';

const PAGE_SIZE = 50;

type Filter = { action: string; from: string; to: string };
const NO_FILTER: Filter = { action: '', from: '', to: '' };

function Refusal({ state, status }: { state: RefusalState; status: number }) {
  return (
    <p role="alert" data-refusal={`activity-${state}`}>
      {REFUSAL_COPY[state]}
      {state === 'failed' && ` (HTTP ${status})`}
    </p>
  );
}

function EventDetail({ eventId }: { eventId: string }) {
  const [row, setRow] = useState<ActivityRow | null>(null);
  const [refusal, setRefusal] = useState<{ state: RefusalState; status: number } | null>(null);

  useEffect(() => {
    setRow(null);
    setRefusal(null);
    readActivity(eventId)
      .then((result) => {
        if (result.ok) setRow(activityRows([result.data])[0]);
        else setRefusal({ state: refusalState(result.status), status: result.status });
      })
      .catch(() => setRefusal({ state: 'failed', status: 0 }));
  }, [eventId]);

  return (
    <div data-activity-event={eventId}>
      <p>
        <a href="#/activity" data-action="back-to-activity">Back to activity</a>
      </p>
      {refusal !== null && <Refusal state={refusal.state} status={refusal.status} />}
      {row !== null && (
        <dl>
          <dt>When</dt>
          <dd><time dateTime={row.at}>{row.at}</time></dd>
          <dt>Actor</dt>
          <dd data-actor-cell>{row.actor}</dd>
          <dt>Action</dt>
          <dd>{row.action}</dd>
          <dt>Targets</dt>
          <dd>{row.targets}</dd>
          <dt>Before</dt>
          <dd><pre>{row.before}</pre></dd>
          <dt>After</dt>
          <dd><pre>{row.after}</pre></dd>
        </dl>
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

  useEffect(() => {
    const onHashChange = () => setEventId(eventIdFromHash(window.location.hash));
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  const load = useCallback(() => {
    listActivity(activityQuery({ ...applied, page, size: PAGE_SIZE }))
      .then((answer) => {
        if (answer.ok) {
          setResult(answer.data);
          setRefusal(null);
        } else {
          setRefusal({ state: refusalState(answer.status), status: answer.status });
        }
      })
      .catch(() => setRefusal({ state: 'failed', status: 0 }));
  }, [applied, page]);

  useEffect(() => {
    if (eventId === null) load();
  }, [eventId, load]);

  const apply = (event: FormEvent) => {
    event.preventDefault();
    setApplied(draft);
    setPage(0);
  };

  if (eventId !== null) {
    return (
      <div data-page="activity">
        <h2>Activity event</h2>
        <EventDetail eventId={eventId} />
      </div>
    );
  }

  const rows = result ? activityRows(result.items) : [];
  const pager = pagerState(page, result?.hasMore ?? false);

  return (
    <div data-page="activity" data-activity-loaded={result !== null || refusal !== null ? 'true' : 'false'}>
      <h2>Activity</h2>
      <p>Configuration changes in your tenant, newest first.</p>
      <form data-activity-filter onSubmit={apply}>
        <label htmlFor="activity-action">Action</label>{' '}
        <input id="activity-action" type="text" value={draft.action}
          onChange={(e) => setDraft({ ...draft, action: e.target.value })} />{' '}
        <label htmlFor="activity-from">From</label>{' '}
        <input id="activity-from" type="date" value={draft.from}
          onChange={(e) => setDraft({ ...draft, from: e.target.value })} />{' '}
        <label htmlFor="activity-to">To</label>{' '}
        <input id="activity-to" type="date" value={draft.to}
          onChange={(e) => setDraft({ ...draft, to: e.target.value })} />{' '}
        <button type="submit" className="btn" data-action="apply-activity-filter">Apply</button>
      </form>
      {refusal !== null && <Refusal state={refusal.state} status={refusal.status} />}
      <table className="table" aria-label="Activity">
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
                <a href={`#/activity/${encodeURIComponent(row.id)}`}><time dateTime={row.at}>{row.at}</time></a>
              </td>
              <td data-actor-cell>{row.actor}</td>
              <td>{row.action}</td>
              <td>{row.targets}</td>
            </tr>
          ))}
          {result !== null && rows.length === 0 && refusal === null && (
            <tr>
              <td colSpan={4} data-empty="activity-none">No activity matches the filter.</td>
            </tr>
          )}
        </tbody>
      </table>
      <p>
        <button type="button" className="btn" data-action="activity-previous" disabled={!pager.previous}
          onClick={() => setPage(page - 1)}>Previous</button>{' '}
        <button type="button" className="btn" data-action="activity-next" disabled={!pager.next}
          onClick={() => setPage(page + 1)}>Next</button>
      </p>
    </div>
  );
}
