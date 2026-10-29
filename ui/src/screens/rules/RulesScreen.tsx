import { useState } from 'react';
import type { ChangeEvent, ReactElement, ReactNode } from 'react';
import {
  Button, Field, Input, PageHeader, Panel,
  Drawer, DataTable, KeyValue, DecisionTable, Callout
} from '../../ds/components/index.js';
import './rules.css';

export interface RuleRowVM {
  name: string;
  conditions: number;
  outputs: number;
  /** Percentage of evaluated messages that matched this rule. */
  hitRate: number;
  updated: string;
}

export interface RuleDetailVM {
  name: string;
  updated?: string;
  /** Condition columns; evaluated left to right, all must match. */
  columns: { key: string; header: string }[];
  /** Decision rows; every row also carries an `output` value. */
  rows: Record<string, string | number>[];
}

export interface RuleDraft {
  name: string;
  conditions: string[];
  output: string;
}

export interface RulesScreenProps {
  rows?: RuleRowVM[];
  loading?: boolean;
  error?: string | null;
  onRetry?: () => void;
  onRowSelect?: (row: RuleRowVM) => void;
  newOpen?: boolean;
  onNewOpen?: () => void;
  onNewClose?: () => void;
  saving?: boolean;
  onRuleCreate?: (draft: RuleDraft) => void;
  detailOpen?: boolean;
  detail?: RuleDetailVM | null;
  detailError?: string | null;
  onDetailClose?: () => void;
  onEditRule?: () => void;
  /** Pass-through: extra attributes on the header New Rule button (console wiring keeps old e2e hooks). */
  newButtonProps?: Record<string, string | boolean | undefined>;
}

/** Drawer.Section is a runtime-only export (Drawer.d.ts declares only the Drawer function). */
interface DrawerSectionProps { title?: string; children?: ReactNode }
const DrawerSection = (Drawer as unknown as { Section?: (props: DrawerSectionProps) => ReactElement }).Section
  ?? ((props: DrawerSectionProps) => <div {...props} />);

const DEFAULT_DRAFT: RuleDraft = { name: '', conditions: [''], output: '' };

