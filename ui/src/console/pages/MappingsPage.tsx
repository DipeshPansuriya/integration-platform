// Console wiring: #/mappings on the new design - MappingsScreen over the real worklist: the tenant's
// flows (useFlows, ONE call) and the selected flow's routes (GET /api/v1/admin/flows/{flowId}/routes,
// ONE call per selection; the first flow is selected on load). Replaces the landing half of
// pages/MappingDesigner (MappingRoutes) and keeps all it offered: the flow pills, the Route /
// Interface / Target system / Mapping (Saved / Not saved) / Open worklist, its named empty and error
// states, "Open mapping" -> #/mappings/<routeId>. There is no create-mapping endpoint (a mapping is a
// route's saved drawing), so New Mapping explains that instead of opening the screen's drawer; source /
// target formats, field counts and edit times are not in the routes answer, so none is shown.
// A non-null routeId (same props as MappingDesigner) opens the existing designer unchanged.

import { useEffect, useState } from 'react';
import { api } from '../../api/client';
import { useFlows, useResource } from '../../api/hooks';
import type { ApiAnswer } from '../../api/flows';
import { flowsErrorText, loadRoutes, mappingHref, NO_FLOWS, routeLabel, type FlowRoute } from '../../mapping/routesList';
import { RouteCallbackForm } from '../../mapping/RouteCallbackForm';
import { MappingDesigner } from '../../pages/MappingDesigner';
import MappingsScreen, { type MappingRowVM } from '../../screens/mappings/MappingsScreen';
import { Button } from '../../ds/components/actions/Button.jsx';
import { Callout } from '../../ds/components/feedback/Callout.jsx';
import { Panel } from '../../ds/components/surfaces/Panel.jsx';
import { goTo } from './console-pages';
import './list-pages.css';

const NO_ROUTES: ApiAnswer<FlowRoute[]> = { ok: true, data: [] };

/** The routes call answers a worded sentence, not a status: it is carried as the error text. */
async function flowRoutes(flowId: string): Promise<ApiAnswer<FlowRoute[]>> {
  const result = await loadRoutes(api, flowId);
  return result.state === 'ok' ? { ok: true, data: result.routes } : { ok: false, status: 0, error: result.text };
}

const routeName = (route: FlowRoute) => route.name ?? route.targetEndpointCode ?? 'unnamed endpoint';

export function MappingsPage({ routeId = null }: { routeId?: string | null } = {}) {
  if (routeId) return <MappingDesigner routeId={routeId} />;
  return <MappingsList />;
}

