// T112 (E13 base N3, lane D): the failures screen, #/failures - the bound tenant's failed and
// dead-lettered messages per flow and UTC day, read LIVE through the ONE typed client
// (src/api/messages.ts, GET .../failures, T110), no mock (E3-F3-S1). Each row: tracking id (a link to
// the detail screen, carrying the failing stage), failing stage, error class, last error text, origin
// (PLATFORM = our side, TARGET = the target's answer) and failed-at. Newest first, as answered.
//
// Replay (T111/T112): a dead-lettered delivery row (failing stage DELIVER, with a delivery id) carries a Replay
// button (OperatorActionButton, with a confirm step); a route failure has no delivery to replay, so its action
// cell is empty. Row 582: Replay is REPLAY_DEAD_LETTER (tenant admin, support) - `canOperate` (message-guide.ts, the same gate as the
// message detail's Retry/Replay) drops the button AND the Action column for other roles; UX only, the server still answers 403. After an accepted replay the list is re-read silently; the acted row stays in place with its answer.
//
// Masked fields: no payload field is shown. lastError is rendered as a React text child (escaped,
// never parsed as HTML); masking any sensitive value inside it is the backend's (T108), not this screen's.
//
// B28 (UI audit B0/B1/B7): Flows pattern - header block, quiet filter toolbar, ONE card with overflow-x,
// the last-error cell wraps and clamps, tracking id mono + copy, origin as a badge, relative + absolute time.
// `#/failures?flow=<id>&date=<day>` pre-fills the filters (the Queue's Failed link).

import { useEffect, useRef, useState } from 'react';
import { listFlows, type FlowSummary } from '../api/flows';
import { messageFailures, type Failure } from '../api/messages';
import { OperatorActionButton } from './OperatorActionButton';
import { NoAccess } from '../ui/NoAccess';
import { isNoAccess } from '../ui/no-access';
import { failureAction, type OperatorAction } from './operator-actions';
import {
  emptyText,
  failureColumns,
  failureKey,
  failureRows,
  failuresCaption,
  failuresParamsFromHash,
  failuresQuery,
  flowLabels,
  keepActed,
  originTone,
  relativeAge,
  todayUtc,
  type FailureRow
} from './failures-view';
import './operator.css';

type Load = 'loading' | 'ready' | 'refused' | 'error';

function initialParams() {
  return failuresParamsFromHash(typeof window === 'undefined' ? '' : window.location.hash);
}

/** One failure row, its replay action the last cell. The Flow cell shows only in the All-flows view. */
function FailureRowView({ row, showFlow, showAction, action, now, copied, onCopy, onDone }: {
  row: FailureRow;
  showFlow: boolean;
  showAction: boolean;
  action: OperatorAction | null;
  now: Date;
  copied: boolean;
  onCopy: (trackingId: string) => void;
  onDone: (action: OperatorAction) => void;
}) {
  return (
    <tr data-failure-row={row.trackingId} data-failing-stage={row.failingStage} data-origin={row.origin}>
      <td>
        <span className="op-id">
          <a href={row.detailHash} data-message-link={row.trackingId} className="op-mono" title={row.trackingId}>
            {row.trackingId}
          </a>
          <button type="button" className="op-copy" data-copy-id={row.trackingId} aria-label={`Copy tracking id ${row.trackingId}`} onClick={() => onCopy(row.trackingId)}>
            {copied ? 'Copied' : 'Copy'}
          </button>
        </span>
      </td>
      {showFlow && <td data-flow-cell>{row.flowLabel}</td>}
      <td>{row.stageLabel}</td>
      <td data-error-class className="op-mono">{row.errorClass}</td>
      <td data-last-error={row.hasLastError ? 'text' : 'none'}>
        <span className="op-clamp" title={row.lastErrorText}>{row.lastErrorText}</span>
      </td>
      <td>
        <span className={`badge badge-${originTone(row.origin)}`}>{row.originWord}</span>
      </td>
      <td>
        <time dateTime={row.failedAt} className="op-mono">{row.failedAtText}</time>
        <div className="op-sub">{relativeAge(row.failedAt, now)}</div>
      </td>
      {showAction && <td data-action-cell>{action && <OperatorActionButton action={action} confirm onDone={onDone} />}</td>}
    </tr>
  );
}

