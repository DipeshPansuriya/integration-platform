// T-025 (E6-F1-S1, lane D): the jdm-editor implementation of the editor seam (ADR-003: @gorules/jdm-editor
// pinned at 1.52.0). The page renders jdm-editor's DecisionTable for each table this adapter lists and
// hands every change back here; the editor's value carries its own diff markers and a private symbol on
// nodes, which toZen() drops, so only ZEN JSON ever leaves the page.

import type { DecisionTableEditorAdapter } from '../DecisionTableEditorAdapter';
import { withTable, ZenGraphAdapter } from '../zen';

/**
 * jdm-editor 1.52.0's DecisionTable reports a change through a 100 ms debounce (dist/index.js, the
 * DecisionTable's useDebouncedCallback(onChange, 100)), so an edit made just before Save would still be
 * on its way; settle() waits that window out with a margin before the page reads toZen().
 */
export const JDM_EDITOR_CHANGE_SETTLE_MS = 150;

export class JdmEditorAdapter extends ZenGraphAdapter implements DecisionTableEditorAdapter {
  settle(): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, JDM_EDITOR_CHANGE_SETTLE_MS));
  }

  updateTable(nodeId: string, table: unknown): void {
    this.graph = withTable(this.graph, nodeId, table);
  }
}
