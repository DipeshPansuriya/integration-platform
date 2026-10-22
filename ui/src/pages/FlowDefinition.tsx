// T-017 (E3 lane D): the flow-definition screen (E3-F1-S1). A configurator defines a flow
// naming its source system and destination(s) as configuration rows, with the interface's
// mandatory business-key rule and ordering setting (PER_KEY default, ADR-011), and the draft
// save sends the required sourceSchema too (E3-F3-S1) - every write through the typed client
// to the real API, every refusal surfaced, nothing invented.
// Row 310 lane B (T88, 10-screens §3): a 2-pane form with a sticky action bar. Opening the editor
// never creates a flow and never hydrates from browser storage - the list landing (#/flows) picks the
// flow; #/flow/new is create mode. Leaving with unsaved edits asks first.
// Row 310 B11-B13 (vijay-developer, lane D): the shell remounts this editor per address change (App.tsx key);
// Back and a typed #/ address ask through the shell's guard (EditorGuard); the leave dialog is modal,
// closes on Escape and returns focus; every failing field shows its error text and is aria-invalid.

import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent as ReactKeyboardEvent, type MutableRefObject, type MouseEvent as ReactMouseEvent } from 'react';
import { createFlow, readFlow, saveFlowDraft, saveSourceSchema, validationFieldOf } from '../api/flows';
import { loadFlowForm, saveFlowDefinition } from './flow-save';
import {
  editorFlowId,
  fieldA11y,
  flowHash,
  focusSelectorFor,
  isDirty,
  trapTab,
  validationErrorId,
  validationText,
  type EditorGuardState,
  type FlowForm
} from './flow-lifecycle';
import './flows.css';

/** B12: what the shell asks the open editor before an address change unmounts it. */
export type EditorGuard = { state: () => EditorGuardState; confirm: (to: string) => void };

