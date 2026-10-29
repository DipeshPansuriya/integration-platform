import { useEffect, useRef, useState } from 'react';
import { listFlows, type FlowSummary } from '../api/flows';
import { messageDelivered, type Delivered } from '../api/messages';
import { NoAccess } from '../ui/NoAccess';
import { isNoAccess } from '../ui/no-access';
import { messageHash, utcText } from './message-timeline';
import './operator.css';

type Load = 'loading' | 'ready' | 'refused' | 'error';

function todayUtc(now: Date): string {
  return now.toISOString().slice(0, 10);
}

function flowLabels(flows: readonly { id: string; code: string }[]): Record<string, string> {
  const labels: Record<string, string> = {};
  for (const f of flows) labels[f.id] = f.code;
  return labels;
}

function relativeAge(iso: string, now: Date): string {
  const ms = now.getTime() - new Date(iso).getTime();
  if (ms < 60_000) return 'just now';
  const min = Math.floor(ms / 60_000);
  if (min < 60) return `${min}m ago`;
  const h = Math.floor(min / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.floor(h / 24)}d ago`;
}

function DeliveredRow({ row, showFlow, now, copied, onCopy }: {
  row: Delivered;
  showFlow: boolean;
  now: Date;
  copied: boolean;
  onCopy: (trackingId: string) => void;
}) {
  return (
    <tr data-delivered-row={row.trackingId}>
      <td>
        <span className="op-id">
          <a href={messageHash(row.trackingId, null)} data-message-link={row.trackingId} className="op-mono" title={row.trackingId}>
            {row.trackingId}
          </a>
          <button type="button" className="op-copy" data-copy-id={row.trackingId} aria-label={`Copy tracking id ${row.trackingId}`} onClick={() => onCopy(row.trackingId)}>
            {copied ? 'Copied' : 'Copy'}
          </button>
        </span>
      </td>
      {showFlow && <td data-flow-cell>{row.flowId}</td>}
      <td>{row.targetId ?? '—'}</td>
      <td><span className="badge badge-success">{row.outcome ?? 'SUCCESS'}</span></td>
      <td>
        <time dateTime={row.deliveredAt} className="op-mono">{utcText(row.deliveredAt)}</time>
        <div className="op-sub">{relativeAge(row.deliveredAt, now)}</div>
      </td>
    </tr>
  );
}

export function DeliveredMessages() {
  const [flows, setFlows] = useState<FlowSummary[]>([]);
  const [flowId, setFlowId] = useState('');
  const [date, setDate] = useState(() => todayUtc(new Date()));
  const [items, setItems] = useState<Delivered[]>([]);
  const [load, setLoad] = useState<Load>('loading');
  const [refusal, setRefusal] = useState('');
  const [noAccess, setNoAccess] = useState(false);
  const [reloads, setReloads] = useState(0);
  const [copied, setCopied] = useState('');
  const [copyNote, setCopyNote] = useState<{ text: string; failed: boolean }>({ text: '', failed: false });
  const copyTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const labels = flowLabels(flows);

  useEffect(() => {
    void listFlows()
      .then((answer) => { if (answer.ok) setFlows(answer.data); })
      .catch(() => undefined);
  }, []);

  useEffect(() => {
    let current = true;
    setItems([]);
    setLoad('loading');
    const query: { flowId?: string; date?: string } = {};
    if (flowId) query.flowId = flowId;
    if (date) query.date = date;
    messageDelivered(query)
      .then((answer) => {
        if (!current) return;
        if (answer.ok) {
          setItems(answer.data);
          setNoAccess(false);
          setLoad('ready');
        } else {
          setItems([]);
          setRefusal(answer.refusal ?? answer.error ?? 'delivered list refused');
          setNoAccess(isNoAccess(answer.status));
          setLoad('refused');
        }
      })
      .catch(() => {
        if (!current) return;
        setItems([]);
        setNoAccess(false);
        setLoad('error');
      });
    return () => { current = false; };
  }, [flowId, date, reloads]);

  useEffect(() => () => {
    if (copyTimer.current !== null) clearTimeout(copyTimer.current);
  }, []);

  function copyId(trackingId: string) {
    const failed = () => setCopyNote({ text: 'Could not copy automatically. Select the tracking id and copy it by hand.', failed: true });
    if (typeof navigator === 'undefined' || !navigator.clipboard?.writeText) { failed(); return; }
    void navigator.clipboard.writeText(trackingId).then(
      () => {
        setCopied(trackingId);
        setCopyNote({ text: `Copied tracking id ${trackingId}.`, failed: false });
        if (copyTimer.current !== null) clearTimeout(copyTimer.current);
        copyTimer.current = setTimeout(() => { setCopied(''); setCopyNote({ text: '', failed: false }); }, 1500);
      },
      failed
    );
  }

  const showFlow = !flowId;
  const now = new Date();
  const columns = showFlow ? ['Tracking id', 'Flow', 'Target', 'Outcome', 'Delivered at'] : ['Tracking id', 'Target', 'Outcome', 'Delivered at'];

  const statusText =
    load === 'loading' ? 'Loading delivered messages...'
    : load === 'refused' ? refusal
    : load === 'error' ? 'The delivered list could not be loaded. Reload to try again.'
    : items.length === 0 ? 'No delivered messages found for this filter.'
    : `${items.length} delivered ${items.length === 1 ? 'message' : 'messages'}.`;
  const designedEmpty = load === 'ready' && items.length === 0;

  return (
    <div data-page="delivered" data-load={load} className="operator-page">
      <header className="op-head">
        <div>
          <h1>Delivered</h1>
          <p className="op-lead">Successfully delivered messages per flow and UTC day. Open a tracking id to see its full journey.</p>
        </div>
        <button type="button" data-action="reload-delivered" data-state={load === 'loading' ? 'busy' : 'ready'}
          aria-busy={load === 'loading'} onClick={() => setReloads((n) => n + 1)}>
          Reload
        </button>
      </header>
      <nav aria-label="Messages views" data-message-tabs className="op-tabs">
        <a href="#/messages">Results</a>
        <a href="#/delivered" aria-current="page">Delivered</a>
        <a href="#/failures">Needs attention</a>
      </nav>
      <div className="op-toolbar">
        <label htmlFor="delivered-flow">
          Flow
          <select id="delivered-flow" data-field="delivered-flow" value={flowId} onChange={(e) => setFlowId(e.target.value)}>
            <option value="">All flows</option>
            {flows.map((f) => (
              <option key={f.id} value={f.id}>
                {f.name ? `${f.code} - ${f.name}` : f.code}
              </option>
            ))}
          </select>
        </label>
        <label htmlFor="delivered-date">
          Day (UTC, empty for all days)
          <input id="delivered-date" type="date" data-field="delivered-date" value={date} onChange={(e) => setDate(e.target.value)} />
        </label>
      </div>
      {load === 'refused' && noAccess ? (
        <NoAccess refusal="delivered-refused" />
      ) : (
        <p
          role={load === 'refused' || load === 'error' ? 'alert' : 'status'}
          className={designedEmpty ? 'op-empty' : load === 'refused' || load === 'error' ? 'op-alert op-alert-danger' : 'op-status'}
          data-status="delivered"
          data-empty={designedEmpty ? 'delivered-none' : undefined}
        >
          {statusText}
        </p>
      )}
      <p role="status" data-copy-status className={copyNote.failed ? 'op-alert op-alert-danger' : 'op-sr-only'}>
        {copyNote.text}
      </p>
      <div className="op-card">
        <table className="table op-table" data-delivered>
          <caption className="op-sr-only">Delivered messages{flowId ? ` for flow ${labels[flowId] ?? flowId}` : ''}{date ? ` on ${date}` : ''}</caption>
          <thead>
            <tr>
              {columns.map((col) => <th scope="col" key={col}>{col}</th>)}
            </tr>
          </thead>
          <tbody>
            {items.map((row) => (
              <DeliveredRow
                key={`${row.deliveryId}-${row.trackingId}`}
                row={{ ...row, flowId: labels[row.flowId] ?? row.flowId }}
                showFlow={showFlow}
                now={now}
                copied={copied === row.trackingId}
                onCopy={copyId}
              />
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
