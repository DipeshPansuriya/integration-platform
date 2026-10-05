// T-158 (E3 lane D): the typed client is GENERATED from the backend's own OpenAPI document and
// never hand-edited. openapi-typescript (a pure type emitter, no runtime codegen) reads the
// document fetched from a really running platform-app and writes ui/src/api/openapi.d.ts.
// The committed generated output must always equal what the live document generates - proved by
// the drift test (ui/tests/openapi-contract.docker.test.mjs). No secret value, only names (R07).

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const UI = path.resolve(HERE, '..');

/** Runtime env names the console may read (R07 style: names, never values). */
export const API_URL_VARIABLE = 'VITE_API_URL';
export const OPENAPI_URL_VARIABLE = 'API_OPENAPI_URL';
export const OPENAPI_URL_DEFAULT = 'http://localhost:8080/v3/api-docs';

export function generatedTypesPath() {
  return path.join(UI, 'src', 'api', 'openapi.d.ts');
}

export function committedDocumentPath() {
  return path.join(UI, 'openapi.json');
}

export async function fetchDocumentFrom(apiUrl) {
  const response = await fetch(apiUrl, { headers: { Accept: 'application/json' } });
  if (!response.ok) {
    throw new Error(`GET ${apiUrl} answered ${response.status} - the backend OpenAPI document is not reachable`);
  }
  return response.json();
}

/** Emits the typed client for the given document object; returns its full text. */
export async function regenerateClientTypes(documentObject) {
  const openapiTS = (await import('openapi-typescript')).default;
  const { astToString } = await import('openapi-typescript');
  const ast = await openapiTS(documentObject);
  return astToString(ast);
}

/** Writes both committed artifacts (the document snapshot and the generated types). */
export async function persistGeneratedClient(documentObject) {
  fs.mkdirSync(path.dirname(generatedTypesPath()), { recursive: true });
  fs.writeFileSync(generatedTypesPath(), await regenerateClientTypes(documentObject));
  fs.writeFileSync(committedDocumentPath(), `${JSON.stringify(documentObject, null, 2)}\n`);
  return { types: generatedTypesPath(), doc: committedDocumentPath() };
}

/** Type-checks the console together with the generated client in-process (returns exit code). */
export function typeCheckConsole() {
  const npx = process.platform === 'win32' ? 'npx.cmd' : 'npx';
  const result = spawnSync(npx, ['tsc', '-p', 'tsconfig.json', '--noEmit'], {
    cwd: UI,
    encoding: 'utf8',
    shell: process.platform === 'win32'
  });
  if (result.stdout) process.stdout.write(result.stdout);
  if (result.stderr) process.stderr.write(result.stderr);
  return result.status ?? 1;
}