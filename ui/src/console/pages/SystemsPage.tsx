// Console container for the Systems screen: wires screens/systems (SystemsScreen + SystemDetailDrawer) to the real
// systems API and keeps every behaviour of pages/Systems.tsx. Nothing here is sample data: health, latency and last
// check come only from connection tests run in this session; the 24h KPIs have no backend source and read '—'.
// Address (unchanged from the old page): #/systems/<id> opens the edit drawer (the old EditSystemForm with
// EndpointAuthForm, Kafka/type settings, a connection test and Go live). The card name opens the design's detail
// drawer as local state, without touching the address. Each card keeps the old row hooks (data-system-row, Open,
// Test connection and its result). Register uses the old RegisterSystemForm: the design's register drawer collects
// fields (protocol, health check, tags) the API does not store.

import { useCallback, useEffect, useRef, useState } from 'react';
import { useSystems } from '../../api/hooks';
import { listFlows, readFlow } from '../../api/flows';
import {
  AUTH_METHOD_LABELS,
  readEndpointAuth,
  readMyRoles,
  readSystem,
  testConnection,
  TYPE_LABELS,
  type AuthMethod,
  type SystemType,
  type SystemView
} from '../../api/systems';
import { SystemsScreen } from '../../screens/systems/SystemsScreen';
import { SystemDetailDrawer } from '../../screens/systems/SystemDetailDrawer';
import type { SystemDetailRow, SystemEvent, SystemKpis, SystemRow, SystemStatus } from '../../screens/systems/types';
import { Drawer } from '../../ui/Drawer';
import { refusalWords } from '../../ui/refusal-words';
import { EditSystemForm } from '../../pages/systems/EditSystemForm';
import { RegisterSystemForm } from '../../pages/systems/RegisterSystemForm';
import { GoLiveApprovedTables } from '../../pages/systems/GoLiveApprovedTables';
import { GoLiveSourceCredentials } from '../../pages/systems/GoLiveSourceCredentials';
import { canStartTest, filterSystems, TESTING_WORD, testCell, type TestOutcome } from '../../pages/systems/systems-view';
import '../../pages/systems/systems.css';

const LIST_HASH = '#/systems';
/** Design spec §5: Slow = latency above the system's threshold (default 200ms). */
const SLOW_MS = 200;
const MAX_PROBES = 10;
/** No backend source for the 24h performance band: an empty marker, never a number. */
const NO_KPIS: SystemKpis = { avgLatency: '—', uptime: '—', requests: '—', errors: '—' };

/** One connection test run in this session (the only health signal the API gives). */
type Probe = { at: Date; outcome: TestOutcome; responseTimeMs: number | null };
type UsedBy = { loading: boolean; tags: string[] };

/** The id in #/systems/<id>, or null for the list alone. */
function openIdFromHash(): string | null {
  const match = /^#\/?systems\/([^/?#]+)/.exec(window.location.hash);
  return match ? decodeURIComponent(match[1]) : null;
}

const systemHash = (id: string) => `#/systems/${encodeURIComponent(id)}`;
const hhmmss = (at: Date) => at.toLocaleTimeString('en-GB', { hour12: false });
const typeWord = (system: SystemView) => (system.type ? (TYPE_LABELS[system.type as SystemType] ?? system.type) : 'Untyped');
const directionWord = (system: SystemView) => (system.direction === 'INBOUND' ? 'Inbound' : 'Outbound');
const isOutboundApi = (system: SystemView) => system.direction === 'OUTBOUND' && system.type === 'API';

function statusOf(probe: Probe | undefined): SystemStatus {
  if (!probe || probe.outcome.kind !== 'answer') return 'Unknown';
  const result = probe.outcome.result.toUpperCase();
  if (result === 'SUCCESS' || result === 'OK') return probe.responseTimeMs != null && probe.responseTimeMs > SLOW_MS ? 'Slow' : 'Healthy';
  if (result === 'FAILED' || result === 'FAILURE' || result === 'REFUSED') return 'Down';
  return 'Unknown';
}

function toRow(system: SystemView, last: Probe | undefined): SystemRow {
  return {
    id: system.id,
    name: system.name,
    code: system.code,
    status: statusOf(last),
    protocol: `${system.code} · ${typeWord(system)} · ${directionWord(system)}`,
    latencyMs: last?.responseTimeMs ?? undefined,
    lastCheck: last ? hhmmss(last.at) : undefined
  };
}

