# Field

A label, one control and optional help text: a 12px 600 `ink2` label 5px above the control, help in 10.5px `ink3` 3px below, and 16px below the field.

- **Wiring:** Field gives its child an id, links the label with `htmlFor` and the help with `aria-describedby`. Pass exactly one Input or Select.
- **Field.Row:** a two-column grid with a 12px gap, for pairs like Protocol + Direction or Timeout + Retry Limit. It stacks to one column at ≤600px.
- **Labels:** Title Case nouns ("Base URL", "Client Secret"). Put units in the label: "Timeout (ms)".
- Switches carry their own label. Don't wrap them in Field.

```jsx
<Field label="Base URL" help="Include the version segment"><Input placeholder="https://api.partner.com/v2" /></Field>
<Field.Row>
  <Field label="Timeout (ms)"><Input type="number" defaultValue={30000} /></Field>
  <Field label="Retry Limit"><Input type="number" defaultValue={3} /></Field>
</Field.Row>
```
