# Icon

Inline SVG line icons copied from the prototype, drawn in `currentColor` so they take the text colour of whatever holds them.

- **Grid:** 16 units, stroke 1.4 (nav, cards, tiles), 1.5 for actions and search, 1.8 for the drawer close. `arrow` is 20×12; `moon`, `sun` and `palette` are 20-unit topbar icons.
- **Sizes:** 14px in nav pills and buttons, 16px in quick actions and the topbar, 18px in entity cards, 20px in stat tiles, 28px in the dropzone.
- **Provide:** `name`; `title` only when the icon stands alone and carries meaning (it then gets `role="img"`). Otherwise icons are decorative and hidden from assistive tech, so the button or link around them needs its own label.
- The same glyphs ship as SVG files in the Icons asset group for non-React use.
- **Don't** mix in another icon set, fill the outline icons, or use emoji.

```jsx
<Icon name="flows" size={14} />
<Icon name="failures" size={20} title="Failures" />  {/* standalone, meaningful */}
```
