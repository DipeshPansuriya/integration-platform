// T130 (row 323, F21 BS-B) step 5: the base suite's boot - the SAME harness the screens suite boots
// (scripts/boot-backend.mjs) plus four additive options, all of them its own:
//   extraTenants       tenant B (TENANT_B_PLAN): its own TENANT_ADMIN and APPROVER, so B publishes its own
//                      flow under the two-person rule; the harness's four logins are never repurposed
//   services           the ONE local WireMock, DECLARED here (the pinned image, a 200 stub per tenant path
//                      plus BS-07's unnamed-loopback leg) and ONE Kafka broker (the pinned image, main's
//                      three topics - BS-09 reads their headers), both started only by the boot, inside its lock
//   appEnv             the dev-only exception's profile and host (settings.mjs), ADDED through mergeAppEnv -
//                      their decided values arrive only through the environment; nothing here names a
//                      host or an app property
//   sourceCredentials  the realm's admin client secret by its NAME (PLATFORM_ADMIN_CLIENT_SECRET, a random
//                      value in memory only), so each tenant's source credential is issued through the API
// Not configured or misconfigured: requireException throws here - before any build, lock or container.

import { PG_IMAGE } from '../../../scripts/boot-backend.mjs';
import { DB_NAME, DB_SELF_TABLE, DB_TABLE } from './approved-tables.mjs';
import { SHOWCASE_PORTS_VAR, requireException } from './settings.mjs';
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

/** The ports the app itself listens on or refuses to reach: never the target database's (SystemPersistenceConfiguration). */
const PLATFORM_PORTS = Object.freeze([5432, 9092]);
const KEK_ID = 'T1';

/**
 * F3 (row 523, ADR-H-IPASS-03): the target database a DB table approval dials, as the app sees it. `port` is the one
 * loopback port the sidecar publishes on; `kek` a random 32-byte key (base64, memory only) so a target account's password
 * can be stored in the vault. The DB allow-list is EXACTLY that port, the showcase-local ports are the exception port plus
 * it (the host stays the one exception host), and `test` joins `showcase` because it alone unlocks sslmode=disable - its
 * only effect in main code (DbAdapterConfiguration.dbSender); the sidecar speaks no TLS.
 */
function targetDbOptions(exception, { port, kek } = {}) {
  if (!Number.isInteger(port) || port < 1 || port > 65535 || port === exception.port || PLATFORM_PORTS.includes(port)) {
    throw new Error(`base suite: targetDb port ${port} must be one TCP port, not the exception port or ${PLATFORM_PORTS.join('/')}`);
  }
  if (typeof kek !== 'string' || !/^[A-Za-z0-9+/]{43}=$/.test(kek) || Buffer.from(kek, 'base64').length !== 32) {
    throw new Error('base suite: targetDb KEK must be 32 bytes, base64 (a random value, memory only)');
  }
  return {
    service: { image: PG_IMAGE, hostPort: port, database: DB_NAME, tables: [DB_TABLE, DB_SELF_TABLE] },
    appEnv: {
      SPRING_PROFILES_ACTIVE: `${exception.profile},test`,
      [SHOWCASE_PORTS_VAR]: `${exception.port},${port}`,
      PLATFORM_CONFIG_DESTINATIONS_DB_ALLOWED_PORTS: String(port),
      PLATFORM_CONFIG_DESTINATIONS_DB_SSLMODE: 'disable',
      [`PLATFORM_CREDENTIAL_KEK_${KEK_ID}`]: kek,
      PLATFORM_CREDENTIAL_KEK_ACTIVE: KEK_ID
    }
  };
}

/** bootBackend's options for the base suite; throws the exception's reason when it is not configured. `targetDb` ({ port, kek }) adds the target database. */
export function baseSuiteBootOptions(settings, { lockHolder, targetDb } = {}) {
  const exception = requireException(settings);
  const target = targetDb === undefined ? null : targetDbOptions(exception, targetDb);
  if (typeof lockHolder !== 'string' || lockHolder.trim() === '') {
    throw new Error('base suite: name the lock holder - the docker-suite lock note says who holds it');
  }
  return {
    extraTenants: [TENANT_B_PLAN],
    services: { wiremock: { ...BS_WIREMOCK, hostPort: exception.port }, kafka: BS_KAFKA, ...(target ? { targetDb: target.service } : {}) },
    appEnv: { ...exception.appEnv, IPASS_DB_TARGET_ENABLED: 'true', ...target?.appEnv },   // the DB target adapter beans exist only with it (DbAdapterConfiguration.java:32)
    sourceCredentials: true,
    lockHolder
  };
}
