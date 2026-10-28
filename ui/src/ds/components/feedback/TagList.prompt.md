# TagList

A wrapped row of tags: 3px 10px padding, `r-badge` corners, 11px 600 in `accent-fi` on `accent-faint` with an `accent-line` border, and a 6px gap.

- **Where:** system tags (lowercase: production, external, customs), and "Used by" sections in detail drawers, where the entries are flow names — set `mono`.
- **Provide:** `tags` (strings) and a `label` for the list.
- Tags are not interactive. If they become filters, make each one a button.

```jsx
<TagList tags={['production', 'external', 'customs']} label="Tags" />
<TagList mono tags={usedBy} label="Used by flows" />
```
