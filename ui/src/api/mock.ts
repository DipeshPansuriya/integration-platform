// W5 (iPaaS Console plan): contract-shaped mocks for every resource, behind ONE switch -
// `VITE_API_MODE=mock` (default off), installed from main.tsx before React starts, so screens
// run with `npm run dev` + the switch and no backend. Nothing else changes. This module never
// reads import.meta.env itself (node --test loads it directly); the guard stays in main.tsx.
//
// E3-F3-S1: no mock may hide a mismatch with openapi.d.ts. Every real call already goes through
// the typed client (client.ts), whose paths are checked against the generated contract; the
// switch only swaps the transport at consoleFetch's single `fetch` call. Anything that is not a
// designed console path - the SSO entry point, /logout, anything unmapped - passes through to
// the real fetch, so a mismatch fails loudly instead of being papered over.
//
// Fixtures answer in the raw backend wire shapes (the same JSON the api/* modules already cast):
// flows list/detail raw, createFlow → {id}, contracts → {contracts: [...]}, activity →
// {items, page, size, hasMore}, errors `{error}` for designed refusal and `{refusal: <code>}`
// for named ones (ConfigApiException codes). CSRF is a no-op here - the mock never demands a
// token; writes simply mutate the in-memory store, so screen writes round-trip.

import type { FlowDetail, FlowSummary } from './flows';
import type { SystemView } from './systems';
import type { ContractVersion } from './contracts';
import type { Failure, Timeline, DeliveryTime } from './messages';
import type { QueueRow } from './queue';
import type { ActivityPage } from './activity';
import type { DailyCounts } from './reports';
import type { RuleSet } from '../rules/ruleSets';
import type { SavedMappingAnswer } from '../mapping/graph';

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const notFound = () => json({ error: 'not found' }, 404);

type FlowRow = FlowDetail & { status: FlowSummary['status'] };

const uuid = (n: number): string =>
  `${String(n).padStart(8, '0')}-1111-4111-8111-${String(n).padStart(12, '0')}`;

