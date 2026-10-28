// T-025 + T-028 (E6-F1-S1, E6-F3-S1, lane D): the rules editor. #/rules lists a flow's rule sets and
// creates one from a flow (POST /api/v1/admin/flows/{flowId}/rule-sets, starting from the kind's starter
// table); #/rules/<ruleSetId> authors it as decision tables in jdm-editor behind the ONE editor seam
// (DecisionTableEditorAdapter -> ZEN JSON, ADR-003) and saves it with PUT, beside the always-visible
// test panel. Styled by row 310 T102 (rules-editor.css, tokens only).
// Refusals stay visible: a malformed table names the cell and the engine's reason; another tenant's id
// is the plain not-found state, with no hint that the row exists.
// SELECTOR CONTRACT (lane C's e2e relies on it): [data-page="rules-editor"][data-rule-set-id],
// [data-action="save-rule-set"], and the panel's attributes (RuleTestPanel.tsx).
// Row 310 T102 (10-screens s7): home is a header (title + "New rule set" primary) over a flow + kind
// filter and the rule sets table with worded kind badges; the editor keeps its two columns - settings
// row and table cards left, the sticky "Test this rule set" card right - inside the .rules-editor
// wrapper that carries the jdm-editor --grl-* overrides (rules-editor.css, 01-tokens s4.1). A refused
// cell is shown above its own table; the save row links to it.

import { homeListState, homeLoadError } from './rules-home-view';
import { useEffect, useMemo, useState } from 'react';
import { listFlows, type FlowSummary } from '../api/flows';
import type { EditableTable } from './DecisionTableEditorAdapter';
import { JdmEditorAdapter } from './jdm/JdmEditorAdapter';
import { JdmDecisionTable } from './jdm/JdmDecisionTable';
import { createRuleSet, listRuleSets, readRuleSet, saveRuleSet, type RuleSet, type RuleSetAnswer } from './ruleSets';
import { KIND_LABELS, RULE_KINDS, kindBadge, sampleInput, starterJdm, type RuleKind } from './templates';
import { RuleTestPanel } from './RuleTestPanel';
import './rules-editor.css';

export function RulesEditor({ ruleSetId }: { ruleSetId: string | null }) {
  return ruleSetId ? <RuleSetEditor key={ruleSetId} ruleSetId={ruleSetId} /> : <RulesHome />;
}

/** Row 575 S4: #/rules?flow=<id> (from a flow's Rules tab) pre-selects that flow, so the flow is never asked for twice. */
function flowFromHash(): string {
  const hash = typeof window === 'undefined' ? '' : window.location.hash;
  return hash.includes('?') ? new URLSearchParams(hash.slice(hash.indexOf('?') + 1)).get('flow') ?? '' : '';
}

