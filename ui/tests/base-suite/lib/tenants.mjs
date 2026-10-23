// T130 (row 323, F21 BS-B): the base suite's two-tenant seed table - ONE data set for every scenario
// (plan release-suite-base-20261022.md section 2).
//
// Both tenants own the same flow shape: source "Customer A" contract v1.0 {customerId, orderId,
// amount, cardNumber}, target "Target B" contract v2.0 {clientId, referenceNo, invoiceValue, cardRef}, flow "orders" with
// one route - the T94 trial seed shape (ui/scripts/trial-up.mjs:17-21, :46-56). A tenant marker sits in
// every text value (A-CUST-0001 / B-CUST-0001, A-ORD-... / B-ORD-...), so a leak search is plain text.
//
// Logins: tenant A rides the harness's existing TENANT_ADMIN ("default", the author) and APPROVER
// ("approver", never the author) - boot-backend.mjs:357-365. Tenant B gets its OWN tenant admin and
// approver (TENANT_B_PLAN, bound by boot-backend's extraTenants): the harness's other-tenant login is a
// CONFIGURATOR that existing specs need refused the dashboard (queue-dashboard spec :133-140), so it is
// never repurposed. Each tenant's target answers on its own path of the one WireMock.

const SCHEMA = 'https://json-schema.org/draft/2020-12/schema';
const objectOf = (fields) => ({
  $schema: SCHEMA,
  type: 'object',
  required: Object.keys(fields),
  properties: Object.fromEntries(Object.entries(fields).map(([name, type]) => [name, { type }]))
});

export const SOURCE_CONTRACT = Object.freeze({
  system: 'customer-a',
  name: 'Customer A',
  role: 'SOURCE_REQUEST',
  versionLabel: '1.0',
  // cardNumber (T206): the field BS-11 declares MASKED. It is not an identity field: customerId is the one the platform
  // checks against the credential (SourceCustomerFilter.java:49-56), so a probe value there is refused 403 before intake.
  document: objectOf({ customerId: 'string', orderId: 'string', amount: 'number', cardNumber: 'string' })
});

export const TARGET_CONTRACT = Object.freeze({
  system: 'target-b',
  name: 'Target B',
  role: 'TARGET_REQUEST',
  versionLabel: '2.0',
  document: objectOf({ clientId: 'string', referenceNo: 'string', invoiceValue: 'number', cardRef: 'string' })
});

export const FLOW = Object.freeze({ code: 'orders', name: 'orders', businessKeyRule: 'orderId' });

/** The drawing on the designer (mapping-designer spec T92, :397-401): each source field to its target field. */
export const LINKS = Object.freeze([
  Object.freeze(['customerId', 'clientId']),
  Object.freeze(['orderId', 'referenceNo']),
  Object.freeze(['amount', 'invoiceValue']),
  Object.freeze(['cardNumber', 'cardRef'])
]);

/** BS-07's negative leg: a target on the UNNAMED loopback literal, on its own path - it must stay refused. */
export const LOOP = Object.freeze({ system: 'target-loop', name: 'Target loop', host: '127.0.0.1', path: '/tenant-a/loop' });

export const TENANTS = Object.freeze({
  A: Object.freeze({ key: 'A', admin: 'default', approver: 'approver', marker: 'A', customerId: 'A-CUST-0001', stubPath: '/tenant-a/orders' }),
  B: Object.freeze({ key: 'B', admin: 'bs-b-admin', approver: 'bs-b-approver', marker: 'B', customerId: 'B-CUST-0001', stubPath: '/tenant-b/orders' })
});

/** boot-backend's extraTenants entry for tenant B: a NEW tenant `<harness code>-bs-b` with its own logins. */
export const TENANT_B_PLAN = Object.freeze({
  key: 'bs-b',
  logins: Object.freeze({ 'bs-b-admin': 'TENANT_ADMIN', 'bs-b-approver': 'APPROVER' })
});

/** Record n of a tenant: its own customer, a marked order id, a distinct amount. */
export function orderRecord(tenant, n) {
  if (!Number.isInteger(n) || n < 1 || n > 9999) throw new Error(`orderRecord: n ${n} is outside 1..9999`);
  return { customerId: tenant.customerId, orderId: `${tenant.marker}-ORD-${String(n).padStart(4, '0')}`, amount: Number((100 + n + 0.25).toFixed(2)), cardNumber: cardNumberOf(tenant, n) };
}

/** A tenant-marked card number for record n (never a real card shape). */
export function cardNumberOf(tenant, n) {
  return `${tenant.marker}-CARD-${String(n).padStart(4, '0')}`;
}

/** The contract translation the target must receive for a record (Customer A v1.0 -> Target B v2.0). */
export function expectedTargetBody(record) {
  return { clientId: record.customerId, referenceNo: record.orderId, invoiceValue: record.amount, cardRef: record.cardNumber };
}

/** Tenant B's source posting its own order under tenant A's customer (backlog.md:3057-3062). */
export function forgedRecord(n) {
  if (!Number.isInteger(n) || n < 1 || n > 999) throw new Error(`forgedRecord: n ${n} is outside 1..999`);
  return { customerId: TENANTS.A.customerId, orderId: `B-ORD-F${String(n).padStart(3, '0')}`, amount: Number((900 + n + 0.75).toFixed(2)), cardNumber: `B-CARD-F${String(n).padStart(3, '0')}` };
}

const MARKER = /\b([AB])-(?:CUST|ORD|CARD)-[A-Z0-9]+\b/g;

/** The OTHER tenant's markers found in `text` - a leak search is a plain text search. */
export function leakHits(text, tenant) {
  const other = tenant.marker === 'A' ? 'B' : 'A';
  return [...String(text ?? '').matchAll(MARKER)].filter((match) => match[1] === other).map((match) => match[0]);
}
