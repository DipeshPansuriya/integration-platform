# UI Redesign — Indigo Accent V4

Execute the visual redesign of the Integration Platform Console UI. This is a CSS-token-driven redesign — the codebase uses `--color-*` tokens for ALL colours, so most changes propagate from one file (`tokens.css`).

**Full plan with rationale:** `ui/PLAN-ui-redesign-v4.md`

---

## SAFETY RULES — READ FIRST

**DO NOT CHANGE any of these:**
- Hash routes (`#/flows`, `#/versions`, `#/queue`, etc.) in `App.tsx`
- `data-nav` attributes (`data-nav="dashboard"`, `data-nav="flows"`, etc.) — E2E test hooks
- `data-action` attributes (`data-action="toggle-nav"`, `data-action="toggle-theme"`, `data-action="account"`, `data-action="logout"`)
- TypeScript interfaces, props, state variables, API calls, imports (except adding CSS imports or `className`)
- Routing logic: `parseHash()`, `hashMove()`, `editorGuard`, `setRoute`, `setRouteSeq`
- Session/auth: `checkSession()`, `/api/v1/me`, `logout()`, `onLogin`, `session` state
- Third-party subtrees: JDM `[data-decision-table]`, xyflow `[data-canvas]`, CodeMirror `.raw-expression`
- Existing CSS class names referenced in TSX — only ADD new classes or change CSS rules behind existing names

**SAFE to change:** Token values in `tokens.css`, CSS rules in `base.css` + per-page CSS files, adding new CSS classes, adding `className` to JSX (additive), adding a `<link>` in `index.html`.

---

## EXECUTION ORDER

Run steps in this exact order. After each step, run `npx vite build` to verify no compilation errors.

---

### Step 1: index.html — Google Fonts + pre-paint colours

**File:** `ui/index.html`

**1a.** Add Google Fonts link inside `<head>`, BEFORE the inline `<script>`:
```html
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Inter:opsz,wght@14..32,400;14..32,500;14..32,600;14..32,700;14..32,800&family=JetBrains+Mono:wght@400;500&display=swap">
```

**1b.** Update the pre-paint `<style>` block (lines 26-30) — hex values MUST match the new `--color-canvas` tokens:
```css
html { background: #f0f2f5; }
html[data-theme="dark"] { background: #08090e; }
```

---

### Step 2: tokens.css — Replace ALL token values

**File:** `ui/src/tokens.css`

Replace the ENTIRE `:root, [data-theme="light"]` block with:

```css
:root,
[data-theme="light"] {
  --color-canvas: #f0f2f5;
  --color-surface: #ffffff;
  --color-surface-raised: #f7f8fa;
  --color-surface-muted: #eef0f4;
  --color-ink: #0f1729;
  --color-ink-muted: #3d4663;
  --color-ink-faint: #6b7394;
  --color-ink-ghost: #a0a7be;
  --color-border: #dfe2ea;
  --color-border-strong: #c7ccd8;
  --color-border-control: #7f8ea3;

  --color-accent: #4f46e5;
  --color-accent-hover: #4338ca;
  --color-link: #4f46e5;
  --color-accent-faint: #eef2ff;
  --color-accent-faint-ink: #3730a3;
  --color-accent-soft: rgba(79, 70, 229, 0.06);
  --color-halo: rgba(79, 70, 229, 0.22);

  --color-success: #059669;
  --color-success-text: #065f46;
  --color-success-bg: #ecfdf5;
  --color-warn-text: #92400e;
  --color-warn-bg: #fffbeb;
  --color-danger: #dc2626;
  --color-danger-text: #991b1b;
  --color-danger-bg: #fef2f2;
  --color-neutral-text: #3d4663;
  --color-neutral-bg: #eef0f4;

  --color-focus: #4f46e5;
  --color-focus-dark: #818cf8;
  --color-glass: rgba(255, 255, 255, 0.78);
  --shadow-card: 0 1px 2px rgba(15, 23, 41, 0.06);
  --shadow-1: 0 1px 3px rgba(15, 23, 41, 0.05), 0 6px 16px -4px rgba(15, 23, 41, 0.08);
  --shadow-2: 0 4px 6px rgba(15, 23, 41, 0.04), 0 12px 32px -4px rgba(15, 23, 41, 0.12);
  --shadow-btn: 0 2px 8px rgba(79, 70, 229, 0.25);

  --font-body: 'Inter', system-ui, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
  --font-mono: 'JetBrains Mono', ui-monospace, "Cascadia Mono", "SF Mono", Consolas, monospace;

  --r-1: 8px;
  --r-2: 12px;
  --r-3: 14px;
  --r-pill: 999px;
  --tr: 150ms cubic-bezier(0.2, 0.8, 0.3, 1);
  --space-1: 4px; --space-2: 8px; --space-3: 12px; --space-4: 16px;
  --space-5: 24px; --space-6: 32px; --space-7: 40px; --space-8: 48px;

  --nav-w: 252px;
  --nav-w-collapsed: 64px;
  --topbar-h: 52px;
  --content-max: 1140px;
}
```

