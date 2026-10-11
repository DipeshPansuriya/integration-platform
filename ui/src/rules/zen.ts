// T-026 + T-025 (lane D): the ZEN JSON side of the editor seam, shared by both adapters - so the
// jdm-editor adapter and the fallback grid emit one shape by construction, and the contract test
// guards that nobody forks it.

import type { EditableTable, ZenDecisionTable, ZenJdm } from './DecisionTableEditorAdapter';

/** jdm-editor's own bookkeeping on tables, columns, cells and edges; the engine never reads it. */
const EDITOR_ONLY_KEYS = new Set(['_diff']);

/**
 * A deep copy holding only JSON: string keys in their original order (so the stored document re-opens
 * exactly as it was left), editor-only markers dropped, symbol-keyed editor state never copied.
 */
export function cleanZen<T>(value: T): T {
  if (Array.isArray(value)) return value.map((item) => cleanZen(item)) as T;
  if (value !== null && typeof value === 'object') {
    const out: Record<string, unknown> = {};
    for (const key of Object.keys(value)) {
      if (!EDITOR_ONLY_KEYS.has(key)) out[key] = cleanZen((value as Record<string, unknown>)[key]);
    }
    return out as T;
  }
  return value;
}

export function decisionTables(jdm: ZenJdm): EditableTable[] {
  return jdm.nodes
    .filter((node) => node.type === 'decisionTableNode')
    .map((node) => ({ nodeId: node.id, name: node.name, table: node.content as ZenDecisionTable }));
}

/** The graph with one decision-table node's content replaced; every other node and edge untouched. */
export function withTable(jdm: ZenJdm, nodeId: string, table: unknown): ZenJdm {
  return { ...jdm, nodes: jdm.nodes.map((node) => (node.id === nodeId ? { ...node, content: table } : node)) };
}

/** What both adapters share: hold one graph, show its tables, emit it as ZEN JSON. */
export abstract class ZenGraphAdapter {
  protected graph: ZenJdm = { nodes: [], edges: [] };

  load(jdm: ZenJdm): void {
    this.graph = cleanZen(jdm);
  }

  tables(): EditableTable[] {
    return decisionTables(this.graph);
  }

  settle(): Promise<void> {
    return Promise.resolve();
  }

  toZen(): ZenJdm {
    return cleanZen(this.graph);
  }
}
