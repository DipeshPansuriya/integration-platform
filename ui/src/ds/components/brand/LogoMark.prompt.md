# LogoMark

The console mark: the Flows glyph in `on-accent` on a 135° `accent` → `purple` tile with a `halo` glow, so it follows the active accent and mode.

- **Sizes:** 32px with `r-1` corners in the sidebar logo block (beside "iPaaS Console" in `title-card` and the version in 10px `ink3`); 44px with 13px corners on the login card.
- **Provide:** `size` and, if the product name differs, `label` (the accessible name).
- **Don't** recolour it, add a wordmark inside the tile, or use it as a decorative bullet.
- The prototype's glyph had its first circle clipped at the top of the 16px box; this component uses the corrected Flows icon geometry.

```jsx
<LogoMark size={32} />          {/* sidebar */}
<LogoMark size={44} />          {/* login card */}
```
