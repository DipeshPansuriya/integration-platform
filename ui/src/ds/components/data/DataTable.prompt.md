# DataTable

The console table. The header row is `raised` with `label-table` headings (10px 700 uppercase .04em `ink3`). Cells are 12.5px `ink2` with 10px × `pad-card` padding and 1px `bdr` row rules, with no rule after the last row. Rows wash `accent-soft` on hover.

- **Columns:** `{ key, header, align, mono, numeric, render, width }`. Identifiers use `mono` (JetBrains Mono 11.5px 600 `ink`). Numeric columns are `align: 'right'` + `numeric` (tabular figures). Statuses render a StatusBadge.
- **Clickable rows:** pass `onRowClick`. Rows become focusable and respond to Enter and Space, with a 2px inset `accent` focus ring.
- **Failures pattern:** the Error cell is the code (UPPER_SNAKE, `err-t` 600) over a one-line message in 11px `ink3`. The action is Retry (small) until retries are exhausted, then Escalate (small, danger).
- **Provide:** `columns`, `rows`, `rowKey`, optional `caption` (screen-reader only). The table scrolls horizontally inside its panel on narrow screens.

```jsx
<DataTable rowKey="flow" rows={flows} onRowClick={(r) => openFlow(r.flow)} columns={[
  { key: 'flow', header: 'Flow', mono: true },
  { key: 'status', header: 'Status', render: (r) => <StatusBadge status={r.status} /> },
  { key: 'last', header: 'Last Run' },
  { key: 'msgs', header: 'Messages', align: 'right', numeric: true }]} />
```
