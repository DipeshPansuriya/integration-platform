// T93 (row 278 close-E7, T-162 gap 2): open a mapping from the screens - FAILING FIRST.
//
// The #/mappings landing lists the tenant's flows; selecting one asks GET
// /api/v1/admin/flows/{flowId}/routes ONCE (no N+1) and shows each route by its target system and
// endpoint with an "Open mapping" link to #/mappings/<routeId> (the route id the designer reads with
// GET /api/v1/admin/routes/{routeId}/mapping). Named empty and error states, nothing hidden.
// The screen side is e2e-ui/tests/screens/mapping-designer.docker.spec.mjs.

import test from 'node:test';
import assert from 'node:assert/strict';
import { NO_FLOWS, flowsErrorText, loadRoutes, mappingHref, routeLabel } from '../../src/mapping/routesList.ts';

function recordingClient(answer) {
  const calls = [];
  return {
    calls,
    GET: async (route, init) => {
      calls.push({ route, init });
      if (answer instanceof Error) throw answer;
      return answer;
    }
  };
}

const ROUTES_ANSWER = {
  flowId: 'f-1',
  versionId: 'v-1',
  version: 1,
  versionStatus: 'DRAFT',
  routes: [
    { id: 'r-a', name: 'Target A endpoint', sequence: 1, isPrimary: true, status: 'ACTIVE', condition: null,
      targetEndpointId: 'e-a', targetEndpointCode: 'tgt-a-default', targetSystem: 'tgt-a', mappingId: 'm-a' },
    { id: 'r-b', name: null, sequence: 2, isPrimary: false, status: 'ACTIVE', condition: null,
      targetEndpointId: 'e-b', targetEndpointCode: 'tgt-b-default', targetSystem: 'tgt-b', mappingId: null }
  ]
};

test('one flow\'s routes are read in ONE call and kept in the listed order', async () => {
  const client = recordingClient({ data: ROUTES_ANSWER, response: { status: 200, ok: true } });
  const answer = await loadRoutes(client, 'f-1');
  assert.deepEqual(client.calls, [
    { route: '/api/v1/admin/flows/{flowId}/routes', init: { params: { path: { flowId: 'f-1' } } } }
  ]);
  assert.equal(answer.state, 'ok');
  assert.deepEqual(answer.routes.map((r) => r.id), ['r-a', 'r-b']);
});

test('a route is named by its endpoint and target system, with fall-backs when a name is missing', () => {
  assert.equal(routeLabel(ROUTES_ANSWER.routes[0]), 'Target A endpoint (tgt-a)');
  assert.equal(routeLabel(ROUTES_ANSWER.routes[1]), 'tgt-b-default (tgt-b)');
  assert.equal(routeLabel({ id: 'r-x' }), 'unnamed endpoint (unknown system)');
});

test('"Open mapping" links to the designer of exactly that route id', () => {
  assert.equal(mappingHref('3f2c1a9e-0000-4000-8000-000000000001'), '#/mappings/3f2c1a9e-0000-4000-8000-000000000001');
  assert.equal(mappingHref('a/b c'), '#/mappings/a%2Fb%20c');
});

test('@negative a refused routes listing is a named error state carrying the server\'s message', async () => {
  for (const [status, body, shown] of [
    [404, { error: 'not found' }, 'not found'],
    [403, { refusal: 'NO_BOUND_USER' }, 'NO_BOUND_USER'],
    [404, { message: 'flow gone', code: 'NOT_FOUND' }, 'flow gone'],
    [500, undefined, 'HTTP 500']
  ]) {
    const client = recordingClient({ error: body, response: { status, ok: false } });
    const answer = await loadRoutes(client, 'f-x');
    assert.deepEqual(answer, { state: 'error', text: `The routes could not be listed: ${shown}` });
  }
});

test('@negative a routes call that cannot reach the server is a named error state, never an exception', async () => {
  const answer = await loadRoutes(recordingClient(new Error('network down')), 'f-1');
  assert.deepEqual(answer, { state: 'error', text: 'The routes could not be listed: network down' });
});

test('@negative the flows list has a named empty state and a named error state', () => {
  assert.equal(NO_FLOWS, 'No flows yet');
  assert.equal(flowsErrorText({ ok: false, status: 403, refusal: 'NO_BOUND_USER' }), 'The flows could not be listed: NO_BOUND_USER');
  assert.equal(flowsErrorText({ ok: false, status: 500 }), 'The flows could not be listed: HTTP 500');
  assert.equal(flowsErrorText({ ok: false, status: 400, error: 'bad' }), 'The flows could not be listed: bad');
});
