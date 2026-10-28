# Button

The console's one button. **Secondary** is the default; **primary** is the gradient `btn-from` → `accent-h` fill with `on-accent` text and a `halo` glow, used once per page header or drawer footer and always placed last.

- **Variants:** `secondary` (`surface`, 1px `bdr`, `ink2`), `primary`, `danger` (secondary with `err-t` text, e.g. "Escalate" when retries are exhausted).
- **Sizes:** `md` 8px 16px / 13px 600 (`control`); `sm` 4px 10px / 11px for table row actions; `lg` + `block` for the full-width login buttons.
- **Provide:** the label (Title Case, verb first: "New Flow", "Create & Deploy"), optional `icon` (14px, 16 at `lg`), `onClick`, `type` for form submits.
- **Dark mode:** primary becomes a flat `accent` fill with near-black `on-accent` text — white fails on the light dark-mode accents.
- **Don't** put two primaries side by side (the prototype's Register System footer did; Test Connection is secondary), or use primary for destructive actions.

```jsx
<Button icon="refresh">Refresh</Button>
<Button variant="primary" icon="plus" onClick={openNewFlow}>New Flow</Button>
<Button size="sm">Retry</Button>
<Button size="sm" variant="danger">Escalate</Button>
```
