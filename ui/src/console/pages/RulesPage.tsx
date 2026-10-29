// Console wiring: #/rules on the new design - RulesScreen over a flow's real rule sets (useFlows +
// useRuleSets -> GET /api/v1/admin/flows/{flowId}/rule-sets). Replaces the list half of
// rules/RulesEditor (RulesHome) and keeps all it offered: the flow picker (#/rules?flow=<id>
// pre-selects), the kind filter, "New rule set" opening the inline create card (kind + name ->
// POST .../rule-sets with the kind's starter table, then #/rules/<id>), the refusal sentence, worded
// load errors with Retry, kind badges and Open links. The screen's own table and New Rule drawer have
// no kind column / kind choice and a Hit Rate / Updated the API does not carry, so the screen draws the
// header and the loading / empty / error states and this container draws the rule-set table.
// A non-null ruleSetId (same props as RulesEditor) opens the existing editor unchanged.

import { lazy, Suspense, useEffect, useState } from 'react';
import { useFlows, useRuleSets } from '../../api/hooks';
import { createRuleSet, type RuleSet } from '../../rules/ruleSets';
import { homeLoadError } from '../../rules/rules-home-view';
import { KIND_LABELS, RULE_KINDS, kindBadge, starterJdm, type RuleKind } from '../../rules/templates';
import RulesScreen, { type RuleRowVM } from '../../screens/rules/RulesScreen';
import { Button } from '../../ds/components/actions/Button.jsx';
import { Callout } from '../../ds/components/feedback/Callout.jsx';
import { Field } from '../../ds/components/forms/Field.jsx';
import { Input } from '../../ds/components/forms/Input.jsx';
import { Select } from '../../ds/components/forms/Select.jsx';
import { Panel } from '../../ds/components/surfaces/Panel.jsx';
import { goTo } from './console-pages';
import './list-pages.css';

const RulesEditor = lazy(() => import('../../rules/RulesEditor').then((m) => ({ default: m.RulesEditor })));

/** #/rules?flow=<id> (from a flow's Rules tab) pre-selects that flow - same as RulesHome. */
function flowFromHash(): string {
  const hash = typeof window === 'undefined' ? '' : window.location.hash;
  return hash.includes('?') ? new URLSearchParams(hash.slice(hash.indexOf('?') + 1)).get('flow') ?? '' : '';
}

const ruleHref = (id: string) => `#/rules/${id}`;

/** Condition / output columns summed over the rule set's decision tables (from its stored ZEN content). */
function columnCounts(ruleSet: RuleSet): { conditions: number; outputs: number } {
  let conditions = 0;
  let outputs = 0;
  for (const node of ruleSet.content?.nodes ?? []) {
    if (node.type !== 'decisionTableNode') continue;
    const table = (node.content ?? {}) as { inputs?: unknown[]; outputs?: unknown[] };
    conditions += table.inputs?.length ?? 0;
    outputs += table.outputs?.length ?? 0;
  }
  return { conditions, outputs };
}

/** The screen only uses these rows for its loading / empty / table decision (its table is hidden):
 * no hit rate or edit time exists, so none is invented - those two cells stay empty. */
function screenRow(ruleSet: RuleSet): RuleRowVM {
  return { name: ruleSet.name, ...columnCounts(ruleSet), hitRate: null as unknown as number, updated: '' };
}

export function RulesPage({ ruleSetId = null }: { ruleSetId?: string | null } = {}) {
  if (ruleSetId) {
    return (
      <Suspense fallback={<p>Loading the rules editor...</p>}>
        <RulesEditor ruleSetId={ruleSetId} />
      </Suspense>
    );
  }
  return <RulesList />;
}

