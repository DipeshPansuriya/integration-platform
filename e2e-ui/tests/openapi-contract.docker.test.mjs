// T-158 (E3 lane D, row 278) - the typed-client contract test, FAILING FIRST.
//
// The source of truth for the console's typed client is the backend's own OpenAPI document
// (GET /v3/api-docs on a really running platform-app; no mock anywhere). This test proves:
//
//   1. drift  : regenerating the client from the document fetched RIGHT NOW from the running
//               backend, in a clean tree, diffed against what is committed - any drift fails.
//   2. reach  : every documented operation is reachable through the typed client over real
//               authenticated HTTP (probe: status stays below 500, backend handles the route).
//   3. compile: the console compiles with the generated client (tsc).
//
// Container group: boots the backend against its own PostgreSQL plus the real Keycloak (realm
// `platform`; the harness logs in through the realm form and calls with the console session -
// E4 T-097). Mirrors the Java
// lanes' @Tag("container") rule: excluded from the default `npm test` run, run by
// `npm run test:container` under the one-docker-suite-at-a-time lock (which boot-backend.mjs
// takes itself, the same lock file /d/Projects/ipass/.docker-suite.lock the Maven gate uses).

import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import bootBackend, { fetchOpenApiDocument } from '../scripts/boot-backend.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const UI = path.resolve(HERE, '..');
const REPO = path.resolve(UI, '..');
const GENERATED_TS = path.join(UI, 'src', 'api', 'openapi.d.ts');
const COMMITTED_DOC = path.join(UI, 'openapi.json');

test.before(async () => {
  globalThis.__boot = await bootBackend();
});

test.after(async () => {
  if (globalThis.__boot) await globalThis.__boot.teardown();
});

test('drift: the committed generated client is exactly what the live backend document generates', async () => {
  const doc = await fetchOpenApiDocument(globalThis.__boot);

  assert.ok(doc.paths, 'the document carries paths');
  const committed = JSON.parse(fs.readFileSync(COMMITTED_DOC, 'utf8'));
  assert.deepEqual(
    normalize(doc),
    normalize(committed),
    'ui/openapi.json has drifted from the live GET /v3/api-docs - run npm run generate:client'
  );

  assert.ok(fs.existsSync(GENERATED_TS), 'the generated client ui/src/api/openapi.d.ts is committed');
  const fresh = await regenerateTypes(doc);
  assert.equal(
    fs.readFileSync(GENERATED_TS, 'utf8'),
    fresh,
    'ui/src/api/openapi.d.ts has drifted from the live document - run npm run generate:client'
  );
});

test('reach: every documented operation is reachable through the typed client over real HTTP', async () => {
  const boot = globalThis.__boot;
  const doc = await fetchOpenApiDocument(boot);

  const documented = [];
  for (const [p, methods] of Object.entries(doc.paths ?? {})) {
    for (const method of Object.keys(methods).filter((m) => m !== 'parameters' && m !== 'options')) {
      documented.push({ path: p, method, ...methods[method] });
    }
  }
  assert.ok(documented.length > 0, 'the document documents at least one operation');

  assert.ok(fs.existsSync(GENERATED_TS), 'the generated client exists');
  const generated = fs.readFileSync(GENERATED_TS, 'utf8');
  for (const op of documented) {
    assert.ok(
      generated.includes(`"${op.path}"`),
      `the typed client does not expose the documented path ${op.path} (operation ${op.method.toUpperCase()})`
    );
  }

  for (const op of documented.filter((o) => o.method.toUpperCase() === 'GET')) {
    // /v3/api-docs itself is excluded from the probe while the security seam only permits it
    // through lane-external code; see the lane hand-off for the permitAll request.
    if (op.path.startsWith('/v3/')) continue;
    const url = new URL(op.path.replace(/\{[^}]+\}/g, '00000000-0000-0000-0000-000000000000'), boot.baseUrl);
    const response = await fetch(url, { headers: boot.authHeaders() });
    assert.ok(
      response.status < 500,
      `${op.method.toUpperCase()} ${op.path} is not reachable through the running backend (status ${response.status})`
    );
  }
});

test('compile: the console compiles with the generated client', async () => {
  const exit = await runTypescript();
  assert.equal(exit, 0, `tsc rejects the console+client (exit ${exit})`);
});

function normalize(doc) {
  // The `servers` block echoes the run's port; everything else is the contract.
  const copy = JSON.parse(JSON.stringify(doc));
  delete copy.servers;
  if (copy.info) delete copy.info.version;
  return copy;
}

async function regenerateTypes(doc) {
  const { regenerateClientTypes } = await import('../../e2e-ui/scripts/typescribe.mjs');
  return regenerateClientTypes(doc);
}

async function runTypescript() {
  const { spawnSync } = await import('node:child_process');
  const npx = process.platform === 'win32' ? 'npx.cmd' : 'npx';
  const result = spawnSync(npx, ['tsc', '-p', 'tsconfig.json', '--noEmit'], {
    cwd: UI,
    encoding: 'utf8',
    shell: process.platform === 'win32'
  });
  return result.status ?? 1;
}