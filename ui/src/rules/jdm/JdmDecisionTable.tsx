// T-025 (E6-F1-S1, lane D): jdm-editor's DecisionTable (pinned 1.52.0, ADR-003) for one table of the
// rule set - the only file of the console that imports the editor component. Every change goes back to
// the page, which hands it to the JdmEditorAdapter; the editor's value never leaves the page untouched.

import '@gorules/jdm-editor/dist/style.css';
import { DecisionTable, JdmConfigProvider, type DecisionTableType } from '@gorules/jdm-editor';
import type { ZenDecisionTable } from '../DecisionTableEditorAdapter';

type Props = {
  nodeId: string;
  name: string;
  table: ZenDecisionTable;
  onChange: (nodeId: string, table: unknown) => void;
};

export function JdmDecisionTable({ nodeId, name, table, onChange }: Props) {
  return (
    <section data-decision-table={nodeId}>
      <h3>{name}</h3>
      <JdmConfigProvider>
        <DecisionTable
          id={nodeId}
          tableHeight={420}
          mountDialogsOnBody
          defaultValue={table as unknown as DecisionTableType}
          onChange={(value) => onChange(nodeId, value)}
        />
      </JdmConfigProvider>
    </section>
  );
}
