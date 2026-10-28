# StatusBadge

The status pill: 3px 9px padding, `r-badge` corners and `badge` type (10.5px 600) in the tone's `-t` colour on its `-bg` tint. The word always shows; colour only reinforces it.

- **Vocabulary** (`StatusBadge.vocabulary`, the single source of truth):
  - Flow status: Active `ok` + pulsing dot · Draft `warn` + dot · Error `err` + dot · Paused `muted` + dot
  - Queue item: Pending `warn` · Processing `ok` · Retry #n `err`
  - Priority: High and Critical `err` · Normal and Low `muted`
  - System health: Healthy `ok` · Slow `warn` · Down `err`
  - Message result: OK `ok` (`size="xs"` in drawer tables)
  - Contract format: XML and JSON `ok` · EDIFACT `warn` — this borrows status colours for data that isn't a status; the playbook suggests neutral.
- **Provide:** `status` (a vocabulary word). `tone` and `dot` override the mapping, but don't use them to invent new meanings. Unknown words render neutral.

```jsx
<StatusBadge status="Active" />      {/* tone + pulsing dot from the vocabulary */}
<StatusBadge status="Retry #2" />
<StatusBadge status="OK" size="xs" />
```
