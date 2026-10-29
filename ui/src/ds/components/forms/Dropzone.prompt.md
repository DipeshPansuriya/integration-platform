# Dropzone

A drop target for schema files: a 2px dashed `bdr` border, `r-2` corners, `raised` fill and 32px 20px padding. It holds a 28px `ink4` upload icon, a 13px 600 `ink2` title and an 11px `ink3` hint.

- **States:** hover, drag-over and keyboard focus turn the border `accent` and the fill `accent-soft` (an addition — the prototype had no drag state).
- Clicking or pressing Space or Enter opens the file picker. A visually hidden file input carries focus.
- **Provide:** `onFiles(files)`, `accept` (e.g. ".xsd,.json,.wsdl,.edi"), and a different `title` / `hint` if needed.

```jsx
<Dropzone accept=".xsd,.json,.wsdl,.edi" onFiles={(files) => upload(files[0])} />
```
