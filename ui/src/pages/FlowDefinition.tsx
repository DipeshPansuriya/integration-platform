// T-017 (E3 lane D): the flow-definition screen (E3-F1-S1). A configurator defines a flow
// naming its source system and destination(s) as configuration rows, with the interface's
// mandatory business-key rule and ordering setting (PER_KEY default, ADR-011), and the draft
// save sends the required sourceSchema too (E3-F3-S1) - every write through the typed client
// to the real API, every refusal surfaced, nothing invented.
// Row 310 lane B (T88, 10-screens §3): a 2-pane form with a sticky action bar. Opening the editor
// never creates a flow and never hydrates from browser storage - the list landing (#/flows) picks the
// flow; #/flow/new is create mode. Leaving with unsaved edits asks first.

import { useEffect, useState, type FormEvent, type MouseEvent as ReactMouseEvent } from 'react';
import {
  createFlow,
  readFlow,
  saveFlowDraft,
  saveSourceSchema,
  validationFieldOf,
  type FlowDraftBody
} from '../api/flows';
import { editorFlowId, flowHash, isDirty, type FlowForm } from './flow-lifecycle';
import './flows.css';

export type FlowDefinitionProps = {
  /** Deep link (E3-F1-S1 @negative @tenant): open one flow by id - the real API answers 404 for another tenant's row. */
  openFlowId?: string | null;
};

const MIN_DESTINATIONS = 2; // E3-F1-S1: source S with destinations T1/T2 as configuration rows.

const EMPTY: FlowForm = {
  sourceSystem: '',
  destinations: Array(MIN_DESTINATIONS).fill(''),
  interfaceName: '',
  businessKeyRule: '',
  ordering: 'PER_KEY', // ADR-011 default
  sourceSchema: ''
};

const errorId = (field: string) => `flow-error-${field}`;

