// T-159 (E5 lane D, E5-F1-S1): the systems registry screens - the tenant's systems only (the API
// is tenant-scoped; this screen never filters), register a system of type API, DB, MQ or File
// with its type-specific settings, and edit those settings. A refused save shows EVERY validation
// error the API lists. Opening a system is its own address (#/systems/<id>); an id the tenant
// cannot see answers 404 and the screen says not found, with no hint whose row it is.
// The route and the menu entry live in App.tsx (the integrator's seam).
// Row 310 lane C (T101, 10-screens §5): a worklist (filter, Register system the single primary) with
// Register and Edit in the shared right-side Drawer (register closed by default; Open replaces the
// drawer's content in place) and each row's connection-test result in its own cell, next to its trigger.

import { useCallback, useEffect, useState } from 'react';
import { listSystems, readSystem, testConnection, TYPE_LABELS, type SystemType, type SystemView } from '../api/systems';
import { Drawer } from '../ui/Drawer';
import { EditSystemForm } from './systems/EditSystemForm';
import { RegisterSystemForm } from './systems/RegisterSystemForm';
import { drawerMode, filterSystems, settingsSummary, testCell, type TestOutcome } from './systems/systems-view';
import './systems/systems.css';

const LIST_HASH = '#/systems';

/** The id in #/systems/<id>, or null for the list alone. */
function openIdFromHash(): string | null {
  const match = /^#\/?systems\/([^/?#]+)/.exec(window.location.hash);
  return match ? decodeURIComponent(match[1]) : null;
}

