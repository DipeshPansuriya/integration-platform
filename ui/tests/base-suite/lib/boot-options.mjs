// T130 (row 323, F21 BS-B) step 5: the base suite's boot - the SAME harness the screens suite boots
// (scripts/boot-backend.mjs) plus four additive options, all of them its own:
//   extraTenants       tenant B (TENANT_B_PLAN): its own TENANT_ADMIN and APPROVER, so B publishes its own
//                      flow under the two-person rule; the harness's four logins are never repurposed
//   services           the ONE local WireMock, DECLARED here (the pinned image, a 200 stub per tenant path
//                      plus BS-07's unnamed-loopback leg) and ONE Kafka broker (the pinned image, main's
//                      three topics - BS-09 reads their headers), both started only by the boot, inside its lock
//   appEnv             the dev-only exception's profile and host (settings.mjs), ADDED through mergeAppEnv -
//                      their real names are TBD(row278) (row 278's build) and arrive only through the
//                      environment; nothing here names a profile, a host or an app property
//   sourceCredentials  the realm's admin client secret by its NAME (PLATFORM_ADMIN_CLIENT_SECRET, a random
//                      value in memory only), so each tenant's source credential is issued through the API
// Not configured, or still TBD(row278): requireException throws here - before any build, lock or container.

import { SCREEN_ALLOWED_HOSTS } from '../../screens/screen-hosts.mjs';
import { ALLOWED_HOSTS_VAR, requireException } from './settings.mjs';
import { TENANTS, TENANT_B_PLAN } from './tenants.mjs';
import { STUB_IDS, WIREMOCK_IMAGE, stubMapping } from './wiremock.mjs';

/** The one local WireMock both tenants' targets answer on: declared here, started by bootBackend only. */
export const BS_WIREMOCK = Object.freeze({
  image: WIREMOCK_IMAGE,
  mappings: Object.freeze(Object.keys(STUB_IDS).map((stubPath) => Object.freeze(stubMapping(stubPath, 200))))
});

/** The broker image, pinned by tag AND digest (R05). */
export const KAFKA_IMAGE = 'apache/kafka:4.3.1@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837';

/** The one local Kafka broker: declared here, started by bootBackend only. Topics: main's OutboxTopic plus the poison topic. */
export const BS_KAFKA = Object.freeze({
  image: KAFKA_IMAGE,
  topics: Object.freeze(['platform.message.accepted', 'platform.delivery.due', 'platform.poison'])
});

/** The harness logins the base suite drives (console-config.json; names only - the sessions stay in the boot). */
export const BS_CONSOLE_LOGINS = Object.freeze([TENANTS.A.approver, ...Object.keys(TENANT_B_PLAN.logins)]);

/** bootBackend's options for the base suite; throws the exception's reason when it is not configured. */
export function baseSuiteBootOptions(settings, { lockHolder } = {}) {
  const exception = requireException(settings);
  if (typeof lockHolder !== 'string' || lockHolder.trim() === '') {
    throw new Error('base suite: name the lock holder - the docker-suite lock note says who holds it');
  }
  return {
    extraTenants: [TENANT_B_PLAN],
    services: { wiremock: { ...BS_WIREMOCK, hostPort: exception.port }, kafka: BS_KAFKA },
    // T97's save-time check refuses a saved system whose host is not on the allow-list, and the seed's
    // source system carries a fake *.partner.test fixture name (never called) - the screens boot passes
    // the same list; the exception host stays first in the entry-validation order it was composed in.
    appEnv: {
      ...exception.appEnv,
      [ALLOWED_HOSTS_VAR]: [exception.host, ...SCREEN_ALLOWED_HOSTS].join(','),
      // the Kafka boot composes the routing factory (ConditionalOnProperty), so the publish time scheme
      // guard runs with the operator's list - unset it approves NOTHING (E11's DipeshExample test sets the same)
      ROUTING_CORE_ALLOWED_SCHEMES: 'http,https'
    },
    sourceCredentials: true,
    lockHolder
  };
}