Replace the ENTIRE `[data-theme="dark"]` block with:

```css
[data-theme="dark"] {
  --color-canvas: #08090e;
  --color-surface: #12131c;
  --color-surface-raised: #181a25;
  --color-surface-muted: #1e2030;
  --color-ink: #eceef4;
  --color-ink-muted: #9498b0;
  --color-ink-faint: #5c6180;
  --color-ink-ghost: #3a3e55;
  --color-border: #1f2133;
  --color-border-strong: #2d3048;
  --color-border-control: #6b7a90;

  --color-accent: #818cf8;
  --color-accent-hover: #6366f1;
  --color-link: #818cf8;
  --color-accent-faint: rgba(99, 102, 241, 0.1);
  --color-accent-faint-ink: #c7d2fe;
  --color-accent-soft: rgba(129, 140, 248, 0.06);
  --color-halo: rgba(129, 140, 248, 0.28);

  --color-success: #34d399;
  --color-success-text: #6ee7b7;
  --color-success-bg: rgba(5, 150, 105, 0.1);
  --color-warn-text: #fcd34d;
  --color-warn-bg: rgba(217, 119, 6, 0.1);
  --color-danger: #f87171;
  --color-danger-text: #fca5a5;
  --color-danger-bg: rgba(220, 38, 38, 0.1);
  --color-neutral-text: #9498b0;
  --color-neutral-bg: rgba(148, 163, 184, 0.08);

  --color-focus: #818cf8;
  --color-focus-dark: #818cf8;
  --color-glass: rgba(18, 19, 28, 0.72);
  --shadow-card: 0 1px 2px rgba(0, 0, 0, 0.3);
  --shadow-1: 0 1px 3px rgba(0, 0, 0, 0.4), 0 0 0 1px rgba(255, 255, 255, 0.03);
  --shadow-2: 0 4px 12px rgba(0, 0, 0, 0.5), 0 0 0 1px rgba(255, 255, 255, 0.04);
  --shadow-btn: 0 2px 12px rgba(129, 140, 248, 0.2);
}
```

Keep the file header comment. Keep the blank line at end of file.

---

### Step 3: base.css — Shell + primitives

**File:** `ui/src/ui/base.css`

Make these changes in order. For each, find the existing rule and modify only the listed properties — keep everything else.

**3a. `.btn`** — add `font-weight: 600;` and `box-shadow: var(--shadow-card);`

**3b. `.btn:hover`** — add `box-shadow: var(--shadow-1);`

**3c. `.badge`** — change `border-radius` to `6px`, change `font-weight` to `600`

**3d. Add table hover** (after existing `.table td` rule):
```css
.table tbody tr {
  transition: background-color var(--tr);
}
.table tbody tr:hover {
  background: var(--color-accent-soft);
}
```

**3e. `.topbar`** — change `padding` to `0 var(--space-5)`, change `background` to `var(--color-surface)`. Remove any `[data-theme="dark"] .topbar` backdrop-filter rule if present.

**3f. `.brand`** — change `font-size` to `14px`, `font-weight` to `700`, add `letter-spacing: -0.02em;`

**3g. Add topbar separator** (after .brand rules):
```css
.topbar-sep {
  width: 1px;
  height: 22px;
  background: var(--color-border);
  flex-shrink: 0;
}
```

