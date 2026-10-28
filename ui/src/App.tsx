// T-158 (E3 lane D): the console shell. Hash routing so every admin screen is directly
// loadable (the p95 measurement loads pages by URL); the flow-definition screen (T-017) and
// the version + approval screen (T-022) hang off the [data-nav] entries. Both use ONE typed
// client (src/api/client.ts) against the real API - never a mock (E3-F3-S1). No visual design
// is invented here: design.md (T-095, mahesh-architect) is the agreed look.
//
// T-097 (E4-F1-S1): the shell shows the session's bound tenant (GET /api/v1/me) and signs
// out by POSTing /logout (CSRF header) and navigating to the answer's sign-out address.
//
// T78 lane F (amit-developer, ui-modernization 00-§2 + 02-style-guide v3): the shell is
// redesigned on the token layer (src/tokens.css + src/ui/base.css) - persistent left side
// nav (232px / 64px collapsed rail, collapse persisted), 48px top bar with tenant chip,
// theme toggle and account menu, landmarks banner/navigation/main, real-link nav entries.
// The [data-nav] attribute values, the sign-out semantics and the hash routes are UNCHANGED
// - the screen e2e suite keys off these exact attributes and URLs.

import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import { api, LOGIN_ROUTE, readCookie } from './api/client';
import { FlowDefinition, type EditorGuard } from './pages/FlowDefinition';
import { hashMove } from './pages/flow-lifecycle'; // row 310 B12: Back / typed address vs unsaved edits
import { FlowsList } from './pages/FlowsList'; // row 310 lane B (T88): #/flows list landing
import { Versions } from './pages/Versions';
// E5 seam (arjun-fullstack, F13): route + menu stubs for E5 lane D's screens (T-159 systems registry, T-160
// contract versions); lane D fills the two page files and never edits this shell.
import { Systems } from './pages/Systems';
import { ContractVersions } from './pages/ContractVersions';
// F10-narrow seam (arjun-fullstack, row278 plan step 8): route + menu entry for lane D's queue
// dashboard; the page file is the whole slice's UI - this shell line stays one route + one menu line.
import { QueueDashboard } from './pages/QueueDashboard';
// T112 (row327 lane D): #/failures (menu, beside Queue) and #/messages/<trackingId> (reached from Failures).
import { Failures } from './pages/Failures';
import { MessageDetail } from './pages/MessageDetail';
import { parseMessageRoute } from './pages/message-timeline';
// Row 327 T119 (lane D): the tenant's activity view (#/activity, #/activity/<eventId>) - one route + one menu line.
import { Activity } from './pages/Activity';
import DeliveryReport from './pages/DeliveryReport';
// E7 seam (arjun-fullstack, F5): route + menu stub for lane D1's mapping canvas (#/mappings/<routeId>, T-034);
// lane D fills the page file and never edits this shell.
import { MappingDesigner } from './pages/MappingDesigner';
// Row 310 lane A (sanjay-developer, T87): the Dashboard at #/ and the in-app sign-in card at #/login.
import { Dashboard } from './pages/Dashboard';
import { Login } from './pages/Login';
import { clearAttempt, landingAfterLogin, markLoggedOut, rememberReturnTo, takeReturnTo } from './pages/login-flow';
import { Destinations } from './pages/Destinations';
import { safeDecode } from './safe-decode';
// Row 575 (S1 of the guided flow): the six-item role-aware menu is one table (nav-model.ts); legacy screens sit in a temporary "More" group.
import type { Route } from './route-types';
import { canOpen, moreFor, navFor, platformTools, showPlatformTools, titleOf, type NavItem } from './nav-model';
import { NoAccess } from './ui/NoAccess';
import { PageHeader } from './ui/PageHeader';
import { focusMainHeading, skipToMain } from './route-focus';
import { ApprovalsLanding, MessagesLanding } from './pages/GuidedLandings';
import { sessionStateOf, type SessionState } from './pages/dashboard-counts';
// E6 (lane D, T-025/T-028): the rules editor - #/rules lists and creates a flow's rule sets,
// #/rules/<ruleSetId> authors one as decision tables beside the always-visible test panel. Loaded on
// first use: the decision-table editor is a large bundle no other screen should pay for (page-load p95).
const RulesEditor = lazy(() => import('./rules/RulesEditor').then((m) => ({ default: m.RulesEditor })));

