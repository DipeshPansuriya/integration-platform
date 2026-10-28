# KpiCard

A centred value over a label on a glass card. Three sizes come from the prototype:

- **lg** — the Queue page's three stats (`ip-grid-stats`): `value-stat` 28px, label 11.5px 500, padding 18px 20px.
- **md** — Reports and other four-up KPI rows (`ip-grid-kpi`): `value-kpi` 24px, label 11px.
- **sm** — inside drawers (System detail "Performance (Last 24h)"): `value-kpi-sm` 18px. At this size a tone switches to its `-t` text colour so it stays above 4.5:1.
- **Tone by meaning:** `ok` for good numbers, `err` for failures, `accent` for a neutral highlight, none for plain counts. Never colour a number for decoration.

```jsx
<div className="ip-grid-kpi">
  <KpiCard tone="ok" value="99.2%" label="Success Rate" />
  <KpiCard value="12,847" label="Delivered Today" />
</div>
<KpiCard size="lg" tone="accent" value="24" label="Pending" />
```
