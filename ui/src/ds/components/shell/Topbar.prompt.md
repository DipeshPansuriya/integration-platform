# Topbar

The 54px (`topbar-h`) sticky bar on `surface`, with a 1px bottom border (`bdr`, `bdr-s` in dark) and 22px side padding. Left to right: product name "Integration Platform" (`product-name`), a 1×20px `bdr` divider, the search slot, and the right cluster (gap 8).

- **Right cluster order:** EnvBadge · ModeToggle · ThemePicker · UserChip.
- **Provide:** `search` (a CommandSearch) and the cluster as children. Pass `onMenu` to get the menu button at ≤800px, where the search hides.
- The ThemePicker popover anchors to this bar: 6px below it, 22px from its right edge.

```jsx
<Topbar search={<CommandSearch />} onMenu={openNav}>
  <EnvBadge env="Production" />
  <ModeToggle mode={mode} onToggle={setMode} />
  <ThemePicker accent={accent} mode={mode} onAccentChange={setAccent} onModeChange={setMode} />
  <UserChip name="Dipesh" initials="DP" />
</Topbar>
```
