# PageHeader

The top of every page: an h1 in `title-page` (24px 800 -.03em) with a 13px `ink3` subtitle on the left, actions on the right, and `space-header` (22px) below it. It wraps on narrow screens.

- **Actions:** secondary buttons first, then at most one primary, last. The primary opens the page's create drawer ("New Flow", "Register System", "Import Contract", "New Rule", "New Mapping").
- **Subtitle:** a count or a plain description ("47 integration flows configured", "Message schema definitions").

```jsx
<PageHeader title="Flows" subtitle="47 integration flows configured"
  actions={<><Button>Filter</Button><Button variant="primary" icon="plus">New Flow</Button></>} />
```
