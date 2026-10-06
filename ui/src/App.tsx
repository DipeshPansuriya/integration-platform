// T-158 (E3 lane D): the console shell. Hash routing so every admin screen is directly
// loadable (the p95 measurement loads pages by URL); the flow-definition screen (T-017) and
// the version + approval screen (T-022) hang off the [data-nav] entries. Both use ONE typed
// client (src/api/client.ts) against the real API - never a mock (E3-F3-S1). No visual design
// is invented here: design.md (T-095, mahesh-architect) is the agreed look.

import { useEffect, useState } from 'react';
import { FlowDefinition } from './pages/FlowDefinition';
import { Versions } from './pages/Versions';

type Route = { page: 'home' | 'flows' | 'versions'; flowId: string | null };

function parseHash(): Route {
  const hash = window.location.hash.replace(/^#\/?/, '');
  const flow = /^flow\/(.+)$/.exec(hash);
  if (flow) return { page: 'flows', flowId: flow[1] };
  if (hash.startsWith('flows')) return { page: 'flows', flowId: null };
  if (hash.startsWith('versions')) return { page: 'versions', flowId: null };
  return { page: 'home', flowId: null };
}

export function App() {
  const [route, setRoute] = useState<Route>(parseHash());

  useEffect(() => {
    const onHashChange = () => setRoute(parseHash());
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  return (
    <div>
      <nav>
        <button type="button" data-nav="flows" onClick={() => (window.location.hash = '#/flows')}>
          Flows
        </button>
        <button type="button" data-nav="versions" onClick={() => (window.location.hash = '#/versions')}>
          Versions
        </button>
      </nav>
      {route.page === 'flows' && <FlowDefinition openFlowId={route.flowId} />}
      {route.page === 'versions' && <Versions />}
      {route.page === 'home' && <main data-page="console-home">Integration Platform Console</main>}
    </div>
  );
}
