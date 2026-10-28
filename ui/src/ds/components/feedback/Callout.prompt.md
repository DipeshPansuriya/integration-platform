# Callout

A tinted result box: 12px 14px padding, `r-1` corners, the tone's `-bg` fill and a 1px tone border, with a 12px 600 title and an 11px body, both in the tone's `-t` colour.

- **Where:** the Rule detail drawer's Test Rule result ("Match: Row 1 · Route To: ACS Gateway · Priority: Normal"). The **No match** state (`warn`) is an addition — the prototype only showed a match.
- It's a polite live region (`role="status"`), so changes are announced as inputs change.
- **Provide:** `tone`, `title` and the body as children.

```jsx
<Callout tone="ok" title="Match: Row 1">Route To: <strong>ACS Gateway</strong> · Priority: <strong>Normal</strong></Callout>
```
