# EnvBadge

A topbar pill naming the environment: glass with an `env-blur` backdrop, 7px corners, `env` type (10.5px 600 uppercase .04em) in `ink3`, and a 6px dot pulsing every 2s.

- **Provide:** `env` and `tone`. The prototype shows only Production with an `ok` dot. Staging = `warn` and Dev = `info` are suggestions to confirm with product.
- The word carries the meaning; the dot only reinforces it.

```jsx
<EnvBadge env="Production" tone="ok" />
<EnvBadge env="Staging" tone="warn" />
```