function RulesHome() {
  const [flows, setFlows] = useState<FlowSummary[]>([]);
  const [flowId, setFlowId] = useState(flowFromHash);
  const [kind, setKind] = useState<RuleKind>('VALIDATION');
  const [name, setName] = useState('');
  const [ruleSets, setRuleSets] = useState<RuleSet[]>([]);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [kindFilter, setKindFilter] = useState('');
  const [creating, setCreating] = useState(false);
  // A9: a failed fetch is an error with Retry, not an empty list.
  const [loadError, setLoadError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const shown = kindFilter ? ruleSets.filter((ruleSet) => ruleSet.kind === kindFilter) : ruleSets;

  useEffect(() => {
    void listFlows().then((result) => {
      const failed = homeLoadError('flows', result);
      if (failed || !result.ok) {
        setLoadError(failed);
        return;
      }
      setLoadError(null);
      setFlows(result.data);
      setFlowId((current) => current || (result.data[0]?.id ?? ''));
    }).catch(() => setLoadError(homeLoadError('flows', { ok: false, status: 0 })));
  }, [attempt]);

  useEffect(() => {
    if (!flowId) return;
    void listRuleSets(flowId).then((result) => {
      const failed = homeLoadError('rule sets', result);
      if (failed || !result.ok) {
        setLoadError(failed);
        return;
      }
      setLoadError(null);
      setRuleSets(result.data);
    }).catch(() => setLoadError(homeLoadError('rule sets', { ok: false, status: 0 })));
  }, [flowId, attempt]);

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
    <div data-page="rules-home">
      <div className="rules-home">
        <div className="rules-head">
          <h1>Rules</h1>
          <button
            type="button"
            className="btn btn-primary"
            data-action="new-rule-set"
            aria-expanded={creating}
            aria-controls="rules-create"
            onClick={() => setCreating((open) => !open)}
          >
            New rule set
          </button>
        </div>
        <section id="rules-create" className="rules-card rules-create" aria-labelledby="rules-create-title" hidden={!creating}>
          <h3 id="rules-create-title">New rule set</h3>
          <div className="rules-create-row">
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
            <button type="button" className="btn btn-primary" data-action="create-rule-set" disabled={!flowId} onClick={() => void create()}>
              Create
            </button>
          </div>
          {refusal && (
            <p className="rules-alert" role="alert" data-refusal="rule-set-create">
              {refusal}
            </p>
          )}
        </section>
        <div className="rules-filter">
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
          <label>
            Kind filter
            <select data-field="rule-set-kind-filter" value={kindFilter} onChange={(e) => setKindFilter(e.target.value)}>
              <option value="">All kinds</option>
              {RULE_KINDS.map((k) => (
                <option key={k} value={k}>
                  {KIND_LABELS[k]}
                </option>
              ))}
            </select>
          </label>
        </div>
        {loadError !== null ? (
          <div className="rules-alert" role="alert" data-load-error="rules-home">
            <p>{loadError}</p>
            <button type="button" className="btn" data-action="retry-load" onClick={() => setAttempt((n) => n + 1)}>
              Retry
            </button>
          </div>
        ) : homeListState({ error: loadError, total: ruleSets.length, shown: shown.length }) !== 'rows' ? (
          <p className="rules-empty">{ruleSets.length === 0 ? 'No rule sets on this flow yet.' : 'No rule sets of this kind on this flow.'}</p>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Kind</th>
                <th scope="col">Open</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((ruleSet) => {
                const badge = kindBadge(ruleSet.kind);
                return (
                  <tr key={ruleSet.id} data-rule-set-row={ruleSet.id}>
                    <td>{ruleSet.name}</td>
                    <td>
                      <span className={`badge badge-${badge.tone}`}>{badge.word}</span>
                    </td>
                    <td>
                      <a href={`#/rules/${ruleSet.id}`} aria-label={`Open ${ruleSet.name}`}>
                        Open
                      </a>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>
    </div>
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
  // A9: edits not yet saved - the browser asks before a reload or tab close, and the test panel says what it runs.
  const [dirty, setDirty] = useState(false);
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

  useEffect(() => {
    if (!dirty) return;
    const onUnload = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', onUnload);
    return () => window.removeEventListener('beforeunload', onUnload);
  }, [dirty]);

  function onTableChange(nodeId: string, table: unknown) {
    adapter.updateTable(nodeId, table);
    setSaved(false);
    setDirty(true);
  }

  async function save() {
    setSaved(false);
    setRefusal(null);
    await adapter.settle(); // the editor's last edit must reach the adapter before it is saved
    const result = await saveRuleSet(ruleSetId, name, adapter.toZen());
    if (result.ok) {
      setSaved(true);
      setDirty(false);
    } else if (result.status === 404) setLoad({ state: 'not-found' });
    else setRefusal(result);
  }

  if (load.state !== 'loaded') {
    return (
      <div data-page="rules-editor" data-rule-set-id={ruleSetId}>
        {load.state === 'loading' && <p>Loading the rule set...</p>}
        {load.state === 'not-found' && <p data-refusal="rule-set-not-found">Rule set not found.</p>}
        {load.state === 'failed' && <p data-refusal="rule-set-unreadable">The rule set could not be read (status {load.status}).</p>}
      </div>
    );
  }

  const kind = load.ruleSet.kind;
  // a RULE_TABLE_INVALID refusal names its table: shown above that table, linked from the save row
  const refusedNode = refusal && refusal.status === 400 && refusal.reason !== undefined ? (refusal.cell?.node ?? null) : null;
  const anchored = refusal !== null && refusedNode !== null && tables.some((t) => t.nodeId === refusedNode);
  return (
    <div
      data-page="rules-editor"
      data-rule-set-id={ruleSetId}
    >
      <div className="rules-editor rules-layout">
        <div className="rules-main">
          <div className="rules-head">
            <h1>{kindBadge(kind).word}</h1>
          </div>
          <section className="rules-card" aria-label="Rule set settings">
            <div className="rules-settings">
              <label>
                Name
                <input
                  data-field="rule-set-name"
                  value={name}
                  onChange={(e) => {
                    setName(e.target.value);
                    setSaved(false);
                    setDirty(true);
                  }}
                />
              </label>
              <button type="button" className="btn btn-primary" data-action="save-rule-set" onClick={() => void save()}>
                Save
              </button>
              {dirty && !saved && (
                <span className="rules-muted" data-unsaved="true">
                  Unsaved changes
                </span>
              )}
              {saved && (
                <span className="rules-saved" role="status" data-saved="true">
                  Saved.
                </span>
              )}
            </div>
            {anchored && (
              <p className="rules-muted">
                Not saved: a cell of table {refusedNode} is refused.
                <button type="button" className="rules-jump" data-action="jump-to-refusal" onClick={jumpToRefusal}>
                  Go to the refused cell
                </button>
              </p>
            )}
          </section>
          {dirty && (
            <p className="rules-muted" role="status" data-test-panel-note="true">
              You have unsaved edits: the test panel runs the last saved version. Save first to test them.
            </p>
          )}
          {refusal && !anchored && <SaveRefusal refusal={refusal} />}
          {tables.map((t) => (
            <div key={t.nodeId} className="rules-card rules-table-card">
              {refusal && anchored && refusedNode === t.nodeId && <SaveRefusal refusal={refusal} />}
              <JdmDecisionTable nodeId={t.nodeId} name={t.name} table={t.table} onChange={onTableChange} />
            </div>
          ))}
        </div>
        <RuleTestPanel ruleSetId={ruleSetId} sample={sampleInput(kind)} />
      </div>
    </div>
  );
}

const REFUSAL_ID = 'rules-refusal';

function jumpToRefusal() {
  const alert = document.getElementById(REFUSAL_ID);
  alert?.scrollIntoView({ block: 'center' });
  alert?.focus({ preventScroll: true });
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
        id={REFUSAL_ID}
        tabIndex={-1}
        className="rules-alert"
        role="alert"
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
  return (
    <p className="rules-alert" role="alert" data-refusal="rule-set-save">
      Not saved: {refusal.error ?? refusal.refusal ?? `refused ${refusal.status}`}
    </p>
  );
}
