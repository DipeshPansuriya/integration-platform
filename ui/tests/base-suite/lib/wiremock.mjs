// T130 (row 323, F21 BS-B): pure helpers for the ONE local WireMock both tenants' targets answer on
// (plan section 2) - stub mappings and the request journal, as WireMock's admin API shapes them.
//
// One stub per path: /tenant-a/orders and /tenant-b/orders (one per tenant), /tenant-a/loop (BS-07's
// unnamed-loopback leg; nothing may ever reach it). Each stub has a fixed id, so BS-10's 403 and the
// restore to 200 replace the SAME stub in place (PUT /__admin/mappings/{id}). A delivery is the API
// adapter's send: POST carrying Idempotency-Key = the delivery id (ApiTargetCall.java:73-78 on main) -
// a test-connection probe or any stray request is never counted as one.

/** R05: tag AND digest, exactly the Java suites' pin (platform-routing ApiAdapterWireMock.java:39). */
export const WIREMOCK_IMAGE = 'wiremock/wiremock:3.13.1@sha256:d61e7720f89483fdef5366843b58d1dfd06bcce5828179c9f2f54de5c28354b0';

export const STUB_IDS = Object.freeze({
  '/tenant-a/orders': '6a1e0a01-3130-4b5e-9a01-000000000a01',
  '/tenant-b/orders': '6a1e0a01-3130-4b5e-9a01-000000000b01',
  '/tenant-a/loop': '6a1e0a01-3130-4b5e-9a01-000000000a02'
});

/** The stub of one path answering `status` - 200 (accepts) or 403 (PERMANENT, ApiTargetCallTest.java:155). */
export function stubMapping(path, status) {
  const id = STUB_IDS[path];
  if (!id) throw new Error(`unknown stub path ${path} - the suite has one stub per tenant path`);
  if (status !== 200 && status !== 403) throw new Error(`a stub answers 200 or 403 (plan section 2), not ${status}`);
  return {
    id,
    priority: 1,
    request: { method: 'POST', urlPath: path },
    response: { status, headers: { 'Content-Type': 'application/json' }, jsonBody: { stub: path, status } }
  };
}

/** One header value, case-insensitively; the first of a multi-value array; null when absent. */
export function header(headers, name) {
  if (!headers || typeof headers !== 'object') return null;
  const wanted = name.toLowerCase();
  for (const [key, value] of Object.entries(headers)) {
    if (key.toLowerCase() !== wanted) continue;
    const first = Array.isArray(value) ? value[0] : value;
    return first == null ? null : String(first);
  }
  return null;
}

const pathOf = (url) => (String(url).startsWith('/') ? String(url).split('?')[0] : new URL(String(url)).pathname);

function parsed(body) {
  try {
    return JSON.parse(body);
  } catch {
    return null;
  }
}

/** Every journal entry (GET /__admin/requests) on `path`, any method - "nothing sent" means this is []. */
export function requestsTo(journal, path) {
  return (journal?.requests ?? []).filter((entry) => entry?.request?.url !== undefined && pathOf(entry.request.url) === path);
}

/** The adapter's deliveries on `path`: { deliveryId, body (parsed JSON or null), text, loggedDate }, journal order. */
export function deliveriesTo(journal, path) {
  return requestsTo(journal, path)
    .filter(({ request }) => request.method === 'POST' && header(request.headers, 'Idempotency-Key') !== null)
    .map(({ request }) => ({
      deliveryId: header(request.headers, 'Idempotency-Key'),
      body: parsed(request.body),
      text: request.body ?? '',
      loggedDate: request.loggedDate ?? null
    }));
}
