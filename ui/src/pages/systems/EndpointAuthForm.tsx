import { Fragment, useEffect, useState, type FormEvent } from 'react';
import {
  AUTH_METHODS,
  AUTH_METHOD_LABELS,
  AUTH_IDENTITY_LABEL,
  CREDENTIAL_FIELDS_BY_AUTH,
  readEndpointAuth,
  readTargetCredentials,
  saveTargetCredentials,
  updateEndpointAuth,
  type AuthMethod
} from '../../api/systems';
import { ValidationErrors } from './SystemFields';

export function EndpointAuthForm({ systemId }: { systemId: string }) {
  const [authMethod, setAuthMethod] = useState<AuthMethod>('NO_AUTH');
  const [authIdentity, setAuthIdentity] = useState('');
  const [authTokenUrl, setAuthTokenUrl] = useState('');
  const [credentialValues, setCredentialValues] = useState<Record<string, string>>({});
  const [sealedFields, setSealedFields] = useState<string[]>([]);
  const [errors, setErrors] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    let current = true;
    setLoading(true);
    void (async () => {
      const authResult = await readEndpointAuth(systemId);
      if (!current) return;
      if (authResult.ok) {
        const auth = authResult.data;
        setAuthMethod((auth.authMethod ?? 'NO_AUTH') as AuthMethod);
        setAuthIdentity(auth.authIdentity ?? '');
        setAuthTokenUrl(auth.authTokenUrl ?? '');
        const credResult = await readTargetCredentials(auth.endpointId);
        if (!current) return;
        if (credResult.ok) {
          setSealedFields(credResult.data.fields.map((f) => f.field));
        }
      }
      setLoading(false);
    })();
    return () => { current = false; };
  }, [systemId]);

  const identityLabel = AUTH_IDENTITY_LABEL[authMethod];
  const credentialDefs = CREDENTIAL_FIELDS_BY_AUTH[authMethod] ?? [];
  const needsIdentity = !!identityLabel;
  const needsTokenUrl = authMethod === 'OAUTH2_CLIENT_CREDENTIALS';

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setSaving(true);
    setSaved(false);
    setErrors([]);
    try {
      const authResult = await updateEndpointAuth(systemId, {
        authMethod,
        authIdentity: needsIdentity ? authIdentity.trim() || null : null,
        authTokenUrl: needsTokenUrl ? authTokenUrl.trim() || null : null
      });
      if (!authResult.ok) {
        setErrors(authResult.errors?.length ? authResult.errors : [`Save refused (HTTP ${authResult.status}).`]);
        return;
      }

      const fieldsToSeal: Record<string, string> = {};
      for (const def of credentialDefs) {
        const value = credentialValues[def.field]?.trim();
        if (value) fieldsToSeal[def.field] = value;
      }
      if (Object.keys(fieldsToSeal).length > 0 && authResult.data.endpointId) {
        const credResult = await saveTargetCredentials(authResult.data.endpointId, fieldsToSeal);
        if (!credResult.ok) {
          setErrors(credResult.errors?.length ? credResult.errors : [`Credential save refused (HTTP ${credResult.status}).`]);
          return;
        }
        setSealedFields(credResult.data.fields.map((f) => f.field));
        setCredentialValues({});
      }
      setSaved(true);
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return <p className="systems-muted">Loading auth settings...</p>;
  }

  return (
    <form data-form="endpoint-auth" onSubmit={onSubmit}>
      <fieldset data-settings-for="endpoint-auth">
        <legend>Target authentication</legend>

        <label>
          Auth method *
          <select
            data-field="auth-method"
            value={authMethod}
            onChange={(e) => {
              setAuthMethod(e.target.value as AuthMethod);
              setCredentialValues({});
              setSaved(false);
            }}
          >
            {AUTH_METHODS.map((method) => (
              <option key={method} value={method}>{AUTH_METHOD_LABELS[method]}</option>
            ))}
          </select>
        </label>

        {needsIdentity && (
          <label>
            {identityLabel} *
            <input
              data-field="auth-identity"
              value={authIdentity}
              aria-required
              onChange={(e) => setAuthIdentity(e.target.value)}
              placeholder={identityLabel}
            />
          </label>
        )}

        {needsTokenUrl && (
          <label>
            Token URL *
            <input
              data-field="auth-token-url"
              value={authTokenUrl}
              aria-required
              onChange={(e) => setAuthTokenUrl(e.target.value)}
              placeholder="https://auth.example.com/oauth/token"
            />
          </label>
        )}

        {credentialDefs.map((def) => {
          const isSealed = sealedFields.includes(def.field);
          return (
            <Fragment key={def.field}>
              <label>
                {def.label} {isSealed ? '(sealed — leave blank to keep)' : '*'}
                <input
                  type="password"
                  data-field={`credential-${def.field}`}
                  value={credentialValues[def.field] ?? ''}
                  placeholder={isSealed ? '••••••••' : def.placeholder}
                  autoComplete="off"
                  onChange={(e) => setCredentialValues((v) => ({ ...v, [def.field]: e.target.value }))}
                />
              </label>
              {isSealed && (
                <p className="systems-muted">A sealed value exists. Enter a new value to replace it, or leave blank to keep the current one.</p>
              )}
            </Fragment>
          );
        })}
      </fieldset>

      <button type="submit" data-action="save-endpoint-auth" disabled={saving}>
        {saving ? 'Saving...' : 'Save auth settings'}
      </button>
      {saved && <p role="status" className="systems-alert systems-alert-success" data-saved="true">Auth settings saved.</p>}
      <ValidationErrors errors={errors} />
    </form>
  );
}
