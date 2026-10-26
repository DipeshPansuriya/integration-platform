// T-028 (E6-F3-S1, lane D): the always-visible test panel's logic - FAILING FIRST.
//
// The panel runs a sample through the RUNTIME evaluation port only - POST
// /api/v1/admin/rule-sets/{ruleSetId}/evaluate on the one typed client - never an engine in the
// browser. Its verdict is the canonical JSON (keys sorted, no whitespace) of {output, violations}
// from the evaluate answer, so lane C's parity test (T-029) can compare it byte for byte with the
// port's own answer. The screen side is e2e-ui/tests/screens/rules-editor.docker.spec.mjs.

import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { canonicalJson, runSample, verdictText } from '../../src/rules/testPanel.ts';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const RULES_SRC = path.resolve(HERE, '..', '..', 'src', 'rules');

/** A stand-in for the typed client that records the one call the panel makes. */
function recordingClient(answer) {
  const calls = [];
  return {
    calls,
    POST: async (route, init) => {
      calls.push({ route, init });
      return answer;
    }
  };
}

test('canonical JSON sorts keys at every depth and has no whitespace', () => {
  assert.equal(canonicalJson({ b: 1, a: { d: [3, { z: 0, y: ' x ' }], c: null } }), '{"a":{"c":null,"d":[3,{"y":" x ","z":0}]},"b":1}');
});

test('the verdict is the canonical JSON of {output, violations} only', () => {
  const answer = {
    ruleSetId: 'r-1',
    kind: 'VALIDATION',
    violations: [{ message: 'amount must not exceed 100', field: 'amount', code: 'Z_AMOUNT_TOO_HIGH' }],
    output: { violation: [] }
  };
  assert.equal(
    verdictText(answer),
    '{"output":{"violation":[]},"violations":[{"code":"Z_AMOUNT_TOO_HIGH","field":"amount","message":"amount must not exceed 100"}]}'
  );
});

test('a sample runs through the runtime evaluation port with {input} and nothing else', async () => {
  const client = recordingClient({ data: { ruleSetId: 'r-1', kind: 'ROUTING', output: { target: 'dst-eu' }, violations: [] }, response: { status: 200 } });
  const result = await runSample(client, 'r-1', '{"country":"NL"}');
  assert.deepEqual(client.calls, [
    { route: '/api/v1/admin/rule-sets/{ruleSetId}/evaluate', init: { params: { path: { ruleSetId: 'r-1' } }, body: { input: { country: 'NL' } } } }
  ]);
  assert.deepEqual(result, { state: 'done', text: '{"output":{"target":"dst-eu"},"violations":[]}' });
});

test('@negative a sample that is not JSON is an error state and calls nothing', async () => {
  const client = recordingClient({});
  const result = await runSample(client, 'r-1', '{"country":');
  assert.equal(result.state, 'error');
  assert.match(result.text, /not valid JSON/);
  assert.deepEqual(client.calls, []);
});

test('@negative a refused evaluation is an error state that shows the status and the reason', async () => {
  const client = recordingClient({ error: { error: 'not found' }, response: { status: 404 } });
  const result = await runSample(client, 'r-x', '{}');
  assert.deepEqual(result, { state: 'error', text: 'refused 404: not found' });
});

test('@negative @security no engine runs in the browser: src/rules imports neither the wasm engine nor the editor simulator', () => {
  const files = fs.readdirSync(RULES_SRC, { recursive: true }).filter((f) => /\.tsx?$/.test(f));
  assert.ok(files.length > 0, 'the rules sources exist');
  const forbidden = /zen-engine-wasm|GraphSimulator|ensureWasmLoaded|evaluateExpression|ZenEngine/;
  const offenders = files.filter((f) => forbidden.test(fs.readFileSync(path.join(RULES_SRC, f), 'utf8')));
  assert.deepEqual(offenders, []);
});