**3h. Add command palette search** (after topbar section):
```css
.cmd-search {
  flex: 1;
  max-width: 460px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  gap: var(--space-2);
  height: 36px;
  padding: 0 14px;
  background: var(--color-surface-raised);
  border: 1px solid var(--color-border);
  border-radius: var(--r-1);
  cursor: text;
  transition: border-color var(--tr), box-shadow var(--tr);
}
.cmd-search:hover {
  border-color: var(--color-border-strong);
}
.cmd-search:focus-within {
  border-color: var(--color-accent);
  box-shadow: 0 0 0 3px var(--color-accent-soft);
}
.cmd-search svg {
  color: var(--color-ink-ghost);
  flex-shrink: 0;
}
.cmd-search input {
  flex: 1;
  border: 0;
  background: transparent;
  color: var(--color-ink);
  font: inherit;
  font-size: 13px;
  outline: none;
}
.cmd-search input::placeholder {
  color: var(--color-ink-ghost);
}
.cmd-search kbd {
  padding: 2px 7px;
  border: 1px solid var(--color-border);
  border-radius: 4px;
  background: var(--color-surface);
  font: inherit;
  font-size: 10.5px;
  font-weight: 600;
  color: var(--color-ink-ghost);
  line-height: 1.5;
}
@media (max-width: 900px) {
  .cmd-search { display: none; }
}
```

**3i. Add environment badge:**
```css
.env-badge {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 11px;
  border-radius: 6px;
  border: 1px solid var(--color-border);
  font-size: 11px;
  font-weight: 600;
  color: var(--color-ink-muted);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.env-badge-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-success);
}
```

**3j. Add avatar button:**
```css
.avatar-btn {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 4px 10px 4px 5px;
  border-radius: 7px;
  border: 1px solid var(--color-border);
  background: var(--color-surface);
  cursor: pointer;
  transition: border-color var(--tr);
}
.avatar-btn:hover {
  border-color: var(--color-border-strong);
}
.avatar-circle {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: var(--color-accent);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10.5px;
  font-weight: 700;
}
```

**3k. `.sidenav`** — add `overflow-y: auto;`, `display: flex;`, `flex-direction: column;`

**3l. `.nav-title`** — change `font-size` to `10.5px`, `font-weight` to `700`, `color` to `var(--color-ink-ghost)`. Add `text-transform: uppercase;` and `letter-spacing: 0.07em;`

**3m. `.nav-item`** — add `gap: 9px;`, change `border-radius` to `7px`, `font-size` to `13px`. Change `transition` to `all var(--tr);`

**3n. `.nav-item-active`** — change `background` to `var(--color-accent-faint)`, `color` to `var(--color-accent)`. Change `box-shadow` to `none` (removes inset accent bar — the icon pill carries the active state now).

**3o. Add nav icon pill:**
```css
.nav-icon-pill {
  width: 24px;
  height: 24px;
  border-radius: 6px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-surface-muted);
  color: var(--color-ink-faint);
  transition: all var(--tr);
}
.nav-icon-pill svg {
  width: 14px;
  height: 14px;
}
.nav-item:hover .nav-icon-pill {
  background: var(--color-surface-raised);
  color: var(--color-ink-muted);
}
.nav-item-active .nav-icon-pill {
  background: var(--color-accent);
  color: #ffffff;
}
```

**3p. Add nav badge:**
```css
.nav-badge {
  margin-left: auto;
  min-width: 20px;
  text-align: center;
  padding: 1px 7px;
  border-radius: 10px;
  font-size: 10.5px;
  font-weight: 700;
  line-height: 1.5;
}
.nav-badge-danger {
  background: var(--color-danger-bg);
  color: var(--color-danger-text);
}
.nav-badge-muted {
  background: var(--color-surface-muted);
  color: var(--color-ink-faint);
}
```

**3q. Update contrast comments** — find comments referencing `5.17:1 on light accent`, `#3b82f6`, or `#2563eb` and update them to reference `5.9:1 on indigo accent`, `#818cf8`, and `#6366f1`.

---

### Step 4: App.tsx — Topbar enhancements

**File:** `ui/src/App.tsx`

**ADDITIVE ONLY.** Do not change routing, state, data attributes, or component logic.

