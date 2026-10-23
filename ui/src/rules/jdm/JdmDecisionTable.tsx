// T-025 (E6-F1-S1, lane D): jdm-editor's DecisionTable (pinned 1.52.0, ADR-003) for one table of the
// rule set - the only file of the console that imports the editor component. Every change goes back to
// the page, which hands it to the JdmEditorAdapter; the editor's value never leaves the page untouched.

import '@gorules/jdm-editor/dist/style.css';
import { DecisionTable, JdmConfigProvider, type DecisionTableType } from '@gorules/jdm-editor';
import { useEffect, useState } from 'react';
import { jdmThemeMode, type JdmMode } from './theme-mode';
import type { ZenDecisionTable } from '../DecisionTableEditorAdapter';

type Props = {
  nodeId: string;
  name: string;
  table: ZenDecisionTable;
  onChange: (nodeId: string, table: unknown) => void;
};

/** Follows the app toggle (html[data-theme]) so jdm renders its dark algorithm with the app. */
function useAppThemeMode(): JdmMode {
  const root = typeof document === 'undefined' ? null : document.documentElement;
  const [mode, setMode] = useState<JdmMode>(() => jdmThemeMode(root?.dataset.theme));
  useEffect(() => {
    if (!root) return;
    const sync = () => setMode(jdmThemeMode(root.dataset.theme));
    sync();
    const observer = new MutationObserver(sync);
    observer.observe(root, { attributes: true, attributeFilter: ['data-theme'] });
    return () => observer.disconnect();
  }, [root]);
  return mode;
}

export function JdmDecisionTable({ nodeId, name, table, onChange }: Props) {
  const mode = useAppThemeMode();
  return (
    <section data-decision-table={nodeId}>
      <h3>{name}</h3>
      <JdmConfigProvider theme={{ mode }}>
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
