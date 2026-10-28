import { useCallback, useEffect, useRef, useState } from 'react';
import { listAllowedHosts, addAllowedHost, removeAllowedHost, type HostEntry } from '../api/destinations';
import './destinations.css';

export function Destinations() {
  const [entries, setEntries] = useState<HostEntry[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [newPattern, setNewPattern] = useState('');
  const [addError, setAddError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  const load = useCallback(() => {
    listAllowedHosts()
      .then((data) => { setEntries(data); setLoaded(true); setError(null); })
      .catch((err) => { setError(String(err.message || err)); setLoaded(true); });
  }, []);

  useEffect(() => { load(); }, [load]);

  async function handleAdd(e: React.FormEvent) {
    e.preventDefault();
    const pattern = newPattern.trim();
    if (!pattern) return;
    setAdding(true);
    setAddError(null);
    try {
      await addAllowedHost(pattern);
      setNewPattern('');
      load();
    } catch (err: unknown) {
      setAddError(err instanceof Error ? err.message : String(err));
    } finally {
      setAdding(false);
      inputRef.current?.focus();
    }
  }

  async function handleRemove(id: string) {
    try {
      await removeAllowedHost(id);
      load();
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }

  return (
    <div className="destinations">
      <div className="destinations-header">
        <h1>Destination allow-list</h1>
        <p className="destinations-subtitle">
          Manage the hosts this platform is allowed to reach. Exact names
          (<code>api.example.com</code>) or wildcard suffixes (<code>*.example.com</code>).
        </p>
      </div>

      <form className="destinations-add" onSubmit={handleAdd}>
        <input
          ref={inputRef}
          type="text"
          className="input"
          placeholder="Host pattern, e.g. api.example.com or *.example.com"
          value={newPattern}
          onChange={(e) => setNewPattern(e.target.value)}
          disabled={adding}
          aria-label="New host pattern"
        />
        <button type="submit" className="btn btn-primary" disabled={adding || !newPattern.trim()}>
          {adding ? 'Adding...' : 'Add host'}
        </button>
      </form>
      {addError && <p className="destinations-error" role="alert">{addError}</p>}

      {error && <p className="destinations-error" role="alert">{error}</p>}

      {!loaded ? (
        <p className="destinations-loading">Loading...</p>
      ) : entries.length === 0 ? (
        <p className="destinations-empty">
          No hosts in the database allow-list. Hosts from the deployment configuration
          (<code>PLATFORM_CONFIG_DESTINATIONS_ALLOWEDHOSTS</code>) are always active.
        </p>
      ) : (
        <table className="destinations-table">
          <thead>
            <tr>
              <th>Pattern</th>
              <th className="destinations-actions-col">Actions</th>
            </tr>
          </thead>
          <tbody>
            {entries.map((entry) => (
              <tr key={entry.id}>
                <td><code>{entry.pattern}</code></td>
                <td className="destinations-actions-col">
                  <button
                    type="button"
                    className="btn btn-danger-subtle"
                    onClick={() => handleRemove(entry.id)}
                    title={`Remove ${entry.pattern}`}
                  >
                    Remove
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