function toDetail(system: SystemView, last: Probe | undefined, auth: string | undefined): SystemDetailRow {
  const settings = system.settings ?? {};
  const baseUrl = typeof settings.baseUrl === 'string' ? settings.baseUrl : undefined;
  const timeout = Number(settings.timeoutMs);
  return {
    ...toRow(system, last),
    protocol: typeWord(system),
    auth,
    baseUrl,
    timeoutMs: settings.timeoutMs != null && Number.isFinite(timeout) ? timeout : undefined,
    direction: directionWord(system)
  };
}

function toEvent(probe: Probe): SystemEvent {
  const cell = testCell(probe.outcome);
  const tone = cell.tone === 'success' ? 'ok' : cell.tone === 'danger' ? 'err' : 'warn';
  const took = probe.responseTimeMs != null ? ` in ${probe.responseTimeMs}ms` : '';
  return { time: hhmmss(probe.at), text: `Connection test: ${cell.word}${took}${cell.detail ? ` — ${cell.detail}` : ''}`, tone };
}

/** The old row cell's markup and data-* hooks (card and edit drawer connection test). */
function TestResult({ code, pending, probe }: { code: string; pending: boolean; probe: Probe | undefined }) {
  const outcome = probe?.outcome;
  const cell = outcome ? testCell(outcome) : null;
  return (
    <span className="systems-test">
      {pending && (
        <span role="status" data-test-connection-pending>
          <span className="systems-sr-only">Connection test for {code}: </span>
          {TESTING_WORD}
        </span>
      )}
      {!pending && outcome?.kind === 'answer' && cell && (
        <span role="status" data-test-connection-result={outcome.result}>
          <span className="systems-sr-only">Connection test for {code}: </span>
          <span className={`badge badge-${cell.tone}`}>{cell.word}</span>
          {cell.detail && <span className="systems-test-detail" title={cell.detail}>{cell.detail}</span>}
        </span>
      )}
      {!pending && outcome?.kind === 'refusal' && cell && (
        <span role="alert" data-refusal={`test-connection-refused-${outcome.status}`}>
          <span className="systems-sr-only">The API refused the connection test for {code} (HTTP {outcome.status}): </span>
          <span className={`badge badge-${cell.tone}`}>{cell.word}</span>
          {cell.detail && <span className="systems-test-detail" title={cell.detail}>{cell.detail}</span>}
        </span>
      )}
    </span>
  );
}