export type FlowDefinitionProps = {
  /** Deep link (E3-F1-S1 @negative @tenant): open one flow by id - the real API answers 404 for another tenant's row. */
  openFlowId?: string | null;
  /** B12: the shell's slot for this editor's guard (Back, Forward, a typed #/ address). */
  guard?: MutableRefObject<EditorGuard | null>;
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

export function FlowDefinition({ openFlowId = null, guard }: FlowDefinitionProps) {
  const [form, setForm] = useState<FlowForm>(EMPTY);
  const [baseline, setBaseline] = useState<FlowForm>(EMPTY);
  const [flowId, setFlowId] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [validationField, setValidationField] = useState<string | null>(null);
  const [validationServerText, setValidationServerText] = useState<string | null>(null);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // A1-A3: a worded failure of the open or the save; loadError blocks the form (no blank form to save).
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [leaveTo, setLeaveTo] = useState<string | null>(null);
  // B13: where focus goes back when the dialog closes - the link that opened it, else the heading.
  const opener = useRef<HTMLElement | null>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const dialog = useRef<HTMLDivElement>(null);
  // B12: true once the user chose Leave - the shell then lets the address change through.
  const released = useRef(false);

  const dirty = isDirty(baseline, form);
  const dirtyNow = useRef(dirty);
  dirtyNow.current = dirty;
  const set = (patch: Partial<FlowForm>) => {
    setSaved(false); // an edit after a save is unsaved again
    setForm((current) => ({ ...current, ...patch }));
  };

  const hydrate = async (id: string) => {
    setLoadError(null);
    const opened = await loadFlowForm(readFlow, id);
    if (opened.kind === 'not-found') {
      setRefusal('flow-not-found');
      return;
    }
    if (opened.kind === 'failed') {
      setLoadError(opened.text); // A3: flowId stays null AND the form is hidden, so Save cannot create a duplicate
      return;
    }
    setFlowId(id);
    setForm(opened.form);
    setBaseline(opened.form);
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
      opener.current = link;
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

  // B12: Back, Forward and a typed #/ address reach the shell's hashchange first - it asks this guard.
  useEffect(() => {
    if (!guard) return;
    const mine: EditorGuard = {
      state: () => ({ dirty: dirtyNow.current, released: released.current }),
      confirm: (to) => {
        opener.current = null; // no element opened it: focus goes back to the heading
        setLeaveTo(to);
      }
    };
    guard.current = mine;
    return () => {
      if (guard.current === mine) guard.current = null;
    };
  }, [guard]);

  const onBack = (event: ReactMouseEvent<HTMLAnchorElement>) => {
    if (!dirty) return;
    event.preventDefault();
    opener.current = event.currentTarget;
    setLeaveTo('#/flows');
  };

  const stay = () => {
    setLeaveTo(null);
    (opener.current ?? heading.current)?.focus();
  };

  // B13: Escape is Stay while the dialog shows.
  useEffect(() => {
    if (leaveTo === null) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') stay();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [leaveTo]);

  // A8: a failing field takes focus (its error text is a live alert), so keyboard users land on the fix.
  useEffect(() => {
    const selector = focusSelectorFor(validationField);
    if (selector) document.querySelector<HTMLElement>(selector)?.focus();
  }, [validationField, validationServerText]);

  // A8: Tab stays inside the leave dialog (Escape already means Stay).
  const onDialogKey = (event: ReactKeyboardEvent) => {
    if (event.key !== 'Tab') return;
    const buttons = Array.from(dialog.current?.querySelectorAll<HTMLElement>('button') ?? []);
    const next = trapTab(buttons.indexOf(document.activeElement as HTMLElement), buttons.length, event.shiftKey);
    if (next < 0) return;
    event.preventDefault();
    buttons[next].focus();
  };

  const leave = () => {
    const to = leaveTo;
    setLeaveTo(null);
    setBaseline(form); // the user chose to drop the edits
    released.current = true;
    if (to !== null) window.location.hash = to;
  };

  const onDestination = (index: number, value: string) =>
    set({ destinations: form.destinations.map((row, position) => (position === index ? value : row)) });
  const addDestination = () => set({ destinations: [...form.destinations, ''] });
  const removeDestination = (index: number) => set({ destinations: form.destinations.filter((_, position) => position !== index) });

  const onSaveDraft = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setSaved(false);
    setValidationField(null);
    setValidationServerText(null);
    setRefusal(null);
    setSaveError(null);
    try {
      const outcome = await saveFlowDefinition(
        { createFlow, saveFlowDraft, saveSourceSchema, validationFieldOf },
        flowId,
        form
      );
      // The draft exists even when the schema step failed: remember its id so the next Save updates it.
      const createdId = 'id' in outcome ? outcome.id : undefined;
      if (createdId && !flowId) {
        setFlowId(createdId);
        // The address names the flow now (a reload re-opens it) without a hashchange re-render.
        window.history.replaceState(null, '', flowHash(createdId));
      }
      if (outcome.kind === 'not-found') setRefusal('flow-not-found');
      else if (outcome.kind === 'validation') {
        setValidationField(outcome.field);
        setValidationServerText(outcome.text);
      } else if (outcome.kind === 'failed') setSaveError(outcome.text);
      else {
        setBaseline(form);
        setSaved(true);
      }
    } finally {
      setBusy(false);
    }
  };

  const a11y = (field: string) => fieldA11y(validationField, field);
  // B13: the visible error text a failing field's aria-describedby points at. B13 rework: it carries the
  // refusal's own sentence when the server gave one (an unknown system must not read 'sourceSystem is
  // required'); validationText's fixed sentence remains the fallback for a client-side refusal.
  const errorText = (field: string) =>
    validationField === field && (
      <p className="flows-error" id={validationErrorId(field)} role="alert" data-validation-error={field}>
        {validationServerText ?? validationText(field, validationServerText)}
      </p>
    );

  return (
    <div data-page="flow-definition" className="flows-page">
      <div className="flows-head">
        <h2 ref={heading} tabIndex={-1}>
          Flow definition
        </h2>
        <a href="#/flows" data-action="back-to-flows" onClick={onBack}>
          All flows
        </a>
      </div>

      {leaveTo !== null && (
        <div
          ref={dialog}
          onKeyDown={onDialogKey}
          role="alertdialog"
          aria-modal="true"
          aria-labelledby="flow-leave-title"
          className="flows-alert flows-alert-warn"
          data-confirm="unsaved-changes"
        >
          <p id="flow-leave-title">You have unsaved changes. Leave without saving?</p>
          <div className="flows-alert-actions">
            <button type="button" className="btn" data-action="stay" onClick={stay} autoFocus>
              Stay
            </button>
            <button type="button" className="btn btn-primary" data-action="leave-without-saving" onClick={leave}>
              Leave without saving
            </button>
          </div>
        </div>
      )}

      {loadError !== null ? (
        <div role="alert" className="flows-card flows-alert-danger" data-load-error="flow">
          <p>{loadError}</p>
          <button type="button" className="btn" data-action="retry-load" onClick={() => void hydrate(editorFlowId(openFlowId) ?? '')}>
            Retry
          </button>{' '}
          <a href="#/flows">Back to the flow list</a>
        </div>
      ) : refusal === 'flow-not-found' ? (
        <div role="alert" className="flows-card flows-alert-danger" data-refusal="flow-not-found">
          <p>This flow does not exist for your tenant.</p>
          <a href="#/flows">Back to the flow list</a>
        </div>
      ) : (
        <form className="flow-form" onSubmit={(event) => void onSaveDraft(event)}>
          {validationField === 'request' && (
            <p className="flows-error" role="alert" id={validationErrorId('request')} data-validation-error="request">
              {validationText('request', validationServerText)}
            </p>
          )}
          <p className="flows-muted" data-required-note="true">
            <span aria-hidden="true">*</span> required
          </p>
          <div className="flow-form-grid">
            <div className="flow-form-col">
              <fieldset className="flows-card">
                <legend>Identity</legend>
                <label className="flows-field">
                  Interface name
                  <input data-field="interface-name" value={form.interfaceName} onChange={(e) => set({ interfaceName: e.target.value })} />
                </label>
                <label className="flows-field">
                  Source system<span aria-hidden="true"> *</span>
                  <input
                    aria-required="true"
                    data-field="source-system"
                    value={form.sourceSystem}
                    {...a11y('source-system')}
                    onChange={(e) => set({ sourceSystem: e.target.value })}
                  />
                </label>
                {errorText('source-system')}
              </fieldset>
              <fieldset className="flows-card">
                <legend>Destinations</legend>
                {form.destinations.map((row, index) => (
                  <div className="flow-destination" key={index}>
                    <label className="flows-field">
                      Destination {index + 1}
                      {index === 0 && <span aria-hidden="true"> *</span>}
                      <input
                        aria-required="true"
                        data-field={`destination-${index + 1}`}
                        value={row}
                        {...a11y('destinations')}
                        onChange={(e) => onDestination(index, e.target.value)}
                      />
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
                {errorText('destinations')}
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
                    aria-required="true"
                    data-field="business-key-rule"
                    value={form.businessKeyRule}
                    {...a11y('business-key-rule')}
                    onChange={(e) => set({ businessKeyRule: e.target.value })}
                  />
                </label>
                {errorText('business-key-rule')}
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
                    {...a11y('source-schema')}
                    onChange={(e) => set({ sourceSchema: e.target.value })}
                  />
                </label>
                {errorText('source-schema')}
              </fieldset>
            </div>
          </div>

          <div className="flow-actions">
            {saveError !== null && (
              <p className="flows-alert flows-alert-danger" role="alert" data-save-error="true">
                {saveError}
              </p>
            )}
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
    </div>
  );
}
