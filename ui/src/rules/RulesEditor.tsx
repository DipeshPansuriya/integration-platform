// T-025 + T-028 (E6-F1-S1, E6-F3-S1, lane D): the rules editor. #/rules lists a flow's rule sets and
// creates one from a flow (POST /api/v1/admin/flows/{flowId}/rule-sets, starting from the kind's starter
// table); #/rules/<ruleSetId> authors it as decision tables in jdm-editor behind the ONE editor seam
// (DecisionTableEditorAdapter -> ZEN JSON, ADR-003) and saves it with PUT, beside the always-visible
// test panel. Functional and unstyled like every E3 screen (design.md section 4; the look is E19's).
// Refusals stay visible: a malformed table names the cell and the engine's reason; another tenant's id
// is the plain not-found state, with no hint that the row exists.
// SELECTOR CONTRACT (lane C's e2e relies on it): [data-page="rules-editor"][data-rule-set-id],
// [data-action="save-rule-set"], and the panel's attributes (RuleTestPanel.tsx).

import { useEffect, useMemo, useState } from 'react';
import { listFlows, type FlowSummary } from '../api/flows';
import type { EditableTable } from './DecisionTableEditorAdapter';
import { JdmEditorAdapter } from './jdm/JdmEditorAdapter';
import { JdmDecisionTable } from './jdm/JdmDecisionTable';
import { createRuleSet, listRuleSets, readRuleSet, saveRuleSet, type RuleSet, type RuleSetAnswer } from './ruleSets';
import { KIND_LABELS, RULE_KINDS, sampleInput, starterJdm, type RuleKind } from './templates';
import { RuleTestPanel } from './RuleTestPanel';

export function RulesEditor({ ruleSetId }: { ruleSetId: string | null }) {
  return ruleSetId ? <RuleSetEditor key={ruleSetId} ruleSetId={ruleSetId} /> : <RulesHome />;
}

function RulesHome() {
  const [flows, setFlows] = useState<FlowSummary[]>([]);
  const [flowId, setFlowId] = useState('');
  const [kind, setKind] = useState<RuleKind>('VALIDATION');
  const [name, setName] = useState('');
  const [ruleSets, setRuleSets] = useState<RuleSet[]>([]);
  const [refusal, setRefusal] = useState<string | null>(null);

  useEffect(() => {
    void listFlows().then((result) => {
      if (!result.ok) return;
      setFlows(result.data);
      setFlowId((current) => current || (result.data[0]?.id ?? ''));
    });
  }, []);

  useEffect(() => {
    if (!flowId) return;
    void listRuleSets(flowId).then((result) => setRuleSets(result.ok ? result.data : []));
  }, [flowId]);

  async function create() {
    setRefusal(null);
    const result = await createRuleSet(flowId, kind, name, starterJdm(kind));
    if (result.ok) {
      window.location.hash = `#/rules/${result.data.id}`;
    } else {
      setRefusal(result.error ?? result.refusal ?? `refused ${result.status}`);
    }
  }

  return (
    <main data-page="rules-home">
      <h1>Rules</h1>
      <label>
        Flow
        <select data-field="rule-set-flow" value={flowId} onChange={(e) => setFlowId(e.target.value)}>
          {flows.map((flow) => (
            <option key={flow.id} value={flow.id}>
              {flow.name || flow.code || flow.id}
            </option>
          ))}
        </select>
      </label>
      <ul>
        {ruleSets.map((ruleSet) => (
          <li key={ruleSet.id} data-rule-set-row={ruleSet.id}>
            <a href={`#/rules/${ruleSet.id}`}>{ruleSet.name}</a> ({KIND_LABELS[ruleSet.kind as RuleKind] ?? ruleSet.kind})
          </li>
        ))}
      </ul>
      <h2>New rule set</h2>
      <label>
        Kind
        <select data-field="rule-set-kind" value={kind} onChange={(e) => setKind(e.target.value as RuleKind)}>
          {RULE_KINDS.map((k) => (
            <option key={k} value={k}>
              {KIND_LABELS[k]}
            </option>
          ))}
        </select>
      </label>
      <label>
        Name
        <input data-field="rule-set-name" value={name} onChange={(e) => setName(e.target.value)} />
      </label>
      <button type="button" data-action="create-rule-set" disabled={!flowId} onClick={() => void create()}>
        Create
      </button>
      {refusal && <p data-refusal="rule-set-create">{refusal}</p>}
    </main>
  );
}

