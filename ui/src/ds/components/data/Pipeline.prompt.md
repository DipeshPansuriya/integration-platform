# Pipeline

The flow's stages as outlined chips (8px 14px, `r-1`, 2px border, 12px 600) joined by 20×12 `ink4` arrows. It wraps on narrow drawers.

- **Stage roles:** source = `accent` (text `accent-ink`); validate = `warn`; transform = `purple` (text mixed toward `ink` so it passes 4.5:1 in every accent); target = `ok`.
- **Provide:** `stages` `{ label, role }`. It's used in the Flow detail drawer's Pipeline section.

```jsx
<Pipeline stages={[{ label: 'SITA Inbound', role: 'source' }, { label: 'Validate', role: 'validate' },
  { label: 'Transform', role: 'transform' }, { label: 'ACS Gateway', role: 'target' }]} />
```