export default function RulesScreen(props: RulesScreenProps) {
  const {
    rows, loading, error, onRetry, onRowSelect,
    newOpen, onNewOpen, onNewClose, saving, onRuleCreate,
    detailOpen, detail, detailError, onDetailClose, onEditRule, newButtonProps
  } = props;

  const [draft, setDraft] = useState<RuleDraft>({ ...DEFAULT_DRAFT });
  const [sample, setSample] = useState<Record<string, string>>({});
  const [testResult, setTestResult] = useState<
    { matched: true; rowIndex: number; key: string; output: string | number }
    | { matched: false } | null
  >(null);

  const columns = [
    { key: 'name', header: 'Rule', mono: true },
    { key: 'conditions', header: 'Conditions', numeric: true },
    { key: 'outputs', header: 'Outputs', numeric: true },
    {
      key: 'hitRate', header: 'Hit Rate', numeric: true,
      render: (r: RuleRowVM) => `${r.hitRate}%`
    },
    { key: 'updated', header: 'Updated' }
  ];

  const openNew = () => {
    setDraft({ ...DEFAULT_DRAFT, conditions: [''] });
    onNewOpen?.();
  };

  const patch = (part: Partial<RuleDraft>) => setDraft({ ...draft, ...part });
  const setCondition = (index: number, value: string) => {
    const conditions = draft.conditions.map((c, i) => (i === index ? value : c));
    setDraft({ ...draft, conditions });
  };
  const addCondition = () => setDraft({ ...draft, conditions: [...draft.conditions, ''] });
  const removeCondition = (index: number) => {
    const conditions = draft.conditions.filter((_, i) => i !== index);
    setDraft({ ...draft, conditions: conditions.length > 0 ? conditions : [''] });
  };

  const runTest = () => {
    if (!detail) return;
    const keys = detail.columns.map((c) => c.key);
    for (let i = 0; i < detail.rows.length; i++) {
      const row = detail.rows[i];
      const hit = keys.every((k) => row[k] !== undefined && String(row[k]) === String(sample[k] ?? ''));
      if (hit) {
        setTestResult({ matched: true, rowIndex: i, key: keys[0], output: row.output ?? '' });
        return;
      }
    }
    setTestResult({ matched: false });
  };

  return (
    <div className="rules-screen">
      <PageHeader
        title="Rules"
        subtitle="Decision rules that route messages"
        actions={
          <Button variant="primary" icon="plus" onClick={openNew} {...newButtonProps}>New Rule</Button>
        }
      />

      {error ? (
        <div data-region="error">
          <Callout tone="err" title="Could not load rules">
            {error}
          </Callout>
          <div className="lane-c-colrow">
            <Button variant="secondary" icon="refresh" onClick={() => onRetry?.()}>Retry</Button>
          </div>
        </div>
      ) : loading ? (
        <div data-region="loading" className="lane-c-region">
          <div className="lane-c-empty">Loading rules&hellip;</div>
        </div>
      ) : !rows || rows.length === 0 ? (
        <div data-region="empty" className="lane-c-region">
          <div className="lane-c-empty">
            <p className="lane-c-empty-title">No rules yet</p>
            <p className="lane-c-note">Create a decision rule to get started.</p>
            <div className="lane-c-colrow">
              <Button variant="secondary" onClick={openNew}>New Rule</Button>
            </div>
          </div>
        </div>
      ) : (
        <div data-region="table" className="lane-c-region">
          <Panel title="Rules">
            <DataTable columns={columns} rows={rows} rowKey="name" onRowClick={(r: RuleRowVM) => onRowSelect?.(r)} />
          </Panel>
        </div>
      )}

      {detailOpen && (
        <Drawer
          title={detail?.name ?? ''}
          open
          onClose={() => onDetailClose?.()}
          footer={
            <>
              <Button variant="secondary" onClick={() => onDetailClose?.()}>Close</Button>
              <Button variant="primary" onClick={() => onEditRule?.()}>Edit Rule</Button>
            </>
          }
        >
          {detailError ? (
            <Callout tone="err" title="Could not load rule">{detailError}</Callout>
          ) : detail ? (
            <>
              <DrawerSection title="Rule Info">
                <div className="lane-c-kv-col">
                  <KeyValue label="Name" value={detail.name} mono />
                  <KeyValue label="Updated" value={detail.updated} />
                </div>
              </DrawerSection>
              <DrawerSection title="Decision Table">
                <DecisionTable
                  columns={[
                    ...detail.columns.map((c) => ({ key: c.key, header: c.header })),
                    { key: 'output', header: 'Output' }
                  ]}
                  rows={detail.rows}
                />
              </DrawerSection>
              <DrawerSection title="Tester">
                {detail.columns.map((c) => (
                  <div className="lane-c-colrow" key={c.key}>
                    <Field label={c.header}>
                      <Input
                        code
                        placeholder={c.header}
                        value={sample[c.key] ?? ''}
                        onChange={(e: ChangeEvent<HTMLInputElement>) =>
                          setSample({ ...sample, [c.key]: e.target.value })}
                      />
                    </Field>
                  </div>
                ))}
                <div className="lane-c-colrow">
                  <Button variant="secondary" onClick={runTest}>Run test</Button>
                </div>
                {testResult?.matched ? (
                  <Callout tone="ok" title={`Match: Row ${testResult.rowIndex + 1}`}>
                    Output: {testResult.output}
                  </Callout>
                ) : testResult ? (
                  <Callout tone="warn" title="No match">
                    No row matched &mdash; adjust the sample values.
                  </Callout>
                ) : null}
              </DrawerSection>
            </>
          ) : null}
        </Drawer>
      )}

      {newOpen && (
        <Drawer
          title="New Rule"
          open
          onClose={() => onNewClose?.()}
          footer={
            <>
              <Button variant="secondary" onClick={() => onNewClose?.()}>Cancel</Button>
              <Button variant="primary" disabled={saving} onClick={() => onRuleCreate?.(draft)}>Create Rule</Button>
            </>
          }
        >
          <DrawerSection title="Details">
            <div className="lane-c-colrow">
              <Field label="Rule name">
                <Input
                  code
                  placeholder="Rule name"
                  value={draft.name}
                  onChange={(e: ChangeEvent<HTMLInputElement>) => patch({ name: e.target.value })}
                />
              </Field>
            </div>
            <div className="lane-c-colrow">
              <Field label="Output">
                <Input
                  code
                  placeholder="Output value"
                  value={draft.output}
                  onChange={(e: ChangeEvent<HTMLInputElement>) => patch({ output: e.target.value })}
                />
              </Field>
            </div>
          </DrawerSection>
          <DrawerSection title="Conditions">
            {draft.conditions.map((value, index) => (
              <div className="lane-c-colrow" key={`condition-${index}`}>
                <Field label={`Condition ${index + 1}`}>
                  <Input
                    code
                    placeholder={`Condition ${index + 1}`}
                    value={value}
                    onChange={(e: ChangeEvent<HTMLInputElement>) => setCondition(index, e.target.value)}
                  />
                </Field>
                <Button
                  variant="ghost"
                  aria-label={`Remove Condition ${index + 1}`}
                  onClick={() => removeCondition(index)}
                >
                  Remove
                </Button>
              </div>
            ))}
            <div className="lane-c-colrow">
              <Button variant="secondary" onClick={addCondition}>+ Add condition</Button>
            </div>
          </DrawerSection>
        </Drawer>
      )}
    </div>
  );
}