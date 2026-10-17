// T131 (row 323, F21 BS-C, BS-09): the trace oracle - W3C traceparent, the app's JSON log lines, the
// Kafka records' headers.
//
// T107 criteria: one correlation id set at intake - taken from an inbound traceparent when present - on
// every log line and Kafka header for intake, route, transform and deliver; log lines structured JSON with
// tenant id, message id and correlation id. Records carry the outbox row's stored trace context as the one
// header "traceparent" (KafkaPublishPort.java:41-42 on main). BS-09 posts WITH a fresh traceparent, so
// the one id is known before the POST: its trace id. The JSON field names are T107's (pending-names.mjs).

export const TRACEPARENT = 'traceparent';
export const ACCEPTED_TOPIC = 'platform.message.accepted';

const hex = (bytes) => Buffer.from(bytes).toString('hex');
const TP = /^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$/;

/** A sampled W3C traceparent from 16 trace-id bytes and 8 parent-id bytes (the caller draws them). */
export function traceparentOf(traceBytes, parentBytes) {
  if (traceBytes?.length !== 16 || parentBytes?.length !== 8) throw new Error('traceparentOf: 16 bytes of trace id and 8 of parent id');
  const trace = hex(traceBytes);
  const parent = hex(parentBytes);
  if (/^0+$/.test(trace) || /^0+$/.test(parent)) throw new Error('traceparentOf: an all-zero id is invalid (W3C trace-context)');
  return `00-${trace}-${parent}-01`;
}

/** The trace id of a valid traceparent; null for anything else. */
export function traceIdOf(value) {
  const match = typeof value === 'string' ? value.match(TP) : null;
  if (!match || match[1] === 'ff' || /^0+$/.test(match[2]) || /^0+$/.test(match[3])) return null;
  return match[2];
}

/** A log or header correlation value as the one id: a full traceparent counts as its trace id. */
export function correlationOf(value) {
  if (value === undefined || value === null || value === '') return null;
  return traceIdOf(String(value)) ?? String(value);
}

/**
 * Problems of the app log written while one message went through; [] when every line is a JSON object,
 * every line naming the message carries its tenant, message and correlation ids, and every stage has one.
 */
export function logProblems(text, { messageId, tenantId, correlationId, fields, stages }) {
  const problems = [];
  const lines = String(text ?? '').split('\n').map((raw, i) => ({ n: i + 1, raw: raw.replace(/\r$/, '') })).filter((l) => l.raw.trim() !== '');
  const ofMessage = [];
  for (const line of lines) {
    let json = null;
    try {
      json = JSON.parse(line.raw);
    } catch {
      json = null;
    }
    if (!json || typeof json !== 'object' || Array.isArray(json)) {
      problems.push(`log line ${line.n} is not a JSON object: ${line.raw.slice(0, 160)}`);
      continue;
    }
    if (line.raw.includes(messageId)) ofMessage.push({ ...line, json });
  }
  if (ofMessage.length === 0) return [...problems, `no log line names message ${messageId}`];
  for (const { n, json } of ofMessage) {
    const wanted = [[fields.tenant, tenantId], [fields.message, messageId], [fields.correlation, correlationId]];
    for (const [field, value] of wanted) {
      const shown = field === fields.correlation ? correlationOf(json[field]) : json[field];
      if (shown !== value) problems.push(`log line ${n} of the message: ${field} ${json[field]}, not ${value}`);
    }
  }
  for (const stage of stages) {
    if (!ofMessage.some(({ json }) => json[fields.stage] === stage)) problems.push(`no log line of message ${messageId} at stage ${stage}`);
  }
  return problems;
}

/** kafka-console-consumer lines with print.headers=true and print.key=true: "<k:v,...|NO_HEADERS>\t<key>\t<value>". */
export function parseConsumerOutput(text) {
  return String(text ?? '').split('\n').map((l) => l.replace(/\r$/, '')).filter((l) => l.trim() !== '').map((line) => {
    const [head, key, ...rest] = line.split('\t');
    const headers = {};
    if (head !== 'NO_HEADERS') {
      for (const pair of head.split(',')) {
        const at = pair.indexOf(':');
        if (at > 0) headers[pair.slice(0, at)] = pair.slice(at + 1);
      }
    }
    return { headers, key: key ?? '', value: rest.join('\t') };
  });
}

/** Problems of one topic's records: every record naming the message carries traceparent with the one trace id. */
export function headerProblems(records, { messageId, correlationId, topic }) {
  const ofMessage = records.filter((r) => r.key.includes(messageId) || r.value.includes(messageId));
  if (ofMessage.length === 0) return [`${topic}: no record of message ${messageId}`];
  const problems = [];
  for (const record of ofMessage) {
    const value = record.headers[TRACEPARENT];
    if (value === undefined) problems.push(`${topic}: a record of ${messageId} has no ${TRACEPARENT} header`);
    else if (traceIdOf(value) !== correlationId) problems.push(`${topic}: ${TRACEPARENT} trace id ${traceIdOf(value) ?? value}, not ${correlationId}`);
  }
  return problems;
}
