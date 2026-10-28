# KeyValue

A label (12px `ink3`) on the left and a bold value (`ink2`) on the right, with 4px vertical padding. It's used for the facts on entity cards and the Overview / Info / Configuration sections of drawers.

- **Value tones:** status values take their tone's `-t` colour (`tone="ok"` for "Healthy").
- **Mono:** URLs, paths and codes use `mono` (11px). Long values wrap instead of overflowing.
- **Provide:** `label`, `value`, and optional `tone` and `mono`.

```jsx
<KeyValue label="Status" value="Healthy" tone="ok" />
<KeyValue label="Base URL" value="https://acs.kale.internal/api" mono />
```
