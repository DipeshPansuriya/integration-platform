// T133 (row 323, F21 BS-11, karan-security): the masked-field probe - T-066 (backlog.md:3236-3252,
// E12-F1-S2). Tenant A declares the source field customerId MASKED; a record carries the probe value
// MASK-PROBE-7Q3Z in it; the probe must then be found 0 times in the app log, the detail (timeline) and
// failures API bodies and the screens, on the success path, the failure path and an intake refusal.
//
// Pure functions only (unit/mask-probe.test.mjs). The T108 / T112 names landed (feat/e9-e16 d5e2c6b)
// and are held HERE; the fail-closed rule stays - a later regressed TBD(<item>) value is never guessed,
// maskName throws naming the item and that test is red, never silent.
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
  // T108 (masking) landed - feat/e9-e16 d5e2c6b: the flow draft-save PUT carries `maskedFields`
  // (FlowAdminService.java:74,:111 -> JdbcFlowRepository.updateMaskedFields.java:215; the flows path is
  // EndpointMatrix.java:174), the marker is MaskedFields.MARKER; the tracking reads redact from the
  // interface_version snapshot (JdbcMessageTrackingStore.java:82) and every JSON log line scrubs the
  // registered values (MaskingLogCustomizer.java:20, application.yml logging.structured.json.customizer).
  maskDeclareApi: 'PUT /api/v1/admin/flows/{flowId}', // the draft-save that updates the interface's mask list
  // same shape the seed's flow body carries (lib/tenants.mjs SOURCE/TARGET/FLOW); {field} -> customerId
  maskDeclareBody: '{"businessKeyRule":"$.orderId","sourceSystem":"customer-a","destinations":["target-b"],"maskedFields":["{field}"]}',
  maskApiMarker: '[MASKED]', // MaskedFields.MARKER - what a masked value is replaced with
  // T112 (screens) landed showing NO payload field (MessageDetail.tsx:10, Failures.tsx:10): a masked
  // value can reach a screen only inside a redacted lastError text, rendered as a text child in the
  // failures row's cell - checked as the same [MASKED] marker.
  maskScreenLastCell: '[data-last-error]' // the failures row cell whose text carries the marker, not the value
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

/** T108's declare call: [method, path, body] with {field} filled - fails closed while TBD. */
export function declareCall() {
  const [method, path] = maskName('maskDeclareApi').split(' ');
  if (!method || !path) throw new Error('maskDeclareApi must be "<METHOD> <path>"');
  const body = JSON.parse(maskName('maskDeclareBody').split('{field}').join(MASKED_FIELD));
  return [method, path, body];
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