type Load = { state: 'loading' } | { state: 'not-found' } | { state: 'failed'; status: number } | { state: 'loaded'; ruleSet: RuleSet };
type Refusal = Extract<RuleSetAnswer<unknown>, { ok: false }>;

function RuleSetEditor({ ruleSetId }: { ruleSetId: string }) {
  const adapter = useMemo(() => new JdmEditorAdapter(), []);
  const [load, setLoad] = useState<Load>({ state: 'loading' });
  const [tables, setTables] = useState<EditableTable[]>([]);
  const [name, setName] = useState('');
  const [saved, setSaved] = useState(false);
  const [refusal, setRefusal] = useState<Refusal | null>(null);

  useEffect(() => {
    void readRuleSet(ruleSetId).then((result) => {
      if (!result.ok) {
        setLoad(result.status === 404 ? { state: 'not-found' } : { state: 'failed', status: result.status });
        return;
      }
      adapter.load(result.data.content);
      setTables(adapter.tables());
      setName(result.data.name);
      setLoad({ state: 'loaded', ruleSet: result.data });
    });
  }, [adapter, ruleSetId]);

  function onTableChange(nodeId: string, table: unknown) {
    adapter.updateTable(nodeId, table);
    setSaved(false);
  }

  async function save() {
    setSaved(false);
    setRefusal(null);
    await adapter.settle(); // the editor's last edit must reach the adapter before it is saved
    const result = await saveRuleSet(ruleSetId, name, adapter.toZen());
    if (result.ok) setSaved(true);
    else if (result.status === 404) setLoad({ state: 'not-found' });
    else setRefusal(result);
  }

  if (load.state !== 'loaded') {
    return (
      <main data-page="rules-editor" data-rule-set-id={ruleSetId}>
        {load.state === 'loading' && <p>Loading the rule set...</p>}
        {load.state === 'not-found' && <p data-refusal="rule-set-not-found">Rule set not found.</p>}
        {load.state === 'failed' && <p data-refusal="rule-set-unreadable">The rule set could not be read (status {load.status}).</p>}
      </main>
    );
  }

  const kind = load.ruleSet.kind;
  return (
    <main
      data-page="rules-editor"
      data-rule-set-id={ruleSetId}
      style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) minmax(280px, 380px)', gap: 16, alignItems: 'start' }}
    >
      <div>
        <h1>{KIND_LABELS[kind as RuleKind] ?? kind}</h1>
        <label>
          Name
          <input
            data-field="rule-set-name"
            value={name}
            onChange={(e) => {
              setName(e.target.value);
              setSaved(false);
            }}
          />
        </label>
        <button type="button" data-action="save-rule-set" onClick={() => void save()}>
          Save
        </button>
        {saved && <span data-saved="true">Saved.</span>}
        {refusal && <SaveRefusal refusal={refusal} />}
        {tables.map((t) => (
          <JdmDecisionTable key={t.nodeId} nodeId={t.nodeId} name={t.name} table={t.table} onChange={onTableChange} />
        ))}
      </div>
      <RuleTestPanel ruleSetId={ruleSetId} sample={sampleInput(kind)} />
    </main>
  );
}

/** A refused save: RULE_TABLE_INVALID names the cell (node, 0-based row index, column id) and the reason. */
function SaveRefusal({ refusal }: { refusal: Refusal }) {
  if (refusal.status === 400 && refusal.reason !== undefined) {
    const cell = refusal.cell ?? {};
    const where = [
      cell.node ? `table ${cell.node}` : null,
      typeof cell.row === 'number' ? `row ${cell.row + 1}` : null,
      cell.column ? `column ${cell.column}` : null
    ].filter(Boolean);
    return (
      <p
        data-refusal="rule-table-invalid"
        data-cell-node={cell.node ?? undefined}
        data-cell-row={typeof cell.row === 'number' ? String(cell.row) : undefined}
        data-cell-column={cell.column ?? undefined}
      >
        Not saved: {where.length > 0 ? `${where.join(', ')}: ` : ''}
        <span data-refusal-reason>{refusal.reason}</span>
      </p>
    );
  }
  return <p data-refusal="rule-set-save">Not saved: {refusal.error ?? refusal.refusal ?? `refused ${refusal.status}`}</p>;
}
