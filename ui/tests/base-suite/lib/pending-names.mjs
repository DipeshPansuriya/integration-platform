// T131 (row 323, F21 BS-C): the ONE registry of names BS-08 / BS-09 / BS-10 need that are NOT on main yet
// (re-resolved against main 596eecb and the seam branches, 18-Oct). One line per name, so the Tue 20 run
// swaps one line each. ON_MAIN below is the ONE flag per T-item a spec's test.fixme reads (waitsOn).
//
//   provisional  measured on a lane, not on main - used as is; re-measure on the Tue 20 candidate sha
//   TBD(<item>)  unknown until that item lands - nameOf() FAILS the step naming the item, never a guess
//                (the same fail-closed pattern as T130's TBD(row278), settings.mjs PLACEHOLDER)
//
// Measured on main and therefore NOT here: the stage set (timeline.mjs), the refusal names
// (failure-cause.mjs), the delivery / event / attempt registers (replay.mjs), the Kafka header name
// "traceparent" and the topic platform.message.accepted (trace.mjs), the dashboard selectors (BS-12).

export const NAMES = Object.freeze({
  // provisional - seam-row327-t112 0f87c60 (latest edfa28c), NOT on main 596eecb; routes App.tsx:30
  detailRoute: '#/messages/{trackingId}',
  failuresRoute: '#/failures',
  detailPage: '[data-page="message-detail"]', // + data-message-loaded (MessageDetail.tsx:93)
  detailTrackingId: '[data-tracking-id]', // MessageDetail.tsx:113
  detailFailingStage: '[data-failing-stage]', // MessageDetail.tsx:139 - the marked failing stage
  detailStageRow: '[data-stage-row]', // + data-stage-status, data-duration-ms (MessageDetail.tsx:160-162)
  failuresPage: '[data-page="failures"]', // Failures.tsx:116
  failureRow: '[data-failure-row="{trackingId}"]', // + data-failing-stage, data-origin (Failures.tsx:35)
  failureErrorClass: '[data-error-class]', // Failures.tsx:43
  failureLastError: '[data-last-error]', // Failures.tsx:44
  failureDetailLink: '[data-message-link="{trackingId}"]', // Failures.tsx:37 - carries ?failed=<STAGE>
  // RESOLVED - T110 on main 596eecb: MessageEndpointRoles.java:17 + MessageTrackingController.java:40/:64/:79
  // (platform-app/src/main/java/io/integrationplatform/app/message/)
  timelineApi: '/api/v1/admin/messages/{trackingId}/timeline',
  failuresApi: '/api/v1/admin/messages/failures',

  // T111 (replay) - not built (rohit lane t111 = 596eecb, no commits): path, the button, the audit action
  replayApi: 'TBD(T111)', // "<METHOD> <path with {deliveryId}>", e.g. the delivery replay endpoint
  replayAction: 'TBD(T111|T112)', // selector of the Replay control on the failures row (Failures.tsx:7 "replay not built")
  auditReplayAction: 'TBD(T111)', // audit_event.action of one replay
  // T109 on main: JdbcAuditEventStore.java:42 binds actor = event.who(); writers pass the user id uuid
  // (LookupEntryService.java:97) - the replay writer (T111) decides what it passes, so it waits on T111
  auditActor: 'TBD(T111)',

  // provisional - T107 seam-row278-t107 78792e4, NOT on main: MDC fields CorrelationContext.java:25-28
  logFieldTenant: 'tenantId',
  logFieldMessage: 'messageId',
  logFieldCorrelation: 'correlationId',
  // T107 has NO stage field (CorrelationContext.java:31 FIELDS; "message" is the log text,
  // StructuredJsonLogTest.java:54-57) - open decision, see the row 323 hand-back
  logFieldStage: 'TBD(T107)',
  logStageValues: 'TBD(T107)', // comma list, pipeline order: the stage field's value at intake, route, transform, deliver

  // T106 composition IS on main (OutboxRelayRunnerConfiguration.java:41); the gap is the base-suite
  // HARNESS: it boots NO Kafka (servicePlan refuses one, unit/boot-services.test.mjs:42) - item T130
  kafkaContainerEnv: 'TBD(T130)', // the BS_* environment name holding the broker container's name
  kafkaConsumer: 'TBD(T130)', // console-consumer argv prefix inside that container, incl. --bootstrap-server
  // main publishes only platform.message.accepted + platform.delivery.due (OutboxTopic.java:10/:13)
  kafkaStageTopics: 'TBD(T107)' // comma list: the topics after platform.message.accepted that carry the message
});

// One flag per T-item: is its feature on local main (measured 596eecb, 18-Oct)? Flip ONE line when it lands;
// every spec guarded by waitsOn(item) then runs. Never flip for a lane or seam branch.
export const ON_MAIN = Object.freeze({
  T105: false, // stage timings - seam-row278-t105 d609f17
  T106: true, // composition - OutboxRelayRunnerConfiguration.java:41
  T107: false, // correlation + JSON logs - seam-row278-t107 78792e4
  T109: true, // audit writer - JdbcAuditEventStore.java:42
  T110: true, // tracking reads - MessageTrackingController.java:40
  T111: false, // replay - not built
  T112: false, // detail + failures screens - seam-row327-t112 0f87c60
  T113: false, // dashboard - lane t70 only
  T138: true // dev-only destination exception - SystemPersistenceConfiguration.java:57
});

/** null when every item is on main; else the test.fixme reason naming each missing item and the flag. */
export function waitsOn(...items) {
  if (items.length === 0) throw new Error('waitsOn: name at least one item');
  for (const item of items) if (!Object.hasOwn(ON_MAIN, item)) throw new Error(`waitsOn: unknown item ${item}`);
  const missing = items.filter((item) => !ON_MAIN[item]);
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
  if (isPending(value)) {
    throw new Error(`${key} is still ${value} - swap its one line in tests/base-suite/lib/pending-names.mjs when that item lands`);
  }
  return value;
}

/** Every name still a placeholder: [[key, value], ...] - the handoff's placeholder list. */
export function pendingNames() {
  return Object.entries(NAMES).filter(([, value]) => isPending(value));
}

/** A route or path template with each {param} replaced, URI-encoded. */
export function fill(template, params) {
  if (isPending(template)) throw new Error(`fill: ${template} is still TBD`);
  return template.replace(/\{([A-Za-z]+)\}/g, (_, name) => {
    if (params?.[name] === undefined || params[name] === null) throw new Error(`fill: ${template} is missing ${name}`);
    return encodeURIComponent(String(params[name]));
  });
}
