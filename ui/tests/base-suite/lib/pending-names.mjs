// T131 (row 323, F21 BS-C): the ONE registry of names BS-08 / BS-09 / BS-10 need that are NOT on main yet
// (re-resolved against main 596eecb and the seam branches, 18-Oct; T105 + T107 against main efa7d1d). One line per name, so the Tue 20 run
// swaps one line each. ON_MAIN below is the ONE flag per T-item a spec's test.fixme reads (waitsOn).
//
//   measured     read from a file on main (path:line beside it); ON_MAIN_PROBES below re-proves each landed item
//   TBD(<item>)  unknown until that item lands - nameOf() FAILS the step naming the item, never a guess
//                (the same fail-closed pattern as T130's TBD(row278), settings.mjs PLACEHOLDER)
//
// Measured on main and therefore NOT here: the stage set (timeline.mjs), the refusal names
// (failure-cause.mjs), the delivery / event / attempt registers (replay.mjs), the Kafka header name
// "traceparent" and the topic platform.message.accepted (trace.mjs), the dashboard selectors (BS-12).

export const NAMES = Object.freeze({
  // measured on main b6106af2 (T112 landed ee3aee93; follow-ups 38942ebc): routes App.tsx:30-32 + nav :80
  detailRoute: '#/messages/{trackingId}',
  failuresRoute: '#/failures',
  detailPage: '[data-page="message-detail"]', // + data-message-loaded (MessageDetail.tsx:120)
  detailTrackingId: '[data-tracking-id]', // MessageDetail.tsx:140
  detailFailingStage: '[data-failing-stage]', // MessageDetail.tsx:175 - the marked failing stage
  detailStageRow: '[data-stage-row]', // + data-stage-status, data-duration-ms (MessageDetail.tsx:196-198)
  failuresPage: '[data-page="failures"]', // Failures.tsx:119
  failureRow: '[data-failure-row="{trackingId}"]', // + data-failing-stage, data-origin (Failures.tsx:37)
  failureErrorClass: '[data-error-class]', // Failures.tsx:45
  failureLastError: '[data-last-error]', // Failures.tsx:46
  failureDetailLink: '[data-message-link="{trackingId}"]', // Failures.tsx:39 - carries ?failed=<STAGE>
  // RESOLVED - T110 on main 596eecb: MessageEndpointRoles.java:17 + MessageTrackingController.java:40/:64/:79
  // (platform-app/src/main/java/io/integrationplatform/app/message/)
  timelineApi: '/api/v1/admin/messages/{trackingId}/timeline',
  failuresApi: '/api/v1/admin/messages/failures',

  // RESOLVED - T111 (replay) on main aeddfdd9 (measured 22-Oct): DeliveryOperatorController.java:61 +
  // DeliveryOperatorEndpointRoles.java:16/:21; the Replay button is OperatorActionButton.tsx:34 (data-action = the
  // action kind) in the failures row's [data-action-cell] (Failures.tsx:51); the audit action is
  // AuditActions.OPERATOR_REPLAY (platform-domain .../audit/AuditActions.java:23).
  replayApi: 'POST /api/v1/admin/messages/deliveries/{deliveryId}/replay', // "<METHOD> <path with {deliveryId}>"
  replayAction: '[data-action="replay"]', // the Replay control on the failures row
  auditReplayAction: 'OPERATOR_REPLAY', // audit_event.action of one replay
  // The audit actor is the acting admin's USER ID (DeliveryOperatorController.java:63: actors.requireActor().userId()),
  // which only exists at run time: BS-10 reads it with direct-sql.mjs adminUserIdSql (so no name here).

  // T107 on main efa7d1d: MDC fields CorrelationContext.java:25-28, written as top-level JSON fields
  // (logback-spring.xml:3-5, StructuredLogEncoder logstash :10-11)
  logFieldTenant: 'tenantId',
  logFieldMessage: 'messageId',
  logFieldCorrelation: 'correlationId',
  // T107 has NO stage field (CorrelationContext.java:31 FIELDS on efa7d1d) - null, with its reason in ABSENT;
  // BS-09 skips the stage part of its log leg and proves the stages from the T105 timing rows instead
  logFieldStage: null,
  logStageValues: null, // would be a comma list, pipeline order: the stage field's value at intake, route, transform, deliver

  // T106 composition IS on main (OutboxRelayRunnerConfiguration.java:41); T130 boots the broker
  // (servicePlan kafka, lib/boot-options.mjs BS_KAFKA) and global-setup names its container
  kafkaContainerEnv: 'BS_KAFKA_CONTAINER', // the BS_* environment name holding the broker container's name
  kafkaConsumer: '/opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:19092', // argv prefix inside it
  // main efa7d1d publishes only platform.message.accepted + platform.delivery.due (OutboxTopic.java:10/:13);
  // delivery.due is keyed "delivery:<deliveryId>" with the stored traceparent (JdbcDeliveryRepository.java:471-474)
  // and its value names the delivery id, not the message id (KafkaPublishPort.java:114-120)
  kafkaStageTopics: 'platform.delivery.due' // comma list: the topics after platform.message.accepted that carry the message
});

