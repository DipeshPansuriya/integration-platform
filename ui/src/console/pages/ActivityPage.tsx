// Console wiring: the Activity container (#/activity). The list is read through useActivity (same query as
// pages/Activity.tsx: action / from / to filter, page + size 50, server-scoped to the tenant, newest first) and
// mapped onto screens/activity/ActivityScreen's timeline. Filter shows / hides the old filter form, Export saves
// the page's rows as CSV in the browser. The old table (each event links to #/activity/<eventId>), the pager and
// the refusal / no-access states stay below the screen. #/activity/<eventId> renders the old page unchanged
// (its event detail with before / after).

import { useEffect, useState, type FormEvent } from 'react';
import { useActivity } from '../../api/hooks';
import { Activity } from '../../pages/Activity';
import {
  activityQuery,
  activityRows,
  activityTone,
  eventIdFromHash,
  pagerState,
  refusalState,
  REFUSAL_COPY,
  type ActivityRow
} from '../../pages/activity-view';
import { utcText } from '../../pages/message-timeline';
import { refusalWords } from '../../ui/refusal-words';
import { NoAccess } from '../../ui/NoAccess';
import { ActivityScreen, type ActivityItem } from '../../screens/activity/ActivityScreen';
import { downloadCsv } from './console-pages';
import '../../pages/operator.css';

const PAGE_SIZE = 50;

type Filter = { action: string; from: string; to: string };
const NO_FILTER: Filter = { action: '', from: '', to: '' };

const TONE: Record<ReturnType<typeof activityTone>, ActivityItem['tone']> = { success: 'ok', danger: 'err', neutral: undefined };

function timelineOf(rows: readonly ActivityRow[]): ActivityItem[] {
  return rows.map((row) => ({
    time: utcText(row.at),
    text: [row.action, row.actor, row.targets].filter((part) => part !== '').join(' · '),
    tone: TONE[activityTone(row.action)]
  }));
}

function exportRows(page: number, rows: readonly ActivityRow[]): void {
  downloadCsv(
    `activity-page-${page + 1}.csv`,
    ['When (UTC)', 'Actor', 'Action', 'Targets'],
    rows.map((row) => [row.at, row.actor, row.action, row.targets])
  );
}

function ActivityList() {
  const [draft, setDraft] = useState<Filter>(NO_FILTER);
  const [applied, setApplied] = useState<Filter>(NO_FILTER);
  const [page, setPage] = useState(0);
  const [filterShown, setFilterShown] = useState(true);
  const activity = useActivity(activityQuery({ ...applied, page, size: PAGE_SIZE }));

  const result = activity.data;
  const refusal = activity.status !== null ? { state: refusalState(activity.status), status: activity.status } : null;
  const loading = activity.loading;
  const rows = result ? activityRows(result.items) : [];
  const pager = pagerState(page, result?.hasMore ?? false);
  const noAccess = refusal?.state === 'no-access';
  const refusalText = refusal === null || noAccess
    ? undefined
    : refusal.state === 'failed' && refusal.status > 0 ? refusalWords(refusal.status, 'the activity') : REFUSAL_COPY[refusal.state];

  const apply = (event: FormEvent) => {
    event.preventDefault();
    setApplied(draft);
    setPage(0);
  };

  return (
    <div data-page="activity" data-activity-loaded={result !== null || refusal !== null ? 'true' : 'false'} className="operator-page">
      <ActivityScreen
        className={refusal !== null ? 'cp-header-only' : undefined}
        items={timelineOf(rows)}
        loading={loading && refusal === null}
        onFilter={() => setFilterShown((shown) => !shown)}
        onExport={() => exportRows(page, rows)}
      />
      <div className="cp-below">
        {filterShown && (
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
        )}
        {noAccess && <NoAccess refusal="activity-no-access" />}
        {refusal !== null && !noAccess && (
          <p role="alert" className="op-alert op-alert-danger" data-refusal={`activity-${refusal.state}`}>
            {refusalText}{' '}
            <button type="button" className="btn" data-action="reload-activity" onClick={activity.reload}>Retry</button>
          </p>
        )}
        {loading && refusal === null && <p role="status" className="op-sr-only" data-loading="activity">Loading activity…</p>}
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
    </div>
  );
}

export function ActivityPage() {
  const [eventId, setEventId] = useState<string | null>(() => (typeof window === 'undefined' ? null : eventIdFromHash(window.location.hash)));

  useEffect(() => {
    const onHashChange = () => setEventId(eventIdFromHash(window.location.hash));
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  // One event (#/activity/<eventId>): the old page's detail view, with its back link and refusal states.
  if (eventId !== null) return <Activity />;
  return <ActivityList />;
}
