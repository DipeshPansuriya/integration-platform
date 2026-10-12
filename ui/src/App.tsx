// T-158 (E3 lane D): the console shell. Hash routing so every admin screen is directly
// loadable (the p95 measurement loads pages by URL); the flow-definition screen (T-017) and
// the version + approval screen (T-022) hang off the [data-nav] entries. Both use ONE typed
// client (src/api/client.ts) against the real API - never a mock (E3-F3-S1). No visual design
// is invented here: design.md (T-095, mahesh-architect) is the agreed look.
//
// T-097 (E4-F1-S1): the shell shows the session's bound tenant (GET /api/v1/me) and signs
// out by POSTing /logout (CSRF header) and navigating to the answer's sign-out address.

import { lazy, Suspense, useEffect, useState } from 'react';
import { api, readCookie } from './api/client';
import { FlowDefinition } from './pages/FlowDefinition';
import { Versions } from './pages/Versions';
// E5 seam (arjun-fullstack, F13): route + menu stubs for E5 lane D's screens (T-159 systems registry, T-160
// contract versions); lane D fills the two page files and never edits this shell.
import { Systems } from './pages/Systems';
import { ContractVersions } from './pages/ContractVersions';
// E7 seam (arjun-fullstack, F5): route + menu stub for lane D1's mapping canvas (#/mappings/<routeId>, T-034);
// lane D fills the page file and never edits this shell.
import { MappingDesigner } from './pages/MappingDesigner';
// E6 (lane D, T-025/T-028): the rules editor - #/rules lists and creates a flow's rule sets,
// #/rules/<ruleSetId> authors one as decision tables beside the always-visible test panel. Loaded on
// first use: the decision-table editor is a large bundle no other screen should pay for (page-load p95).
const RulesEditor = lazy(() => import('./rules/RulesEditor').then((m) => ({ default: m.RulesEditor })));

type Route = {
  page: 'home' | 'flows' | 'versions' | 'systems' | 'contracts' | 'rules' | 'mappings';
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

function parseHash(): Route {
  const hash = window.location.hash.replace(/^#\/?/, '');
  const flow = /^flow\/(.+)$/.exec(hash);
  if (flow) return { page: 'flows', flowId: flow[1] };
  if (hash.startsWith('flows')) return { page: 'flows', flowId: null };
  if (hash.startsWith('versions')) return { page: 'versions', flowId: null };
  if (hash.startsWith('systems')) return { page: 'systems', flowId: null };
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
  // The bound tenant of the browser's console session; a 401 sends the browser to the Keycloak
  // login in client.ts, so `me` stays null exactly while there is no session.
  const [me, setMe] = useState<Me | null>(null);

  useEffect(() => {
    const onHashChange = () => setRoute(parseHash());
    window.addEventListener('hashchange', onHashChange);

    api
      .GET('/api/v1/me')
      .then(({ data }) => setMe(data ?? null))
      .catch(() => null);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  /** RP-initiated logout: POST /logout (CSRF-protected), then the answer names the sign-out URL. */
  async function logout() {
    const headers = new Headers();
    const token = readCookie('XSRF-TOKEN');
    if (token) headers.set('X-XSRF-TOKEN', token);
    const answer = await fetch('/logout', { method: 'POST', headers, credentials: 'include' });
    const body = (await answer.json().catch(() => null)) as { redirect?: string } | null;
    window.location.assign(body?.redirect || '/');
  }

  return (
    <div>
      <nav>
        <button type="button" data-nav="flows" onClick={() => (window.location.hash = '#/flows')}>
          Flows
        </button>
        <button type="button" data-nav="versions" onClick={() => (window.location.hash = '#/versions')}>
          Versions
        </button>
        <button type="button" data-nav="systems" onClick={() => (window.location.hash = '#/systems')}>
          Systems
        </button>
        <button type="button" data-nav="contracts" onClick={() => (window.location.hash = '#/contracts')}>
          Contracts
        </button>
        <button type="button" data-nav="rules" onClick={() => (window.location.hash = '#/rules')}>
          Rules
        </button>
        <button type="button" data-nav="mappings" onClick={() => (window.location.hash = '#/mappings')}>
          Mappings
        </button>
        {me?.tenantCode && <span data-session-tenant>{me.tenantCode}</span>}
        {me && (
          <button type="button" data-action="logout" onClick={() => void logout()}>
            Sign out
          </button>
        )}
      </nav>
      {route.page === 'flows' && <FlowDefinition openFlowId={route.flowId} />}
      {route.page === 'versions' && <Versions />}
      {route.page === 'systems' && <Systems />}
      {route.page === 'contracts' && <ContractVersions />}
      {route.page === 'rules' && (
        <Suspense fallback={<p>Loading the rules editor...</p>}>
          <RulesEditor ruleSetId={route.ruleSetId ?? null} />
        </Suspense>
      )}
      {route.page === 'mappings' && <MappingDesigner routeId={route.routeId ?? null} />}
      {route.page === 'home' && <main data-page="console-home">Integration Platform Console</main>}
    </div>
  );
}