// Names the landed item does NOT have: null in NAMES, the reason here. nameOf fails on them with the reason;
// pendingNames does not list them (nothing will land to fill them - a spec skips that leg, naming the reason).
const NO_STAGE_FIELD = 'no stage field - T107 MDC FIELDS are correlationId, tenantId, messageId, deliveryId '
  + '(platform-ingest .../port/CorrelationContext.java:31, main efa7d1d)';
export const ABSENT = Object.freeze({
  logFieldStage: NO_STAGE_FIELD,
  logStageValues: NO_STAGE_FIELD
});

// One flag per T-item: is its feature on main (measured efa7d1d, 18-Oct; T111/T112/T113 re-measured aeddfdd9, 22-Oct)? Flip ONE line when it lands;
// every spec guarded by waitsOn(item) then runs. Never flip for a lane or seam branch. ON_MAIN_PROBES (below) holds the
// file + needle that proves each flag; tests/base-suite/unit/on-main-probes.test.mjs fails when a flag and its probe
// disagree, in either direction.
export const ON_MAIN = Object.freeze({
  T105: true, // stage timings - main efa7d1d (landed d609f17)
  T106: true, // composition - OutboxRelayRunnerConfiguration.java:41
  T107: true, // correlation + JSON logs - main efa7d1d (landed 78792e4)
  T109: true, // audit writer - JdbcAuditEventStore.java:42
  T108: true, // masked fields - MaskedFields.java:33 + MessageTrackingService.java redact (merge 31e38382)
  T110: true, // tracking reads - MessageTrackingController.java:40
  T111: true, // replay - DeliveryOperatorController.java:61 (operator replay + retry, main aeddfdd9) + OperatorActionButton.tsx
  T112: true, // detail + failures screens - ui/src/pages/MessageDetail.tsx, Failures.tsx (main aeddfdd9)
  T113: true, // dashboard - ui/src/pages/QueueDashboard.tsx (main aeddfdd9)
  T138: true, // dev-only destination exception - SystemPersistenceConfiguration.java:57
  DELIVERY_BASE_URL: true // delivery takes the system's settings.baseUrl, not the placeholder endpoint - main 73257d41: SystemAdminService.java:93 (create) + :185-189 (save); the placeholder stays only as the null fallback (JdbcSystemRepository.java:87)
});

