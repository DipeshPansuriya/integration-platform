# QuickAction

A shortcut card for the dashboard's 2×2 Quick Actions grid (`QuickAction.Grid`, gap 8). It has 14px padding, `r-1` corners, a `surface` fill and a `bdr` border, with a 34px tinted icon chip, a 12.5px 600 title and a 10.5px `ink3` description.

- **Hover:** `accent` border, 3px `accent-soft` glow, 2px lift.
- **The four actions, in order:** New Flow (accent) · Add System (ok) · New Rule (info) · Import (warn). Each opens the matching create drawer.
- **Provide:** `icon`, `title`, `description`, `tone`, `onClick`. It is a real button.

```jsx
<QuickAction.Grid>
  <QuickAction icon="plus" title="New Flow" description="Create integration" tone="accent" onClick={openNewFlow} />
  <QuickAction icon="systems" title="Add System" description="Register endpoint" tone="ok" onClick={openRegister} />
</QuickAction.Grid>
```