**4a.** Add `NAV_ICONS` constant after imports, before the component function:
```tsx
const NAV_ICONS: Record<string, string> = {
  dashboard: 'M1 1h6v6H1zM9 1h6v3.5H9zM1 9h6v3.5H1zM9 6.5h6v6H9z',
  queue: 'M2.5 8.5 4 3h8l1.5 5.5M2.5 8.5h3a1 1 0 0 1 1 1v.5a1 1 0 0 0 1 1h1a1 1 0 0 0 1-1v-.5a1 1 0 0 1 1-1h3v3.5a1 1 0 0 1-1 1h-8a1 1 0 0 1-1-1z',
  failures: 'M7.3 2.2a.8.8 0 0 1 1.4 0L14 12a.8.8 0 0 1-.7 1.2H2.7a.8.8 0 0 1-.7-1.2zM8 6v3M8 11v.01',
  activity: 'M1 8h2.5l2-4.5 3 9 2-4.5H14',
  reports: 'M1.5 1.5h13v13h-13zM5 11V7M8 11V5M11 11V8',
  flows: 'M5 3.5a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM11 12.5a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM5 5.5v2.5a2.5 2.5 0 0 0 2.5 2.5H9',
  versions: 'M8 1.5 1.5 5 8 8.5 14.5 5zM1.5 8.5l6.5 3.5 6.5-3.5M1.5 11.5l6.5 3.5 6.5-3.5',
  systems: 'M1 5.5h5.5v5.5H1zM9.5 5.5H15v5.5H9.5zM6.5 8.25h3',
  contracts: 'M4 1.5h6l3.5 3.5V13a1.5 1.5 0 0 1-1.5 1.5H4A1.5 1.5 0 0 1 2.5 13V3A1.5 1.5 0 0 1 4 1.5zM10 1.5v3.5h3.5M6 8h4M6 10.5h2',
  rules: 'M8 2a6 6 0 1 0 0 12 6 6 0 0 0 0-12zM8 5.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5z',
  mappings: 'M4 4.5a1.8 1.8 0 1 0 0-3.6 1.8 1.8 0 0 0 0 3.6zM12 4.5a1.8 1.8 0 1 0 0-3.6 1.8 1.8 0 0 0 0 3.6zM4 11.5a1.8 1.8 0 1 0 0 3.6 1.8 1.8 0 0 0 0-3.6zM12 11.5a1.8 1.8 0 1 0 0 3.6 1.8 1.8 0 0 0 0-3.6zM5.8 4.5h4.4M5.8 11.5h4.4',
};
```

**4b.** In the topbar JSX, after `<span className="brand">Integration Platform</span>`, ADD:
```tsx
<span className="topbar-sep" aria-hidden="true" />
<div className="cmd-search">
  <svg width="14" height="14" viewBox="0 0 16 16" aria-hidden="true">
    <circle cx="7" cy="7" r="4.5" fill="none" stroke="currentColor" strokeWidth="1.5" />
    <path d="m11 11 3.5 3.5" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
  </svg>
  <input placeholder="Search flows, systems, contracts…" aria-label="Search" />
  <kbd>⌘K</kbd>
</div>
```

**4c.** In `<div className="topbar-right">`, BEFORE the theme toggle button, ADD:
```tsx
<span className="env-badge">
  <span className="env-badge-dot" />
  {me?.tenantCode || 'Production'}
</span>
```

**4d.** Replace the `<details className="account">` block's `<summary>` content with:
```tsx
<summary className="avatar-btn" data-action="account">
  <span className="avatar-circle">
    {(me?.userId || 'A').charAt(0).toUpperCase()}
  </span>
  <span style={{ fontSize: '12.5px', fontWeight: 500, color: 'var(--color-ink-muted)' }}>Account</span>
  <svg width="12" height="12" viewBox="0 0 10 10" aria-hidden="true">
    <path d="M2.5 3.5 5 6l2.5-2.5" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
  </svg>
</summary>
```
Keep the `<div className="menu">` and logout button EXACTLY as they are. Keep `data-action="account"` on the summary.