// One probe per T-item: every {file, needle} must hold in the working tree for the flag to be true (paths from the repo root).
const probe = (file, needle, { absent = false } = {}) => Object.freeze({ file, needle, absent });
export const ON_MAIN_PROBES = Object.freeze({
  T105: [probe('platform-app/src/main/java/io/integrationplatform/app/message/MessageTrackingService.java', 'message_stage_timing')],
  T106: [probe('platform-ingest/src/main/java/io/integrationplatform/ingest/internal/relay/OutboxRelayRunnerConfiguration.java', 'OutboxRelayRunner')],
  T107: [probe('platform-ingest/src/main/java/io/integrationplatform/ingest/port/CorrelationContext.java', 'correlationId')],
  T108: [
    probe('platform-config/src/main/java/io/integrationplatform/config/flow/api/MaskedFields.java', 'public static final String MARKER = "[MASKED]"'),
    probe('platform-app/src/main/java/io/integrationplatform/app/message/MessageTrackingService.java', 'MaskedFields.redact')
  ],
  T109: [probe('platform-app/src/main/java/io/integrationplatform/app/audit/JdbcAuditEventStore.java', 'audit_event')],
  T110: [probe('platform-app/src/main/java/io/integrationplatform/app/message/MessageTrackingController.java', '/{trackingId}/timeline')],
  T111: [
    probe('platform-app/src/main/java/io/integrationplatform/app/message/DeliveryOperatorController.java', '/{deliveryId}/replay'),
    probe('ui/src/pages/OperatorActionButton.tsx', 'data-action={action.kind}')
  ],
  T112: [probe('ui/src/pages/MessageDetail.tsx', 'data-page="message-detail"'), probe('ui/src/pages/Failures.tsx', 'data-page="failures"')],
  T113: [probe('ui/src/pages/QueueDashboard.tsx', 'data-page="queue-dashboard"')],
  // landed = the service hands the system's settings.baseUrl to the endpoint on create (:93) and on save (:187).
  // POSITIVE probes: the literal 'https://endpoint.invalid/' stays in JdbcSystemRepository.java:87 as the null fallback
  // (coalesce(:base_url::text, ...)), so "the placeholder is absent" can never turn true.
  DELIVERY_BASE_URL: [
    probe('platform-config/src/main/java/io/integrationplatform/config/system/application/SystemAdminService.java', 'systems.insertDefaultEndpoint(id, code, name, baseUrl)'),
    probe('platform-config/src/main/java/io/integrationplatform/config/system/application/SystemAdminService.java', 'systems.updateEndpointBaseUrl(current.id(), baseUrl)')
  ],
  T138: [probe('platform-config/src/main/java/io/integrationplatform/config/internal/persistence/SystemPersistenceConfiguration.java', 'allowed-hosts')]
});

/** null when every item is on main; else the test.fixme reason naming each missing item and the flag. */
export function waitsOn(...items) {
  return waitsOnFlags(ON_MAIN, ...items);
}

/** waitsOn over a given flag table (the unit test's seam: every real flag is true once its item has landed). */
export function waitsOnFlags(flags, ...items) {
  if (items.length === 0) throw new Error('waitsOn: name at least one item');
  for (const item of items) if (!Object.hasOwn(flags, item)) throw new Error(`waitsOn: unknown item ${item}`);
  const missing = items.filter((item) => !flags[item]);
  if (missing.length === 0) return null;
  return `waits on ${missing.join(', ')} (not on main) - flip ON_MAIN.${missing[0]} in tests/base-suite/lib/pending-names.mjs when it lands`;
}

/** True for a value that is still a placeholder ("TBD(" in any case) or empty. */
export function isPending(value) {
  return typeof value !== 'string' || value.trim() === '' || /tbd\(/i.test(value);
}

/** The value of one name; a TBD one FAILS the step with the item it waits for. */
export function nameOf(key) {
  if (!Object.hasOwn(NAMES, key)) throw new Error(`pending-names: unknown name ${key}`);
  const value = NAMES[key];
  if (value === null && Object.hasOwn(ABSENT, key)) throw new Error(`${key} is not on main: ${ABSENT[key]}`);
  if (isPending(value)) {
    throw new Error(`${key} is still ${value} - swap its one line in tests/base-suite/lib/pending-names.mjs when that item lands`);
  }
  return value;
}

/** Why a name is absent from main (null in NAMES), or null when the name is there or still pending. */
export function absentReason(key) {
  if (!Object.hasOwn(NAMES, key)) throw new Error(`pending-names: unknown name ${key}`);
  return NAMES[key] === null && Object.hasOwn(ABSENT, key) ? ABSENT[key] : null;
}

/** Every name still a placeholder: [[key, value], ...] - the handoff's placeholder list (absent names excluded). */
export function pendingNames() {
  return Object.entries(NAMES).filter(([key, value]) => isPending(value) && absentReason(key) === null);
}

/** A route or path template with each {param} replaced, URI-encoded. */
export function fill(template, params) {
  if (isPending(template)) throw new Error(`fill: ${template} is still TBD`);
  return template.replace(/\{([A-Za-z]+)\}/g, (_, name) => {
    if (params?.[name] === undefined || params[name] === null) throw new Error(`fill: ${template} is missing ${name}`);
    return encodeURIComponent(String(params[name]));
  });
}
