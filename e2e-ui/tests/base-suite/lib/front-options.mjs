// lane qa-release (2026-10-22): the front half's boot - the base suite's scenarios that need NO dev-only
// exception (no showcase profile, no showcase host or port, none of the four IPASS_BS_EXCEPTION_* names).
// The same harness boot as boot-options.mjs, minus the exception and minus the local WireMock:
//   extraTenants       tenant B, exactly as the base suite (TENANT_B_PLAN)
//   services           the ONE pinned Kafka broker (the outbox relay publishes to it); no WireMock - nothing
//                      listens behind a front-half target
//   appEnv             the NORMAL outbound allow-list (platform.config.destinations.allowed-hosts) with ONE exact
//                      host name, so the target system can be saved at all (an empty list refuses every
//                      destination - config-systems.yml:7-9). The name does not resolve to anything local and
//                      the default profile still refuses every loopback/private range.
//   sourceCredentials  as the base suite

import { BS_KAFKA } from './boot-options.mjs';
import { TENANT_B_PLAN } from './tenants.mjs';

/** The one exact host the front half's target systems are saved on: allow-listed, never resolved, never called. */
export const FRONT_TARGET_HOST = 'target-b.partner.test';
export const FRONT_TARGET_BASE = `https://${FRONT_TARGET_HOST}`;
const ALLOWED_HOSTS_VAR = 'PLATFORM_CONFIG_DESTINATIONS_ALLOWED_HOSTS';
const ALLOWED_SCHEMES_VAR = 'ROUTING_CORE_ALLOWED_SCHEMES';

export function frontSuiteBootOptions({ lockHolder } = {}) {
  if (typeof lockHolder !== 'string' || lockHolder.trim() === '') {
    throw new Error('base suite: name the lock holder - the docker-suite lock note says who holds it');
  }
  return {
    extraTenants: [TENANT_B_PLAN],
    services: { kafka: BS_KAFKA },
    // publish refuses an endpoint whose URI scheme is not approved (PipelineCompositionConfiguration.allowedSchemes: unset = none)
    appEnv: { [ALLOWED_HOSTS_VAR]: FRONT_TARGET_HOST, [ALLOWED_SCHEMES_VAR]: 'https' },
    sourceCredentials: true,
    lockHolder
  };
}