**4e.** In each nav item render, ADD the icon pill BEFORE the label text:
```tsx
<span className="nav-icon-pill" aria-hidden="true">
  <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round">
    <path d={NAV_ICONS[entry.nav] || ''} />
  </svg>
</span>
<span className="nav-label">{entry.label}</span>
```
Keep `data-nav`, `data-initial`, `href`, `aria-current` EXACTLY as they are.

---

### Step 5: dashboard.css — Tile accent bars

**File:** `ui/src/pages/dashboard.css`

**5a.** Add to existing `.tile` rule: `position: relative;` and `overflow: hidden;`

**5b.** Add after `.tile`:
```css
.tile::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  background: var(--color-accent);
  border-radius: 3px 0 0 3px;
}
.tile-bar-success::before { background: var(--color-success); }
.tile-bar-warn::before    { background: var(--color-warn-text); }
.tile-bar-danger::before  { background: var(--color-danger); }
.tile-bar-accent::before  { background: var(--color-accent); }
```

**5c.** Add mini bar chart CSS:
```css
.tile-bars {
  display: flex;
  align-items: flex-end;
  gap: 3px;
  height: 28px;
  margin-top: auto;
}
.tile-bars > span {
  flex: 1;
  min-width: 4px;
  max-width: 8px;
  background: var(--color-accent);
  opacity: 0.25;
  border-radius: 2px 2px 0 0;
  transition: opacity var(--tr);
}
.tile:hover .tile-bars > span {
  opacity: 0.45;
}
```

**5d.** Add heading tightness:
```css
.dashboard-head h1 {
  margin: 0;
  letter-spacing: -0.02em;
}
```

---

### Step 6: Per-page heading refinement

All these files already use tokens — only add `letter-spacing: -0.02em` to the h1 rules.

**6a. `ui/src/pages/flows.css`** — Add after existing `.flows-head h1` rule:
```css
:is(.flows-page, .versions-page) .flows-head h1 {
  letter-spacing: -0.02em;
}
```

**6b. `ui/src/pages/systems/systems.css`** — Add:
```css
.systems-page .systems-head h1 {
  letter-spacing: -0.02em;
}
```

**6c. `ui/src/pages/operator.css`** — Add:
```css
.operator-page .op-head h1 {
  letter-spacing: -0.02em;
}
```

**6d. `ui/src/pages/contracts/contracts.css`** — Add:
```css
.contracts-page .contracts-head h1 {
  letter-spacing: -0.02em;
}
```

**6e. `ui/src/rules/rules-editor.css`** — Add:
```css
:is(.rules-home, .rules-editor) .rules-head h1 {
  letter-spacing: -0.02em;
}
```

**6f. `ui/src/mapping/mapping.css`** — Add:
```css
.mapping-page .mapping-head h1 {
  letter-spacing: -0.02em;
}
```

No changes to `drawer.css`, `cm-theme.css`, or `mapping/expression/cm-theme.css` — they are fully token-driven.

---

### Step 7: Login — Split-screen layout

**File:** `ui/src/pages/dashboard.css`

**7a.** Change the existing `.signin` rule:
```css
.signin {
  display: flex;
  min-height: 100vh;
  background: var(--color-canvas);
}
```

**7b.** Add hero section CSS (after `.signin`):
```css
.signin-hero {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
}
.signin-hero h2 {
  font-size: 32px;
  font-weight: 800;
  letter-spacing: -0.04em;
  line-height: 1.2;
  margin-bottom: 12px;
}
.signin-hero p {
  font-size: 15px;
  color: var(--color-ink-muted);
  line-height: 1.65;
  max-width: 380px;
}
.signin-features {
  margin-top: 32px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.signin-feature {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
  color: var(--color-ink-muted);
}
.signin-feature-icon {
  width: 28px;
  height: 28px;
  border-radius: 7px;
  background: var(--color-accent-faint);
  color: var(--color-accent);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
```

**7c.** Change existing `.signin-column`:
```css
.signin-column {
  width: 480px;
  background: var(--color-surface);
  border-left: 1px solid var(--color-border);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
}
```

**7d.** Add responsive collapse:
```css
@media (max-width: 1100px) {
  .signin-hero { display: none; }
  .signin-column { width: 100%; border: none; }
}
```

**File:** `ui/src/pages/Login.tsx`