function RulesList() {
  const flows = useFlows();
  const [flowId, setFlowId] = useState(flowFromHash);
  const ruleSets = useRuleSets(flowId || null);
  const [kindFilter, setKindFilter] = useState('');
  const [creating, setCreating] = useState(false);
  const [kind, setKind] = useState<RuleKind>('VALIDATION');
  const [name, setName] = useState('');
  const [saving, setSaving] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);

  const flowList = flows.data ?? [];
  useEffect(() => {
    if (!flowId && flows.data && flows.data.length > 0) setFlowId(flows.data[0].id);
  }, [flowId, flows.data]);

  const loadError = flows.status !== null
    ? homeLoadError('flows', { ok: false, status: flows.status })
    : flowId && ruleSets.status !== null
      ? homeLoadError('rule sets', { ok: false, status: ruleSets.status })
      : null;
  const list = flowId ? ruleSets.data ?? [] : [];
  const shown = kindFilter ? list.filter((ruleSet) => ruleSet.kind === kindFilter) : list;
  const loading = flows.loading || (Boolean(flowId) && ruleSets.loading);
  const ready = loadError === null && !loading;

  async function create() {
    setRefusal(null);
    setSaving(true);
    try {
      const result = await createRuleSet(flowId, kind, name, starterJdm(kind));
      if (result.ok) goTo(ruleHref(result.data.id));
      else setRefusal(result.error ?? result.refusal ?? `refused ${result.status}`);
    } catch {
      setRefusal('refused 0');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div data-page="rules-home" className="cp-list cp-own-table">
      <div className="cp-list-tools">
        <Field label="Flow">
          <Select
            data-field="rule-set-flow"
            value={flowId}
            onChange={(e) => setFlowId(e.target.value)}
            options={flowList.map((flow) => ({ value: flow.id, label: flow.name || flow.code || flow.id }))}
          />
        </Field>
        <Field label="Kind filter">
          <Select
            data-field="rule-set-kind-filter"
            value={kindFilter}
            onChange={(e) => setKindFilter(e.target.value)}
            options={[{ value: '', label: 'All kinds' }, ...RULE_KINDS.map((k) => ({ value: k, label: KIND_LABELS[k] }))]}
          />
        </Field>
        {flows.data !== null && flowList.length === 0 && (
          <p className="cp-muted cp-list-wide">No flows yet. A rule set belongs to a flow: define a flow first.</p>
        )}
        <section id="rules-create" className="ip-card cp-list-card" aria-labelledby="rules-create-title" hidden={!creating}>
          <h2 id="rules-create-title">New rule set</h2>
          <Field label="Kind">
            <Select
              data-field="rule-set-kind"
              value={kind}
              onChange={(e) => setKind(e.target.value as RuleKind)}
              options={RULE_KINDS.map((k) => ({ value: k, label: KIND_LABELS[k] }))}
            />
          </Field>
          <Field label="Name">
            <Input data-field="rule-set-name" value={name} onChange={(e) => setName(e.target.value)} />
          </Field>
          <Button variant="primary" data-action="create-rule-set" disabled={!flowId || saving} onClick={() => void create()}>
            Create
          </Button>
          <Button onClick={() => setCreating(false)}>Cancel</Button>
          {refusal && (
            <div role="alert" data-refusal="rule-set-create" className="cp-list-wide">
              <Callout tone="err" title="The rule set was not created">{refusal}</Callout>
            </div>
          )}
        </section>
      </div>
      <RulesScreen
        rows={list.map(screenRow)}
        loading={loading}
        error={loadError}
        onRetry={() => {
          flows.reload();
          ruleSets.reload();
        }}
        onNewOpen={() => setCreating((open) => !open)}
        newButtonProps={{ 'data-action': 'new-rule-set', 'aria-expanded': creating, 'aria-controls': 'rules-create' }}
      />
      {ready && list.length > 0 && (
        <div className="cp-list-body">
          <Panel title="Rule sets">
            {shown.length === 0 ? (
              <p className="cp-muted" style={{ padding: '16px 20px' }}>No rule sets of this kind on this flow.</p>
            ) : (
              <div className="ip-table-wrap">
                <table className="ip-table">
                  <thead>
                    <tr>
                      <th scope="col">Rule set</th>
                      <th scope="col">Kind</th>
                      <th scope="col">Conditions</th>
                      <th scope="col">Outputs</th>
                      <th scope="col">Open</th>
                    </tr>
                  </thead>
                  <tbody>
                    {shown.map((ruleSet) => {
                      const badge = kindBadge(ruleSet.kind);
                      const counts = columnCounts(ruleSet);
                      return (
                        <tr key={ruleSet.id} data-rule-set-row={ruleSet.id} className="is-clickable" onClick={() => goTo(ruleHref(ruleSet.id))}>
                          <td><span className="ip-id">{ruleSet.name}</span></td>
                          <td><span className={`badge badge-${badge.tone}`}>{badge.word}</span></td>
                          <td className="is-num">{counts.conditions}</td>
                          <td className="is-num">{counts.outputs}</td>
                          <td>
                            <a href={ruleHref(ruleSet.id)} aria-label={`Open ${ruleSet.name}`} onClick={(e) => e.stopPropagation()}>
                              Open
                            </a>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>
        </div>
      )}
    </div>
  );
}

export default RulesPage;
