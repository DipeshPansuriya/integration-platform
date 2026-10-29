# FlowCard

One integration flow in the Flows grid (`ip-grid-flows`, auto-fill, minimum 300px). The header row has the flow name in `id-lg` (mono 13px 600) and its StatusBadge. Below are a 12.5px `ink3` description, a meta row (11.5px with `<strong>` values) and a 3px progress bar.

- **Meta by status:** Active "Last run · Messages"; Error "Last run · Errors" (count tone `err`); Draft "Created · Version"; Paused "Paused · Messages".
- **Bar:** `accent` → `purple` gradient; an `err` gradient for Error, `warn` for Draft, 0% for Paused. **The prototype doesn't define what the bar measures** — confirm with product before wiring it.
- **Interactive:** pass `onOpen` and the name becomes a stretched button covering the card. Hover lifts 2px with `sh-2` and an `accent` border. It opens the Flow detail drawer.

```jsx
<FlowCard name="CUSTOMS-RESPONSE" status="Error" progress={45}
  description="Processes customs clearance responses and updates shipment status"
  meta={[{ label: 'Last run', value: '12m ago' }, { label: 'Errors', value: '3', tone: 'err' }]}
  onOpen={() => openFlow('CUSTOMS-RESPONSE')} />
```