**7e.** Add the hero section in the JSX. Inside `<div className="signin">`, BEFORE `<div className="signin-column">`, add:
```tsx
<div className="signin-hero">
  <div>
    <h2>Streamline your<br/>cargo integrations</h2>
    <p>Connect systems, transform data, and route messages across your cargo handling ecosystem.</p>
    <div className="signin-features">
      <div className="signin-feature">
        <span className="signin-feature-icon">
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"><path d="M5 3.5a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM11 12.5a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM5 5.5v2.5a2.5 2.5 0 0 0 2.5 2.5H9" /></svg>
        </span>
        Visual flow builder with drag-and-drop
      </div>
      <div className="signin-feature">
        <span className="signin-feature-icon">
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"><path d="M4 1.5h6l3.5 3.5V13a1.5 1.5 0 0 1-1.5 1.5H4A1.5 1.5 0 0 1 2.5 13V3A1.5 1.5 0 0 1 4 1.5zM10 1.5v3.5h3.5M6 8h4M6 10.5h2" /></svg>
        </span>
        Schema contracts with version tracking
      </div>
      <div className="signin-feature">
        <span className="signin-feature-icon">
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"><path d="M8 2a6 6 0 1 0 0 12 6 6 0 0 0 0-12zM8 5.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5z" /></svg>
        </span>
        Decision-table rules engine
      </div>
      <div className="signin-feature">
        <span className="signin-feature-icon">
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"><path d="M1 8h2.5l2-4.5 3 9 2-4.5H14" /></svg>
        </span>
        Real-time delivery monitoring
      </div>
    </div>
  </div>
</div>
```

**CRITICAL:** Keep the SSO button's `onClick`, the session state logic (`state` prop, `onRetry`), and every existing data attribute exactly as they are. Only wrap the existing content in the new layout.

---

### Step 8: Dashboard.tsx — Tile classes (OPTIONAL)

**File:** `ui/src/pages/Dashboard.tsx`

For each stat tile `<a className="tile">`, optionally add a colour variant class:
- Active flows tile → `className="tile tile-bar-accent"`
- Systems tile → `className="tile tile-bar-success"`
- Failures tile → `className="tile tile-bar-danger"`
- Queue tile → `className="tile tile-bar-warn"`

Optionally add mini bar charts inside each tile (after the subtitle element):
```tsx
<div className="tile-bars" aria-hidden="true">
  <span style={{ height: '40%' }} /><span style={{ height: '65%' }} />
  <span style={{ height: '50%' }} /><span style={{ height: '80%' }} />
  <span style={{ height: '55%' }} /><span style={{ height: '70%' }} />
  <span style={{ height: '45%' }} />
</div>
```

**DO NOT** change tile `href`, data fetch logic, skeleton rendering, or conditional rendering.

---

## VERIFICATION

After all steps, verify:

### Build
- [ ] `npx vite build` succeeds with zero errors

### Routing (open each in browser)
- [ ] `#/` → Dashboard
- [ ] `#/login` → Login
- [ ] `#/flows` → Flows list
- [ ] `#/flow/new` → Flow editor
- [ ] `#/versions` → Versions
- [ ] `#/systems` → Systems
- [ ] `#/queue` → Queue
- [ ] `#/failures` → Failures
- [ ] `#/activity` → Activity
- [ ] `#/reports` → Delivery report
- [ ] `#/contracts` → Contracts
- [ ] `#/rules` → Rules editor
- [ ] `#/mappings` → Mappings

### Interactions
- [ ] Sidebar collapse/expand works
- [ ] Theme toggle works, no flash
- [ ] SSO login works
- [ ] Logout works

### Visual
- [ ] Indigo accent on all pages
- [ ] Inter font loaded
- [ ] Light + dark themes both correct
- [ ] Sidebar icon pills visible
- [ ] Command search in topbar
- [ ] Stat tiles have left accent bars
- [ ] Tables have hover highlight
- [ ] Login split-screen layout

### Data attributes (E2E anchors — inspect DOM)
- [ ] `data-nav="dashboard"` present
- [ ] `data-nav="flows"` present
- [ ] `data-action="toggle-nav"` present
- [ ] `data-action="toggle-theme"` present
- [ ] `data-action="account"` present
- [ ] `data-action="logout"` present