/** The in-memory store; tests reset it with `resetMockState()`. Raw wire shapes throughout. */
export const mockState = {
  flows: [
    { id: uuid(1), code: 'ORD-INTAKE', name: 'Order intake', status: 'PUBLISHED', ordering: 1, sourceSystem: 'web-shop', destinations: ['erp', 'warehouse'], businessKeyRule: 'orderNumber + @ + orderLine' },
    { id: uuid(2), code: 'INV-SYNC', name: 'Invoice sync', status: 'SUBMITTED', ordering: 2, sourceSystem: 'billing', destinations: ['ledger'], businessKeyRule: 'invoiceNumber' },
    { id: uuid(3), code: 'PAY-WEB', name: 'Payment webhook', status: 'DRAFT', ordering: 3, sourceSystem: 'web-shop', destinations: ['ledger'], businessKeyRule: 'paymentId' }
  ] as FlowRow[],
  systems: [
    { id: uuid(101), code: 'web-shop', name: 'Web shop', direction: 'INBOUND', type: 'REST_API', settings: { url: 'https://shop.example' } },
    { id: uuid(102), code: 'erp', name: 'ERP', direction: 'OUTBOUND', type: 'REST_API', settings: { url: 'https://erp.example' } },
    { id: uuid(103), code: 'ledger', name: 'Ledger service', direction: 'OUTBOUND', type: 'REST_API', settings: null }
  ] as SystemView[],
  contracts: [
    {
      id: uuid(201),
      system: 'web-shop',
      name: 'Order payload',
      role: 'SOURCE_REQUEST',
      versions: [
        { id: uuid(211), versionLabel: '1.0', status: 'APPROVED', authorId: 'flow-admin', createdAt: '2026-10-27T10:00:00Z', role: 'SOURCE_REQUEST', system: 'web-shop' },
        { id: uuid(212), versionLabel: '1.1', status: 'DRAFT', authorId: 'flow-admin', createdAt: '2026-10-28T09:00:00Z', role: 'SOURCE_REQUEST', system: 'web-shop' }
      ]
    }
  ] as { id: string; system: string; name: string; role: string; versions: ContractVersion[] }[],
  queue: [
    { flowCode: 'INV-SYNC', flowId: uuid(2), flowName: 'Invoice sync', day: '2026-10-27', queued: 4, processed: 4, converted: 3, failed: 1 },
    { flowCode: 'ORD-INTAKE', flowId: uuid(1), flowName: 'Order intake', day: '2026-10-27', queued: 7, processed: 7, converted: 7, failed: 0 },
    { flowCode: 'ORD-INTAKE', flowId: uuid(1), flowName: 'Order intake', day: '2026-10-28', queued: 12, processed: 10, converted: 9, failed: 1 }
  ] as QueueRow[],
  failures: [
    { trackingId: 'trk-0001', deliveryId: uuid(301), flowId: uuid(1), targetId: uuid(102), failingStage: 'DELIVER', errorClass: 'TRANSIENT', lastError: '502 bad gateway from erp endpoint', origin: 'erp', failedAt: '2026-10-28T08:12:00Z' },
    { trackingId: 'trk-0002', deliveryId: null, flowId: uuid(2), targetId: null, failingStage: 'PERSIST', errorClass: 'PERMANENT', lastError: 'business key could not be built', origin: 'billing', failedAt: '2026-10-28T07:31:00Z' }
  ] as Failure[],
  ruleSets: [
    { id: uuid(401), flowId: uuid(1), kind: 'DECISION_TABLE', name: 'routing', content: {} }
  ] as RuleSet[],
  mappings: {
    [uuid(1)]: {
      routeId: uuid(1), fieldMappings: [], compiledExpression: null,
      sourceContractVersionId: null, targetContractVersionId: null, mappingVersionId: null
    }
  } as Record<string, SavedMappingAnswer>,
  timeline: {
    trackingId: 'trk-0001', flowId: uuid(1), state: 'DELIVER_FAILED', acceptedAt: '2026-10-28T08:11:40Z',
    stages: [
      { stage: 'PERSIST', startedAt: '2026-10-28T08:11:41Z', finishedAt: '2026-10-28T08:11:42Z', durationMs: 1_000, sincePreviousMs: 1_000 },
      { stage: 'DELIVER', startedAt: '2026-10-28T08:11:42Z', finishedAt: '2026-10-28T08:12:00Z', durationMs: 18_200, sincePreviousMs: 18_200 }
    ],
    totalMs: 19_200, failingStage: 'DELIVER',
    deliveries: [{ deliveryId: uuid(301), targetId: uuid(102), durationMs: 18_200, outcome: null, state: 'RETRY_WAIT' } as DeliveryTime]
  } as Timeline,
  activityPage: {
    items: [
      { id: uuid(501), actor: 'flow-admin', action: 'FLOW_CREATED', targetIds: [uuid(1)], before: null, after: null, at: '2026-10-28T08:00:00Z' },
      { id: uuid(502), actor: null, action: 'FLOW_APPROVED', targetIds: [uuid(2)], before: null, after: null, at: '2026-10-28T08:05:00Z' }
    ],
    page: 0, size: 20, hasMore: false
  } as ActivityPage,
  report: [
    { interfaceId: uuid(1), targetId: uuid(102), successes: 9, failuresTransient: 1, failuresAmbiguous: 0, failuresPermanent: 0, retriesMade: 1, retriedThenSucceeded: 1, stillStuck: 0 }
  ] as DailyCounts[],
  routes: [uuid(1)] as string[]
};

