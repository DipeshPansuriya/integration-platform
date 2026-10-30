// T93 (row 278 close-E7, T-162 gap 2): open a mapping from the screens. One flow's routes come from
// GET /api/v1/admin/flows/{flowId}/routes in ONE call (FlowRoutesQuery: the route ids are the live
// route rows' ids - the same id GET /api/v1/admin/routes/{routeId}/mapping and #/mappings/<routeId>
// take). Each route is named by its target endpoint and system and links to its designer. Named
// empty and error states (tests/mapping/routes-list.test.mjs).

import type { ConsoleClient } from '../api/client';
import type { ApiAnswer } from '../api/flows';
import { refusalMessage } from './sampleRun';

export const NO_FLOWS = 'No flows yet';

export type FlowRoute = {
  id: string;
  name?: string | null;
  sequence?: number;
  targetEndpointCode?: string | null;
  targetSystem?: string | null;
  mappingId?: string | null;
  callbackMode?: string | null;
  callbackUrl?: string | null;
};

export type RoutesResult = { state: 'ok'; routes: FlowRoute[] } | { state: 'error'; text: string };

const ROUTES_FAILED = 'The routes could not be listed: ';

export async function loadRoutes(client: Pick<ConsoleClient, 'GET'>, flowId: string): Promise<RoutesResult> {
  try {
    const { data, error, response } = await client.GET('/api/v1/admin/flows/{flowId}/routes', {
      params: { path: { flowId } }
    });
    if (error !== undefined || data === undefined) {
      return { state: 'error', text: ROUTES_FAILED + refusalMessage(error, response.status) };
    }
    return { state: 'ok', routes: ((data as { routes?: FlowRoute[] }).routes ?? []).filter((r) => r?.id) };
  } catch (e) {
    return { state: 'error', text: ROUTES_FAILED + (e as Error).message };
  }
}

/** "<endpoint name> (<target system>)" - the endpoint code stands in for a missing name. */
export function routeLabel(route: FlowRoute): string {
  return `${route.name ?? route.targetEndpointCode ?? 'unnamed endpoint'} (${route.targetSystem ?? 'unknown system'})`;
}

export function mappingHref(routeId: string): string {
  return `#/mappings/${encodeURIComponent(routeId)}`;
}

export function flowsErrorText(answer: Extract<ApiAnswer<unknown>, { ok: false }>): string {
  return `The flows could not be listed: ${answer.error ?? answer.refusal ?? `HTTP ${answer.status}`}`;
}
