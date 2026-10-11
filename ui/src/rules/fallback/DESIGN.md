# Fallback decision-table grid - design note (T-026, E6-F1-S2)

Status: **designed, not built.** The seam stub (`FallbackGridAdapter.ts`) exists and passes the seam
contract test; the grid component does not.

## Why it exists

ADR-003 adopts `@gorules/jdm-editor` (MIT) for a limited time: pinned at **1.52.0**, wrapped in one
thin adapter, with our own grid kept ready behind the same seam (ADR-003, rules 1 and 2). At the
2026-09-30 check the editor had gone about 4.5 months without a release.

## When it gets built (and only then)

- jdm-editor goes **about 6 months without a release**, or
- a needed feature forces a fork, or
- its licence changes on a future release (the build's licence check, T-161, fails first).

A fork of 1.52.0 is the other way out. The MIT history allows it.

## The seam it plugs into

`ui/src/rules/DecisionTableEditorAdapter.ts`, the same seam the jdm-editor adapter implements:

| Method | Fallback grid's job |
|---|---|
| `load(jdm)` | Take the stored ZEN JSON (the rule-set API's `content`, unchanged) |
| `tables()` | List each `decisionTableNode` as one grid (`nodeId`, `name`, `table`) |
| `updateTable(nodeId, table)` | Take the grid's new table. **The stub throws here: this is the part not built** |
| `toZen()` | Emit the graph as ZEN JSON. Already implemented through the shared `zen.ts` |

Because `load`, `tables` and `toZen` come from the shared `ZenGraphAdapter`, the grid emits exactly
the shape the jdm-editor adapter emits. `tests/rules/editor-seam.contract.test.mjs` proves this on
the three starter tables (validation, routing, lookup). It also has a planted drifting adapter, so
a fork of that shape fails the test.

## What the grid must do (scope when built)

Only what a configurator who is not a developer needs for the three rule kinds:

1. **One table per decision-table node.** A header row of condition columns (input field name)
   and result columns (output field name), then one row per rule.
2. **Cells are plain text holding ZEN expressions.** Condition cells use unary expressions (`> 100`,
   `"NL"`, empty = any). Result cells use standard expressions (`"dst-eu"`). The grid does not parse
   them: the save answer refuses a bad cell with its node, row, column and the engine's reason (lane B,
   T-023), and the grid highlights that cell.
3. **Row operations:** add, delete, move up and down. Row ids (`_id`) are kept, and new rows get a
   random UUID.
4. **Column operations:** add or remove a condition or result column (`id`, `name`, `field`). A
   condition column with no field and no condition is refused by the API, never silently dropped.
5. **Hit policy:** `first` or `collect`.
6. **Keyboard:** arrow keys move between cells, Enter edits, Escape cancels. Every cell has an
   accessible name "<column> row <n>" (the E19 bar: WCAG AA, visible focus).
7. **Same page, same test panel:** the page swaps `JdmEditorAdapter` for `FallbackGridAdapter` in
   one place (`RulesEditor.tsx`). Nothing else changes: not the API, not the panel, not the
   selector contract.

Out of scope: graph editing (more than one table per rule set, function or expression nodes), Excel
import and export, and any in-browser simulation. The test panel always runs on the runtime's
evaluation port (T-028).

## Build plan when triggered

Test-first, as every task (R01): extend the contract test so `updateTable` round-trips each kind.
Then build the grid component with native table, input and button elements and no new library.
Then re-run the rules-editor screen spec with the fallback adapter switched in.
