# Drawer

The right-hand panel for every create and detail view. It is `drawer-w` (520px, max 92vw) on `surface` over an `overlay` scrim and slides in from the right in 300ms `cubic-bezier(.2,.8,.3,1)`.

- **Anatomy:** a sticky head with the title in `title-drawer` (18px 800) and a 32px close button; a body with `pad-drawer` (24px) padding, split into `Drawer.Section`s whose labels use `label-section` (11px 700 uppercase .06em `ink3`, 20px above each except the first); a sticky footer with right-aligned actions.
- **Footer order:** Cancel or Close first, then secondary actions (Save as Draft, Test Connection, Pause Flow), then one primary last (Register, Create & Deploy, Edit Flow).
- **Behaviour:** closes on ×, overlay click and Esc. `role="dialog"` with `aria-modal`, focus moves to the first field, Tab is trapped, and focus returns to the trigger on close. One drawer at a time.
- **Section order:** detail drawers go Overview → the visual section (pipeline, decision table, schema, KPIs) → "Used by" tags → recent activity. Create drawers go identity → source/connection → processing → target → error handling/options → tags.
- **Provide:** `open`, `title`, `onClose`, `footer`, and sections as children.

```jsx
<Drawer open={open} title="Register System" onClose={close}
  footer={<><Button onClick={close}>Cancel</Button><Button>Test Connection</Button><Button variant="primary">Register</Button></>}>
  <Drawer.Section title="Basic Information">
    <Field label="System Name"><Input placeholder="e.g. Customs Gateway" /></Field>
  </Drawer.Section>
</Drawer>
```
