// L29: where the console project lives now that the suites sit in e2e-ui/. One place, so a path assumption cannot
// stay half-converted (openapi-contract.docker.test.mjs needs Docker; console-paths.test.mjs proves these offline).
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const E2E_UI = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const REPO = path.resolve(E2E_UI, '..');
export const CONSOLE = path.join(REPO, 'ui');
export const GENERATED_TS = path.join(CONSOLE, 'src', 'api', 'openapi.d.ts');
export const COMMITTED_DOC = path.join(CONSOLE, 'openapi.json');
export const TYPESCRIBE = path.join(CONSOLE, 'scripts', 'typescribe.mjs');
export const TSCONFIG = path.join(CONSOLE, 'tsconfig.json');
