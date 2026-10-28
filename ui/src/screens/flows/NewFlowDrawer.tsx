import { useState } from 'react';
import type { FlowDraft, FlowOption, FlowRetryDelay, FlowTrigger, NewFlowDrawerProps } from './types';
import { DEFAULT_FLOW_DRAFT } from './types';
import { Drawer } from '../../ds/components/surfaces/Drawer.jsx';
import { Select } from '../../ds/components/forms/Select.jsx';
import { Switch } from '../../ds/components/forms/Switch.jsx';
import { Button } from '../../ds/components/actions/Button.jsx';
import { Callout } from '../../ds/components/feedback/Callout.jsx';
import './flows.css';

const TRIGGERS: FlowTrigger[] = ['Message Received', 'Scheduled (Cron)', 'Webhook', 'File Drop'];
const RETRY_DELAYS: FlowRetryDelay[] = ['Exponential backoff', 'Fixed 30s', 'Fixed 60s'];

const opt = (values: FlowOption[]) => [{ value: '', label: '— Select —' }, ...values.map((v) => ({ value: v.id, label: v.name }))];

export default function NewFlowDrawer({
  open = false,
  systems = [],
  contracts = [],
  mappings = [],
  rules = [],
  draft = null,
  onClose,
  onSaveDraft,
  onCreateDeploy,
}: NewFlowDrawerProps) {
  const [form, setForm] = useState<FlowDraft>({ ...DEFAULT_FLOW_DRAFT, ...(draft ?? {}) });
  const [attempted, setAttempted] = useState(false);
  const set = (patch: Partial<FlowDraft>) => setForm((f) => ({ ...f, ...patch }));
  const nameMissing = !form.name.trim();

  const submit = (kind: 'draft' | 'deploy') => {
    setAttempted(true);
    if (nameMissing) return;
    const payload: FlowDraft = {
      ...form,
      name: form.name.trim(),
      sourceSystemId: opt_(form.sourceSystemId),
      contractId: opt_(form.contractId),
      mappingId: opt_(form.mappingId),
      ruleId: opt_(form.ruleId),
      targetSystemId: opt_(form.targetSystemId),
    };
    if (kind === 'draft') onSaveDraft?.(payload);
    else onCreateDeploy?.(payload);
  };

  return (
    <Drawer
      open={open}
      title="New Flow"
      onClose={onClose}
      footer={
        <div className="lb-foot">
          <Button data-action="cancel" onClick={onClose}>
            Cancel
          </Button>
          <Button data-action="save-draft" onClick={() => submit('draft')}>
            Save as Draft
          </Button>
          <Button variant="primary" className="lb-foot-primary" data-action="create-deploy" onClick={() => submit('deploy')}>
            Create &amp; Deploy
          </Button>
        </div>
      }
    >
      <div className="lb-form">
        {attempted && nameMissing && (
          <div role="alert" data-validation="flow-name">
            <Callout tone="err" title="Flow Name is required.">
              Give the flow a name before saving or deploying.
            </Callout>
          </div>
        )}

        <h3 className="ip-drawer-label">Flow Details</h3>
        <div className="lb-row">
          <label className="lb-lab" htmlFor="nf-name">
            Flow Name
          </label>
          <input
            id="nf-name"
            className="ip-input"
            value={form.name}
            onChange={(e) => set({ name: e.target.value })}
            autoComplete="off"
          />
        </div>
        <div className="lb-row">
          <label className="lb-lab" htmlFor="nf-description">
            Description
          </label>
          <input
            id="nf-description"
            className="ip-input"
            value={form.description ?? ''}
            onChange={(e) => set({ description: e.target.value })}
            autoComplete="off"
          />
        </div>

        <h3 className="ip-drawer-label">Source</h3>
        <div className="lb-row">
          <label className="lb-lab" htmlFor="nf-source">
            Source System
          </label>
          <Select id="nf-source" value={form.sourceSystemId ?? ''} options={opt(systems)} onChange={(e) => set({ sourceSystemId: e.target.value })} />
        </div>
        <div className="lb-row">
          <label className="lb-lab" htmlFor="nf-trigger">
            Trigger
          </label>
          <Select id="nf-trigger" value={form.trigger} options={TRIGGERS} onChange={(e) => set({ trigger: e.target.value as FlowTrigger })} />
        </div>
        <div className="lb-row">
          <label className="lb-lab" htmlFor="nf-contract">
            Contract
          </label>
          <Select id="nf-contract" value={form.contractId ?? ''} options={opt(contracts)} onChange={(e) => set({ contractId: e.target.value })} />
        </div>
        <div className="lb-row">
          <label className="lb-lab" htmlFor="nf-rule">
            Rule
          </label>
          <Select
            id="nf-rule"
            value={form.ruleId ?? ''}
            options={[{ value: '', label: 'None' }, ...rules.map((r) => ({ value: r.id, label: r.name }))]}
            onChange={(e) => set({ ruleId: e.target.value })}
          />
        </div>

        <h3 className="ip-drawer-label">Processing</h3>
        <div className="lb-row">
          <label className="lb-lab" htmlFor="nf-mapping">
            Mapping
          </label>
          <Select id="nf-mapping" value={form.mappingId ?? ''} options={opt(mappings)} onChange={(e) => set({ mappingId: e.target.value })} />
        </div>
        <div className="lb-row">
          <label className="lb-lab" htmlFor="nf-retry-count">
            Retry Count
          </label>
          <input
            id="nf-retry-count"
            className="ip-input"
            type="number"
            min={0}
            value={String(form.retryCount)}
            onChange={(e) => set({ retryCount: Number(e.target.value) || 0 })}
          />
        </div>
        <div className="lb-row">
          <label className="lb-lab" htmlFor="nf-retry-delay">
            Retry Delay
          </label>
          <Select id="nf-retry-delay" value={form.retryDelay} options={RETRY_DELAYS} onChange={(e) => set({ retryDelay: e.target.value as FlowRetryDelay })} />
        </div>

        <h3 className="ip-drawer-label">Target</h3>
        <div className="lb-row">
          <label className="lb-lab" htmlFor="nf-target">
            Target System
          </label>
          <Select id="nf-target" value={form.targetSystemId ?? ''} options={opt(systems)} onChange={(e) => set({ targetSystemId: e.target.value })} />
        </div>

        <h3 className="ip-drawer-label">Error Handling</h3>
        <div className="lb-row">
          <Switch
            label="Enable dead-letter queue"
            checked={form.deadLetterQueue}
            onChange={(on) => set({ deadLetterQueue: on })}
          />
        </div>
        <div className="lb-row">
          <Switch label="Alert on failure" checked={form.alertOnFailure} onChange={(on) => set({ alertOnFailure: on })} />
        </div>
      </div>
    </Drawer>
  );
}

const opt_ = (v?: string) => (v ? v : undefined);