export function resetMockState(): void {
  mockState.flows = [
    { id: uuid(1), code: 'ORD-INTAKE', name: 'Order intake', status: 'PUBLISHED', ordering: 1, sourceSystem: 'web-shop', destinations: ['erp', 'warehouse'], businessKeyRule: 'orderNumber + @ + orderLine' },
    { id: uuid(2), code: 'INV-SYNC', name: 'Invoice sync', status: 'SUBMITTED', ordering: 2, sourceSystem: 'billing', destinations: ['ledger'], businessKeyRule: 'invoiceNumber' },
    { id: uuid(3), code: 'PAY-WEB', name: 'Payment webhook', status: 'DRAFT', ordering: 3, sourceSystem: 'web-shop', destinations: ['ledger'], businessKeyRule: 'paymentId' }
  ];
  mockState.systems = [
    { id: uuid(101), code: 'web-shop', name: 'Web shop', direction: 'INBOUND', type: 'REST_API', settings: { url: 'https://shop.example' } },
    { id: uuid(102), code: 'erp', name: 'ERP', direction: 'OUTBOUND', type: 'REST_API', settings: { url: 'https://erp.example' } },
    { id: uuid(103), code: 'ledger', name: 'Ledger service', direction: 'OUTBOUND', type: 'REST_API', settings: null }
  ];
  mockState.contracts = [
    { id: uuid(201), system: 'web-shop', name: 'Order payload', role: 'SOURCE_REQUEST', versions: [
      { id: uuid(211), versionLabel: '1.0', status: 'APPROVED', authorId: 'flow-admin', createdAt: '2026-10-27T10:00:00Z', role: 'SOURCE_REQUEST', system: 'web-shop' },
      { id: uuid(212), versionLabel: '1.1', status: 'DRAFT', authorId: 'flow-admin', createdAt: '2026-10-28T09:00:00Z', role: 'SOURCE_REQUEST', system: 'web-shop' }
    ] }
  ];
  mockState.queue = [
    { flowCode: 'INV-SYNC', flowId: uuid(2), flowName: 'Invoice sync', day: '2026-10-27', queued: 4, processed: 4, converted: 3, failed: 1 },
    { flowCode: 'ORD-INTAKE', flowId: uuid(1), flowName: 'Order intake', day: '2026-10-27', queued: 7, processed: 7, converted: 7, failed: 0 },
    { flowCode: 'ORD-INTAKE', flowId: uuid(1), flowName: 'Order intake', day: '2026-10-28', queued: 12, processed: 10, converted: 9, failed: 1 }
  ];
  mockState.failures = [
    { trackingId: 'trk-0001', deliveryId: uuid(301), flowId: uuid(1), targetId: uuid(102), failingStage: 'DELIVER', errorClass: 'TRANSIENT', lastError: '502 bad gateway from erp endpoint', origin: 'erp', failedAt: '2026-10-28T08:12:00Z' },
    { trackingId: 'trk-0002', deliveryId: null, flowId: uuid(2), targetId: null, failingStage: 'PERSIST', errorClass: 'PERMANENT', lastError: 'business key could not be built', origin: 'billing', failedAt: '2026-10-28T07:31:00Z' }
  ];
  mockState.ruleSets = [{ id: uuid(401), flowId: uuid(1), kind: 'DECISION_TABLE', name: 'routing', content: {} }];
  mockState.mappings = {
    [uuid(1)]: {
      routeId: uuid(1), fieldMappings: [], compiledExpression: null,
      sourceContractVersionId: null, targetContractVersionId: null, mappingVersionId: null
    }
  };
  mockState.routes = [uuid(1)];
}

type Handler = (params: Record<string, string>, body: unknown, url: URL) => Response | Promise<Response>;

type Route = { method: string; pattern: RegExp; keys: string[]; handle: Handler };

function route(method: string, path: string, handle: Handler): Route {
  const keys: string[] = [];
  const source = path
    .split('/')
    .map((part) => (part.startsWith('{') ? (() => { keys.push(part.slice(1, -1)); return '([^/]+)'; })() : part))
    .join('/');
  return { method, pattern: new RegExp(`^${source}$`), keys, handle };
}

