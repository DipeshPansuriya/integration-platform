# DecisionTable

A fully bordered mono grid (1px `bdr` on every cell) for rule decision tables and field mappings. Headers match DataTable at 8px 12px. Cells use `mono-cell` (11px).

- **Column kinds:** `cond` = conditions and source paths in `accent-ink` 600; `out` = outputs and target fields in `ok-t` 600, header included; `plain` = transforms and arrows.
- **Rules semantics as drawn:** rows are read top-down and the first match wins; `*` means any; the last row is the all-`*` catch-all. Confirm the hit policy with the rules engine.
- **Provide:** `columns` `{ key, header, kind }` and `rows`. Put it inside a drawer section with `overflow-x` (built in).

```jsx
<DecisionTable columns={[
  { key: 'o', header: 'Origin', kind: 'cond' }, { key: 'c', header: 'Cargo Type', kind: 'cond' },
  { key: 'r', header: 'Route To', kind: 'out' }]}
  rows={[{ o: 'BOM', c: 'General', r: 'ACS Gateway' }, { o: '*', c: '*', r: 'ACS Gateway' }]} />
```
