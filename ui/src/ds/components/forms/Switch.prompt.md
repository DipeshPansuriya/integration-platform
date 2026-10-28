# Switch

An on/off toggle with its label: a 36×20 track (`muted` + `bdr` when off, `accent` when on) with a 16px `switch-knob` knob and `sh-card`, sliding 16px. The label is 12.5px 500 `ink2`, 14px below each switch.

- It's a `button` with `role="switch"` and `aria-checked`. The prototype used a span.
- **Provide:** `label`, plus `checked` + `onChange` or `defaultChecked`.
- **Defaults from the prototype:** dead-letter queue on, alert on failure on, health monitoring on, auto-generate validation rules on, auto-map matching field names on; sample mapping template and sample data off.

```jsx
<Switch label="Enable dead-letter queue" checked={dlq} onChange={setDlq} />
```
