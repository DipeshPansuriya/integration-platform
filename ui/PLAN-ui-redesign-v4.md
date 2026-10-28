# Integration Platform UI Redesign — Implementation Plan

**Author:** Vasu Ritesh  
**Date:** 2026-10-28  
**Design reference:** Artifact https://claude.ai/artifact/PtV6xronK6U51RjmJ4yXLh (V5)  
**Scope:** Visual-only token and CSS changes. NO changes to routing, API calls, state management, component props, data flow, hash routes, or `data-nav` / `data-action` attributes.

---

## Table of Contents

1. [Critical Safety Rules](#1-critical-safety-rules)
2. [Architecture Overview](#2-architecture-overview)
3. [File Change Map](#3-file-change-map)
4. [Phase 1: Token Layer — tokens.css](#4-phase-1-token-layer)
5. [Phase 2: Base Primitives — base.css](#5-phase-2-base-primitives)
6. [Phase 3: Google Fonts — index.html](#6-phase-3-google-fonts)
7. [Phase 4: Shell Topbar — App.tsx + base.css](#7-phase-4-shell-topbar)
8. [Why Token Changes Propagate Automatically](#8-why-token-changes-propagate-automatically)
9. [Phase 6: Dashboard — dashboard.css + Dashboard.tsx](#9-phase-6-dashboard)
10. [Phases 7–10: Operator Pages — operator.css](#10-phase-710-operator-pages)
11. [Phase 11: Flows — flows.css](#11-phase-11-flows-list)
12. [Phase 12: Versions — flows.css (shared)](#12-phase-12-versions)
13. [Phase 13: Systems — systems.css](#13-phase-13-systems)
14. [Phase 14: Contracts — contracts.css](#14-phase-14-contracts)
15. [Phase 15: Rules Editor — rules-editor.css](#15-phase-15-rules-editor)
16. [Phase 16: Mappings — mapping.css](#16-phase-16-mappings)
17. [Phase: Drawer — drawer.css](#17-phase-drawer)
18. [Phase: CodeMirror — cm-theme.css](#18-phase-codemirror-theme)
19. [Phase 17: Login — dashboard.css + Login.tsx](#19-phase-17-login)
20. [Phase 18: Responsive](#20-phase-18-responsive)
21. [Verification Checklist](#21-verification-checklist)

---

## 1. Critical Safety Rules

**These rules are NON-NEGOTIABLE. Breaking them breaks the application.**

### DO NOT CHANGE:
- **Hash routes** — Every `#/flows`, `#/versions`, `#/queue`, etc. route in `App.tsx` must stay exactly as-is.
- **`data-nav` attributes** — The sidebar nav items have `data-nav="dashboard"`, `data-nav="flows"`, etc. These are used by E2E tests. Do not rename, remove, or reorder them.
- **`data-action` attributes** — `data-action="toggle-nav"`, `data-action="toggle-theme"`, `data-action="account"`, `data-action="logout"` are test hooks. Do not touch.
- **Component props and state** — Do not change any TypeScript interfaces, props, state variables, API calls, or imports (except adding CSS imports or adding `className` attributes).
- **Routing logic** — `parseHash()`, `hashMove()`, `editorGuard`, `setRoute`, `setRouteSeq` — hands off.
- **Session/auth flow** — `checkSession()`, `/api/v1/me`, `logout()`, `onLogin`, `session` state — hands off.
- **Third-party subtrees** — The JDM decision table (`[data-decision-table]`), xyflow canvas (`[data-canvas]`), CodeMirror (`.raw-expression`) have their own styles. The `:where()` selectors in base.css deliberately exclude them. Do not change this exclusion logic.
- **CSS class names used in existing TSX files** — Do NOT rename existing CSS classes that are referenced in `.tsx` files. Only ADD new classes or modify the CSS rules behind existing class names.

### SAFE TO CHANGE:
- Token values (colours, shadows, radii, fonts, spacing) in `tokens.css`
- CSS rules (not class names) in `base.css`
- CSS rules in per-page CSS files (`dashboard.css`, `flows.css`, `systems.css`, `operator.css`, `contracts.css`, `rules-editor.css`, `mapping.css`, `drawer.css`)
- Adding new CSS classes (in existing CSS files or a new `redesign.css` file)
- Adding `className` props to JSX elements (additive only — keep existing classNames)
- Adding a Google Fonts `<link>` in `index.html`

---

## 2. Architecture Overview

### Current file structure
```
ui/src/
├── tokens.css          ← Design tokens (colours, shadows, radii, fonts)
├── ui/base.css         ← Shell + primitives (buttons, badges, tables, nav)
├── App.tsx             ← Shell component (topbar + sidebar + routing)
├── pages/
│   ├── Dashboard.tsx + dashboard.css
│   ├── FlowsList.tsx + flows.css
│   ├── FlowDefinition.tsx (flow editor — DO NOT TOUCH LAYOUT)
│   ├── Versions.tsx (shares flows.css)
│   ├── Systems.tsx + systems/systems.css
│   ├── QueueDashboard.tsx (uses operator.css)
│   ├── Failures.tsx (uses operator.css)
│   ├── Activity.tsx
│   ├── DeliveryReport.tsx
│   ├── MessageDetail.tsx
│   ├── Login.tsx + dashboard.css (signin-* classes)
│   ├── contracts/ContractList.tsx + contracts.css
│   ├── ContractVersions.tsx
│   └── MappingDesigner.tsx
├── rules/
│   ├── RulesEditor.tsx + rules-editor.css
│   └── jdm/ (DO NOT TOUCH — third-party decision table)
├── mapping/
│   └── MappingCanvas.tsx + mapping.css
└── ui/
    ├── Drawer.tsx + drawer.css
    └── NoAccess.tsx
```

### Current theme mechanism
- `index.html` reads `localStorage('ip.theme')` and sets `data-theme` on `<html>` before paint
- `tokens.css` defines `:root, [data-theme="light"]` and `[data-theme="dark"]` blocks
- `App.tsx` toggles `data-theme` via `toggleTheme()`
- Class `.theme-switching` on `<html>` disables transitions during flip

### Design system mapping (artifact → codebase)
| Artifact token | Current codebase token |
|---|---|
| `--bg` | `--color-canvas` |
| `--s1` | `--color-surface` |
| `--s2` | `--color-surface-raised` (repurpose as subtle bg) |
| `--s3` | (new: add `--color-surface-muted`) |
| `--ink` | `--color-ink` |
| `--ink2` | `--color-ink-muted` |
| `--ink3` | `--color-ink-faint` |
| `--ink4` | (new: add `--color-ink-ghost`) |
| `--pri` | `--color-accent` |
| `--pri-h` | `--color-accent-hover` |
| `--pri-bg` | `--color-accent-faint` |
| `--pri-ink` | `--color-accent-faint-ink` |
| `--bd` | `--color-border` |
| `--bd2` | `--color-border-strong` |

---

## 3. File Change Map

Files to modify (in order):

| # | File | Change type |
|---|---|---|
| 1 | `ui/index.html` | Add Google Fonts `<link>` |
| 2 | `ui/src/tokens.css` | Update all token values |
| 3 | `ui/src/ui/base.css` | Update shell, buttons, badges, tables, nav, topbar |
| 4 | `ui/src/pages/dashboard.css` | Redesign tiles, panels, signin card |
| 5 | `ui/src/pages/flows.css` | Redesign flows table, versions diff |
| 6 | `ui/src/pages/systems/systems.css` | Redesign system cards |
| 7 | `ui/src/pages/operator.css` | Redesign queue + failures tables |
| 8 | `ui/src/pages/contracts/contracts.css` | Redesign contract cards + schema preview |
| 9 | `ui/src/rules/rules-editor.css` | Redesign decision table header colours |
| 10 | `ui/src/mapping/mapping.css` | Redesign mapping canvas colours |
| 11 | `ui/src/ui/drawer.css` | Drawer surface + border updates (tokens only) |

Files that MAY need className additions (TSX — minimal, additive only):

| # | File | Change |
|---|---|---|
| 1 | `ui/src/App.tsx` | Add command palette search `<div>` in topbar, env badge, avatar button. ADD elements only in the topbar JSX between the brand and topbar-right. DO NOT change routing, nav structure, state, or data attributes. |
| 2 | `ui/src/pages/Dashboard.tsx` | Add `className` to stat tiles for accent bar colour variant, add mini bar chart `<div>` inside tiles, add quick-action cards section. |
| 3 | `ui/src/pages/Activity.tsx` | Add timeline CSS classes to activity items |
| 4 | `ui/src/pages/DeliveryReport.tsx` | Add progress bar elements, KPI card classes |

**Files to NEVER modify:**
- `App.tsx` routing logic, `parseHash()`, `hashMove`, `editorGuard`, session/auth
- `api/*.ts` — all API client code
- `rules/jdm/*` — third-party decision table
- `mapping/graph.ts`, `mapping/emit/*` — mapping logic
- `pages/flow-lifecycle.ts`, `pages/flow-save.ts` — flow state machines
- `pages/*-view.ts` — view-model logic files

---

## 4. Phase 1: Token Layer

**File:** `ui/src/tokens.css`

Replace ALL token values. Keep the SAME token names. Add new tokens at the end of each block.

### Light theme (`:root, [data-theme="light"]`)

```css
:root,
[data-theme="light"] {
  /* Surfaces + ink */
  --color-canvas: #f0f2f5;
  --color-surface: #ffffff;
  --color-surface-raised: #f7f8fa;    /* CHANGED: was #ffffff, now subtle bg for table headers, code blocks */
  --color-surface-muted: #eef0f4;     /* NEW: pills, tag backgrounds, segmented controls */
  --color-ink: #0f1729;
  --color-ink-muted: #3d4663;         /* CHANGED: was #475569, richer blue-grey */
  --color-ink-faint: #6b7394;         /* CHANGED: was #64748b, slightly warmer */
  --color-ink-ghost: #a0a7be;         /* NEW: placeholders, kbd hints */
  --color-border: #dfe2ea;            /* CHANGED: was #e2e8f0, slightly cooler */
  --color-border-strong: #c7ccd8;     /* CHANGED: was #94a3b8, lighter (used less as a divider, more as hover) */
  --color-border-control: #7f8ea3;    /* UNCHANGED: WCAG 1.4.11 */

  /* Accent — shifted from blue to indigo */
  --color-accent: #4f46e5;            /* CHANGED: was #2563eb (blue), now indigo — white text 5.9:1 */
  --color-accent-hover: #4338ca;      /* CHANGED: was #1d4ed8 */
  --color-link: #4f46e5;              /* CHANGED: matches accent */
  --color-accent-faint: #eef2ff;      /* CHANGED: was #dbeafe, lighter indigo tint */
  --color-accent-faint-ink: #3730a3;  /* CHANGED: was #1e40af, indigo family */
  --color-accent-soft: rgba(79, 70, 229, 0.06);  /* CHANGED: indigo-based */
  --color-halo: rgba(79, 70, 229, 0.22);          /* CHANGED: indigo-based */

  /* Status — unchanged values, correct contrast */
  --color-success: #059669;           /* CHANGED: was #16a34a, slightly teal */
  --color-success-text: #065f46;      /* CHANGED: darker for contrast */
  --color-success-bg: #ecfdf5;        /* CHANGED: was #dcfce7, lighter */
  --color-warn-text: #92400e;         /* UNCHANGED */
  --color-warn-bg: #fffbeb;           /* CHANGED: was #fef3c7, lighter */
  --color-danger: #dc2626;            /* UNCHANGED */
  --color-danger-text: #991b1b;       /* CHANGED: was #b91c1c, darker for contrast */
  --color-danger-bg: #fef2f2;         /* CHANGED: was #fee2e2, lighter */
  --color-neutral-text: #3d4663;      /* CHANGED: was #334155 */
  --color-neutral-bg: #eef0f4;        /* CHANGED: was #f1f5f9 */

  /* Focus + elevation */
  --color-focus: #4f46e5;             /* CHANGED: indigo */
  --color-focus-dark: #818cf8;        /* CHANGED: indigo for dark */
  --color-glass: rgba(255, 255, 255, 0.78);  /* UNCHANGED */
  --shadow-card: 0 1px 2px rgba(15, 23, 41, 0.06);                               /* CHANGED: subtler */
  --shadow-1: 0 1px 3px rgba(15, 23, 41, 0.05), 0 6px 16px -4px rgba(15, 23, 41, 0.08);  /* CHANGED: refined */
  --shadow-2: 0 4px 6px rgba(15, 23, 41, 0.04), 0 12px 32px -4px rgba(15, 23, 41, 0.12); /* CHANGED */
  --shadow-btn: 0 2px 8px rgba(79, 70, 229, 0.25);                                /* CHANGED: indigo glow */

  /* Typography — add Inter */
  --font-body: 'Inter', system-ui, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
  --font-mono: 'JetBrains Mono', ui-monospace, "Cascadia Mono", "SF Mono", Consolas, monospace;

  /* Radii — UNCHANGED */
  --r-1: 8px;
  --r-2: 12px;
  --r-3: 14px;
  --r-pill: 999px;

  /* Spacing — UNCHANGED */
  --tr: 150ms cubic-bezier(0.2, 0.8, 0.3, 1);
  --space-1: 4px; --space-2: 8px; --space-3: 12px; --space-4: 16px;
  --space-5: 24px; --space-6: 32px; --space-7: 40px; --space-8: 48px;

  /* Shell dimensions */
  --nav-w: 252px;            /* CHANGED: was 232px, wider for icon pills */
  --nav-w-collapsed: 64px;   /* UNCHANGED */
  --topbar-h: 52px;          /* CHANGED: was 48px, slightly taller for search bar */
  --content-max: 1140px;     /* CHANGED: was 1440px, tighter for better readability */
}
```

### Dark theme (`[data-theme="dark"]`)

```css
[data-theme="dark"] {
  --color-canvas: #08090e;
  --color-surface: #12131c;
  --color-surface-raised: #181a25;
  --color-surface-muted: #1e2030;     /* NEW */
  --color-ink: #eceef4;
  --color-ink-muted: #9498b0;
  --color-ink-faint: #5c6180;
  --color-ink-ghost: #3a3e55;         /* NEW */
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

**IMPORTANT:** The dark theme for `.btn-primary` uses `--color-accent-hover` as its background (existing rule in base.css line 84-86). With the new indigo values:
- **Light:** `#ffffff` on `#4f46e5` (accent) = **5.9:1** — WCAG AA pass
- **Dark:** `#ffffff` on `#6366f1` (accent-hover) = **4.86:1** — WCAG AA pass
- Do NOT change the `.btn-primary` dark override logic.
- UPDATE the old comment in base.css line 76 from `5.17:1 on light accent` to `5.9:1 on indigo accent`.
- UPDATE the comment block at lines 82-83 to reference the new indigo values instead of old blue values.

---

## 5. Phase 2: Base Primitives

**File:** `ui/src/ui/base.css`

### Buttons — enhance `.btn`
```css
/* ADD to existing .btn rule: */
.btn {
  /* ... keep existing ... */
  font-weight: 600;          /* CHANGED: was 500, bolder */
  box-shadow: var(--shadow-card);  /* ADD: subtle lift */
}
.btn:hover {
  /* ... keep existing ... */
  box-shadow: var(--shadow-1);  /* ADD: lift on hover */
}
```

### Badges — enhance `.badge`
```css
/* CHANGE the existing .badge rule: */
.badge {
  /* ... keep existing ... */
  border-radius: 6px;        /* CHANGED: was var(--r-pill) / 999px. Squared pill looks more modern. */
  font-weight: 600;          /* CHANGED: was 500 */
}
```

### Comments to update in base.css
Update stale contrast comments that reference old blue values:
- Line 76: change `5.17:1 on light accent` → `5.9:1 on indigo accent`
- Lines 82-83: change `#3b82f6` and `#2563eb` references to `#818cf8` and `#6366f1`
- Line 475: change `5.17:1 on light accent` → `5.9:1 on indigo accent`
- Lines 481-482: change `#3b82f6` and `#2563eb` references to new indigo values

### Tables — enhance hover
```css
/* ADD new rule after existing .table td: */
.table tbody tr {
  transition: background-color var(--tr);
}
.table tbody tr:hover {
  background: var(--color-accent-soft);
}
```

### Shell topbar
```css
/* CHANGE .topbar height and padding */
.topbar {
  /* ... keep display/flex/gap/sticky/z-index ... */
  height: var(--topbar-h);        /* now 52px via token */
  padding: 0 var(--space-5);      /* CHANGED: was --space-4, wider */
  background: var(--color-surface);  /* CHANGED: was --color-glass, solid surface */
  /* ... keep border-bottom, sticky, z-index ... */
}
/* REMOVE the [data-theme="dark"] .topbar backdrop-filter rule — solid bg works in both themes */

/* CHANGE .brand */
.brand {
  font-size: 14px;                /* CHANGED: was 16px */
  font-weight: 700;              /* CHANGED: was 600 */
  letter-spacing: -0.02em;       /* CHANGED: tighter */
}
```

### ADD: Command palette search (new CSS)
```css
/* ADD at end of topbar section in base.css */
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

### ADD: Environment badge (new CSS)
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

### ADD: Avatar button (new CSS)
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

### Sidebar — enhance `.nav-item`
```css
/* CHANGE .sidenav */
.sidenav {
  width: var(--nav-w);            /* now 252px via token */
  padding: var(--space-4) var(--space-3);
  background: var(--color-surface);
  border-right: 1px solid var(--color-border);
  transition: width var(--tr);
  overflow-y: auto;               /* ADD: scroll if many items */
  flex: none;
  display: flex;                  /* ADD */
  flex-direction: column;         /* ADD */
}

/* CHANGE .nav-title */
.nav-title {
  margin: 0 0 var(--space-1);
  padding: 0 12px;
  font-size: 10.5px;             /* CHANGED: was 12.5px */
  font-weight: 700;             /* CHANGED: was 500 */
  text-transform: uppercase;     /* ADD */
  letter-spacing: 0.07em;       /* ADD */
  color: var(--color-ink-ghost); /* CHANGED: was --color-ink-faint, dimmer */
}

/* CHANGE .nav-item */
.nav-item {
  display: flex;
  align-items: center;
  gap: 9px;                      /* ADD: space for icon pill */
  padding: 7px 12px;
  border-radius: 7px;            /* CHANGED: was var(--r-1) = 8px, slightly rounder */
  color: var(--color-ink-muted);
  font-size: 13px;               /* CHANGED: was 14px */
  font-weight: 500;
  text-decoration: none;
  white-space: nowrap;
  transition: all var(--tr);     /* CHANGED: was specific properties */
}

/* CHANGE .nav-item-active */
.nav-item-active {
  background: var(--color-accent-faint);  /* CHANGED: was --color-accent-soft */
  color: var(--color-accent);             /* CHANGED: was --color-accent-faint-ink, use accent directly */
  font-weight: 500;
  box-shadow: none;                       /* CHANGED: remove the inset 2px bar, icon pill carries the active */
}
```

### ADD: Nav icon pill (new CSS)
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

### ADD: Nav badge count (new CSS)
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

---

## 6. Phase 3: Google Fonts

**File:** `ui/index.html`

### Add Google Fonts link inside `<head>`, BEFORE the inline `<script>`:
```html
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Inter:opsz,wght@14..32,400;14..32,500;14..32,600;14..32,700;14..32,800&family=JetBrains+Mono:wght@400;500&display=swap">
```

The `--font-body` token in tokens.css already references `'Inter'` first (Phase 1 change), with system-ui as fallback.

### Update pre-paint background colours (lines 28-29):

The inline `<style>` in index.html has hard-coded hex values that prevent a flash of wrong colour before CSS loads. Update them to match the new token values:

```html
<style>
  /* pre-paint canvas so a dark boot never flashes white while CSS loads */
  html { background: #f0f2f5; }                /* CHANGED: was #f8fafc, matches new --color-canvas */
  html[data-theme="dark"] { background: #08090e; }  /* CHANGED: was #0f172a, matches new dark --color-canvas */
</style>
```

**CRITICAL:** These hex values MUST match `--color-canvas` in tokens.css exactly. If you change the token value, change these too.

---

## 7. Phase 4: Shell — Topbar

**File:** `ui/src/App.tsx`

**ADDITIVE ONLY.** Add elements between `<span className="brand">` and `<div className="topbar-right">`.

### Add a separator + command palette search:

After `<span className="brand">Integration Platform</span>`, add:
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

### Add separator CSS in base.css:
```css
.topbar-sep {
  width: 1px;
  height: 22px;
  background: var(--color-border);
  flex-shrink: 0;
}
```

### Replace the chip/account section in topbar-right:

BEFORE the theme toggle button, add the environment badge:
```tsx
<span className="env-badge">
  <span className="env-badge-dot" />
  {me?.tenantCode || 'Production'}
</span>
```

REPLACE the `<details className="account">` block with:
```tsx
{me && (
  <details className="account">
    <summary className="avatar-btn" data-action="account">
      <span className="avatar-circle">
        {(me.userId || 'A').charAt(0).toUpperCase()}
      </span>
      <span style={{ fontSize: '12.5px', fontWeight: 500, color: 'var(--color-ink-muted)' }}>Account</span>
      <svg width="12" height="12" viewBox="0 0 10 10" aria-hidden="true">
        <path d="M2.5 3.5 5 6l2.5-2.5" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    </summary>
    <div className="menu">
      <button type="button" data-action="logout" onClick={() => void logout()}>Sign out</button>
    </div>
  </details>
)}
```

### Add SVG icons to sidebar nav items:

In the nav item render (the `return` inside `group.entries.map`), wrap the label with an icon pill. Each nav entry needs an SVG icon.

**APPROACH:** Add a `navIcons` map at the top of App.tsx (after the imports, before the component):

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

Then in the nav-item render, add the icon pill BEFORE the label:

```tsx
<span className="nav-icon-pill" aria-hidden="true">
  <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round">
    <path d={NAV_ICONS[entry.nav] || ''} />
  </svg>
</span>
<span className="nav-label">{entry.label}</span>
```

**IMPORTANT:** Keep `data-nav`, `data-initial`, `href`, `aria-current` exactly as they are. The icon pill is purely additive.

### Add badge counts to Queue and Failures nav items:

This requires knowing the counts. If the Dashboard already fetches counts (it does via `dashboard-counts.ts`), you can optionally show them. However, for the redesign this is OPTIONAL — the visual design works without them. If implementing, add after `<span className="nav-label">`:

```tsx
{entry.nav === 'failures' && <span className="nav-badge nav-badge-danger">3</span>}
{entry.nav === 'queue' && <span className="nav-badge nav-badge-muted">24</span>}
```

These would need to be wired to real data later. For a pure visual redesign, skip this.

---

## 8. Why Token Changes Propagate Automatically

Every per-page CSS file in this codebase already uses `--color-*`, `--shadow-*`, `--r-*`, `--font-*`, and `--space-*` tokens for ALL colours, shadows, radii, fonts, and spacing. There are **zero hard-coded colour literals** in any page CSS.

This means **Phase 1 (tokens.css)** handles ~90% of the visual change across ALL pages — the indigo accent, richer surfaces, Inter font, refined shadows all propagate automatically.

The phases below list only what needs to change **beyond** the token swap.

---

## 9. Phase 6: Dashboard

**Files:** `ui/src/pages/dashboard.css` + `ui/src/pages/Dashboard.tsx`

### CSS changes in `dashboard.css`:

#### CHANGE `.tile` — add left accent bar support:
```css
.tile {
  /* keep all existing properties */
  position: relative;      /* ADD */
  overflow: hidden;        /* ADD */
}
```

#### ADD — accent bar pseudo-element:
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

#### ADD — mini bar chart container inside tiles:
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

#### CHANGE `.dashboard-head h1` — tighter heading:
```css
.dashboard-head h1 {
  margin: 0;
  letter-spacing: -0.02em;  /* ADD: tighter */
}
```

### TSX changes in `Dashboard.tsx` (ADDITIVE ONLY):

For each stat tile `<a className="tile">`, add a colour-variant class:
```tsx
<a className="tile tile-bar-accent" href="#/flows">  {/* was just "tile" */}
```

Optionally add mini bars inside each tile (after `.tile-sub`):
```tsx
<div className="tile-bars" aria-hidden="true">
  <span style={{ height: '40%' }} />
  <span style={{ height: '65%' }} />
  <span style={{ height: '50%' }} />
  <span style={{ height: '80%' }} />
  <span style={{ height: '55%' }} />
  <span style={{ height: '70%' }} />
  <span style={{ height: '45%' }} />
</div>
```

**DO NOT** change the tile's `href`, the data fetching logic (`useDashboardCounts`), skeleton rendering, or any conditional rendering.

---

## 10. Phase 7–10: Operator Pages (Queue, Failures, Activity, Delivery Report)

**File:** `ui/src/pages/operator.css`

All five operator screens (Queue, Failures, Message Detail, Activity, Delivery Report) share `operator.css` and use the root class `.operator-page`. They already use token-based colours throughout.

### CSS changes in `operator.css`:

#### CHANGE `.op-card` — richer card styling (tokens do most of it):
```css
.operator-page .op-card {
  /* existing properties stay unchanged */
  /* shadow-1 value is now richer via token (Phase 1) */
  /* border colour is now cooler via token (Phase 1) */
}
```

#### ADD — page header subtitle refinement:
```css
.operator-page .op-head h1 {
  letter-spacing: -0.02em;  /* ADD: tighter heading */
}
```

#### ADD — enhanced table row hover (reinforces base.css hover):
```css
.operator-page .op-table tbody tr {
  transition: background-color var(--tr);
}
```

#### ADD — code block refinement:
```css
.operator-page .op-pre {
  /* existing properties stay */
  font-feature-settings: 'liga' 0;  /* ADD: no ligatures in mono */
}
```

**No TSX changes needed** — these pages are already fully token-driven. The indigo accent, richer shadows, and Inter font propagate automatically.

---

## 11. Phase 11: Flows List

**File:** `ui/src/pages/flows.css`

All Flows/Versions page CSS already uses tokens. No CSS rule changes needed beyond Phase 1 token propagation.

### Only CSS refinement:
```css
/* ADD to flows.css — heading tightness */
:is(.flows-page, .versions-page) .flows-head h1 {
  letter-spacing: -0.02em;
}
```

**No TSX changes needed.**

---

## 12. Phase 12: Versions

**File:** `ui/src/pages/flows.css` (shared)

Versions shares `flows.css`. The stepper pills (`.versions-stepper li`) already use `--color-accent-faint` and `--color-accent` tokens — the indigo shift propagates automatically. The selected row inset bar (`box-shadow: inset 3px 0 0 var(--color-accent)`) will turn indigo automatically.

**No CSS or TSX changes needed.**

---

## 13. Phase 13: Systems

**File:** `ui/src/pages/systems/systems.css`

Systems page already uses tokens throughout. The table hover (`.systems-row:hover td { background: var(--color-accent-soft) }`) will shift to indigo automatically.

### Only CSS refinement:
```css
/* ADD to systems.css — heading tightness */
.systems-page .systems-head h1 {
  letter-spacing: -0.02em;
}
```

**No TSX changes needed.**

---

## 14. Phase 14: Contracts

**File:** `ui/src/pages/contracts/contracts.css`

Contracts already uses tokens. Compare panes, diff highlighting, and grouped cards all inherit the new values automatically.

### Only CSS refinement:
```css
/* ADD — heading tightness */
.contracts-page .contracts-head h1 {
  letter-spacing: -0.02em;
}
```

**No TSX changes needed.**

---

## 15. Phase 15: Rules Editor

**File:** `ui/src/rules/rules-editor.css`

The rules editor has its own JDM (gorules) theming block that maps `--grl-*` variables to `--color-*` tokens. This mapping is already in place:

```css
:root[data-theme],
[data-theme] .rules-editor {
  --grl-color-primary: var(--color-accent);
  --grl-color-bg-container: var(--color-surface);
  /* ... etc — all already mapped */
}
```

When `--color-accent` changes from blue to indigo, `--grl-color-primary` automatically becomes indigo too. **All JDM decision table styling propagates automatically.**

### Only CSS refinement:
```css
/* ADD — heading tightness */
:is(.rules-home, .rules-editor) .rules-head h1 {
  letter-spacing: -0.02em;
}
```

**No CSS or TSX changes needed.** The JDM third-party internals stay untouched.

---

## 16. Phase 16: Mappings

**File:** `ui/src/mapping/mapping.css`

The mapping page has its own xyflow theming block mapping `--xy-*` to `--color-*` tokens:

```css
[data-theme] .mapping-page .mapping-canvas {
  --xy-edge-stroke: var(--color-accent);
  --xy-handle-background-color: var(--color-accent);
  /* ... all already mapped */
}
```

When `--color-accent` changes from blue to indigo, all xyflow edges, handles, and connection lines become indigo automatically.

### Only CSS refinement:
```css
/* ADD — heading tightness */
.mapping-page .mapping-head h1 {
  letter-spacing: -0.02em;
}
```

**No TSX changes needed.**

---

## 17. Phase: Drawer

**File:** `ui/src/ui/drawer.css`

The drawer is fully token-driven — surface, border, scrim, shadows all use tokens. The `color-mix()` scrim already uses `--color-ink` and `--color-canvas` tokens.

**No CSS or TSX changes needed.** Token propagation handles everything.

---

## 18. Phase: CodeMirror theme

**File:** `ui/src/mapping/expression/cm-theme.css`

Already fully token-driven (confirmed — zero hard-coded colours). Uses `--color-border-control`, `--color-surface`, `--color-ink`, `--color-accent-faint`, `--color-focus`, etc. **No changes needed.** Token propagation handles everything.

---

## 19. Phase 17: Login

**File:** `dashboard.css` (signin-* classes already here)

### Update `.signin` to split-screen layout:

```css
/* CHANGE */
.signin {
  display: flex;              /* CHANGED: was grid/place-items */
  min-height: 100vh;
  background: var(--color-canvas);
}

/* ADD */
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

/* CHANGE .signin-column to be the right panel */
.signin-column {
  width: 480px;
  background: var(--color-surface);
  border-left: 1px solid var(--color-border);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
}

@media (max-width: 1100px) {
  .signin-hero { display: none; }
  .signin-column { width: 100%; border: none; }
}
```

### Update Login.tsx:

Add the hero section BEFORE `.signin-column`:
```tsx
<div className="signin">
  <div className="signin-hero">
    <div>
      <h2>Streamline your<br/>cargo integrations</h2>
      <p>Connect systems, transform data, and route messages across your cargo handling ecosystem.</p>
      <div className="signin-features">
        <div className="signin-feature">
          <span className="signin-feature-icon">/* flow icon SVG */</span>
          Visual flow builder with drag-and-drop
        </div>
        {/* ... more features ... */}
      </div>
    </div>
  </div>
  <div className="signin-column">
    {/* existing card content */}
  </div>
</div>
```

**CRITICAL:** Keep the SSO button's `onClick` handler and the session state logic (`state` prop, `onRetry`) exactly as they are. Only change the JSX structure around them.

---

## 20. Phase 18: Responsive

All responsive breakpoints should be in the CSS files, not in TSX logic.

Key breakpoints (add/update in base.css):

```css
@media (max-width: 1100px) {
  /* Columns stack */
}
@media (max-width: 900px) {
  /* Sidebar collapses to hidden (existing .shell-collapsed already handles this) */
  /* Hide command search */
  /* Stat grids go to 2 columns */
  /* Card grids go to 1 column */
}
@media (max-width: 480px) {
  /* Stat grids go to 1 column */
}
```

---

## 21. Verification Checklist

After implementation, verify each item:

### Routing (MUST NOT BREAK)
- [ ] `#/` → Dashboard loads
- [ ] `#/login` → Login page loads
- [ ] `#/flows` → Flows list loads
- [ ] `#/flow/new` → Flow editor opens
- [ ] `#/versions` → Versions list loads
- [ ] `#/systems` → Systems page loads
- [ ] `#/queue` → Queue dashboard loads
- [ ] `#/failures` → Failures list loads
- [ ] `#/messages/<id>` → Message detail loads
- [ ] `#/activity` → Activity page loads
- [ ] `#/reports` → Delivery report loads
- [ ] `#/contracts` → Contracts page loads
- [ ] `#/rules` → Rules editor loads
- [ ] `#/mappings` → Mapping page loads

### Interactions (MUST NOT BREAK)
- [ ] Sidebar collapse/expand toggle works
- [ ] Theme toggle (light/dark) works, no flash
- [ ] SSO login button works
- [ ] Logout button works
- [ ] Flow editor unsaved-changes guard works
- [ ] Drawer open/close works
- [ ] System register/edit form works
- [ ] Contract import form works
- [ ] Decision table editing works (JDM)
- [ ] Mapping canvas drag/drop works
- [ ] Message detail timeline works

### Visual (NEW)
- [ ] All pages use indigo accent colour
- [ ] All pages have Inter font
- [ ] Both light and dark themes are correct
- [ ] No WCAG AA contrast violations
- [ ] Sidebar has icon pills
- [ ] Topbar has command search bar
- [ ] Stat tiles have left accent bars
- [ ] Tables have hover highlight
- [ ] Login is split-screen layout
- [ ] Responsive at 900px and 480px works

### Data attributes (MUST NOT CHANGE)
- [ ] `data-nav="dashboard"` present
- [ ] `data-nav="flows"` present
- [ ] `data-nav="queue"` present
- [ ] `data-action="toggle-nav"` present
- [ ] `data-action="toggle-theme"` present
- [ ] `data-action="account"` present
- [ ] `data-action="logout"` present
- [ ] `data-session-tenant` present on tenant chip

---

## Implementation Order

Execute phases in this exact order:

1. **Phase 3** — Add Google Fonts link (index.html)
2. **Phase 1** — Update tokens.css (all token values)
3. **Phase 2** — Update base.css (shell + primitives)
4. **Phase 4** — Update App.tsx topbar (additive JSX)
5. **Phase 5** — Update App.tsx sidebar (icon pills, additive JSX)
6. **Phase 6** — Dashboard
7. **Phases 7–16** — Remaining pages (any order)
8. **Phase 17** — Login
9. **Phase 18** — Responsive check

After each phase, run `npx vite build` to verify no compilation errors.

---

*End of plan. This document is the single source of truth for the UI redesign implementation.*
