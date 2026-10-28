# Panel

A glass section with a title row: 16px 20px 12px padding, a 1px `bdr` divider, and a `title-card` h3. An `aside` slot on the right takes a link (11.5px 600 `accent-ink`, e.g. "See all", "Auto-refresh: ON") or a Segmented control.

- **Body:** flush by default so DataTables run edge to edge. Use `padded` (20px) for timelines and free content.
- **Where:** Throughput and Status Mix on the dashboard. On Queue, Failures, Reports and Contracts, a single Panel holds the page's table, with no entrance animation.

```jsx
<Panel title="Throughput" aside={<Segmented value={period} onChange={setPeriod} />}>
  <LineChart data={points} max={100} />
  <DataTable columns={cols} rows={topFlows} />
</Panel>
```
