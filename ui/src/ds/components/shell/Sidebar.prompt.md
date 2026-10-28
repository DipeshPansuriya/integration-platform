# Sidebar

The `nav-w` (248px) navigation column on `surface` with a 1px `bdr` right edge. Top to bottom it holds the logo block (LogoMark 32 + "iPaaS Console" + version), the **Operations** group (Dashboard, Queue, Failures, Activity, Reports), the **Configuration** group (Flows, Systems, Contracts, Rules, Mappings) and a footer slot pinned to the bottom.

- **Provide:** `active` (the current id), `onNavigate(id)` or real `href`s, live `count`s in `groups` (Queue uses a neutral count, Failures a danger count showing unresolved failures), and a `footer`. The prototype's "View Login" was a demo link; put account or sign out there.
- Group titles use `label-nav` (10px 700 uppercase .08em `ink3`). Items are NavItems inside a `nav aria-label="Main"`.
- `Sidebar.defaultGroups` holds the standard structure; copy it and change counts rather than inventing new groups.
- **Don't** add a third group without design sign-off, or show counts that are not live.

```jsx
<Sidebar version="v3.2.0" active="flows" onNavigate={(id) => navigate('/' + id)}
  groups={withCounts(Sidebar.defaultGroups, { queue: 24, failures: 3 })}
  footer={<NavItem icon="sign-out" label="Sign out" href="/logout" />} />
```
