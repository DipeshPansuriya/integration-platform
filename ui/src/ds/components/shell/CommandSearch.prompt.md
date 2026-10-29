# CommandSearch

A global search field: 36px tall, frosted `glass` with a `cmd-blur` backdrop, a `glass-b` border, `r-1` corners and `sh-card`. It holds a 14px search icon, a 13px input and a `kbd` hint in mono 10px.

- **Shortcut:** Ctrl K (⌘K on macOS) focuses it from anywhere. The component registers the shortcut; the prototype only showed the hint.
- **States:** hover = `bdr-s` + `sh-1`; focus = `accent` border + 3px `accent-soft` glow.
- **Provide:** `onSubmit(value)` or `onChange(value)`, and a different `placeholder` if the scope changes. The default is "Search flows, systems, contracts...".
- It hides at ≤800px. Offer search from the page there if it matters.

```jsx
<CommandSearch onSubmit={(q) => navigate('/search?q=' + encodeURIComponent(q))} />
```
