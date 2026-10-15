// T92 (row 278 close-E7, T-162 gap 1): the mapping designer's "Run sample" logic - FAILING FIRST.
//
// The sample runs the SAVED mapping through POST /api/v1/admin/mappings/{id}/test on the one typed
// client ({"sample": ...}); nothing transforms in the browser. Every refusal is shown, none hidden:
// a sample that is not JSON and an unsaved drawing are refused before any call; a server refusal
// (400/403/404) shows the server's own message; a 200 with ERROR or MAPPING_TIMEOUT is an error state
// carrying the engine's text. The screen side is ui/tests/screens/mapping-designer.docker.spec.mjs.

import test from 'node:test';
import assert from 'node:assert/strict';
import { NOT_JSON, SAVE_FIRST, runMappingSample } from '../../src/mapping/sampleRun.ts';

/** A stand-in for the typed client that records the one call the panel makes. */
function recordingClient(answer) {
  const calls = [];
  return {
    calls,
    POST: async (route, init) => {
      calls.push({ route, init });
      if (answer instanceof Error) throw answer;
      return answer;
    }
  };
}

test('a sample runs the saved mapping through the test endpoint with {sample} and nothing else', async () => {
  const client = recordingClient({
    data: { mappingId: 'm-1', status: 'OK', output: { clientId: 'C-0001', invoiceValue: 42.5 }, error: null },
    response: { status: 200 }
  });
  const result = await runMappingSample(client, 'm-1', '{"customerId":"C-0001","amount":42.5}', false);
  assert.deepEqual(client.calls, [
    {
      route: '/api/v1/admin/mappings/{id}/test',
      init: { params: { path: { id: 'm-1' } }, body: { sample: { customerId: 'C-0001', amount: 42.5 } } }
    }
  ]);
  assert.equal(result.state, 'ok');
  assert.equal(result.status, 'OK');
  assert.deepEqual(JSON.parse(result.text), { clientId: 'C-0001', invoiceValue: 42.5 });
  assert.equal(result.text, JSON.stringify({ clientId: 'C-0001', invoiceValue: 42.5 }, null, 2), 'pretty output JSON');
});

test('@negative a sample that is not JSON is refused and calls nothing', async () => {
  const client = recordingClient({});
  const result = await runMappingSample(client, 'm-1', '{"customerId":', false);
  assert.deepEqual(result, { state: 'refused', status: null, text: NOT_JSON });
  assert.deepEqual(client.calls, []);
});

test('@negative unsaved edits on the drawing are refused with the save-first message and call nothing', async () => {
  const client = recordingClient({});
  const result = await runMappingSample(client, 'm-1', '{"customerId":"C-0001"}', true);
  assert.equal(SAVE_FIRST, 'Save the mapping first - the sample runs the saved mapping');
  assert.deepEqual(result, { state: 'refused', status: null, text: SAVE_FIRST });
  assert.deepEqual(client.calls, []);
});

test('@negative no saved mapping id is refused with the save-first message and calls nothing', async () => {
  const client = recordingClient({});
  const result = await runMappingSample(client, null, '{}', false);
  assert.deepEqual(result, { state: 'refused', status: null, text: SAVE_FIRST });
  assert.deepEqual(client.calls, []);
});

test('@negative a server refusal shows the status and the server\'s own message', async () => {
  for (const [status, body, shown] of [
    [400, { error: 'the mandatory sample is missing' }, 'the mandatory sample is missing'],
    [404, { message: 'not found', code: 'NOT_FOUND' }, 'not found'],
    [403, { refusal: 'NO_BOUND_USER' }, 'NO_BOUND_USER']
  ]) {
    const client = recordingClient({ error: body, response: { status } });
    const result = await runMappingSample(client, 'm-1', 'null', false);
    assert.deepEqual(result, { state: 'refused', status: null, text: `Refused (${status}): ${shown}` });
    assert.equal(client.calls.length, 1, 'the server was asked once');
  }
});

test('@negative a 200 with ERROR or MAPPING_TIMEOUT is an error state carrying the engine\'s text', async () => {
  for (const status of ['ERROR', 'MAPPING_TIMEOUT']) {
    const client = recordingClient({
      data: { mappingId: 'm-1', status, output: null, error: `engine said ${status}` },
      response: { status: 200 }
    });
    const result = await runMappingSample(client, 'm-1', '{}', false);
    assert.deepEqual(result, { state: 'error', status, text: `engine said ${status}` });
  }
});

test('@negative a call that cannot reach the server is an error state, never an exception', async () => {
  const client = recordingClient(new Error('network down'));
  const result = await runMappingSample(client, 'm-1', '{}', false);
  assert.deepEqual(result, { state: 'error', status: null, text: 'The sample could not run: network down' });
});
