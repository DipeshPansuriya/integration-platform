// T133 (row 323, F21 BS-11, karan-security): the masked-field probe - T-066 (backlog.md:3236-3252,
// E12-F1-S2). Tenant A declares the source field customerId MASKED; a record carries the probe value
// MASK-PROBE-7Q3Z in it; the probe must then be found 0 times in the app log, the detail (timeline) and
// failures API bodies and the screens, on the success path, the failure path and an intake refusal.
//
// Pure functions only (unit/mask-probe.test.mjs). Same fail-closed rule as pending-names.mjs: a TBD(<item>) value
// is never guessed - the scenario that needs it is test.fixme naming the item.
//
// Where the probe may legitimately appear (NOT scanned): the target's request (WireMock journal) - the
// delivery must carry the real value; the platform's own payload store. Masking is about what the
// platform SHOWS (logs, API, screens), not what it delivers.

import { stubMapping } from './wiremock.mjs';

/** The probe: unique, upper-case, no tenant marker (lib/tenants.mjs MARKER never matches it). */
export const PROBE = 'MASK-PROBE-7Q3Z';

/** The field declared masked on tenant A's source contract (Customer A v1.0, lib/tenants.mjs:23-29). */
export const MASKED_FIELD = 'customerId';

export const MASK_NAMES = Object.freeze({
  // T108 is on main (ON_MAIN.T108, probe in pending-names.mjs). A field is declared masked by the flow's draft-save
  // carrying maskedFields (FlowAdminService.java:103/:111, FlowBundle.java:64-66), which takes effect once that
  // draft is submitted, approved and published (the message pins the interface version snapshot,
  // JdbcMessageTrackingStore.java:82). The T112 screens carry no payload field (MessageDetail.tsx:10,
  // Failures.tsx:10), so there is no screen mask name: the mask is checked at API level only (Dipesh, option 1).
  maskDeclareApi: 'PUT /api/v1/admin/flows/{flowId}', // FlowAdminController.java:112; then POST .../submit, .../approve (approver), .../publish (:143/:157/:171)
  maskDeclareBody: '{"maskedFields":["{field}"]}', // merged into the flow's own read (declareCall)
  maskApiMarker: '[MASKED]' // MaskedFields.java:33: what redact() puts in place of a masked value in an error text
});

/** True for a placeholder ("TBD(" in any case) or an empty value - the pending-names.mjs rule. */
export function isPendingName(value) {
  return typeof value !== 'string' || value.trim() === '' || /tbd\(/i.test(value);
}

/** The value of one mask name; a TBD one throws naming the item (never a guess). */
export function maskName(key) {
  if (!Object.hasOwn(MASK_NAMES, key)) throw new Error(`mask-probe: unknown name ${key}`);
  const value = MASK_NAMES[key];
  if (isPendingName(value)) throw new Error(`${key} is still ${value} - swap its one line in tests/base-suite/lib/mask-probe.mjs when that item lands`);
  return value;
}

/** The T-items the given names still wait for, e.g. ['T108', 'T112'] - the fixme reason; [] = ready. */
export function waitsFor(...keys) {
  const items = new Set();
  for (const key of keys) {
    if (!Object.hasOwn(MASK_NAMES, key)) throw new Error(`mask-probe: unknown name ${key}`);
    const value = MASK_NAMES[key];
    if (!isPendingName(value)) continue;
    const inside = /tbd\(([^)]*)\)/i.exec(value)?.[1] ?? 'unnamed';
    for (const item of inside.split('|')) items.add(item.trim());
  }
  return [...items].sort();
}

/** Tenant A's record n carrying the probe in the masked field; the other fields as orderRecord shapes them. */
export function probeRecord(n, { amount } = {}) {
  if (!Number.isInteger(n) || n < 1 || n > 9999) throw new Error(`probeRecord: n ${n} is outside 1..9999`);
  return { [MASKED_FIELD]: PROBE, orderId: `A-ORD-${String(n).padStart(4, '0')}`, amount: amount ?? Number((100 + n + 0.25).toFixed(2)) };
}

const b64 = (bytes) => Buffer.from(bytes).toString('base64');

/**
 * The forms a leaked probe takes in text: the probe itself (case-insensitive) and the base64 of it at
 * each of the three byte alignments - only the characters that depend on the probe's bytes alone, so a
 * base64-logged body that contains the probe anywhere is found. URL-encoding and JSON escaping leave
 * this probe unchanged (letters, digits and '-' only).
 */
