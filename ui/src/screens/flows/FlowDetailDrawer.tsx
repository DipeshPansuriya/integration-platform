import { useEffect, useState } from 'react';
import type { FlowDetailDrawerProps, FlowMessageRow } from './types';
import { Drawer } from '../../ds/components/surfaces/Drawer.jsx';
import { StatusBadge } from '../../ds/components/feedback/StatusBadge.jsx';
import { KeyValue } from '../../ds/components/data/KeyValue.jsx';
import { Pipeline } from '../../ds/components/data/Pipeline.jsx';
import { DecisionTable } from '../../ds/components/data/DecisionTable.jsx';
import { Button } from '../../ds/components/actions/Button.jsx';
import './flows.css';

const MESSAGE_COLUMNS = [
  { key: 'id', header: 'ID' },
  { key: 'time', header: 'Time' },
  { key: 'status', header: 'Status' },
  { key: 'latency', header: 'Latency' },
];

export default function FlowDetailDrawer({
  open = false,
  flow = null,
  messages = null,
  messagesLoading = false,
  messagesError = '',
  onRetryMessages,
  onClose,
  onPause,
  onEdit,
}: FlowDetailDrawerProps) {
  const [confirming, setConfirming] = useState(false);
  useEffect(() => {
    if (!open) setConfirming(false);
  }, [open]);

  const active = Boolean(flow && open);
  const messageState: 'loading' | 'error' | 'ready' | 'empty' = messagesLoading
    ? 'loading'
    : messagesError
      ? 'error'
      : messages?.length
        ? 'ready'
        : 'empty';

  return (
    <Drawer
      open={open}
      title="Flow detail"
      onClose={onClose}
      footer={
        flow ? (
          <div className="lb-foot">
            <Button data-action="close" onClick={onClose}>
              Close
            </Button>
            {flow.status === 'Active' && (
              <Button data-action="pause-flow" onClick={() => setConfirming(true)}>
                Pause Flow
              </Button>
            )}
            <Button
              variant="primary"
              className="lb-foot-primary"
              data-action="edit-flow"
              onClick={onEdit ? () => onEdit(flow) : undefined}
            >
              Edit Flow
            </Button>
          </div>
        ) : (
          <div className="lb-foot">
            <Button variant="primary" data-action="close" onClick={onClose}>
              Close
            </Button>
          </div>
        )
      }
    >
      {!flow && active && <p className="lb-muted">No flow selected.</p>}
      {flow && (
        <>
          <h3 className="ip-drawer-label">Overview</h3>
          <div className="lb-form">
            <div className="lb-row" data-field="status">
              <span className="lb-lab">Status</span>
              <StatusBadge status={flow.status} />
            </div>
            <KeyValue label="Last Run" value={flow.lastRun ?? '—'} />
            <KeyValue
              label="Messages Today"
              value={flow.messagesToday != null ? <span className="lb-num">{flow.messagesToday}</span> : '—'}
            />
            <KeyValue label="Success Rate" value={flow.successRate ?? '—'} />
            <KeyValue label="Workers" value={flow.workers != null ? flow.workers : '—'} />
          </div>

          <h3 className="ip-drawer-label">Pipeline</h3>
          <Pipeline
            stages={[
              { label: flow.sourceSystem ?? 'Source', role: 'source' },
              { label: 'Validate', role: 'validate' },
              { label: 'Transform', role: 'transform' },
              { label: flow.targetSystem ?? 'Target', role: 'target' },
            ]}
          />

          <h3 className="ip-drawer-label">Configuration</h3>
          <div className="lb-form">
            <KeyValue label="Source System" value={flow.sourceSystem ?? '—'} />
            <KeyValue label="Target System" value={flow.targetSystem ?? '—'} />
            <KeyValue label="Contract" value={flow.contract ?? '—'} />
            <KeyValue label="Mapping" value={flow.mapping ?? '—'} />
            <KeyValue label="Rule" value={flow.rule ?? '—'} />
          </div>

          <h3 className="ip-drawer-label">Recent Messages</h3>
          <div data-region="recent-messages" data-state={messageState}>
            {messageState === 'loading' && (
              <div>
                <div className="lb-skel lb-skel-row" />
                <div className="lb-skel lb-skel-row" />
                <div className="lb-skel lb-skel-row" />
              </div>
            )}
            {messageState === 'error' && (
              <div className="lb-errorbar" role="alert" data-error="messages">
                <p className="lb-muted">{messagesError}</p>
                <Button data-action="retry-messages" onClick={onRetryMessages}>
                  Retry
                </Button>
              </div>
            )}
            {messageState === 'empty' && <p className="lb-muted">No recent messages.</p>}
            {messageState === 'ready' && <MessageTable messages={messages as FlowMessageRow[]} />}
          </div>

          {confirming && (
            <div className="lb-confirm-scrim" data-confirm="pause-flow">
              <div className="lb-confirm" role="dialog" aria-label="Pause flow">
                <h3>Pause this flow?</h3>
                <p className="lb-muted">The flow stops processing messages until it is resumed.</p>
                <div className="lb-confirm-foot">
                  <Button data-action="cancel-pause" onClick={() => setConfirming(false)}>
                    Cancel
                  </Button>
                  <Button
                    variant="danger"
                    data-action="confirm-pause"
                    onClick={() => {
                      setConfirming(false);
                      onPause?.(flow);
                    }}
                  >
                    Pause Flow
                  </Button>
                </div>
              </div>
            </div>
          )}
        </>
      )}
    </Drawer>
  );
}

function MessageTable({ messages }: { messages: FlowMessageRow[] }) {
  const rows = messages.map((m) => ({
    id: <span className="lb-code">{m.id}</span>,
    time: m.time,
    status: <StatusBadge status={m.status} />,
    latency: <span className="lb-num">{m.latency}</span>,
  }));
  return <DecisionTable columns={MESSAGE_COLUMNS} rows={rows} />;
}