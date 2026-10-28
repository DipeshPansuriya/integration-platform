# ThemePicker

The topbar theme control. A palette IconButton opens a 200px popover (`surface`, `bdr`, `r-2`, `sh-2`, fadeUp .2s) with **Theme color** — six swatches in a 3-column grid — and **Mode**, a Light / Dark pair.

- **Accents:** indigo (default), emerald, rose, amber, cyan, violet. A swatch paints its own palette token (`--indigo` …). Selected = 2px `ink` border plus a `surface` / `ink` double ring.
- **Mode buttons:** selected = `btn-from` fill with `on-accent` text.
- **Provide:** `accent`, `mode`, `onAccentChange`, `onModeChange`. It closes on outside click and Esc. Use `inline` to render the panel without the trigger.
- **Engine:** `ThemePicker.useConsoleTheme()` sets `data-accent` and `data-theme` on `<html>` and persists them to localStorage (`ip-t`, `ip-m`, wrapped in try/catch). Store the choice in user preferences server-side in production. With nothing saved, it follows `prefers-color-scheme`. Apply the attributes before first paint to avoid a flash.
- **Watch:** Emerald's accent equals `ok` and Amber's equals `warn`, so accent UI can read as status in those themes (see the playbook).

```jsx
const theme = ThemePicker.useConsoleTheme();   // sets data-accent + data-theme on <html>
<ThemePicker accent={theme.accent} mode={theme.mode}
  onAccentChange={theme.setAccent} onModeChange={theme.setMode} />
```
