// T-026 (E6-F1-S2, lane D): the fallback decision-table grid's SEAM STUB - designed in ./DESIGN.md, not
// built (ADR-003 rule 2: built only if jdm-editor goes about 6 months without a release or a needed
// feature forces a fork). It already loads and emits ZEN JSON through the same seam as the jdm-editor
// adapter, so the contract test proves today that the grid, once built, plugs in without touching the
// page or the API; only its editing (updateTable and the grid component) is missing.

import type { DecisionTableEditorAdapter } from '../DecisionTableEditorAdapter';
import { ZenGraphAdapter } from '../zen';

export class FallbackGridAdapter extends ZenGraphAdapter implements DecisionTableEditorAdapter {
  updateTable(nodeId: string, _table: unknown): void {
    throw new Error(
      `the fallback decision-table grid is not built (ADR-003 rule 2) - cannot edit table ${nodeId}; see ui/src/rules/fallback/DESIGN.md`
    );
  }
}
