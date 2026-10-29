# Card

The frosted glass surface under tiles, panels, KPIs and entity cards: `glass` fill, `blur(glass-blur)` backdrop, 1px `glass-b` border, `r-2` (14px) corners and `sh-glass`. It falls back to solid `surface` where `backdrop-filter` is unsupported.

- **Padding:** `pad-card` (20px). Pass `padded={false}` for flush content such as tables.
- **Interactive:** hover lifts 2px with `sh-2`. Make the whole card a single target with a stretched `.ip-card-link` button on its title (FlowCard and EntityCard do this) instead of `onClick` on a div.
- **Title:** use `.ip-card-title` (`title-card`, 14px 700) with 14px below it.
- **Don't** nest cards, or apply card styling to every block on a page. Bottom-row cards (System Health, Quick Actions) are Cards; tables belong in Panels.

```jsx
<Card>
  <h3 className="ip-card-title">System Health</h3>
  {systems.map((s) => <HealthRow key={s.id} {...s} />)}
</Card>
```
