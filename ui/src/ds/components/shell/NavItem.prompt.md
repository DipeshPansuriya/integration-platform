# NavItem

A single sidebar link: a 26px icon pill (7px corners, `muted` fill), a 13px 500 label in `ink3`, and an optional count.

- **States:** hover = `accent-soft` wash and `ink2` text. Active (`aria-current="page"`) = `accent-faint` fill, `accent-ink` label, and the pill filled `accent` with an `on-accent` icon and `halo` glow.
- **Count:** neutral = `muted` / `ink3`, danger = `err-bg` / `err-t`. Both use 10px 700 tabular figures.
- **Provide:** `icon`, `label`, `active`, `href` or `onClick`, and optional `count` + `countTone`. It renders a real `<a>`, so keyboard and middle-click work.

```jsx
<NavItem icon="failures" label="Failures" count={3} countTone="danger" href="/failures" active />
```
