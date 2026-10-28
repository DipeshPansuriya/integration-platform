# Icons

The console's own line icons as standalone SVGs: a 16-unit grid (20 for `moon`, `sun` and `palette`; 20×12 for `arrow`), stroke 1.4, round caps and joins.

- **Single ink:** every file is drawn in #646c8c (`ink3` in light mode), which reads on light and dark tiles. An `<img>` cannot inherit colour, so to recolour, inline the SVG and set its `stroke` to `currentColor`, or use the `Icon` component (same glyphs, `currentColor`, sized by prop).
- `palette.svg` keeps its three dots in the Indigo light values of `accent`, `ok` and `err`.
- `moon.svg` is a corrected crescent. The prototype's moon path retraced one half-circle and rendered blank.
- Names match `Icon`'s `name` prop: dashboard, queue, failures, activity, reports, flows, systems, contracts, rules, mappings, mapping-json, mapping-xml, document, rows, trend, check, plus, close, refresh, search, upload, sign-in, sign-out, grid-4, chevron-down, arrow, moon, sun, palette.