/** Operator actions answer the RAW OperatorResult wire body (operator.ts wraps it; no envelope). */
const operatorAnswer = (deliveryId: string, state: string): Response => {
  const row = mockState.failures.find((f) => f.deliveryId === deliveryId);
  if (!row) return json({ error: 'not found' }, 404);
  row.failingStage = 'RESOLVED';
  return json({ outcome: 'ACCEPTED', deliveryId, state });
};

const ROUTES: Route[] = [
  /* Session. */
  route('GET', '/api/v1/me', () => json(['flow-admin'])),

  /* Flows (W5: raw list, raw detail, create → {id}). */
  route('GET', '/api/v1/admin/flows', () => json(mockState.flows)),
  route('GET', '/api/v1/admin/flows/{flowId}', (p) => {
    const flow = mockState.flows.find((f) => f.id === p.flowId);
    return flow ? json(flow) : notFound();
  }),
  route('POST', '/api/v1/admin/flows', (_p, body) => {
    const b = (body ?? {}) as { sourceSystem?: string; name?: string };
    const id = uuid(Date.now() % 100_000);
    mockState.flows.push({ id, code: b.name ?? 'NEW-FLOW', name: b.name ?? 'New flow', status: 'DRAFT', ordering: mockState.flows.length + 1, sourceSystem: b.sourceSystem ?? '', destinations: [], businessKeyRule: '' });
    return json({ id });
  }),
  route('PUT', '/api/v1/admin/flows/{flowId}', (p, body) => {
    const b = (body ?? {}) as Partial<FlowDetail>;
    const flow = mockState.flows.find((f) => f.id === p.flowId);
    if (!flow) return notFound();
    Object.assign(flow, { sourceSystem: b.sourceSystem ?? flow.sourceSystem, destinations: b.destinations ?? flow.destinations, businessKeyRule: b.businessKeyRule ?? flow.businessKeyRule, name: b.name ?? flow.name, ordering: b.ordering ?? flow.ordering });
    return json(flow);
  }),
  route('POST', '/api/v1/admin/flows/{flowId}/submit', (p) => {
    const flow = mockState.flows.find((f) => f.id === p.flowId);
    if (!flow) return notFound();
    flow.status = 'SUBMITTED';
    return json(flow);
  }),
  route('POST', '/api/v1/admin/flows/{flowId}/approve', (p) => {
    const flow = mockState.flows.find((f) => f.id === p.flowId);
    if (!flow) return notFound();
    flow.status = 'APPROVED';
    return json(flow);
  }),
  route('POST', '/api/v1/admin/flows/{flowId}/publish', (p) => {
    const flow = mockState.flows.find((f) => f.id === p.flowId);
    if (!flow) return json({ error: 'only APPROVED flows publish (designed refusal)' }, 409);
    flow.status = 'PUBLISHED';
    return json(flow);
  }),

  /* Rule sets (T-121). */
  route('GET', '/api/v1/admin/flows/{flowId}/rule-sets', (p) =>
    json(mockState.ruleSets.filter((r) => r.flowId === p.flowId))),
  route('POST', '/api/v1/admin/flows/{flowId}/rule-sets', (p, body) => {
    const b = (body ?? {}) as { kind?: string; name?: string; content?: unknown };
    const bad = b.content === undefined || typeof b.content !== 'object';
    if (bad) return json({ error: 'RULE_TABLE_INVALID', cell: { row: 0, column: 0 }, reason: 'the table carries no cells' }, 400);
    const ruleSet: RuleSet = { id: uuid(Date.now() % 100_000), flowId: p.flowId, kind: b.kind ?? 'DECISION_TABLE', name: b.name ?? 'rules', content: b.content as never };
    mockState.ruleSets.push(ruleSet);
    return json(ruleSet);
  }),

  /* Contracts (T-160): the {contracts} envelope, import stores a version. */
  route('GET', '/api/v1/admin/contracts', () => json({ contracts: mockState.contracts })),
  route('GET', '/api/v1/admin/contracts/versions/{versionId}', (p) => {
    for (const contract of mockState.contracts) {
      const version = contract.versions.find((v) => v.id === p.versionId);
      if (version) return json(version);
    }
    return notFound();
  }),
  route('POST', '/api/v1/admin/contracts/versions/import', (_p, body) => {
    const b = (body ?? {}) as { system?: string; role?: string; versionLabel?: string; schemaName?: string; document?: string };
    let parsed: unknown;
    try { parsed = JSON.parse(b.document ?? ''); } catch { return json({ error: 'the document is not valid JSON' }, 400); }
    const contract = mockState.contracts.find((c) => c.system === b.system) ?? { id: uuid(Date.now() % 100_000), system: b.system ?? '', name: b.schemaName ?? 'Imported contract', role: b.role ?? 'SOURCE_REQUEST', versions: [] };
    const version: ContractVersion = { id: uuid(Date.now() % 100_000), versionLabel: b.versionLabel ?? '0.1', status: 'DRAFT', authorId: 'flow-admin', createdAt: new Date().toISOString(), role: b.role as never, system: b.system, sourceSchema: parsed } as unknown as ContractVersion;
    contract.versions.push(version);
    return json(version);
  }),
  route('POST', '/api/v1/admin/contracts/versions/{versionId}/submit', (p) => {
    for (const contract of mockState.contracts) {
      const version = contract.versions.find((v) => v.id === p.versionId);
      if (version) { version.status = 'PENDING_APPROVAL'; return json(version); }
    }
    return notFound();
  }),
  route('POST', '/api/v1/admin/contracts/versions/{versionId}/approve', (p) => {
    for (const contract of mockState.contracts) {
      const version = contract.versions.find((v) => v.id === p.versionId);
      if (version) {
        if (version.status !== 'PENDING_APPROVAL') return json({ refusal: 'NOT_PENDING_APPROVAL' }, 409);
        version.status = 'APPROVED';
        return json(version);
      }
    }
    return notFound();
  }),

  /* Systems (row 310 lanes B/C). */
  route('GET', '/api/v1/admin/systems', () => json(mockState.systems)),
  route('GET', '/api/v1/admin/systems/{systemId}', (p) => {
    const system = mockState.systems.find((s) => s.id === p.systemId);
    return system ? json(system) : notFound();
  }),
  route('POST', '/api/v1/admin/systems', (_p, body) => {
    const b = (body ?? {}) as { code?: string; name?: string; direction?: string; type?: string; settings?: unknown };
    const exists = mockState.systems.find((s) => s.code === b.code);
    if (exists) return json({ error: `a system named ${String(b.code)} is already registered` }, 409);
    const system: SystemView = { id: uuid(Date.now() % 100_000), code: b.code ?? '', name: b.name ?? b.code ?? '', direction: b.direction as SystemView['direction'] ?? 'INBOUND', type: b.type ?? null, settings: (b.settings ?? null) as SystemView['settings'] };
    mockState.systems.push(system);
    return json(system);
  }),
  route('PUT', '/api/v1/admin/systems/{systemId}', (p, body) => {
    const b = (body ?? {}) as Partial<SystemView> & { settings?: unknown };
    const system = mockState.systems.find((s) => s.id === p.systemId);
    if (!system) return notFound();
    Object.assign(system, { name: b.name ?? system.name, direction: b.direction ?? system.direction, type: b.type ?? system.type, settings: b.settings ?? system.settings });
    return json(system);
  }),
  route('POST', '/api/v1/admin/systems/{systemId}/test-connection', () =>
    json({ refusal: 'TEST_CONNECTION_RATE_LIMITED', error: 'the last attempt is still cooling down' }, 429)),

  /* Queue and failures (S4): both refuse 403 with their named code when ?denied=1 (Q4). */
  route('GET', '/api/v1/admin/queue/summary', (p, _b, url) => {
    if (url.searchParams.get('denied') === '1') return json({ refusal: 'NO_BOUND_USER' }, 403);
    let rows = mockState.queue;
    const flowId = p.flowId ?? url.searchParams.get('flowId');
    const day = url.searchParams.get('day') ?? url.searchParams.get('date');
    if (flowId) rows = rows.filter((r) => r.flowId === flowId);
    if (day) rows = rows.filter((r) => r.day === day);
    // The backend's order: flow code, then day.
    return json([...rows].sort((a, b) => (a.flowCode + a.day).localeCompare(b.flowCode + b.day)));
  }),
  route('GET', '/api/v1/admin/messages/failures', (p, _b, url) => {
    if (url.searchParams.get('denied') === '1') return json({ refusal: 'ROLE_NOT_GRANTED' }, 403);
    let rows = mockState.failures;
    const flowId = url.searchParams.get('flowId');
    const day = url.searchParams.get('day') ?? url.searchParams.get('date');
    if (flowId) rows = rows.filter((r) => r.flowId === flowId);
    if (day) rows = rows.filter((r) => (r.failedAt ?? '').startsWith(day));
    return json(rows);
  }),
  route('GET', '/api/v1/admin/messages/{trackingId}/timeline', (p) => {
    const failure = mockState.failures.find((f) => f.trackingId === p.trackingId);
    if (!failure || p.trackingId !== mockState.timeline.trackingId) return notFound();
    return json(mockState.timeline);
  }),
  route('POST', '/api/v1/admin/messages/deliveries/{deliveryId}/replay', (p) => operatorAnswer(p.deliveryId, 'PENDING')),
  route('POST', '/api/v1/admin/messages/deliveries/{deliveryId}/retry', (p) => operatorAnswer(p.deliveryId, 'RETRY_WAIT')),

  /* Activity and delivery report (lane A casts; these paths are also not in openapi.d.ts yet). */
  route('GET', '/api/v1/activity', (_p, _b, url) => {
    const page = Number(url.searchParams.get('page') ?? '0');
    const size = Number(url.searchParams.get('size') ?? '20');
    return json({ ...mockState.activityPage, page, size });
  }),
  route('GET', '/api/v1/reports/delivery', () => json(mockState.report)),

  /* Mapping (ADR-011 D4): a 404 is the same answer for unknown route and another tenant's. */
  route('GET', '/api/v1/admin/routes/{routeId}/mapping', (p) => {
    const saved = mockState.mappings[p.routeId];
    return saved ? json(saved) : notFound();
  })
];

const originalFetch = globalThis.fetch.bind(globalThis);

/** Swap globalThis.fetch for the contract-shaped mock. Console code goes through consoleFetch
    (client.ts), which calls exactly this fetch. Called once from main.tsx under the switch. */
export function installMockApi(): void {
  globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = new URL(input instanceof Request ? input.url : String(input), 'http://mock.local');
    const method = (init?.method ?? (input instanceof Request ? input.method : 'GET')).toUpperCase();
    let body: unknown;
    if (init?.body) {
      try { body = JSON.parse(String(init.body)); } catch { body = init.body; }
    } else if (input instanceof Request) {
      try { body = await input.clone().json(); } catch { /* not JSON - leave body unset */ }
    }
    for (const { method: m, pattern, keys, handle } of ROUTES) {
      if (m !== method) continue;
      const match = pattern.exec(url.pathname);
      if (!match) continue;
      const params: Record<string, string> = {};
      keys.forEach((key, i) => { params[key] = match[i + 1]; });
      return handle(params, body, url);
    }
    // Unhandled (auth entry, /logout, anything unknown): the real fetch - a mismatch fails loudly.
    return originalFetch(input, init);
  };
}