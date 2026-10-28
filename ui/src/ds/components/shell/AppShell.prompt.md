# AppShell

The full-height console frame: Sidebar on the left, then a column with the sticky Topbar over `main.ip-content`. The content area is the only thing that scrolls. Its padding is `pad-page-top` / `pad-page-x` / `pad-page-bottom`, and it paints `--wash` (accent 6% + `purple` or `ok` 4% radial washes) over `bg`.

- **Provide:** `sidebar` (a Sidebar), `topbar` (a Topbar), and the page as children (PageHeader first).
- **≤800px:** the sidebar hides. Pass `onMenu` to Topbar and wire it to `navOpen` / `onNavClose` here, and the sidebar slides in as an off-canvas panel over a scrim. The prototype had no mobile navigation; this is the required addition.
- **Theme:** set `data-theme` and `data-accent` on `<html>` (see `ThemePicker.useConsoleTheme`). The `html` and `body` elements need `height: 100%`, which bundle.css sets.
- **Don't** nest a second scroll container inside the content area, or put the topbar inside `main`.

```jsx
const [nav, setNav] = useState(false);
<AppShell
  navOpen={nav} onNavClose={() => setNav(false)}
  sidebar={<Sidebar version="v3.2.0" active={route} onNavigate={go} />}
  topbar={<Topbar search={<CommandSearch onSubmit={search} />} onMenu={() => setNav(true)}>
    <EnvBadge /> <ModeToggle mode={mode} onToggle={setMode} />
    <ThemePicker accent={accent} mode={mode} onAccentChange={setAccent} onModeChange={setMode} />
    <UserChip name="Dipesh" initials="DP" />
  </Topbar>}
>
  <PageHeader title="Dashboard" subtitle="Real-time integration overview" />
  {/* page body */}
</AppShell>
```
