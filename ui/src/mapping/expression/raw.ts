/**
 * T-039 (E7 wave 2 lane D2, row 278): the raw-expression editor state - the pure contract under
 * the CodeMirror 6 component (`RawExpressionEditor.tsx`), so `node --test` holds the editor
 * scenarios without a browser. Reads the mapping graph lane D's own `graph.ts` keeps; the emitter
 * itself is lane B3's (ui/src/mapping/emit/**, read-only for this lane).
 *
 * Scenarios served (E7-F2-S2 editor side, ADR-006 Accepted 2026-10-12):
 *   - Drawn equals typed: the editor renders the graph's expression; an edit lands back and
 *     round-trips into the save request (toSaveRequest carries it as compiledExpression).
 *   - Money rounding never via $round (ADR-006 rule 8): a raw edit that brings $round in is
 *     refused with the rule named - the graph keeps its previous expression. The raw surface
 *     cannot tell money fields from other numbers, so the only edit that can never break the
 *     rule is no $round at all; rounding belongs to the drawn links (halves-to-even there).
 */

import type { MappingGraph } from '../graph';
import type { MutatorResult } from '../graph';

/** The refusal the editor shows when a typed expression brings $round in (ADR-006 rule 8). */
export const ROUND_REFUSAL = 'money rounding never uses $round (ADR-006 rule 8: halves-to-even vs the reference rounding) - round drawn links or the rule engine instead';

/** What the editor renders first: exactly the expression the mapping keeps. */
export function renderExpression(graph: MappingGraph): string {
  return graph.expression;
}

/** An expression edit: the typed text becomes the graph's expression - unless it brings
 * $round in, in which case the edit is not applied and the rule is named. */
export function applyExpressionEdit(graph: MappingGraph, text: string): MutatorResult {
  if (text.includes('$round')) {
    return { graph, refusal: ROUND_REFUSAL };
  }
  return { graph: { ...graph, expression: text }, refusal: null };
}