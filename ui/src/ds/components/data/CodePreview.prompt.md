# CodePreview

A scrollable schema or payload sample: `raised` fill, `bdr` border, `r-1` corners, 14px 16px padding, `code` type (mono 11.5px, line-height 1.7), max-height 180px.

- **Colouring:** tag, attribute and JSON key names in `accent-ink`; strings and text values in `ok-t`; numbers in `warn-t`.
- **Provide:** `code`, `language` (`xml`, `json` or `plain`) and a `label` for the focusable scroll region.
- It's used in the Contract detail drawer's Schema Preview.

```jsx
<CodePreview language="xml" code={schemaSample} />
```
