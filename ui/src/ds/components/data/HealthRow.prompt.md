# HealthRow

One system in the dashboard's System Health card: an 8px status dot with a glow of the same colour, the name (12.5px 500 `ink`), the latency right-aligned (`mono-sm`, `ink3`), and a 60px status word.

- **Status:** Healthy = `ok`; Slow = `warn` when latency is above the system's threshold (200ms default); Down = `err` for a timeout or unreachable system, with a dot that pulses every 1.5s and the word in `err-t`.
- **Provide:** `name`, `latency` ("12ms", "Timeout") and `status`. Rows separate with 1px `bdr` rules, none after the last.

```jsx
<HealthRow name="Customs API" latency="320ms" status="Slow" />
```
