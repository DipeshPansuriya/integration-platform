// T-026 + T-025 (E6-F1-S1/S2, lane D): THE seam between the rules editor page and whatever edits the
// decision tables (ADR-003 rule 1: the editor is pinned and wrapped in ONE thin adapter, so the UI emits
// ZEN JSON through one seam). Two implementations exist:
//   - jdm/JdmEditorAdapter        - @gorules/jdm-editor 1.52.0, the editor in use;
//   - fallback/FallbackGridAdapter - our own grid, designed (fallback/DESIGN.md) but NOT built: a stub
//     that plugs into the same seam, built only if jdm-editor stalls ~6 months or forces a fork.
// The contract test (tests/rules/editor-seam.contract.test.mjs) runs the same fixtures through both.

/** A decision-table column: the input field it reads, or the output field it writes. */
export type ZenColumn = { id: string; name: string; field?: string; [key: string]: unknown };

/** One decision table in the open ZEN format: columns and rows of expression cells. */
export type ZenDecisionTable = {
  hitPolicy: string;
  inputs: ZenColumn[];
  outputs: ZenColumn[];
  rules: Record<string, string>[];
  [key: string]: unknown;
};

export type ZenNode = {
  id: string;
  type: string;
  name: string;
  position: { x: number; y: number };
  content?: unknown;
  [key: string]: unknown;
};

export type ZenEdge = { id: string; sourceId: string; targetId: string; type?: string; [key: string]: unknown };

/** A JSON Decision Model graph - what the rule-set API stores and the ZEN engine evaluates. */
export type ZenJdm = { nodes: ZenNode[]; edges: ZenEdge[]; [key: string]: unknown };

/** A decision table as the page shows it: the graph node that holds it, and its content. */
export type EditableTable = { nodeId: string; name: string; table: ZenDecisionTable };

export interface DecisionTableEditorAdapter {
  /** Takes the stored ZEN JSON (as the rule-set API returned it). */
  load(jdm: ZenJdm): void;
  /** The decision tables the editor shows, one per decision-table node, in graph order. */
  tables(): EditableTable[];
  /** The editor's new value for one table (it may carry the editor's own markers). */
  updateTable(nodeId: string, table: unknown): void;
  /** Resolves once every edit made so far has reached updateTable (an editor may report changes late). */
  settle(): Promise<void>;
  /** The graph as ZEN JSON - only what the engine reads, never an editor's own markers. */
  toZen(): ZenJdm;
}
