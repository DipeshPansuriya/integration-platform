# IconButton

A 34px square, 9px corners, `surface` fill and 1px `bdr` border with a 16px `ink3` icon. It's used in the topbar for the mode toggle, the theme picker and the ≤800px menu button.

- **Provide:** `icon` (or custom children) and **`label`**, which becomes the accessible name and tooltip. It is required.
- **Hover:** border `bdr-s` and `sh-1`. Focus: `focus-ring`.
- **Don't** use it for a primary action. If the action needs a word to be understood, use Button.

```jsx
<IconButton icon="refresh" label="Refresh" onClick={reload} />
```