function MappingsList() {
  const flows = useFlows();
  const [selected, setSelected] = useState<string | null>(null);
  const [hint, setHint] = useState(false);
  const [expandedCb, setExpandedCb] = useState<string | null>(null);
  const routes = useResource<FlowRoute[], ApiAnswer<FlowRoute[]>>(
    () => (selected === null ? Promise.resolve(NO_ROUTES) : flowRoutes(selected)),
    [selected]
  );

  useEffect(() => {
    if (selected === null && flows.data && flows.data.length > 0) setSelected(flows.data[0].id);
  }, [selected, flows.data]);

  const flowList = flows.data ?? [];
  const current = flowList.find((flow) => flow.id === selected) ?? null;
  const flowsError = flows.status !== null
    ? flowsErrorText({ ok: false, status: flows.status, error: flows.error ?? undefined, refusal: flows.refusal ?? undefined })
    : null;
  const routesError = selected !== null && routes.status !== null ? routes.error ?? 'The routes could not be listed.' : null;
  const loading = flows.loading || (selected !== null && routes.loading);
  const list = selected !== null && !loading ? routes.data ?? [] : [];

  // The screen's table is hidden (its columns are formats / field counts the API does not carry);
  // these rows only drive its loading / empty / table decision, with no invented values.
  const rows: MappingRowVM[] = list.map((route) => ({
    name: routeLabel(route),
    sourceFormat: '',
    targetFormat: '',
    fields: null as unknown as number,
    usedBy: current?.name ?? null,
    updated: ''
  }));

  return (
    <div data-page="mapping-designer" data-mapping-loaded="true" className="cp-list cp-own-table">
      <div className="cp-list-tools">
        <p className="cp-muted cp-list-wide">
          Each route of an interface has one mapping: a source contract version bound to the target version. Pick an interface, then open the mapping of one of its routes.
        </p>
        {hint && (
          <div className="cp-list-wide" role="status">
            <Callout tone="info" title="A mapping belongs to a route">
              Pick a flow, then open one of its routes (Not saved = no mapping yet) and save the first link there.{' '}
              <Button size="sm" variant="ghost" onClick={() => setHint(false)}>Dismiss</Button>
            </Callout>
          </div>
        )}
        {flows.data !== null && flowList.length === 0 && (
          <p className="cp-muted cp-list-wide" data-empty="mapping-flows">
            {NO_FLOWS}. Define a flow first; each target it sends to gets a mapping.
          </p>
        )}
        {flowList.length > 0 && (
          <ul className="cp-pills cp-list-wide" data-mapping-flows aria-label="Flows">
            {flowList.map((flow) => (
              <li key={flow.id}>
                <Button
                  size="sm"
                  variant={selected === flow.id ? 'primary' : 'secondary'}
                  data-mapping-flow={flow.id}
                  aria-pressed={selected === flow.id}
                  onClick={() => setSelected(flow.id)}
                >
                  {flow.name} <span className="ip-id">({flow.code})</span>
                </Button>
              </li>
            ))}
          </ul>
        )}
      </div>
      <MappingsScreen
        rows={rows}
        loading={loading}
        error={flowsError ?? routesError}
        onRetry={() => {
          flows.reload();
          routes.reload();
        }}
        onNewOpen={() => setHint(true)}
      />
      {!loading && routesError === null && selected !== null && routes.data !== null && list.length === 0 && (
        <p className="cp-muted cp-list-body" data-empty="mapping-routes">This flow has no routes yet.</p>
      )}
      {!loading && flowsError === null && routesError === null && list.length > 0 && (
        <div className="cp-list-body">
          <Panel title="Routes">
            <div className="ip-table-wrap">
              <table className="ip-table" data-mapping-routes>
                <caption className="ip-sr">Routes of the selected flow</caption>
                <thead>
                  <tr>
                    <th scope="col">Route</th>
                    <th scope="col">Interface</th>
                    <th scope="col">Target system</th>
                    <th scope="col">Mapping</th>
                    <th scope="col">Callback</th>
                    <th scope="col">Open</th>
                  </tr>
                </thead>
                <tbody>
                  {list.map((route) => {
                    const cbWord = !route.callbackMode ? 'Off' : route.callbackMode === 'IMMEDIATE' ? 'Immediate' : 'Scheduled';
                    const cbTone = !route.callbackMode ? 'neutral' : route.callbackMode === 'IMMEDIATE' ? 'success' : 'info';
                    const cbOpen = expandedCb === route.id;
                    return (
                      <tr key={route.id} data-mapping-route={route.id} className="is-clickable" onClick={() => goTo(mappingHref(route.id))}>
                        <td>
                          <span className="ip-id">{routeName(route)}</span>
                          {route.targetEndpointCode && <span className="cp-sub">{route.targetEndpointCode}</span>}
                        </td>
                        <td>
                          {current?.name}
                          {current?.code && <span className="cp-sub">{current.code}</span>}
                        </td>
                        <td><span className="ip-id">{route.targetSystem ?? 'unknown system'}</span></td>
                        <td>
                          <span className={`badge badge-${route.mappingId ? 'success' : 'neutral'}`}>{route.mappingId ? 'Saved' : 'Not saved'}</span>
                        </td>
                        <td>
                          <span className={`badge badge-${cbTone}`}>{cbWord}</span>
                          <button
                            className="ip-btn ip-btn--ghost ip-btn--sm"
                            data-action="toggle-callback"
                            aria-expanded={cbOpen}
                            aria-label={`Configure callback: ${routeLabel(route)}`}
                            onClick={(e) => { e.stopPropagation(); setExpandedCb(cbOpen ? null : route.id); }}
                          >
                            {cbOpen ? '▲' : '▼'}
                          </button>
                        </td>
                        <td>
                          <a
                            className="ip-btn ip-btn--secondary ip-btn--sm"
                            data-action="open-mapping"
                            href={mappingHref(route.id)}
                            aria-label={`Open mapping: ${routeLabel(route)}`}
                            onClick={(e) => e.stopPropagation()}
                          >
                            Open mapping
                          </a>
                        </td>
                      </tr>
                    );
                  })}
                  {expandedCb && selected && (() => {
                    const route = list.find((r) => r.id === expandedCb);
                    if (!route) return null;
                    return (
                      <tr key={`${expandedCb}-cb`} data-callback-row={expandedCb}>
                        <td colSpan={6} style={{ padding: '12px 16px', background: 'var(--surface-raised, #1a1a2e)' }}>
                          <RouteCallbackForm
                            flowId={selected}
                            route={route}
                            onSaved={(mode, url) => {
                              route.callbackMode = mode;
                              route.callbackUrl = url;
                              routes.reload();
                            }}
                          />
                        </td>
                      </tr>
                    );
                  })()}
                </tbody>
              </table>
            </div>
          </Panel>
        </div>
      )}
    </div>
  );
}

export default MappingsPage;
