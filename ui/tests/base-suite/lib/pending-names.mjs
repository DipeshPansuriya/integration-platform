// T131 (row 323, F21 BS-C): the ONE registry of names BS-08 / BS-09 / BS-10 need that are NOT on main yet
// (main a4cfcca / lane base 4b3516b). One line per name, so the Tue 20 run swaps one line each.
//
//   provisional  measured on a lane, not on main - used as is; re-measure on the Tue 20 candidate sha
//   TBD(<item>)  unknown until that item lands - nameOf() FAILS the step naming the item, never a guess
//                (the same fail-closed pattern as T130's TBD(row278), settings.mjs PLACEHOLDER)
//
// Measured on main and therefore NOT here: the stage set (timeline.mjs), the refusal names
// (failure-cause.mjs), the delivery / event / attempt registers (replay.mjs), the Kafka header name
// "traceparent" and the topic platform.message.accepted (trace.mjs), the dashboard selectors (BS-12).

export const NAMES = Object.freeze({
  // provisional - lane t112 @21f6835 (vijay, T112; App.tsx claim arjun 13:24 IST 17-Oct, uncommitted screens)
  detailRoute: '#/messages/{trackingId}',
  failuresRoute: '#/failures',
  // provisional - T110 2a9c835 (MessageTrackingController, lane t109/t112 base 2344b8d)
  timelineApi: '/api/v1/admin/messages/{trackingId}/timeline',
  failuresApi: '/api/v1/admin/messages/failures',

  // T111 (replay) - not built: path, the button, the audit action it writes
  replayApi: 'TBD(T111)', // "<METHOD> <path with {deliveryId}>", e.g. the delivery replay endpoint
  replayAction: 'TBD(T111|T112)', // selector of the Replay control on the failures row (Failures.tsx:22 "T111 adds its replay action")
  auditReplayAction: 'TBD(T111)', // audit_event.action of one replay
  // T109 (audit writer) - what audit_event.actor holds for tenant A's admin login "default"
  auditActor: 'TBD(T109)',

  // T107 (correlation + JSON logs) - the JSON field names and the stage values in them
  logFieldTenant: 'TBD(T107)',
  logFieldMessage: 'TBD(T107)',
  logFieldCorrelation: 'TBD(T107)',
  logFieldStage: 'TBD(T107)',
  logStageValues: 'TBD(T107)', // comma list, pipeline order: the stage field's value at intake, route, transform, deliver

  // T106 (composition) - the base-suite harness boots NO Kafka today (servicePlan refuses one,
  // unit/boot-services.test.mjs:42); the broker the pipeline uses must be reachable from the specs
  kafkaContainerEnv: 'TBD(T106)', // the BS_* environment name holding the broker container's name
  kafkaConsumer: 'TBD(T106)', // console-consumer argv prefix inside that container, incl. --bootstrap-server
  kafkaStageTopics: 'TBD(T106|T107)' // comma list: the topics after platform.message.accepted that carry the message
});

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
