// IB08 Phase 5: per-route callback configuration inline form. Lets the operator toggle callback
// mode (off / IMMEDIATE / SCHEDULED) and set the callback URL. Saves via PATCH to the route
// callback endpoint. Shown in the routes table below each route row when expanded.

import { useState, type FormEvent } from 'react';
import { patchRouteCallback, type RouteCallbackBody } from '../api/flows';
import type { FlowRoute } from './routesList';

export type RouteCallbackFormProps = {
  flowId: string;
  route: FlowRoute;
  onSaved: (mode: string | null, url: string | null) => void;
};

export function RouteCallbackForm({ flowId, route, onSaved }: RouteCallbackFormProps) {
  const [mode, setMode] = useState<string>(route.callbackMode ?? '');
  const [url, setUrl] = useState<string>(route.callbackUrl ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setSaved(false);
    const body: RouteCallbackBody = {
      callbackMode: mode || null,
      callbackUrl: mode === 'SCHEDULED' ? url.trim() : null
    };
    const answer = await patchRouteCallback(flowId, route.id, body);
    setBusy(false);
    if (answer.ok) {
      setSaved(true);
      onSaved(body.callbackMode, body.callbackUrl);
    } else {
      setError(answer.error ?? `HTTP ${answer.status}`);
    }
  };

  return (
    <form className="route-callback-form" data-route-callback={route.id} onSubmit={(e) => void onSubmit(e)}>
      <fieldset disabled={busy}>
        <label className="flows-field">
          Callback mode
          <select data-field="callback-mode" value={mode} onChange={(e) => { setMode(e.target.value); setSaved(false); }}>
            <option value="">Off (no callback)</option>
            <option value="IMMEDIATE">Immediate (sync response)</option>
            <option value="SCHEDULED">Scheduled (webhook ~15 s)</option>
          </select>
        </label>
        {mode === 'IMMEDIATE' && (
          <p className="flows-note" data-field="callback-hint">Response will be returned in the same intake API call.</p>
        )}
        {mode === 'SCHEDULED' && (
          <label className="flows-field">
            Callback URL
            <input
              type="url"
              data-field="callback-url"
              value={url}
              placeholder="https://example.com/api/integration/callback"
              required
              onChange={(e) => { setUrl(e.target.value); setSaved(false); }}
            />
          </label>
        )}
        <div className="route-callback-actions">
          <button type="submit" className="btn btn-primary" data-action="save-callback" aria-busy={busy}>
            {busy ? 'Saving...' : 'Save callback'}
          </button>
          {saved && <span className="flows-success" data-callback-saved>Saved</span>}
          {error && <span className="flows-error" role="alert" data-callback-error>{error}</span>}
        </div>
      </fieldset>
    </form>
  );
}
