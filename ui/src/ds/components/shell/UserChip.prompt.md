# UserChip

The signed-in user at the end of the topbar: a bordered chip with 9px corners holding a 26px avatar and the first name in 12px 500 `ink2`.

- **Avatar:** 10px 700 initials in `on-accent` on a `btn-from` → `accent-h` gradient in light mode (white on the plain Emerald, Amber or Cyan accent fails 4.5:1), or `accent` → `purple` in dark mode.
- **Provide:** `name`, optional `initials` (derived from the name otherwise), and `onClick` to open the account menu (not designed yet).

```jsx
<UserChip name="Dipesh" initials="DP" onClick={openAccountMenu} />
```
