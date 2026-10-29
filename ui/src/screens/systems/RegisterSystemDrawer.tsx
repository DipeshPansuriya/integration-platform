import { useState } from 'react';
import type {
  HealthInterval,
  RegisterSystemDrawerProps,
  SystemAuthMethod,
  SystemDirection,
  SystemDraft,
  SystemProtocol,
} from './types';
import { DEFAULT_SYSTEM_DRAFT } from './types';
import { Drawer } from '../../ds/components/surfaces/Drawer.jsx';
import { Select } from '../../ds/components/forms/Select.jsx';
import { Switch } from '../../ds/components/forms/Switch.jsx';
import { Dropzone } from '../../ds/components/forms/Dropzone.jsx';
import { Button } from '../../ds/components/actions/Button.jsx';
import { Callout } from '../../ds/components/feedback/Callout.jsx';
import './systems.css';

const PROTOCOLS: SystemProtocol[] = ['REST API', 'SOAP/XML', 'EDIFACT', 'Type-B Telex', 'SFTP'];
const DIRECTIONS: SystemDirection[] = ['Bidirectional', 'Inbound Only', 'Outbound Only'];
const AUTH_METHODS: SystemAuthMethod[] = ['HMAC Signature', 'OAuth 2.0', 'API Key', 'Certificate (mTLS)', 'Basic Auth'];
const INTERVALS: HealthInterval[] = ['Every 30 seconds', 'Every minute', 'Every 5 minutes'];

