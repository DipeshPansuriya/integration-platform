// T93 (row 278 close-E7, T-162 gap 2): the #/mappings landing - the tenant's flows (ONE call), and the
// selected flow's routes (ONE call per selection, never per flow on load) with an "Open mapping" link
// to #/mappings/<routeId>. Selector contract: [data-mapping-flows], [data-mapping-flow="<flowId>"],
// [data-mapping-route="<routeId>"] with <a data-action="open-mapping">.

import { useEffect, useRef, useState } from 'react';
import { api } from '../api/client';
import { listFlows, type FlowSummary } from '../api/flows';
import { flowsErrorText, loadRoutes, mappingHref, NO_FLOWS, routeLabel, type RoutesResult } from './routesList';

export function MappingRoutes() {
  const [flows, setFlows] = useState<FlowSummary[] | null>(null);
  const [flowsError, setFlowsError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [routes, setRoutes] = useState<RoutesResult | null>(null);
  const latest = useRef<string | null>(null); // a later selection wins over a slower earlier answer

  useEffect(() => {
    let cancelled = false;
    listFlows()
      .then((answer) => {
        if (cancelled) return;
        if (answer.ok) setFlows(answer.data);
        else setFlowsError(flowsErrorText(answer));
      })
      .catch((e: Error) => !cancelled && setFlowsError(`The flows could not be listed: ${e.message}`));
    return () => {
      cancelled = true;
    };
  }, []);

  async function select(flowId: string) {
    latest.current = flowId;
    setSelected(flowId);
    setRoutes(null);
    const answer = await loadRoutes(api, flowId);
    if (latest.current === flowId) setRoutes(answer);
  }

  if (flowsError) return <p role="alert" data-load-error="mapping-flows">{flowsError}</p>;
  if (!flows) return <p aria-live="polite">Loading flows...</p>;
  if (flows.length === 0) return <p data-empty="mapping-flows">{NO_FLOWS}. Define a flow first; each target it sends to gets a mapping.</p>;

  return (
    <>
      <p>Pick a flow, then open the mapping of one of its routes.</p>
      <ul data-mapping-flows aria-label="Flows">
        {flows.map((flow) => (
          <li key={flow.id}>
            <button
              type="button"
              data-mapping-flow={flow.id}
              aria-pressed={selected === flow.id}
              onClick={() => void select(flow.id)}
            >
              {flow.name} ({flow.code})
            </button>
          </li>
        ))}
      </ul>
      {selected && !routes && <p aria-live="polite">Loading routes...</p>}
      {routes?.state === 'error' && <p role="alert" data-load-error="mapping-routes">{routes.text}</p>}
      {routes?.state === 'ok' && routes.routes.length === 0 && <p data-empty="mapping-routes">This flow has no routes yet.</p>}
      {routes?.state === 'ok' && routes.routes.length > 0 && (
        <table>
          <caption>Routes of the selected flow</caption>
          <thead>
            <tr>
              <th>Target (endpoint and system)</th>
              <th>Mapping</th>
            </tr>
          </thead>
          <tbody>
            {routes.routes.map((route) => (
              <tr key={route.id} data-mapping-route={route.id}>
                <td>{routeLabel(route)}</td>
                <td>
                  <a data-action="open-mapping" href={mappingHref(route.id)} aria-label={`Open mapping: ${routeLabel(route)}`}>
                    Open mapping
                  </a>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}