// V4 4a: nav icon pills - inline 16x16 stroke paths keyed by the nav's [data-nav] value.
// No routing or attribute semantics change; missing keys render an empty (invisible) path.
const NAV_ICONS: Record<string, string> = {
  home: 'M1 1h6v6H1zM9 1h6v3.5H9zM1 9h6v3.5H1zM9 6.5h6v6H9z',
  queue: 'M2.5 8.5 4 3h8l1.5 5.5M2.5 8.5h3a1 1 0 0 1 1 1v.5a1 1 0 0 0 1 1h1a1 1 0 0 0 1-1v-.5a1 1 0 0 1 1-1h3v3.5a1 1 0 0 1-1 1h-8a1 1 0 0 1-1-1z',
  approvals: 'M3 8.5l3 3 7-7M2 2.5h12v11H2z',
  messages: 'M1.5 3.5h13v9h-13zM1.5 4l6.5 5 6.5-5',
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

type Me = {
  userId?: string;
  tenantId?: string;
  tenantCode?: string;
  timeZone?: string;
  roles?: string[];
};

type Theme = 'light' | 'dark';

/** The theme index.html resolved pre-paint; React only toggles it. */
function currentTheme(): Theme {
  return document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light';
}

function parseHash(): Route {
  const hash = window.location.hash.replace(/^#\/?/, '');
  if (hash.startsWith('login')) return { page: 'login', flowId: null };
  const flow = /^flow\/(.+)$/.exec(hash);
  if (flow) return { page: 'flows', flowId: flow[1] };
  if (hash.startsWith('flows')) return { page: 'flows', flowId: null };
  const version = /^versions\/(.+)$/.exec(hash);
  if (version) return { page: 'versions', flowId: safeDecode(version[1]) };
  if (hash.startsWith('versions')) return { page: 'versions', flowId: null };
  if (hash.startsWith('systems')) return { page: 'systems', flowId: null };
  if (hash.startsWith('queue')) return { page: 'queue', flowId: null };
  if (hash.startsWith('failures')) return { page: 'failures', flowId: null };
  if (/^messages\/?$/.test(hash)) return { page: 'messages', flowId: null }; // the Messages landing (S3 fills it)
  if (hash.startsWith('approvals')) return { page: 'approvals', flowId: null }; // the Approvals list (S5 fills it)
  const message = /^messages\/(.+)$/.exec(hash);
  if (message) return { page: 'messages', flowId: null, ...parseMessageRoute(message[1]) };
  if (hash.startsWith('activity')) return { page: 'activity', flowId: null };
  if (hash.startsWith('reports')) return { page: 'reports', flowId: null };
  if (hash.startsWith('contracts')) return { page: 'contracts', flowId: null };
  const ruleSet = /^rules\/(.+)$/.exec(hash);
  if (ruleSet) return { page: 'rules', flowId: null, ruleSetId: safeDecode(ruleSet[1]) };
  if (hash.startsWith('rules')) return { page: 'rules', flowId: null, ruleSetId: null };
  // E7 seam: #/mappings lists mappings - per route once the canvas (lane D1) fills the page.
  const routeId = /^mappings\/(.+)$/.exec(hash);
  if (routeId) return { page: 'mappings', flowId: null, routeId: safeDecode(routeId[1]) };
  if (hash.startsWith('mappings')) return { page: 'mappings', flowId: null, routeId: null };
  if (hash.startsWith('destinations')) return { page: 'destinations', flowId: null };
  return { page: 'home', flowId: null };
}

export function App() {
  const [route, setRoute] = useState<Route>(parseHash());
  // Row 310 B11: one count per routed address change - the editor is keyed by it, so #/flow/A -> #/flow/new
  // (or a saved create-mode editor -> #/flow/new again) remounts it empty instead of keeping A's state.
  const [routeSeq, setRouteSeq] = useState(0);
  // Row 310 B12: the open flow editor's guard - asked before an address change unmounts it.
  const editorGuard = useRef<EditorGuard | null>(null);
  // The bound tenant of the browser's console session; a 401 sends the browser to the #/login
  // sign-in card in client.ts, so `me` stays null exactly while there is no session.
  const [me, setMe] = useState<Me | null>(null);
  // Lane A: what GET /api/v1/me said - 'checking' until it answers (10-screens §2 states).
  const [session, setSession] = useState<SessionState | 'checking'>('checking');
  const onLogin = route.page === 'login';
  const sessionChecked = useRef(false);
  // The theme index.html already applied before first paint - React mirrors it for the toggle.
  const [theme, setTheme] = useState<Theme>(currentTheme);
  // Collapse state persisted per browser (00-§2); storage may be unavailable in hardened runs.
  const [collapsed, setCollapsed] = useState<boolean>(() => {
    try {
      return localStorage.getItem('ip.navCollapsed') === '1';
    } catch {
      return false;
    }
  });

  useEffect(() => {
    // T125 (B12 back-history): what tells a Back/Forward traversal from a new navigation (typed
    // address, goto, in-app link) is the Navigation API `navigate` event on window.navigation,
    // which fires BEFORE popstate/hashchange with the location still on the old fragment; its
    // navigationType is 'traverse' for Back/Forward and 'push' (or 'replace') for everything new
    // (measured on this suite's Chromium - probes t125-fix-navorder-probe*.log). Where the event
    // carries no navigationType, a traversal is still the move whose destination already exists
    // in this tab's history list while a push's does not. popstate alone cannot discriminate:
    // Chromium also fires popstate before the hashchange of a typed same-document goto, and that
    // mis-routed the typed address into the traversal branch's history.go(1) - a no-op at the
    // forward tip, so the address was never restored. A back-triggered leave must NOT replaceState
    // the entry the user just landed on - that rewrites (destroys) it and the next Back skips a
    // page. The entry still exists one step back, so a traversal restores the editor by moving
    // FORWARD again; the traversal that restoration causes is swallowed (the screen and its edits
    // never left, so it must not re-route or re-confirm). A new navigation never left the flow
    // entry, so it restores over the landed entry with replaceState. The swallowing flag holds the
    // URL the go(1) must arrive on: a hashchange for any other URL means it was stale, and a real
    // fragment move is then handled normally. Browsers without the Navigation API - or a move the
    // API did not announce - keep the popped-based semantics.
    let popped = false;
    let restoring: string | null = null;
    let navType: 'traverse' | 'new' | null = null;
    const navHost = (window as unknown as { navigation?: EventTarget & { entries?: () => Array<{ url: string }> } }).navigation;
    const navEntries = typeof navHost?.entries === 'function' ? navHost.entries.bind(navHost) : null;
    const onNavigate = (event: Event) => {
      const navEvent = event as { navigationType?: string; destination?: { url?: string } };
      if (typeof navEvent.navigationType === 'string') {
        navType = navEvent.navigationType === 'traverse' ? 'traverse' : 'new';
        return;
      }
      const destination = navEvent.destination?.url;
      if (destination === undefined || navEntries === null) {
        navType = null; // no usable signal: fall back to popped for this move
        return;
      }
      navType = navEntries().some((entry) => entry.url === destination) ? 'traverse' : 'new';
    };
    const onPopState = () => { popped = true; };
    const onHashChange = (event: HashChangeEvent) => {
      const wasPop = popped;
      popped = false;
      const wasTraverse = navType !== null ? navType === 'traverse' : wasPop;
      navType = null;
      if (restoring !== null) {
        const swallow = event.newURL === restoring; // the go(1) below arriving back on the editor's own entry
        restoring = null;
        if (swallow) return;
      }
      const editor = editorGuard.current;
      const move = hashMove(event.oldURL, event.newURL, editor ? editor.state() : null);
      if (move.kind === 'confirm' && editor) {
        if (wasTraverse) {
          restoring = move.restore;
          window.history.go(1); // return onto the still-existing flow entry; the landed one survives
        } else {
          // A push (typed address): the flow's entry stays where it is and the new entry goes back
          // on the flow (no second hashchange).
          window.history.replaceState(null, '', move.restore);
        }
        editor.confirm(move.leaveTo);
        return;
      }
      setRoute(parseHash());
      setRouteSeq((seq) => seq + 1);
    };
    const hasNavigateSignal = typeof navHost?.addEventListener === 'function';
    window.addEventListener('hashchange', onHashChange);
    window.addEventListener('popstate', onPopState);
    if (hasNavigateSignal) navHost.addEventListener('navigate', onNavigate);
    return () => {
      window.removeEventListener('hashchange', onHashChange);
      window.removeEventListener('popstate', onPopState);
      if (hasNavigateSignal) navHost.removeEventListener('navigate', onNavigate);
    };
  }, []);

  function checkSession() {
    setSession('checking');
    api
      .GET('/api/v1/me')
      .then(({ data, response }) => {
        setMe(data ?? null);
        setSession(sessionStateOf(response.status, data));
      })
      .catch(() => setSession(sessionStateOf(null, undefined)));
  }

  // Once on load, and again on every arrival at #/login (a mid-session 401 lands there with a
  // stale 'signed-in' - re-asking /me is what keeps #/login -> #/ from looping).
  useEffect(() => {
    if (sessionChecked.current && !onLogin) return;
    sessionChecked.current = true;
    checkSession();
  }, [onLogin]);

  // A live session never sees the sign-in card: #/login goes on to the Dashboard (no history entry).
  // A known no-session never sees a screen: a hash-only move off #/login (no API call, so no 401
  // from client.ts) goes back to the card.
  useEffect(() => {
    if (session === 'signed-in') {
      // Row 571: a live session proves the last sign-in attempt worked (the loop guard forgets it), and the
      // screen asked for before the sign-in (remembered by client.ts) is restored - from the bare address only.
      clearAttempt();
      const landing = landingAfterLogin(window.location.hash, takeReturnTo());
      if (landing) {
        window.location.replace(landing);
        return;
      }
    }
    if (onLogin && session === 'signed-in') window.location.replace('#/');
    if (!onLogin && session === 'signed-out') {
      rememberReturnTo(window.location.hash);
      window.location.hash = LOGIN_ROUTE;
    }
  }, [onLogin, session]);

  // Row 575 (S1): after a routed address change focus moves to the new screen's heading (else main); never on first paint.
  // routeSeq only counts moves the router accepted, so a blocked move (unsaved-edit dialog) keeps its focus.
  useEffect(() => {
    if (routeSeq === 0) return;
    const frame = requestAnimationFrame(() => {
      focusMainHeading(document.getElementById('main-content'));
    });
    return () => cancelAnimationFrame(frame);
  }, [routeSeq]);

  function toggleNav() {
    setCollapsed((c) => {
      const next = !c;
      try {
        localStorage.setItem('ip.navCollapsed', next ? '1' : '0');
      } catch {
        /* storage unavailable - the toggle still works for this session */
      }
      return next;
    });
  }

  function toggleTheme() {
    const next: Theme = theme === 'dark' ? 'light' : 'dark';
    const html = document.documentElement;
    // Transitions off for the flip (base.css .theme-switching), on again after a painted frame.
    html.classList.add('theme-switching');
    html.dataset.theme = next;
    void html.offsetHeight; // commit the new theme's styles while transitions are off
    requestAnimationFrame(() => requestAnimationFrame(() => html.classList.remove('theme-switching')));
    try {
      localStorage.setItem('ip.theme', next);
    } catch {
      /* storage unavailable - the toggle still works for this session */
    }
    setTheme(next);
  }

  /** RP-initiated logout: POST /logout (CSRF-protected), then the answer names the sign-out URL. */
  async function logout() {
    const headers = new Headers();
    const token = readCookie('XSRF-TOKEN');
    if (token) headers.set('X-XSRF-TOKEN', token);
    const answer = await fetch('/logout', { method: 'POST', headers, credentials: 'include' });
    markLoggedOut(); // the page the provider returns to shows a Sign in button, not an automatic redirect (row 571)
    const body = (await answer.json().catch(() => null)) as { redirect?: string } | null;
    window.location.assign(body?.redirect || '/');
  }

  // #/login is the empty shell: the card alone - no top bar, no side nav, no [data-nav].
  if (onLogin) return <Login state={session === 'signed-in' ? 'checking' : session} onRetry={checkSession} />;

  // Row 575 (S1): the roles GET /api/v1/me answered; null = unknown, so nothing is hidden (the server's 403 stays the gate).
  // null = not loaded / failed (everything shown); a LOADED empty list means no grants (Home only) - Varun blocker, row 575.
  const roles = Array.isArray(me?.roles) ? me.roles : null;
  const moreItems = moreFor(roles);
  const open = canOpen(roles, route.page);
  const shown = open ? route.page : null;

  function renderNavItem(entry: NavItem) {
    const active = route.page === entry.page;
    return (
      <a
        key={entry.nav}
        className={active ? 'nav-item nav-item-active' : 'nav-item'}
        data-nav={entry.nav}
        data-initial={entry.initial}
        href={entry.hash}
        title={entry.label}
        aria-current={active ? 'page' : undefined}
      >
        <span className="nav-icon-pill" aria-hidden="true"><svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"><path d={NAV_ICONS[entry.nav] || ''} /></svg></span>
        <span className="nav-label">{entry.label}</span>
      </a>
    );
  }

  return (
    <div className={collapsed ? 'shell shell-collapsed' : 'shell'}>
      <a className="skip-link" href="#main-content" onClick={(event) => skipToMain(event, document.getElementById('main-content'))}>
        Skip to main content
      </a>
      <header className="topbar">
        <button
          type="button"
          className="btn btn-icon"
          data-action="toggle-nav"
          aria-expanded={!collapsed}
          aria-label={collapsed ? 'Expand navigation' : 'Collapse navigation'}
          title={collapsed ? 'Expand navigation' : 'Collapse navigation'}
          onClick={toggleNav}
        >
          <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
            <path d="M2 4h12M2 8h12M2 12h12" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
          </svg>
        </button>
        <span className="brand">Integration Platform</span>
        <span className="topbar-sep" aria-hidden="true" />
        <div className="cmd-search">
          <svg width="14" height="14" viewBox="0 0 16 16" aria-hidden="true">
            <circle cx="7" cy="7" r="4.5" fill="none" stroke="currentColor" strokeWidth="1.5" />
            <path d="m11 11 3.5 3.5" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
          </svg>
          <input placeholder="Search flows, systems, contracts…" aria-label="Search" />
          <kbd>⌘K</kbd>
        </div>
        <div className="topbar-right">
          {me?.tenantCode && <span className="chip" data-session-tenant>{me.tenantCode}</span>}
          <span className="env-badge"><span className="env-badge-dot" />{me?.tenantCode || 'Production'}</span>
          <button
            type="button"
            className="btn btn-icon"
            data-action="toggle-theme"
            aria-label={`Switch to the ${theme === 'dark' ? 'light' : 'dark'} theme`}
            title={`Switch to the ${theme === 'dark' ? 'light' : 'dark'} theme`}
            onClick={toggleTheme}
          >
            {theme === 'dark' ? (
              <svg width="15" height="15" viewBox="0 0 16 16" aria-hidden="true">
                <circle cx="8" cy="8" r="3.4" fill="currentColor" />
                <path d="M8 1v2M8 13v2M1 8h2M13 8h2M3 3l1.4 1.4M11.6 11.6L13 13M13 3l-1.4 1.4M4.4 11.6L3 13" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
              </svg>
            ) : (
              <svg width="15" height="15" viewBox="0 0 16 16" aria-hidden="true">
                <path d="M13.5 9.7A6 6 0 0 1 6.3 2.5a6 6 0 1 0 7.2 7.2Z" fill="currentColor" />
              </svg>
            )}
          </button>
          {me && (
            <details className="account">
              <summary className="avatar-btn" data-action="account">
                <span className="avatar-circle">{(me?.userId || 'A').charAt(0).toUpperCase()}</span>
                <span style={{ fontSize: '12.5px', fontWeight: 500, color: 'var(--color-ink-muted)' }}>Account</span>
                <svg width="12" height="12" viewBox="0 0 10 10" aria-hidden="true"><path d="M2.5 3.5 5 6l2.5-2.5" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" /></svg>
              </summary>
              <div className="menu">
                {showPlatformTools(roles) && (
                  <>
                    <p className="menu-title">Platform tools</p>
                    {platformTools(window.location.hostname, window.location.protocol).map((tool) => (
                      <a
                        key={tool.nav}
                        data-nav={tool.nav}
                        href={tool.href}
                        target="_blank"
                        rel="noopener noreferrer"
                        title={`${tool.label} (opens in new tab)`}
                      >
                        {tool.label}
                      </a>
                    ))}
                  </>
                )}
                <button type="button" data-action="logout" onClick={() => void logout()}>
                  Sign out
                </button>
              </div>
            </details>
          )}
        </div>
      </header>
      <div className="shell-body">
        <nav className="sidenav" aria-label="Primary">
          <div className="nav-group">
            {navFor(roles).map((item) => renderNavItem(item))}
          </div>
          {moreItems.length > 0 && (
            <div className="nav-group" data-nav-group="more">
              <p className="nav-title">More</p>
              {moreItems.map((item) => renderNavItem(item))}
            </div>
          )}
        </nav>
        <main className="content" id="main-content" tabIndex={-1}>
          {/* Row 575 (S1): a screen the known roles cannot use shows the NoAccess state, never a blank page. UX only - the server answers 403 regardless. */}
          {!open && (
            <>
              <PageHeader title={titleOf(route.page)} />
              <NoAccess refusal="ROLE_NOT_GRANTED" />
            </>
          )}
          {shown === 'flows' &&
            (route.flowId ? (
              <FlowDefinition key={`${route.flowId}#${routeSeq}`} openFlowId={route.flowId} guard={editorGuard} />
            ) : (
              <FlowsList />
            ))}
          {shown === 'versions' && <Versions selectId={route.flowId} />}
          {shown === 'systems' && <Systems />}
          {shown === 'queue' && <QueueDashboard />}
          {shown === 'failures' && <Failures />}
          {shown === 'messages' && (route.trackingId === undefined ? <MessagesLanding /> : <MessageDetail trackingId={route.trackingId} failedHint={route.failedHint ?? null} />)}
          {shown === 'approvals' && <ApprovalsLanding />}
          {shown === 'activity' && <Activity />}
          {shown === 'reports' && <DeliveryReport />}
          {shown === 'contracts' && <ContractVersions />}
          {shown === 'rules' && (
            <Suspense fallback={<p>Loading the rules editor...</p>}>
              <RulesEditor ruleSetId={route.ruleSetId ?? null} />
            </Suspense>
          )}
          {shown === 'mappings' && <MappingDesigner routeId={route.routeId ?? null} />}
          {shown === 'destinations' && <Destinations />}
          {shown === 'home' &&
            (session === 'checking' ? <p>Checking your session...</p> : session !== 'signed-out' && <Dashboard readsConfiguration={canOpen(roles, 'flows')} roles={roles} />)}
        </main>
      </div>
    </div>
  );
}