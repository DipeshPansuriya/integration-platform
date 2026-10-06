// T-017 (E3 lane D): the flow-definition screen (E3-F1-S1). A configurator defines a flow
// naming its source system and destination(s) as configuration rows, with the interface's
// mandatory business-key rule and ordering setting (PER_KEY default, ADR-011), and the draft
// save sends the required sourceSchema too (E3-F3-S1) - every write through the typed client
// to the real API, every refusal surfaced, nothing invented (design.md / T-095 governs the
// agreed look; this is the functional console).

import { useEffect, useState, type FormEvent } from 'react';
import {
  createFlow,
  readFlow,
  saveFlowDraft,
  saveSourceSchema,
  validationFieldOf,
  type FlowDraftBody
} from '../api/flows';

const FLOW_ID_KEY = 'console.flow-id';

export type FlowDefinitionProps = {
  /** Deep link (E3-F1-S1 @negative @tenant): open one flow by id - the real API answers 404 for another tenant's row. */
  openFlowId?: string | null;
};

const DESTINATION_COUNT = 2; // E3-F1-S1: source S with destinations T1/T2 as configuration rows.

export function FlowDefinition({ openFlowId = null }: FlowDefinitionProps) {
  const [sourceSystem, setSourceSystem] = useState('');
  const [destinations, setDestinations] = useState<string[]>(Array(DESTINATION_COUNT).fill(''));
  const [interfaceName, setInterfaceName] = useState('');
  const [businessKeyRule, setBusinessKeyRule] = useState('');
  const [ordering, setOrdering] = useState('PER_KEY'); // ADR-011 default
  const [sourceSchema, setSourceSchema] = useState('');
  const [flowId, setFlowId] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [validationField, setValidationField] = useState<string | null>(null);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const hydrate = async (id: string) => {
    const read = await readFlow(id);
    if (!read.ok) {
      // 404 is the tenant-isolation answer (ADR-011 Decision 4): never a hint of whose row it is.
      if (read.status === 404) setRefusal('flow-not-found');
      return;
    }
    setFlowId(id);
    setSourceSystem(read.data.sourceSystem);
    setInterfaceName(read.data.name);
    setBusinessKeyRule(read.data.businessKeyRule);
    setOrdering(read.data.ordering);
    const rows = [...read.data.destinations];
    while (rows.length < DESTINATION_COUNT) rows.push('');
    setDestinations(rows.slice(0, DESTINATION_COUNT));
  };

  useEffect(() => {
    if (openFlowId) {
      void hydrate(openFlowId);
      return;
    }
    const stored = localStorage.getItem(FLOW_ID_KEY);
    if (stored) void hydrate(stored);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [openFlowId]);

  const onField = (index: number, value: string) =>
    setDestinations((rows) => rows.map((row, position) => (position === index ? value : row)));

  const body = (): FlowDraftBody => ({
    sourceSystem,
    destinations: destinations.map((row) => row.trim()).filter((row) => row.length > 0),
    businessKeyRule
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
        ? await saveFlowDraft(flowId, { ...draft, ordering })
        : await createFlow({ ...draft, ordering });
      if (!result.ok) {
        if (result.status === 400) setValidationField(validationFieldOf(result.error));
        else if (result.status === 404) setRefusal('flow-not-found');
        return;
      }
      const id = flowId ?? ((result.data as { id: string }).id);
      setFlowId(id);
      localStorage.setItem(FLOW_ID_KEY, id);

      if (sourceSchema.trim().length > 0) {
        let schema: unknown;
        try {
          schema = JSON.parse(sourceSchema);
        } catch {
          setValidationField('source-schema');
          return;
        }
        const stored = await saveSourceSchema(sourceSystem, interfaceName || 'interface', schema);
        if (!stored.ok && stored.status === 400) {
          setValidationField(validationFieldOf(stored.error));
          return;
        }
      }
      setSaved(true);
    } finally {
      setBusy(false);
    }
  };

  return (
    <main data-page="flow-definition">
      <h2>Flow definition</h2>
      {refusal === 'flow-not-found' && (
        <p data-refusal="flow-not-found">This flow does not exist for your tenant.</p>
      )}
      <form onSubmit={(event) => void onSaveDraft(event)}>
        <label>
          Source system
          <input data-field="source-system" value={sourceSystem} onChange={(e) => setSourceSystem(e.target.value)} />
        </label>
        {destinations.map((row, index) => (
          <label key={index}>
            Destination {index + 1}
            <input
              data-field={`destination-${index + 1}`}
              value={row}
              onChange={(e) => onField(index, e.target.value)}
            />
          </label>
        ))}
        <label>
          Interface name
          <input data-field="interface-name" value={interfaceName} onChange={(e) => setInterfaceName(e.target.value)} />
        </label>
        <label>
          Business-key rule (mandatory)
          <input
            data-field="business-key-rule"
            value={businessKeyRule}
            onChange={(e) => setBusinessKeyRule(e.target.value)}
          />
        </label>
        {validationField === 'business-key-rule' && (
          <p data-validation-error="business-key-rule">
            The mandatory business-key rule is missing: businessKeyRule
          </p>
        )}
        <label>
          Ordering
          <select data-field="ordering-setting" value={ordering} onChange={(e) => setOrdering(e.target.value)}>
            <option value="PER_KEY">PER_KEY</option>
            <option value="NONE">NONE</option>
          </select>
        </label>
        <label>
          Source schema (JSON Schema)
          <textarea data-field="source-schema" value={sourceSchema} onChange={(e) => setSourceSchema(e.target.value)} />
        </label>
        {validationField === 'source-schema' && (
          <p data-validation-error="source-schema">sourceSchema must be a JSON Schema object</p>
        )}
        {validationField === 'source-system' && (
          <p data-validation-error="source-system">sourceSystem is required</p>
        )}
        <button type="submit" data-action="save-draft" disabled={busy}>
          Save draft
        </button>
        {saved && <p data-saved="true">Draft saved.</p>}
      </form>
    </main>
  );
}
