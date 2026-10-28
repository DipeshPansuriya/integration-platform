# MappingCanvas

A static preview of a mapping in the Mapping detail drawer. On a `raised` box (minimum 200px tall), the source node (accent dot, top left), the target node (ok dot, top right) and the transform node (warn dot, centre) are joined by dashed `bdr-s` connectors.

- It measures its nodes, so the connectors stay attached at any width.
- **Placeholder only:** the real drag-and-drop mapping designer is not designed yet. Don't extend this into an editor without a design.

```jsx
<MappingCanvas source="FWB (XML)" transform="Transform" target="CUSTOMS (JSON)" />
```