export function Systems() {
  const [systems, setSystems] = useState<SystemView[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [listRefusal, setListRefusal] = useState<number | null>(null);
  const [filter, setFilter] = useState('');
  const [registering, setRegistering] = useState(false);
  const [openId, setOpenId] = useState<string | null>(openIdFromHash());
  const [open, setOpen] = useState<SystemView | null>(null);
  const [openRefusal, setOpenRefusal] = useState<string | null>(null);
  const [outcomes, setOutcomes] = useState<Record<string, TestOutcome>>({});

  // V-3 (row 306): the row's Test connection button - the endpoint's answer (one fixed shape) is
  // shown as it names it; a refusal (403/404 and the 429 TEST_CONNECTION_RATE_LIMITED) is surfaced
  // like the screen's other refusals. T101: kept per row and shown in that row's cell.
  const onTestConnection = useCallback(async (system: SystemView) => {
    setOutcomes((current) => {
      const rest = { ...current };
      delete rest[system.id];
      return rest;
    });
    const result = await testConnection(system.id);
    const outcome: TestOutcome = result.ok
      ? { kind: 'answer', result: result.data.result, message: result.data.message }
      : { kind: 'refusal', status: result.status, reason: result.refusal ?? result.error ?? '' };
    setOutcomes((current) => ({ ...current, [system.id]: outcome }));
  }, []);

  const refresh = useCallback(async () => {
    const result = await listSystems();
    if (result.ok) {
      setSystems(result.data);
      setListRefusal(null);
    } else {
      setListRefusal(result.status);
    }
    setLoaded(true);
  }, []);

  useEffect(() => {
    void refresh();
    const onHashChange = () => setOpenId(openIdFromHash());
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, [refresh]);

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

  const onSaved = async () => {
    await refresh();
  };

  const mode = drawerMode(openId, registering);
  const closeDrawer = () => {
    setRegistering(false);
    if (openId) window.location.hash = LIST_HASH;
  };
  const drawerTitle =
    mode === 'register'
      ? 'Register a system'
      : open
        ? `Edit system ${open.code} (${open.direction === 'INBOUND' ? 'inbound' : 'outbound'})`
        : openRefusal === 'system-not-found'
          ? 'System not found'
          : 'Open system';
  const shown = filterSystems(systems, filter);

  return (
    <div data-page="systems" data-systems-loaded={String(loaded)}>
      <div className="systems-page">
        <div className="systems-head">
          <h2>Systems</h2>
          <button type="button" className="btn btn-primary" data-action="open-register" onClick={() => setRegistering(true)}>
            Register system
          </button>
        </div>
        {listRefusal !== null && (
          <p role="alert" className="systems-alert systems-alert-danger" data-refusal={`list-refused-${listRefusal}`}>
            The API refused the systems list (HTTP {listRefusal}).
          </p>
        )}
        {loaded && systems.length === 0 && listRefusal === null && (
          <p className="systems-empty" data-systems-empty>
            No systems registered yet.
          </p>
        )}
        {systems.length > 0 && (
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
              {shown.length} of {systems.length}
            </span>
          </div>
        )}
        <div className="systems-grid">
          <table className="table" data-systems-table aria-label="Systems">
            <thead>
              <tr>
                <th>Code</th>
                <th>Name</th>
                <th>Direction</th>
                <th>Type</th>
                <th>Settings</th>
                <th>Connection test</th>
                <th>
                  <span className="systems-sr-only">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {shown.map((system) => {
                const summary = settingsSummary(system.settings);
                const outcome = outcomes[system.id];
                const cell = outcome ? testCell(outcome) : null;
                return (
                  <tr key={system.id} className="systems-row" data-system-row={system.code} data-system-type={system.type ?? ''}>
                    <td className="systems-mono">{system.code}</td>
                    <td>{system.name}</td>
                    <td>{system.direction === 'INBOUND' ? 'Inbound' : 'Outbound'}</td>
                    <td>{system.type ? (TYPE_LABELS[system.type as SystemType] ?? system.type) : 'untyped'}</td>
                    <td className="systems-settings">
                      <span title={summary}>{summary}</span>
                    </td>
                    <td className="systems-test">
                      {outcome?.kind === 'answer' && cell && (
                        <span role="status" data-test-connection-result={outcome.result}>
                          <span className="systems-sr-only">Connection test for {system.code}: </span>
                          <span className={`badge badge-${cell.tone}`}>{cell.word}</span>
                          {cell.detail && (
                            <span className="systems-test-detail" title={cell.detail}>
                              {cell.detail}
                            </span>
                          )}
                        </span>
                      )}
                      {outcome?.kind === 'refusal' && cell && (
                        <span role="alert" data-refusal={`test-connection-refused-${outcome.status}`}>
                          <span className="systems-sr-only">
                            The API refused the connection test for {system.code} (HTTP {outcome.status}):{' '}
                          </span>
                          <span className={`badge badge-${cell.tone}`}>{cell.word}</span>
                          {cell.detail && (
                            <span className="systems-test-detail" title={cell.detail}>
                              {cell.detail}
                            </span>
                          )}
                        </span>
                      )}
                    </td>
                    <td className="systems-actions">
                      <button
                        type="button"
                        className="btn"
                        data-action="open-system"
                        aria-label={`Open ${system.code}`}
                        onClick={() => (window.location.hash = `#/systems/${encodeURIComponent(system.id)}`)}
                      >
                        Open
                      </button>
                      <button
                        type="button"
                        className="btn"
                        data-action="test-connection"
                        aria-label={`Test connection for ${system.code}`}
                        onClick={() => void onTestConnection(system)}
                      >
                        Test connection
                      </button>
                    </td>
                  </tr>
                );
              })}
              {systems.length > 0 && shown.length === 0 && (
                <tr>
                  <td colSpan={7} className="systems-muted">
                    No system matches the filter.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>

        <Drawer open={mode !== null} title={drawerTitle} onClose={closeDrawer} id="systems">
          {mode === 'edit' && openRefusal === 'system-not-found' && (
            <p role="alert" className="systems-alert systems-alert-danger" data-refusal="system-not-found">
              This system does not exist for your tenant.
            </p>
          )}
          {mode === 'edit' && openRefusal !== null && openRefusal !== 'system-not-found' && (
            <p role="alert" className="systems-alert systems-alert-danger" data-refusal={openRefusal}>
              The API refused opening this system ({openRefusal}).
            </p>
          )}
          {mode === 'edit' && !open && openRefusal === null && <p className="systems-muted">Loading the system...</p>}
          {mode === 'edit' && open && <EditSystemForm key={open.id} system={open} onSaved={onSaved} />}
          {mode === 'register' && <RegisterSystemForm onRegistered={refresh} />}
        </Drawer>
      </div>
    </div>
  );
}
