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
import { FlowDefinition } from './pages/FlowDefinition';
import { FlowsList } from './pages/FlowsList'; // row 310 lane B (T88): #/flows list landing
import { Versions } from './pages/Versions';
// E5 seam (arjun-fullstack, F13): route + menu stubs for E5 lane D's screens (T-159 systems registry, T-160
// contract versions); lane D fills the two page files and never edits this shell.
import { Systems } from './pages/Systems';
import { ContractVersions } from './pages/ContractVersions';
// F10-narrow seam (arjun-fullstack, row278 plan step 8): route + menu entry for lane D's queue
// dashboard; the page file is the whole slice's UI - this shell line stays one route + one menu line.
import { QueueDashboard } from './pages/QueueDashboard';
// E7 seam (arjun-fullstack, F5): route + menu stub for lane D1's mapping canvas (#/mappings/<routeId>, T-034);
// lane D fills the page file and never edits this shell.
import { MappingDesigner } from './pages/MappingDesigner';
// Row 310 lane A (sanjay-developer, T87): the Dashboard at #/ and the in-app sign-in card at #/login.
import { Dashboard } from './pages/Dashboard';
import { Login } from './pages/Login';
import { sessionStateOf, type SessionState } from './pages/dashboard-counts';
// E6 (lane D, T-025/T-028): the rules editor - #/rules lists and creates a flow's rule sets,
// #/rules/<ruleSetId> authors one as decision tables beside the always-visible test panel. Loaded on
// first use: the decision-table editor is a large bundle no other screen should pay for (page-load p95).
const RulesEditor = lazy(() => import('./rules/RulesEditor').then((m) => ({ default: m.RulesEditor })));

type Route = {
  page: 'home' | 'login' | 'flows' | 'versions' | 'systems' | 'contracts' | 'rules' | 'mappings' | 'queue';
  flowId: string | null;
  ruleSetId?: string | null;
  routeId?: string | null;
};

type Me = {
  userId?: string;
  tenantId?: string;
  tenantCode?: string;
  timeZone?: string;
};

type Theme = 'light' | 'dark';

/** The theme index.html resolved pre-paint; React only toggles it. */
function currentTheme(): Theme {
  return document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light';
}

// Nav groups in the order a configuration is actually done (00-§2). Dashboard is the
// Overview entry (route `#/`; the dashboard screen itself is lane A's work).
const NAV_GROUPS: Array<{ title: string; entries: Array<{ label: string; nav: string; hash: string; page: Route['page'] }> }> = [
  {
    title: 'Overview',
    entries: [
      { label: 'Dashboard', nav: 'dashboard', hash: '#/', page: 'home' },
      { label: 'Queue', nav: 'queue', hash: '#/queue', page: 'queue' },
    ],
  },
  {
    title: 'Build',
    entries: [
      { label: 'Flows', nav: 'flows', hash: '#/flows', page: 'flows' },
      { label: 'Versions', nav: 'versions', hash: '#/versions', page: 'versions' },
      { label: 'Systems', nav: 'systems', hash: '#/systems', page: 'systems' },
      { label: 'Contracts', nav: 'contracts', hash: '#/contracts', page: 'contracts' },
    ],
  },
  {
    title: 'Author',
    entries: [
      { label: 'Rules', nav: 'rules', hash: '#/rules', page: 'rules' },
      { label: 'Mappings', nav: 'mappings', hash: '#/mappings', page: 'mappings' },
    ],
  },
];

function parseHash(): Route {
  const hash = window.location.hash.replace(/^#\/?/, '');
  if (hash.startsWith('login')) return { page: 'login', flowId: null };
  const flow = /^flow\/(.+)$/.exec(hash);
  if (flow) return { page: 'flows', flowId: flow[1] };
  if (hash.startsWith('flows')) return { page: 'flows', flowId: null };
  if (hash.startsWith('versions')) return { page: 'versions', flowId: null };
  if (hash.startsWith('systems')) return { page: 'systems', flowId: null };
  if (hash.startsWith('queue')) return { page: 'queue', flowId: null };
  if (hash.startsWith('contracts')) return { page: 'contracts', flowId: null };
  const ruleSet = /^rules\/(.+)$/.exec(hash);
  if (ruleSet) return { page: 'rules', flowId: null, ruleSetId: decodeURIComponent(ruleSet[1]) };
  if (hash.startsWith('rules')) return { page: 'rules', flowId: null, ruleSetId: null };
  // E7 seam: #/mappings lists mappings - per route once the canvas (lane D1) fills the page.
  const routeId = /^mappings\/(.+)$/.exec(hash);
  if (routeId) return { page: 'mappings', flowId: null, routeId: decodeURIComponent(routeId[1]) };
  if (hash.startsWith('mappings')) return { page: 'mappings', flowId: null, routeId: null };
  return { page: 'home', flowId: null };
}

export function App() {
  const [route, setRoute] = useState<Route>(parseHash());
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
    const onHashChange = () => setRoute(parseHash());
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
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
    if (onLogin && session === 'signed-in') window.location.replace('#/');
    if (!onLogin && session === 'signed-out') window.location.hash = LOGIN_ROUTE;
  }, [onLogin, session]);

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
    const body = (await answer.json().catch(() => null)) as { redirect?: string } | null;
    window.location.assign(body?.redirect || '/');
  }

  // #/login is the empty shell: the card alone - no top bar, no side nav, no [data-nav].
  if (onLogin) return <Login state={session === 'signed-in' ? 'checking' : session} onRetry={checkSession} />;

  return (
    <div className={collapsed ? 'shell shell-collapsed' : 'shell'}>
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
        <div className="topbar-right">
          {me?.tenantCode && <span className="chip" data-session-tenant>{me.tenantCode}</span>}
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
              <summary className="chip account-summary" data-action="account">Account</summary>
              <div className="menu">
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
          {NAV_GROUPS.map((group) => (
            <div className="nav-group" key={group.title}>
              <p className="nav-title" aria-hidden="true">{group.title}</p>
              {group.entries.map((entry) => {
                const active = route.page === entry.page;
                return (
                  <a
                    key={entry.nav}
                    className={active ? 'nav-item nav-item-active' : 'nav-item'}
                    data-nav={entry.nav}
                    data-initial={entry.label.charAt(0)}
                    href={entry.hash}
                    title={entry.label}
                    aria-current={active ? 'page' : undefined}
                  >
                    <span className="nav-label">{entry.label}</span>
                  </a>
                );
              })}
            </div>
          ))}
        </nav>
        <main className="content">
          {route.page === 'flows' && (route.flowId ? <FlowDefinition openFlowId={route.flowId} /> : <FlowsList />)}
          {route.page === 'versions' && <Versions />}
          {route.page === 'systems' && <Systems />}
          {route.page === 'queue' && <QueueDashboard />}
          {route.page === 'contracts' && <ContractVersions />}
          {route.page === 'rules' && (
            <Suspense fallback={<p>Loading the rules editor...</p>}>
              <RulesEditor ruleSetId={route.ruleSetId ?? null} />
            </Suspense>
          )}
          {route.page === 'mappings' && <MappingDesigner routeId={route.routeId ?? null} />}
          {route.page === 'home' &&
            (session === 'checking' ? <p>Checking your session...</p> : session !== 'signed-out' && <Dashboard />)}
        </main>
      </div>
    </div>
  );
}