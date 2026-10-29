// Console wiring: the Failures container (#/failures). Same reads and behaviour as pages/Failures.tsx -
// messageFailures per flow / UTC day (filters pre-filled from #/failures?flow=&date=), listFlows for the
// flow filter, Replay through OperatorActionButton (confirm step, server refusal worded beside it, silent
// re-read that keeps acted rows), copy tracking id, link to #/messages/<trackingId>. screens/failures/
// FailuresScreen carries the header (unresolved count), the loading skeleton and the error note. The
// failures API has no retry counters and there is no bulk-retry / escalate endpoint, so the screen's table
// (Retries, Retry / Escalate) and its Retry All action are hidden; the old table renders below instead.

import { useEffect, useRef, useState } from 'react';
import { listFlows, type FlowSummary } from '../../api/flows';
import { messageFailures, type Failure } from '../../api/messages';
import { OperatorActionButton } from '../../pages/OperatorActionButton';
import { NoAccess } from '../../ui/NoAccess';
import { isNoAccess } from '../../ui/no-access';
import { failureAction, type OperatorAction } from '../../pages/operator-actions';
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
} from '../../pages/failures-view';
import { FailuresScreen, type FailureRow as ScreenFailureRow } from '../../screens/failures/FailuresScreen';
import './console-pages';
import '../../pages/operator.css';

type Load = 'loading' | 'ready' | 'refused' | 'error';

const ERROR_COPY = 'The failures list could not be loaded. Reload to try again.';

function initialParams() {
  return failuresParamsFromHash(typeof window === 'undefined' ? '' : window.location.hash);
}

/** The screen's row: only fields the API answers. Retry counters do not exist, so the screen table stays hidden. */
function screenRows(rows: readonly FailureRow[]): ScreenFailureRow[] {
  return rows.map((row) => ({
    id: row.key,
    code: row.errorClass,
    message: row.lastErrorText,
    flow: row.flowLabel,
    time: row.failedAtText,
    retriesUsed: 0,
    retriesMax: 0
  }));
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

export function FailuresPage({ canOperate = true }: { canOperate?: boolean }) {
  const [flows, setFlows] = useState<FlowSummary[]>([]);
  const [flowId, setFlowId] = useState(() => initialParams().flowId);
  const [date, setDate] = useState(() => initialParams().date ?? todayUtc(new Date()));
  const [answered, setAnswered] = useState<Failure[]>([]);
  const [load, setLoad] = useState<Load>('loading');
  const [refusal, setRefusal] = useState('');
  const [noAccess, setNoAccess] = useState(false);
  const [reloads, setReloads] = useState(0);
  const [refresh, setRefresh] = useState<{ n: number; acted: string[] }>({ n: 0, acted: [] });
  const [copied, setCopied] = useState('');
  const [copyNote, setCopyNote] = useState<{ text: string; failed: boolean }>({ text: '', failed: false });
  const copyTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const loadSeq = useRef(0);

  useEffect(() => {
    void listFlows()
      .then((answer) => {
        if (answer.ok) setFlows(answer.data);
      })
      .catch(() => undefined);
  }, []);

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

  // The silent refresh after an accepted replay: no loading state, no clearing; a failed re-read leaves the list as it was.
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
  const showFlow = !query.flowId;
  const rows = failureRows(answered, flowLabels(flows));
  const now = new Date();

  const statusText =
    load === 'loading'
      ? 'Loading failures...'
      : load === 'refused'
        ? refusal
        : load === 'error'
          ? ERROR_COPY
          : rows.length === 0
            ? emptyText(flowLabel, day)
            : `${rows.length} failed ${rows.length === 1 ? 'message' : 'messages'}.`;
  const designedEmpty = load === 'ready' && rows.length === 0;
  const screenError = load === 'refused' && !noAccess ? refusal : load === 'error' ? ERROR_COPY : null;

  return (
    <div data-page="failures" data-failures-loaded={load === 'loading' ? 'false' : 'true'} data-load={load} className="operator-page">
      <nav aria-label="Messages views" data-message-tabs className="op-tabs">
        <a href="#/messages">Results</a>
        <a href="#/delivered">Delivered</a>
        <a href="#/failures" aria-current="page">Needs attention</a>
      </nav>
      <FailuresScreen
        className={['cp-hide-ph-actions', load === 'ready' && 'cp-hide-panel', load === 'refused' && noAccess && 'cp-header-only'].filter(Boolean).join(' ')}
        failures={load === 'ready' ? screenRows(rows) : []}
        canOperate={false}
        loading={load === 'loading'}
        error={screenError}
        onReload={() => setReloads((n) => n + 1)}
      />
      <div className="cp-below">
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
          <button type="button" className="btn" data-action="reload-failures" data-state={load === 'loading' ? 'busy' : 'ready'}
            aria-busy={load === 'loading'} onClick={() => setReloads((n) => n + 1)}>
            Reload
          </button>
        </div>
        {/* One persistent live region (announces loading / empty / refused / error); the screen shows them visually,
            so it is visible only for the filter-aware empty text. */}
        {load === 'refused' && noAccess ? (
          <NoAccess refusal="failures-refused" />
        ) : (
          <p
            role={load === 'refused' || load === 'error' ? 'alert' : 'status'}
            className={designedEmpty ? 'op-empty' : 'op-sr-only'}
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
    </div>
  );
}
