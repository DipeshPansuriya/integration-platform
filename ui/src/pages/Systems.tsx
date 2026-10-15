// T-159 (E5 lane D, E5-F1-S1): the systems registry screens - the tenant's systems only (the API
// is tenant-scoped; this screen never filters), register a system of type API, DB, MQ or File
// with its type-specific settings, and edit those settings. A refused save shows EVERY validation
// error the API lists. Opening a system is its own address (#/systems/<id>); an id the tenant
// cannot see answers 404 and the screen says not found, with no hint whose row it is.
// The route and the menu entry live in App.tsx (the integrator's seam).

import { useCallback, useEffect, useState } from 'react';
import { listSystems, readSystem, testConnection, TYPE_LABELS, type SystemType, type SystemView } from '../api/systems';
import { EditSystemForm } from './systems/EditSystemForm';
import { RegisterSystemForm } from './systems/RegisterSystemForm';

/** The id in #/systems/<id>, or null for the list alone. */
function openIdFromHash(): string | null {
  const match = /^#\/?systems\/([^/?#]+)/.exec(window.location.hash);
  return match ? decodeURIComponent(match[1]) : null;
}

/** What the screen keeps of one test-connection answer: the system's code plus the endpoint's words. */
type TestAnswer = { code: string; result: string; message: string | null };
/** A refused connection test, surfaced like the screen's other refusals. */
type TestRefusal = { code: string; status: number; reason: string };

function settingsSummary(system: SystemView): string {
  if (!system.settings) return '-';
  return Object.entries(system.settings)
    .map(([name, value]) => `${name}: ${String(value)}`)
    .join(', ');
}

export function Systems() {
  const [systems, setSystems] = useState<SystemView[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [listRefusal, setListRefusal] = useState<number | null>(null);
  const [openId, setOpenId] = useState<string | null>(openIdFromHash());
  const [open, setOpen] = useState<SystemView | null>(null);
  const [openRefusal, setOpenRefusal] = useState<string | null>(null);
  const [testAnswer, setTestAnswer] = useState<TestAnswer | null>(null);
  const [testRefusal, setTestRefusal] = useState<TestRefusal | null>(null);

  // V-3 (row 306): the row's Test connection button - the endpoint's answer (one fixed shape) is
  // shown as it names it; a refusal (403/404 and the 429 TEST_CONNECTION_RATE_LIMITED) is surfaced
  // like the screen's other refusals.
  const onTestConnection = useCallback(async (system: SystemView) => {
    setTestAnswer(null);
    setTestRefusal(null);
    const result = await testConnection(system.id);
    if (result.ok) {
      setTestAnswer({ code: system.code, result: result.data.result, message: result.data.message });
    } else {
      setTestRefusal({ code: system.code, status: result.status, reason: result.refusal ?? result.error ?? '' });
    }
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

  return (
    <div data-page="systems" data-systems-loaded={String(loaded)}>
      <h2>Systems</h2>
      {listRefusal !== null && (
        <p role="alert" data-refusal={`list-refused-${listRefusal}`}>
          The API refused the systems list (HTTP {listRefusal}).
        </p>
      )}
      <table data-systems-table>
        <thead>
          <tr>
            <th>Code</th>
            <th>Name</th>
            <th>Direction</th>
            <th>Type</th>
            <th>Settings</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {systems.map((system) => (
            <tr key={system.id} data-system-row={system.code} data-system-type={system.type ?? ''}>
              <td>{system.code}</td>
              <td>{system.name}</td>
              <td>{system.direction}</td>
              <td>{system.type ? (TYPE_LABELS[system.type as SystemType] ?? system.type) : 'untyped'}</td>
              <td>{settingsSummary(system)}</td>
              <td>
                <button
                  type="button"
                  data-action="open-system"
                  onClick={() => (window.location.hash = `#/systems/${encodeURIComponent(system.id)}`)}
                >
                  Open
                </button>{' '}
                <button type="button" data-action="test-connection" onClick={() => void onTestConnection(system)}>
                  Test connection
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {loaded && systems.length === 0 && listRefusal === null && <p data-systems-empty>No systems registered yet.</p>}

      {testAnswer && (
        <p data-test-connection-result={testAnswer.result}>
          Connection test for <strong>{testAnswer.code}</strong>: {testAnswer.result}
          {testAnswer.message ? ` - ${testAnswer.message}` : ''}
        </p>
      )}
      {testRefusal && (
        <p role="alert" data-refusal={`test-connection-refused-${testRefusal.status}`}>
          The API refused the connection test for {testRefusal.code} (HTTP {testRefusal.status})
          {testRefusal.reason ? `: ${testRefusal.reason}` : ''}.
        </p>
      )}

      {openRefusal === 'system-not-found' && (
        <p role="alert" data-refusal="system-not-found">
          This system does not exist for your tenant.
        </p>
      )}
      {openRefusal !== null && openRefusal !== 'system-not-found' && (
        <p role="alert" data-refusal={openRefusal}>
          The API refused opening this system ({openRefusal}).
        </p>
      )}
      {open && <EditSystemForm key={open.id} system={open} onSaved={onSaved} />}

      <RegisterSystemForm onRegistered={refresh} />
    </div>
  );
}
