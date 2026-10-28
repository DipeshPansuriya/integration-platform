# Segmented

A compact period switch for panel headers: buttons with 3px 9px padding, `r-badge` corners and 10.5px 600 `ink3` text. Selected = `accent-faint` fill, `accent-fi` text and an `accent-line` border; inactive hover = `muted`.

- **Default options:** 1H · 24H · 7D · 30D, with 24H selected.
- **Provide:** `value` + `onChange` (controlled) or `defaultValue`. Buttons expose `aria-pressed`; the group takes `label`.

```jsx
<Segmented value={period} onChange={setPeriod} />   {/* 1H · 24H · 7D · 30D */}
```