export function Failures({ canOperate = true }: { canOperate?: boolean }) {
  const [flows, setFlows] = useState<FlowSummary[]>([]);
  const [flowId, setFlowId] = useState(() => initialParams().flowId);
  const [date, setDate] = useState(() => initialParams().date ?? todayUtc(new Date()));
  const [answered, setAnswered] = useState<Failure[]>([]);
  const [load, setLoad] = useState<Load>('loading');
  const [refusal, setRefusal] = useState('');
  const [noAccess, setNoAccess] = useState(false); // row 562: a 403 gets the plain no-access state, not the raw refusal text
  const [reloads, setReloads] = useState(0);
  // B2: after an accepted replay/retry the list is re-read silently; `acted` rows stay on screen with their answer.
  const [refresh, setRefresh] = useState<{ n: number; acted: string[] }>({ n: 0, acted: [] });
  const [copied, setCopied] = useState('');
  // L4: what Copy said (announced politely; a failure is shown, not silent) and the timer that resets the button word.
  const [copyNote, setCopyNote] = useState<{ text: string; failed: boolean }>({ text: '', failed: false });
  const copyTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  // M5: bumped whenever a load starts (filter change or Reload); a silent refresh answering after that is stale and dropped.
  const loadSeq = useRef(0);

  // The flow filter's choices: the tenant's own flows (a refusal leaves "All flows" only).
  useEffect(() => {
    void listFlows()
      .then((answer) => {
        if (answer.ok) setFlows(answer.data);
      })
      .catch(() => undefined); // the filter then offers "All flows" only; the list read reports its own failure
  }, []);

  // A link to #/failures?flow=&date= while this screen is open re-applies the filters.
  useEffect(() => {
    if (typeof window === 'undefined') return;
    const onHash = () => {
      if (!window.location.hash.startsWith('#/failures')) return;
      const params = failuresParamsFromHash(window.location.hash);
      setFlowId(params.flowId);
      setDate(params.date ?? todayUtc(new Date()));
    };
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  useEffect(() => {
    let current = true;
    loadSeq.current += 1;
    // Row 328 L7/D1: the old filter's rows never stay on screen under the new filter's caption and columns.
    setAnswered([]);
    setLoad('loading');
    messageFailures(failuresQuery({ flowId, date }))
      .then((answer) => {
        if (!current) return;
        if (answer.ok) {
          setAnswered(answer.data);
          setNoAccess(false);
          setLoad('ready');
        } else {
          setAnswered([]);
          setRefusal(answer.refusal ?? answer.error ?? 'failures list refused');
          setNoAccess(isNoAccess(answer.status));
          setLoad('refused');
        }
      })
      .catch(() => {
        if (!current) return;
        setAnswered([]);
        setNoAccess(false);
        setLoad('error');
      });
    return () => {
      current = false;
    };
  }, [flowId, date, reloads]);

  // The silent refresh: no loading state, no clearing; a failed re-read leaves the list as it was.
  useEffect(() => {
    if (refresh.n === 0) return;
    let current = true;
    const ticket = loadSeq.current;
    messageFailures(failuresQuery({ flowId, date }))
      .then((answer) => {
        if (current && ticket === loadSeq.current && answer.ok) setAnswered((previous) => keepActed(previous, answer.data, refresh.acted));
      })
      .catch(() => undefined);
    return () => {
      current = false;
    };
  }, [refresh.n]);

  useEffect(() => () => {
    if (copyTimer.current !== null) clearTimeout(copyTimer.current);
  }, []);

  function copyId(trackingId: string) {
    const failed = () => setCopyNote({ text: 'Could not copy automatically. Select the tracking id and copy it by hand.', failed: true });
    if (typeof navigator === 'undefined' || !navigator.clipboard?.writeText) {
      failed();
      return;
    }
    void navigator.clipboard.writeText(trackingId).then(
      () => {
        setCopied(trackingId);
        setCopyNote({ text: `Copied tracking id ${trackingId}.`, failed: false });
        if (copyTimer.current !== null) clearTimeout(copyTimer.current);
        copyTimer.current = setTimeout(() => {
          setCopied('');
          setCopyNote({ text: '', failed: false });
        }, 1500);
      },
      failed
    );
  }

  const query = failuresQuery({ flowId, date });
  const flow = flows.find((f) => f.id === query.flowId);
  const flowLabel = query.flowId ? flow?.code ?? query.flowId : null;
  const day = query.date ?? null;
  const showFlow = !query.flowId; // the Flow column: All-flows view only
  // Labelled at render time, so a flow list answering after the failures still names every row.
  const rows = failureRows(answered, flowLabels(flows));
  const now = new Date();

  const statusText =
    load === 'loading'
      ? 'Loading failures...'
      : load === 'refused'
        ? refusal
        : load === 'error'
          ? 'The failures list could not be loaded. Reload to try again.'
          : rows.length === 0
            ? emptyText(flowLabel, day)
            : `${rows.length} failed ${rows.length === 1 ? 'message' : 'messages'}.`;
  const designedEmpty = load === 'ready' && rows.length === 0;

  return (
    <div data-page="failures" data-failures-loaded={load === 'loading' ? 'false' : 'true'} data-load={load} className="operator-page">
      <header className="op-head">
        <div>
          <h1>Failures</h1>
          <p className="op-lead">Failed and dead-lettered messages per flow and UTC day. Open a tracking id to see where it stopped.</p>
        </div>
        <button type="button" data-action="reload-failures" data-state={load === 'loading' ? 'busy' : 'ready'}
          aria-busy={load === 'loading'} onClick={() => setReloads((n) => n + 1)}>
          Reload
        </button>
      </header>
      <nav aria-label="Messages views" data-message-tabs className="op-tabs">
        <a href="#/messages">Results</a>
        <a href="#/delivered">Delivered</a>
        <a href="#/failures" aria-current="page">Needs attention</a>
      </nav>
      <div className="op-toolbar">
        <label htmlFor="failures-flow">
          Flow
          <select id="failures-flow" data-field="failures-flow" value={flowId} onChange={(e) => setFlowId(e.target.value)}>
            <option value="">All flows</option>
            {flows.map((f) => (
              <option key={f.id} value={f.id}>
                {f.name ? `${f.code} - ${f.name}` : f.code}
              </option>
            ))}
          </select>
        </label>
        <label htmlFor="failures-date">
          Day (UTC, empty for all days)
          <input id="failures-date" type="date" data-field="failures-date" value={date} onChange={(e) => setDate(e.target.value)} />
        </label>
      </div>
      {/* One persistent live region: loading, empty, refused and error are announced from it. */}
      {load === 'refused' && noAccess ? (
        <NoAccess refusal="failures-refused" />
      ) : (
      <p
        role={load === 'refused' || load === 'error' ? 'alert' : 'status'}
        className={designedEmpty ? 'op-empty' : load === 'refused' || load === 'error' ? 'op-alert op-alert-danger' : 'op-status'}
        data-status="failures"
        data-empty={designedEmpty ? 'failures-none' : undefined}
        data-refusal={load === 'refused' ? 'failures-refused' : undefined}
      >
        {statusText}
      </p>
      )}
      <p role="status" data-copy-status className={copyNote.failed ? 'op-alert op-alert-danger' : 'op-sr-only'}>
        {copyNote.text}
      </p>
      <div className="op-card">
        <table className="table op-table" data-failures>
          <caption className="op-sr-only">{failuresCaption(flowLabel, day)}</caption>
          <thead>
            <tr>
              {failureColumns(showFlow).map((column) => (
                <th scope="col" key={column}>
                  {column}
                </th>
              ))}
              {canOperate && <th scope="col">Action</th>}
            </tr>
          </thead>
          <tbody>
            {rows.map((row, i) => (
              <FailureRowView
                key={row.key}
                row={row}
                showFlow={showFlow}
                showAction={canOperate}
                action={failureAction(answered[i])}
                now={now}
                copied={copied === row.trackingId}
                onCopy={copyId}
                onDone={() =>
                  setRefresh((r) => ({ n: r.n + 1, acted: [...r.acted, failureKey(answered[i])] }))
                }
              />
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
