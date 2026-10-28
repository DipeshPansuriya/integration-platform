# DonutChart

The dashboard's Status Mix: a 150px ring (radius 55, stroke 13, round caps) starting at 12 o'clock. The total sits in the centre in `value-ring` with a 10.5px `ink3` label, and a full-width legend below (10px swatches with 3px corners, 12px `ink2` labels, 700 tabular values on the right).

- **Segments:** Active `ok`, Draft `warn`, Error `err`, Paused `muted` (drawn in `ink3`; the prototype's `ink4` was too faint).
- **Provide:** `segments` `{ label, value, tone }`, plus optional `total` and `label` ("Total Flows").
- The ring has an accessible summary that lists every value, so the legend is not the only text.

```jsx
<DonutChart label="Total Flows" segments={[
  { label: 'Active', value: 38, tone: 'ok' }, { label: 'Draft', value: 5, tone: 'warn' },
  { label: 'Error', value: 3, tone: 'err' }, { label: 'Paused', value: 1, tone: 'muted' }]} />
```
