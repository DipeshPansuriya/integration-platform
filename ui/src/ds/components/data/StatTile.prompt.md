# StatTile

The dashboard KPI tile and the first row of the Overview pattern: four tiles in `ip-grid-tiles` (2 columns at ≤1100px, 1 at ≤600px).

- **Anatomy:** a 3px tone strip on the left edge; a 38px icon chip in the tone's tint with a pulsing ring; a `label-tile` label (uppercase); a `value-tile` value (30px 800, tabular); a sub-line with the delta in `ok-t` (up) or `err-t` (down); and a 9-bar sparkline in the tone at .2 opacity (.4 on hover).
- **Tones by meaning:** Active Flows = `accent`, Processed Today = `ok`, In Queue = `warn`, Failures = `err`.
- **Provide:** `tone`, `icon`, `label`, `value`, optional `delta` + `deltaDirection`, `sub` (text or nodes), and `spark` (9 values, 0–100).
- **Motion:** fades up on route change with a .06s stagger. Never re-animate on data refresh.

```jsx
<div className="ip-grid-tiles">
  <StatTile tone="accent" icon="flows" label="Active Flows" value="47" delta="+3" sub="this week" spark={[40,65,50,80,55,70,45,60,75]} />
  <StatTile tone="err" icon="failures" label="Failures" value="3" delta="2" deltaDirection="down" sub="retrying" spark={spark} />
</div>
```
