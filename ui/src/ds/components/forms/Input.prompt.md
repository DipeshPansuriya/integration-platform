# Input

A text input: 9px 12px padding, 1px `bdr` border, `r-1` corners, `surface` fill, 13px `ink` text, placeholders in `ink3`.

- **Focus:** `accent` border plus a 3px `accent-soft` glow.
- **`code`:** mono + uppercase for identifiers (Flow Name, System Code). Uppercase the stored value too, not just the display.
- **Read-only:** `raised` fill (fixed column names in New Rule).
- **Secrets:** `type="password"`. When editing, never echo a stored secret — show a masked placeholder with a Replace action.
- **Note:** the 1px `bdr` border is about 1.3:1 against `surface`, below the 3:1 WCAG asks of control borders. It's kept from the prototype and flagged in the playbook.

```jsx
<Input placeholder="https://api.partner.com/v2" />
<Input code placeholder="e.g. IMP-SLOT-BOOKING" />
```