export function SystemsPage() {
  const systems = useSystems();
  const [filter, setFilter] = useState('');
  const [registering, setRegistering] = useState(false);
  const [openId, setOpenId] = useState<string | null>(openIdFromHash());
  const [detailId, setDetailId] = useState<string | null>(null);
  const [open, setOpen] = useState<SystemView | null>(null);
  const [openRefusal, setOpenRefusal] = useState<string | null>(null);
  const [roles, setRoles] = useState<string[] | null>(null);
  const [auth, setAuth] = useState<string | undefined>(undefined);
  const [usedBy, setUsedBy] = useState<UsedBy>({ loading: false, tags: [] });
  const [probes, setProbes] = useState<Record<string, Probe[]>>({});

  // B29 C1 (kept from the old page): a running test blocks a second click for the same system (parallel clicks hit the 429).
  const [testing, setTesting] = useState<ReadonlySet<string>>(new Set());
  const testingRef = useRef<ReadonlySet<string>>(testing);
  const runTest = useCallback(async (systemId: string) => {
    if (!canStartTest(testingRef.current, systemId)) return;
    testingRef.current = new Set(testingRef.current).add(systemId);
    setTesting(testingRef.current);
    try {
      const result = await testConnection(systemId);
      const probe: Probe = result.ok
        ? { at: new Date(), outcome: { kind: 'answer', result: result.data.result, message: result.data.message }, responseTimeMs: result.data.responseTimeMs }
        : { at: new Date(), outcome: { kind: 'refusal', status: result.status, reason: result.refusal ?? result.error ?? '' }, responseTimeMs: null };
      setProbes((current) => ({ ...current, [systemId]: [probe, ...(current[systemId] ?? [])].slice(0, MAX_PROBES) }));
    } finally {
      const next = new Set(testingRef.current);
      next.delete(systemId);
      testingRef.current = next;
      setTesting(next);
    }
  }, []);

  useEffect(() => {
    const onHashChange = () => setOpenId(openIdFromHash());
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  useEffect(() => {
    setOpen(null);
    setOpenRefusal(null);
    if (!openId) return;
    let current = true;
    void readSystem(openId).then((result) => {
      if (!current) return;
      if (result.ok) setOpen(result.data);
      else setOpenRefusal(result.status === 404 ? 'system-not-found' : `open-refused-${result.status}`);
    });
    return () => {
      current = false;
    };
  }, [openId]);

  useEffect(() => {
    let current = true;
    void readMyRoles().then((r) => {
      if (current) setRoles(r);
    });
    return () => {
      current = false;
    };
  }, [openId]);

  // Overview "Auth": the stored endpoint authentication of an outbound API system (GET .../endpoint-auth); other types have none.
  const list = systems.data ?? [];
  const detailSystem = detailId ? list.find((system) => system.id === detailId) ?? null : null;
  const openAuthKey = detailSystem && isOutboundApi(detailSystem) ? detailSystem.id : null;
  useEffect(() => {
    setAuth(undefined);
    if (!openAuthKey) return;
    let current = true;
    void readEndpointAuth(openAuthKey).then((result) => {
      if (current && result.ok) setAuth(AUTH_METHOD_LABELS[result.data.authMethod as AuthMethod] ?? result.data.authMethod);
    });
    return () => {
      current = false;
    };
  }, [openAuthKey]);

  // "Used by Flows": flows whose source system or a destination names this system's code (each flow's detail read).
  const openCode = detailSystem?.code ?? null;
  useEffect(() => {
    setUsedBy({ loading: Boolean(openCode), tags: [] });
    if (!openCode) return;
    let current = true;
    void (async () => {
      const flows = await listFlows();
      if (!flows.ok) {
        if (current) setUsedBy({ loading: false, tags: [`Flows could not be read (HTTP ${flows.status})`] });
        return;
      }
      const details = await Promise.all(flows.data.map((flow) => readFlow(flow.id)));
      const tags = details.flatMap((detail) =>
        detail.ok && (detail.data.sourceSystem === openCode || (detail.data.destinations ?? []).includes(openCode))
          ? [detail.data.code || detail.data.name]
          : []
      );
      const unread = details.filter((detail) => !detail.ok).length;
      if (current) setUsedBy({ loading: false, tags: unread ? [...tags, `${unread} flow(s) could not be read`] : tags });
    })();
    return () => {
      current = false;
    };
  }, [openCode]);

  const onSaved = async () => {
    systems.reload();
    if (!openId) return;
    const result = await readSystem(openId); // refresh without clearing, so the edit form keeps its "System saved."
    if (result.ok) setOpen(result.data);
  };

  const shown = filterSystems(list, filter);
  const byId = new Map(list.map((system) => [system.id, system]));
  const lastOf = (id: string) => probes[id]?.[0];
  const listStatus = systems.status;

  const detailOpen = Boolean(detailSystem && !openId);
  const closeEdit = () => {
    window.location.hash = LIST_HASH;
  };
  const editTitle = open
    ? `Edit system ${open.code} (${open.direction === 'INBOUND' ? 'inbound' : 'outbound'})`
    : openRefusal === 'system-not-found'
      ? 'System not found'
      : 'Open system';
  // roles === null means "unknown" (older server, failed call, empty list): fail open here; the server's 403 stays the gate.
  const canIssue = roles === null || roles.includes('TENANT_ADMIN');
  const canApprove = canIssue || roles?.includes('APPROVER') === true;

  const events: SystemEvent[] = detailId
    ? [
        ...(testing.has(detailId) ? [{ time: hhmmss(new Date()), text: TESTING_WORD, tone: 'info' as const }] : []),
        ...(probes[detailId] ?? []).map(toEvent)
      ]
    : [];

  /** The old row's buttons and result cell, on each card (same data-* hooks and accessible names). */
  const cardExtra = (row: SystemRow) => (
    <div className="systems-actions">
      <button
        type="button"
        className="btn"
        data-action="open-system"
        aria-label={`Open ${row.code}`}
        onClick={() => (window.location.hash = systemHash(row.id))}
      >
        Open
      </button>{' '}
      <button
        type="button"
        className="btn"
        data-action="test-connection"
        aria-disabled={testing.has(row.id) ? 'true' : undefined}
        aria-label={`Test connection for ${row.code}`}
        onClick={() => void runTest(row.id)}
      >
        Test connection
      </button>{' '}
      <TestResult code={row.code} pending={testing.has(row.id)} probe={lastOf(row.id)} />
    </div>
  );

  const toolbar =
    list.length > 0 ? (
      <div className="systems-filter">
        <label>
          <span className="systems-sr-only">Filter systems</span>
          <input
            type="search"
            data-field="systems-filter"
            placeholder="Filter by code, name or type"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
          />
        </label>
        <span className="systems-muted">
          {shown.length} of {list.length}
        </span>
      </div>
    ) : null;

  return (
    <>
      <SystemsScreen
        pageProps={{
          'data-systems-loaded': String(!systems.loading),
          'data-refusal': listStatus !== null ? `list-refused-${listStatus}` : undefined
        }}
        registerAction="open-register"
        emptyAction={list.length === 0 ? 'register-from-empty' : undefined}
        cardProps={(row) => ({ 'data-system-row': row.code, 'data-system-type': byId.get(row.id)?.type ?? '' })}
        renderCardExtra={cardExtra}
        systems={systems.data ? shown.map((system) => toRow(system, lastOf(system.id))) : null}
        loading={systems.loading}
        error={listStatus !== null ? refusalWords(listStatus, 'the systems list') : ''}
        stale={systems.stale}
        onRetry={systems.reload}
        onOpenSystem={(row) => setDetailId(row.id)}
        onRegisterSystem={() => setRegistering(true)}
        toolbar={toolbar}
        total={list.length}
        emptyText={list.length > 0 && shown.length === 0 ? 'No system matches the filter.' : undefined}
      />

      {detailOpen && detailSystem && (
        <SystemDetailDrawer
          open
          system={toDetail(detailSystem, lastOf(detailSystem.id), auth)}
          kpis={NO_KPIS}
          usedBy={usedBy.tags}
          usedByLoading={usedBy.loading}
          events={events}
          onClose={() => setDetailId(null)}
          onTestConnection={(system) => void runTest(system.id)}
          onEdit={(system) => {
            setDetailId(null);
            window.location.hash = systemHash(system.id);
          }}
        />
      )}

      <Drawer open={openId !== null} title={editTitle} onClose={closeEdit} id="systems">
        {openRefusal === 'system-not-found' && (
          <p role="alert" className="systems-alert systems-alert-danger" data-refusal="system-not-found">
            This system does not exist for your tenant.
          </p>
        )}
        {openRefusal !== null && openRefusal !== 'system-not-found' && (
          <p role="alert" className="systems-alert systems-alert-danger" data-refusal={openRefusal}>
            {refusalWords(Number(/(\d{3})$/.exec(openRefusal)?.[1] ?? 500), 'this system')}
          </p>
        )}
        {!open && openRefusal === null && <p className="systems-muted">Loading the system...</p>}
        {open && <EditSystemForm key={open.id} system={open} onSaved={onSaved} />}
        {open && (
          <section className="systems-connection-test" data-connection-test data-system-code={open.code}>
            <h2>Connection test</h2>
            <button
              type="button"
              className="btn"
              data-action="test-connection"
              aria-disabled={testing.has(open.id) ? 'true' : undefined}
              aria-label={`Test connection for ${open.code}`}
              onClick={() => void runTest(open.id)}
            >
              Test connection
            </button>{' '}
            <TestResult code={open.code} pending={testing.has(open.id)} probe={lastOf(open.id)} />
          </section>
        )}
        {open && (
          <section className="systems-go-live" data-go-live data-system-code={open.code}>
            <h2>Go live</h2>
            <GoLiveSourceCredentials systemId={open.id} systemName={open.code || open.name} canIssue={canIssue} />
            <GoLiveApprovedTables systemId={open.id} systemName={open.code || open.name} canApprove={canApprove} />
          </section>
        )}
      </Drawer>

      <Drawer open={registering} title="Register a system" onClose={() => setRegistering(false)} id="systems">
        <RegisterSystemForm onRegistered={async () => systems.reload()} />
      </Drawer>
    </>
  );
}
