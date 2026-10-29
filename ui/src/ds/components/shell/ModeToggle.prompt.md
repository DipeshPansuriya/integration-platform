# ModeToggle

The topbar's one-click light/dark switch: an IconButton showing a moon in light mode and a sun in dark mode, labelled with the mode it switches to.

- **Provide:** `mode` and `onToggle(next)`. Wire it to the same state as ThemePicker (see `ThemePicker.useConsoleTheme`).

```jsx
const { mode, setMode } = ThemePicker.useConsoleTheme();
<ModeToggle mode={mode} onToggle={setMode} />
```