export function FlowDefinition({ openFlowId = null }: FlowDefinitionProps) {
  const [form, setForm] = useState<FlowForm>(EMPTY);
  const [baseline, setBaseline] = useState<FlowForm>(EMPTY);
  const [flowId, setFlowId] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [validationField, setValidationField] = useState<string | null>(null);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [leaveTo, setLeaveTo] = useState<string | null>(null);

  const dirty = isDirty(baseline, form);
  const set = (patch: Partial<FlowForm>) => {
    setSaved(false); // an edit after a save is unsaved again
    setForm((current) => ({ ...current, ...patch }));
  };

  const hydrate = async (id: string) => {
    const read = await readFlow(id);
    if (!read.ok) {
      // 404 is the tenant-isolation answer (ADR-011 Decision 4): never a hint of whose row it is.
      if (read.status === 404) setRefusal('flow-not-found');
      return;
    }
    const rows = [...read.data.destinations];
    while (rows.length < MIN_DESTINATIONS) rows.push('');
    const loaded: FlowForm = {
      sourceSystem: read.data.sourceSystem,
      destinations: rows,
      interfaceName: read.data.name,
      businessKeyRule: read.data.businessKeyRule,
      ordering: read.data.ordering,
      sourceSchema: ''
    };
    setFlowId(id);
    setForm(loaded);
    setBaseline(loaded);
  };

  useEffect(() => {
    const id = editorFlowId(openFlowId);
    if (id) void hydrate(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [openFlowId]);

  // Unsaved changes: an in-app link away from the editor asks first; a reload or tab close gets the browser's prompt.
  useEffect(() => {
    if (!dirty) return;
    const onClick = (event: MouseEvent) => {
      const link = (event.target as Element | null)?.closest?.('a[href^="#"]') as HTMLAnchorElement | null;
      if (!link || link.closest('[data-page="flow-definition"]')) return;
      event.preventDefault();
      event.stopPropagation();
      setLeaveTo(link.getAttribute('href'));
    };
    const onUnload = (event: BeforeUnloadEvent) => event.preventDefault();
    document.addEventListener('click', onClick, true);
    window.addEventListener('beforeunload', onUnload);
    return () => {
      document.removeEventListener('click', onClick, true);
      window.removeEventListener('beforeunload', onUnload);
    };
  }, [dirty]);

  const onBack = (event: ReactMouseEvent) => {
    if (!dirty) return;
    event.preventDefault();
    setLeaveTo('#/flows');
  };

  const leave = () => {
    const to = leaveTo;
    setLeaveTo(null);
    setBaseline(form); // the user chose to drop the edits
    if (to) window.location.hash = to;
  };

  const onDestination = (index: number, value: string) =>
    set({ destinations: form.destinations.map((row, position) => (position === index ? value : row)) });
  const addDestination = () => set({ destinations: [...form.destinations, ''] });
  const removeDestination = (index: number) => set({ destinations: form.destinations.filter((_, position) => position !== index) });

  const body = (): FlowDraftBody => ({
    sourceSystem: form.sourceSystem,
    destinations: form.destinations.map((row) => row.trim()).filter((row) => row.length > 0),
    businessKeyRule: form.businessKeyRule,
    name: form.interfaceName.trim() // F-1: the interface name typed by the user rides on the create
  });

  const onSaveDraft = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setSaved(false);
    setValidationField(null);
    setRefusal(null);
    try {
      const draft = body();
      const result = flowId
        ? await saveFlowDraft(flowId, { ...draft, ordering: form.ordering })
        : await createFlow({ ...draft, ordering: form.ordering });
      if (!result.ok) {
        if (result.status === 400) setValidationField(validationFieldOf(result.error));
        else if (result.status === 404) setRefusal('flow-not-found');
        return;
      }
      const id = flowId ?? (result.data as { id: string }).id;
      if (!flowId) {
        setFlowId(id);
        // The address names the flow now (a reload re-opens it) without a hashchange re-render.
        window.history.replaceState(null, '', flowHash(id));
      }

      if (form.sourceSchema.trim().length > 0) {
        let schema: unknown;
        try {
          schema = JSON.parse(form.sourceSchema);
        } catch {
          setValidationField('source-schema');
          return;
        }
        const stored = await saveSourceSchema(form.sourceSystem, form.interfaceName || 'interface', schema);
        if (!stored.ok && stored.status === 400) {
          setValidationField(validationFieldOf(stored.error));
          return;
        }
      }
      setBaseline(form);
      setSaved(true);
    } finally {
      setBusy(false);
    }
  };

  const describedBy = (field: string) => (validationField === field ? errorId(field) : undefined);

  return (
    <main data-page="flow-definition" className="flows-page">
      <div className="flows-head">
        <h2>Flow definition</h2>
        <a href="#/flows" data-action="back-to-flows" onClick={onBack}>
          All flows
        </a>
      </div>

      {leaveTo !== null && (
        <div role="alertdialog" aria-labelledby="flow-leave-title" className="flows-alert flows-alert-warn" data-confirm="unsaved-changes">
          <p id="flow-leave-title">You have unsaved changes. Leave without saving?</p>
          <div className="flows-alert-actions">
            <button type="button" className="btn" data-action="stay" onClick={() => setLeaveTo(null)} autoFocus>
              Stay
            </button>
            <button type="button" className="btn btn-primary" data-action="leave-without-saving" onClick={leave}>
              Leave without saving
            </button>
          </div>
        </div>
      )}

      {refusal === 'flow-not-found' ? (
        <div role="alert" className="flows-card flows-alert-danger" data-refusal="flow-not-found">
          <p>This flow does not exist for your tenant.</p>
          <a href="#/flows">Back to the flow list</a>
        </div>
      ) : (
        <form className="flow-form" onSubmit={(event) => void onSaveDraft(event)}>
          <div className="flow-form-grid">
            <div className="flow-form-col">
              <fieldset className="flows-card">
                <legend>Identity</legend>
                <label className="flows-field">
                  Interface name
                  <input data-field="interface-name" value={form.interfaceName} onChange={(e) => set({ interfaceName: e.target.value })} />
                </label>
                <label className="flows-field">
                  Source system
                  <input
                    data-field="source-system"
                    value={form.sourceSystem}
                    aria-describedby={describedBy('source-system')}
                    onChange={(e) => set({ sourceSystem: e.target.value })}
                  />
                </label>
                {validationField === 'source-system' && (
                  <p className="flows-error" id={errorId('source-system')} data-validation-error="source-system">sourceSystem is required</p>
                )}
              </fieldset>
              <fieldset className="flows-card">
                <legend>Destinations</legend>
                {form.destinations.map((row, index) => (
                  <div className="flow-destination" key={index}>
                    <label className="flows-field">
                      Destination {index + 1}
                      <input data-field={`destination-${index + 1}`} value={row} onChange={(e) => onDestination(index, e.target.value)} />
                    </label>
                    {form.destinations.length > 1 && (
                      <button
                        type="button"
                        className="btn"
                        data-action={`remove-destination-${index + 1}`}
                        aria-label={`Remove destination ${index + 1}`}
                        onClick={() => removeDestination(index)}
                      >
                        Remove
                      </button>
                    )}
                  </div>
                ))}
                <button type="button" className="btn" data-action="add-destination" onClick={addDestination}>
                  Add destination
                </button>
              </fieldset>
            </div>
            <div className="flow-form-col">
              <fieldset className="flows-card">
                <legend>Contracts and schema</legend>
                <label className="flows-field">
                  Business-key rule (mandatory)
                  <input
                    data-field="business-key-rule"
                    value={form.businessKeyRule}
                    aria-describedby={describedBy('business-key-rule')}
                    onChange={(e) => set({ businessKeyRule: e.target.value })}
                  />
                </label>
                {validationField === 'business-key-rule' && (
                  <p className="flows-error" id={errorId('business-key-rule')} data-validation-error="business-key-rule">
                    The mandatory business-key rule is missing: businessKeyRule
                  </p>
                )}
                <label className="flows-field">
                  Ordering
                  <select
                    data-field="ordering-setting"
                    value={form.ordering}
                    aria-describedby="flow-ordering-help"
                    onChange={(e) => set({ ordering: e.target.value })}
                  >
                    <option value="PER_KEY">PER_KEY</option>
                    <option value="NONE">NONE</option>
                  </select>
                </label>
                <p className="flows-muted" id="flow-ordering-help">Rows are grouped per key before they move</p>
                <label className="flows-field">
                  Source schema (JSON Schema)
                  <textarea
                    data-field="source-schema"
                    rows={8}
                    value={form.sourceSchema}
                    aria-describedby={describedBy('source-schema')}
                    onChange={(e) => set({ sourceSchema: e.target.value })}
                  />
                </label>
                {validationField === 'source-schema' && (
                  <p className="flows-error" id={errorId('source-schema')} data-validation-error="source-schema">
                    sourceSchema must be a JSON Schema object
                  </p>
                )}
              </fieldset>
            </div>
          </div>

          <div className="flow-actions">
            {saved && (
              <p className="flows-alert flows-alert-success" data-saved="true" role="status">
                Draft saved. <a href="#/versions">Submit it for approval</a> when you are ready.
              </p>
            )}
            {dirty && !saved && <span className="flows-muted">Unsaved changes</span>}
            <button type="submit" className="btn btn-primary" data-action="save-draft" disabled={busy} aria-busy={busy}>
              {busy ? 'Saving...' : 'Save draft'}
            </button>
          </div>
        </form>
      )}
    </main>
  );
}