export function probeForms(probe = PROBE) {
  const raw = Buffer.from(probe, 'utf8');
  const forms = [];
  for (const [pad, skip] of [[0, 0], [1, 2], [2, 3]]) {
    const total = pad + raw.length;
    const end = Math.floor(total / 3) * 4; // the last full group; a partial one depends on the next byte
    forms.push(b64(Buffer.concat([Buffer.alloc(pad), raw])).slice(skip, end));
  }
  return { plain: probe.toLowerCase(), base64: [...new Set(forms)] };
}

/** Every hit of the probe in `text`: [{ form, at }]. 0 hits is the only pass. */
export function probeHits(text, probe = PROBE) {
  const haystack = String(text ?? '');
  const { plain, base64 } = probeForms(probe);
  const hits = [];
  const lower = haystack.toLowerCase();
  for (let at = lower.indexOf(plain); at !== -1; at = lower.indexOf(plain, at + 1)) hits.push({ form: 'plain', at });
  for (const form of base64) {
    for (let at = haystack.indexOf(form); at !== -1; at = haystack.indexOf(form, at + 1)) hits.push({ form: 'base64', at });
  }
  return hits;
}

/**
 * The lines of a log slice that leak the probe - each cut to 160 characters AROUND the hit with the
 * probe itself replaced, so a failing report names the line without re-printing the value it guards.
 */
export function leakingLines(text, probe = PROBE) {
  return String(text ?? '').split(/\r?\n/)
    .map((line, index) => ({ line, index }))
    .filter(({ line }) => probeHits(line, probe).length > 0)
    .map(({ line, index }) => {
      const at = probeHits(line, probe)[0].at;
      const cut = line.slice(Math.max(0, at - 80), at + 80);
      return `line ${index + 1}: ${redact(cut, probe)}`;
    });
}

/** `text` with every plain form of the probe replaced by <PROBE> (base64 forms by <PROBE-B64>). */
export function redact(text, probe = PROBE) {
  let out = String(text ?? '').replace(new RegExp(probe.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'gi'), '<PROBE>');
  for (const form of probeForms(probe).base64) out = out.split(form).join('<PROBE-B64>');
  return out;
}

/**
 * What is wrong with one API body: the probe in any form, and - when `marker` is given - the masked
 * field not shown as that marker. `body` is the parsed JSON (scanned as its JSON text).
 */
export function bodyProblems(body, { marker } = {}) {
  const text = typeof body === 'string' ? body : JSON.stringify(body ?? null);
  const problems = probeHits(text).map((hit) => `probe (${hit.form}) at ${hit.at}: ${redact(text.slice(Math.max(0, hit.at - 60), hit.at + 60))}`);
  if (marker !== undefined && !text.includes(marker)) problems.push(`the masked ${MASKED_FIELD} is not shown as ${JSON.stringify(marker)}`);
  return problems;
}

/**
 * T108's declare call: [method, path, body] - the flow's draft-save with customerId in maskedFields. `current` is the
 * flow read (GET /api/v1/admin/flows/{flowId}); the save needs its own source, destinations and key rule back.
 */
export function declareCall(flowId, current) {
  if (typeof flowId !== 'string' || flowId === '') throw new Error('declareCall: a flowId is required');
  const [method, template] = maskName('maskDeclareApi').split(' ');
  if (!method || !template) throw new Error('maskDeclareApi must be "<METHOD> <path>"');
  for (const key of ['name', 'sourceSystem', 'destinations', 'businessKeyRule']) {
    if (current?.[key] === undefined || current[key] === null) throw new Error(`declareCall: the flow read has no ${key}`);
  }
  const { name, sourceSystem, destinations, businessKeyRule, ordering } = current;
  const declared = JSON.parse(maskName('maskDeclareBody').split('{field}').join(MASKED_FIELD));
  const body = { name, sourceSystem, destinations, businessKeyRule, ...(ordering === undefined ? {} : { ordering }), ...declared };
  return [method, template.replace('{flowId}', encodeURIComponent(flowId)), body];
}

/**
 * Tenant A's stub answering 403 and ECHOING the request body back - the failure path's harshest case:
 * if the platform keeps or logs the target's error text, the probe is in it, so T108's "including error
 * and diagnosis text" is tested. The same fixed id as stubMapping, so setStub(path, 200) restores it.
 * Needs WireMock's local response templating (on by default in 3.x when the stub names the transformer).
 */
export function echoStubMapping(path) {
  const base = stubMapping(path, 403);
  return {
    ...base,
    response: {
      status: 403,
      headers: { 'Content-Type': 'application/json' },
      body: `{"stub":${JSON.stringify(path)},"status":403,"echo":{{{request.body}}}}`,
      transformers: ['response-template']
    }
  };
}
