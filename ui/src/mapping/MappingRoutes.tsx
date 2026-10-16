// T93 (row 278 close-E7, T-162 gap 2): the #/mappings landing - the tenant's flows (ONE call), and the
// selected flow's routes (ONE call per selection, never per flow on load) with an "Open mapping" link
// to #/mappings/<routeId>. Selector contract: [data-mapping-flows], [data-mapping-flow="<flowId>"],
// [data-mapping-route="<routeId>"] with <a data-action="open-mapping">.
// Row 310 T103 (wave 4 lane E): restyled into the 10-screens s8 worklist - interface pills, then a
// table [data-mapping-routes] of Route, Interface, Target system, Mapping (worded badge), Open.

import { useEffect, useRef, useState } from 'react';
import { api } from '../api/client';
import { listFlows, type FlowSummary } from '../api/flows';
import { flowsErrorText, loadRoutes, mappingHref, NO_FLOWS, routeLabel, type FlowRoute, type RoutesResult } from './routesList';

/** The worklist's Mapping column (10-screens s8 "Saved badge"): the routes listing carries the saved
 * mapping's id - the word is the carrier, the tone only repeats it. */
const mappingBadge = (route: FlowRoute) =>
  route.mappingId ? { word: 'Saved', tone: 'success' } : { word: 'Not saved', tone: 'neutral' };

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

  if (flowsError) return <p role="alert" className="mapping-alert mapping-alert-danger" data-load-error="mapping-flows">{flowsError}</p>;
  if (!flows) return <p className="mapping-muted" aria-live="polite">Loading flows...</p>;
  if (flows.length === 0) {
    return <p className="mapping-empty" data-empty="mapping-flows">{NO_FLOWS}. Define a flow first; each target it sends to gets a mapping.</p>;
  }

  const current = flows.find((flow) => flow.id === selected) ?? null;
  return (
    <>
      <p className="mapping-lede">Pick an interface, then open the mapping of one of its routes.</p>
      <ul className="mapping-flows" data-mapping-flows aria-label="Flows">
        {flows.map((flow) => (
          <li key={flow.id}>
            <button
              type="button"
              data-mapping-flow={flow.id}
              aria-pressed={selected === flow.id}
              onClick={() => void select(flow.id)}
            >
              {flow.name} <span className="mapping-mono">({flow.code})</span>
            </button>
          </li>
        ))}
      </ul>
      {selected && !routes && <p className="mapping-muted" aria-live="polite">Loading routes...</p>}
      {routes?.state === 'error' && (
        <p role="alert" className="mapping-alert mapping-alert-danger" data-load-error="mapping-routes">{routes.text}</p>
      )}
      {routes?.state === 'ok' && routes.routes.length === 0 && (
        <p className="mapping-empty" data-empty="mapping-routes">This flow has no routes yet.</p>
      )}
      {routes?.state === 'ok' && routes.routes.length > 0 && (
        <div className="mapping-grid">
          <table className="table" data-mapping-routes>
            <caption>Routes of the selected flow</caption>
            <thead>
              <tr>
                <th scope="col">Route</th>
                <th scope="col">Interface</th>
                <th scope="col">Target system</th>
                <th scope="col">Mapping</th>
                <th scope="col">Open</th>
              </tr>
            </thead>
            <tbody>
              {routes.routes.map((route) => {
                const badge = mappingBadge(route);
                return (
                  <tr key={route.id} data-mapping-route={route.id}>
                    <td>
                      {route.name ?? route.targetEndpointCode ?? 'unnamed endpoint'}
                      {route.targetEndpointCode && <span className="mapping-sub mapping-mono">{route.targetEndpointCode}</span>}
                    </td>
                    <td>
                      {current?.name}
                      {current?.code && <span className="mapping-sub mapping-mono">{current.code}</span>}
                    </td>
                    <td className="mapping-mono">{route.targetSystem ?? 'unknown system'}</td>
                    <td>
                      <span className={`badge badge-${badge.tone}`}>{badge.word}</span>
                    </td>
                    <td>
                      <a className="btn" data-action="open-mapping" href={mappingHref(route.id)} aria-label={`Open mapping: ${routeLabel(route)}`}>
                        Open mapping
                      </a>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
