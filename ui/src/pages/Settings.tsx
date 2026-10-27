import { useCallback, useEffect, useRef, useState } from 'react';
import { addAllowedHost, loadAllowedHosts, removeAllowedHost } from '../api/settings';
import './settings.css';

export function Settings() {
  const [hosts, setHosts] = useState<string[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [newPattern, setNewPattern] = useState('');
  const [saving, setSaving] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  const refresh = useCallback(async () => {
    const result = await loadAllowedHosts();
    if (result.ok) {
      setHosts(result.hosts);
      setError(null);
    } else {
      setError(result.error);
    }
    setLoaded(true);
  }, []);

  useEffect(() => { void refresh(); }, [refresh]);

  async function handleAdd(e: React.FormEvent) {
    e.preventDefault();
    const pattern = newPattern.trim();
    if (!pattern) return;
    setSaving(true);
    const result = await addAllowedHost(pattern);
    setSaving(false);
    if (result.ok) {
      setHosts(result.hosts);
      setNewPattern('');
      setAdding(false);
      setError(null);
    } else {
      setError(result.error);
    }
  }

  async function handleRemove(pattern: string) {
    const result = await removeAllowedHost(pattern);
    if (result.ok) {
      setHosts(result.hosts);
      setError(null);
    } else {
      setError(result.error);
    }
  }

  return (
    <div className="page" data-page="settings">
      <div className="page-header">
        <h1>Settings</h1>
      </div>

      <section className="settings-section">
        <div className="settings-section-header">
          <h2>Allowed destination hosts</h2>
          {!adding && (
            <button type="button" className="btn btn-primary" onClick={() => { setAdding(true); setTimeout(() => inputRef.current?.focus(), 0); }}>
              Add host
            </button>
          )}
        </div>
        <p className="settings-muted">
          Hosts that outbound systems may connect to. Exact names or <code>*.domain</code> for sub-domains.
          Changes take effect immediately — no restart needed.
        </p>

        {error && <p role="alert" className="settings-error">{error}</p>}

        {adding && (
          <form className="settings-add-form" onSubmit={(e) => void handleAdd(e)}>
            <input
              ref={inputRef}
              type="text"
              className="input"
              value={newPattern}
              onChange={(e) => setNewPattern(e.target.value)}
              placeholder="e.g. api.example.com or *.example.com"
              disabled={saving}
              autoFocus
            />
            <button type="submit" className="btn btn-primary" disabled={saving || !newPattern.trim()}>
              {saving ? 'Saving...' : 'Save'}
            </button>
            <button type="button" className="btn" onClick={() => { setAdding(false); setNewPattern(''); }}>
              Cancel
            </button>
          </form>
        )}

        {!loaded ? (
          <p className="settings-muted">Loading...</p>
        ) : hosts.length === 0 ? (
          <p className="settings-muted">No runtime hosts configured. Hosts from the startup configuration (environment variables) are always active.</p>
        ) : (
          <table className="table" data-settings-hosts>
            <thead>
              <tr>
                <th scope="col">Pattern</th>
                <th scope="col">Actions</th>
              </tr>
            </thead>
            <tbody>
              {hosts.map((host) => (
                <tr key={host}>
                  <td><code>{host}</code></td>
                  <td>
                    <button
                      type="button"
                      className="btn btn-danger-text"
                      onClick={() => void handleRemove(host)}
                    >
                      Remove
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}
