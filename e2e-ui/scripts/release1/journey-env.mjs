// L28 (row 372): the E2E_* environment the Release 1 browser journey (tests/release1/journey.docker.spec.mjs)
// reads, built from the runner's own boot output. Pure: no docker, no network. Values live in the child
// process environment only (R07); the log carries names.
import path from 'node:path';

/** Every name the journey spec asks for. The unit test fails if the spec and this list drift apart. */
export const JOURNEY_ENV_NAMES = Object.freeze([
  'CONSOLE_URL', 'E2E_BACKEND_URL', 'E2E_BASE_SUITE_LIB', 'E2E_BEARER_CUSTOMER_A',
  'E2E_WIREMOCK_URL', 'E2E_WIREMOCK_ADMIN_URL', 'E2E_INTERFACE_CODE', 'E2E_FLOW_ID', 'E2E_ROUTE_A_ID',
  'E2E_ADMIN_USER', 'E2E_ADMIN_PASSWORD'
]);

export function journeyEnv({ ui, consoleUrl, backend, wiremock, seeded, tokens }) {
  const [routeA] = seeded.routeIds ?? [];
  const routeId = typeof routeA === 'string' ? routeA : routeA?.routeId ?? routeA?.id;
  const env = {
    CONSOLE_URL: consoleUrl,
    E2E_BACKEND_URL: backend.baseUrl,
    E2E_BASE_SUITE_LIB: path.join(ui, 'tests', 'base-suite', 'lib'),
    E2E_BEARER_CUSTOMER_A: tokens.CUSTOMER_A,
    E2E_WIREMOCK_URL: wiremock.url,
    E2E_WIREMOCK_ADMIN_URL: wiremock.adminUrl,
    E2E_INTERFACE_CODE: seeded.interfaceCode,
    E2E_FLOW_ID: seeded.flowId,
    E2E_ROUTE_A_ID: routeId,
    E2E_ADMIN_USER: backend.users.default.username,
    E2E_ADMIN_PASSWORD: backend.users.default.password
  };
  const missing = JOURNEY_ENV_NAMES.filter((name) => !env[name]);
  if (missing.length) throw new Error(`journey env: the boot did not provide ${missing.join(', ')}`);
  return env;
}