export { RegisterSystemDrawer };
export default function RegisterSystemDrawer({
  open = false,
  draft = null,
  testState = 'idle',
  testMessage = '',
  onClose,
  onTestConnection,
  onRegister,
}: RegisterSystemDrawerProps) {
  // Credential fields start EMPTY even when the seed draft carries stored values (write-only).
  const [form, setForm] = useState<SystemDraft>({
    ...DEFAULT_SYSTEM_DRAFT,
    ...(draft ?? {}),
    authClientSecret: '',
    authApiKey: '',
    authPassword: '',
  });
  const [attempted, setAttempted] = useState(false);
  const set = (patch: Partial<SystemDraft>) => setForm((f) => ({ ...f, ...patch }));

  const nameMissing = !form.name.trim();
  const codeMissing = !form.code.trim();
  const urlMissing = !form.baseUrl.trim();
  const invalid = nameMissing || codeMissing || urlMissing;
  const showValidation = attempted && invalid;

  const payload = (): SystemDraft => ({
    ...form,
    name: form.name.trim(),
    code: form.code.trim(),
    baseUrl: form.baseUrl.trim(),
    healthEndpoint: form.healthEndpoint.trim(),
    tags: form.tags
      .join(',')
      .split(',')
      .map((t) => t.trim())
      .filter(Boolean),
  });

  const submit = (kind: 'test' | 'register') => {
    setAttempted(true);
    if (invalid) return;
    const draft = payload();
    if (kind === 'test') onTestConnection?.(draft);
    else onRegister?.(draft);
  };

  const isHmac = form.authMethod === 'HMAC Signature';
  const isOauth = form.authMethod === 'OAuth 2.0';
  const clientAuth = isHmac || isOauth;

  return (
    <Drawer
      open={open}
      title="Register System"
      onClose={onClose}
      footer={
        <div className="lb-foot">
          <Button data-action="cancel" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="secondary" data-action="test-connection" onClick={() => submit('test')}>
            Test Connection
          </Button>
          <Button variant="primary" className="lb-foot-primary" data-action="register" onClick={() => submit('register')}>
            Register
          </Button>
        </div>
      }
    >
      <div className="lb-form">
        {showValidation && (
          <div role="alert" data-validation="register-system">
            <Callout tone="err" title="System Name, System Code and Base URL are required.">
              Fill those three fields before testing or registering.
            </Callout>
          </div>
        )}

        <h3 className="ip-drawer-label">Basic Information</h3>
        <div className="lb-row">
          <label className="lb-lab" htmlFor="rs-name">
            System Name
          </label>
          <input id="rs-name" className="ip-input" value={form.name} onChange={(e) => set({ name: e.target.value })} autoComplete="off" />
        </div>
        <div className="lb-row">
          <label className="lb-lab" htmlFor="rs-code">
            System Code
          </label>
          <input
            id="rs-code"
            className="ip-input ip-input--code"
            value={form.code}
            onChange={(e) => set({ code: e.target.value })}
            autoComplete="off"
          />
        </div>
        <div className="lb-row">
          <label className="lb-lab" htmlFor="rs-protocol">
            Protocol
          </label>
          <Select id="rs-protocol" value={form.protocol} options={PROTOCOLS} onChange={(e) => set({ protocol: e.target.value as SystemProtocol })} />
        </div>
        <div className="lb-row">
          <label className="lb-lab" htmlFor="rs-direction">
            Direction
          </label>
          <Select id="rs-direction" value={form.direction} options={DIRECTIONS} onChange={(e) => set({ direction: e.target.value as SystemDirection })} />
        </div>

        <h3 className="ip-drawer-label">Connection</h3>
        <div className="lb-row">
          <label className="lb-lab" htmlFor="rs-base-url">
            Base URL
          </label>
          <input id="rs-base-url" className="ip-input" value={form.baseUrl} onChange={(e) => set({ baseUrl: e.target.value })} autoComplete="off" />
        </div>
        <div className="lb-row">
          <label className="lb-lab" htmlFor="rs-timeout">
            Timeout (ms)
          </label>
          <input
            id="rs-timeout"
            className="ip-input"
            type="number"
            min={0}
            value={String(form.timeoutMs)}
            onChange={(e) => set({ timeoutMs: Number(e.target.value) || 0 })}
          />
        </div>
        <div className="lb-row">
          <label className="lb-lab" htmlFor="rs-retry">
            Retry Limit
          </label>
          <input
            id="rs-retry"
            className="ip-input"
            type="number"
            min={0}
            value={String(form.retryLimit)}
            onChange={(e) => set({ retryLimit: Number(e.target.value) || 0 })}
          />
        </div>

        <h3 className="ip-drawer-label">Authentication</h3>
        <div className="lb-row">
          <label className="lb-lab" htmlFor="rs-auth-method">
            Auth Method
          </label>
          <Select
            id="rs-auth-method"
            value={form.authMethod}
            options={AUTH_METHODS}
            onChange={(e) => set({ authMethod: e.target.value as SystemAuthMethod })}
          />
        </div>
        {clientAuth && (
          <>
            <div className="lb-row" data-auth-field="client-id">
              <label className="lb-lab" htmlFor="rs-client-id">
                {isOauth ? 'Client ID' : 'HMAC Client ID'}
              </label>
              <input
                id="rs-client-id"
                className="ip-input"
                value={form.authClientId}
                onChange={(e) => set({ authClientId: e.target.value })}
                autoComplete="off"
              />
            </div>
            <div className="lb-row" data-auth-field="client-secret">
              <label className="lb-lab" htmlFor="rs-client-secret">
                Client Secret
              </label>
              <input
                id="rs-client-secret"
                className="ip-input"
                type="password"
                autoComplete="new-password"
                value={form.authClientSecret}
                onChange={(e) => set({ authClientSecret: e.target.value })}
              />
            </div>
          </>
        )}
        {form.authMethod === 'API Key' && (
          <div className="lb-row" data-auth-field="api-key">
            <label className="lb-lab" htmlFor="rs-api-key">
              API Key
            </label>
            <input
              id="rs-api-key"
              className="ip-input"
              type="password"
              autoComplete="new-password"
              value={form.authApiKey}
              onChange={(e) => set({ authApiKey: e.target.value })}
            />
          </div>
        )}
        {form.authMethod === 'Certificate (mTLS)' && (
          <div className="lb-row" data-auth-field="certificate">
            <Dropzone
              title={certLabel(form.certFileName)}
              hint=".pem, .crt or .p12"
              accept=".pem,.crt,.p12"
              onFiles={(files) => set({ certFileName: files[0]?.name ?? '' })}
            />
            {form.certFileName && <p className="lb-hint">Certificate: {form.certFileName}</p>}
          </div>
        )}
        {form.authMethod === 'Basic Auth' && (
          <>
            <div className="lb-row" data-auth-field="username">
              <label className="lb-lab" htmlFor="rs-username">
                Username
              </label>
              <input
                id="rs-username"
                className="ip-input"
                value={form.authUsername}
                onChange={(e) => set({ authUsername: e.target.value })}
                autoComplete="off"
              />
            </div>
            <div className="lb-row" data-auth-field="password">
              <label className="lb-lab" htmlFor="rs-password">
                Password
              </label>
              <input
                id="rs-password"
                className="ip-input"
                type="password"
                autoComplete="new-password"
                value={form.authPassword}
                onChange={(e) => set({ authPassword: e.target.value })}
              />
            </div>
          </>
        )}

        <h3 className="ip-drawer-label">Health Check</h3>
        <div className="lb-row">
          <Switch label="Enable health monitoring" checked={form.healthMonitoring} onChange={(on) => set({ healthMonitoring: on })} />
        </div>
        <div className="lb-row">
          <label className="lb-lab" htmlFor="rs-health-endpoint">
            Health Endpoint
          </label>
          <input
            id="rs-health-endpoint"
            className="ip-input lb-code"
            value={form.healthEndpoint}
            onChange={(e) => set({ healthEndpoint: e.target.value })}
            autoComplete="off"
          />
        </div>
        <div className="lb-row">
          <label className="lb-lab" htmlFor="rs-health-interval">
            Check Interval
          </label>
          <Select
            id="rs-health-interval"
            value={form.healthInterval}
            options={INTERVALS}
            onChange={(e) => set({ healthInterval: e.target.value as HealthInterval })}
          />
        </div>

        <h3 className="ip-drawer-label">Tags</h3>
        <div className="lb-row">
          <label className="lb-lab" htmlFor="rs-tags">
            Tags (comma-separated)
          </label>
          <input
            id="rs-tags"
            className="ip-input"
            value={form.tags.join(', ')}
            onChange={(e) => set({ tags: e.target.value.split(',') })}
            autoComplete="off"
          />
        </div>

        {testState !== 'idle' && (
          <div data-testid="connection-test" data-state={testState} role={testState === 'fail' ? 'alert' : undefined}>
            <Callout
              tone={testState === 'testing' ? 'info' : testState === 'ok' ? 'ok' : 'err'}
              title={testState === 'testing' ? 'Testing connection…' : testState === 'ok' ? 'Connection succeeded' : 'Connection failed'}
            >
              {testState === 'testing' ? 'Checking the endpoint…' : testMessage}
            </Callout>
          </div>
        )}
      </div>
    </Drawer>
  );
}

function certLabel(fileName: string): string {
  return fileName ? 'Replace certificate' : 'Upload certificate';
}

