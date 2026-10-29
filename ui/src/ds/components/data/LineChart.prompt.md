# LineChart

The Throughput chart: a 2.5px `accent` line with round joins, an area gradient from `accent` at .25 to 0, four 0.5px `bdr` gridlines, mono 10px `ink3` axis labels, and an end-point dot (r 4, `accent` fill, 2px `surface` stroke).

- **Provide:** `data` (one value per hour, 24 for 24H), `max` (top of the scale), and optional `xLabels` as `{ at, text }`.
- **Drawn to scale:** it measures its container and draws at the real width, so labels never stretch (the prototype used `preserveAspectRatio="none"`). Hour labels sit on their own index, so "20" marks hour 20 instead of the right edge.
- Colours come from CSS, so the chart follows theme and mode changes without redrawing.
- Place it inside a Panel, between the header and the flow table.

```jsx
<LineChart data={hourlyCounts} max={100} label="Throughput, last 24 hours" />
```
