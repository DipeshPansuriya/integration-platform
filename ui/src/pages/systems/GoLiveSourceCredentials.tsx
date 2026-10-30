import { useEffect, useState, type FormEvent } from 'react';
import {
  issueSourceCredential,
  listSourceCredentials,
  revokeSourceCredential,
  rotateSourceCredential,
  type SourceCredentialView
} from '../../api/systems';
import { refusalWords as refusalText } from '../../api/refusal-words';
import { refusalWords as statusText } from '../../ui/refusal-words';

interface Props {
  systemId: string;
  systemName: string;
  canIssue: boolean;
}

type Confirm = { kind: 'rotate' | 'revoke'; credentialId: string } | null;

export function GoLiveSourceCredentials({ systemId, systemName, canIssue }: Props) {
  const [credentials, setCredentials] = useState<SourceCredentialView[]>([]);
  const [loading, setLoading] = useState(true);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [issuedSecret, setIssuedSecret] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [copyError, setCopyError] = useState(false);
  const [customerId, setCustomerId] = useState('');
  const [issuing, setIssuing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirm, setConfirm] = useState<Confirm>(null);
  const [forbidden, setForbidden] = useState(false);
  const [rotatingId, setRotatingId] = useState<string | null>(null);
  const [revokingId, setRevokingId] = useState<string | null>(null);

  const refresh = async () => {
    const result = await listSourceCredentials(systemId);
    if (!result.ok) {
      if (result.status === 403) setForbidden(true);
      else if (result.status === 404) setError('This system does not exist for your tenant.');
      else setError(result.errors?.length ? result.errors.join(' ') : result.refusal ? refusalText(result.refusal) : statusText(result.status, 'source credentials'));
      return;
    }
    setCredentials(result.data ?? []);
    setError(null);
  };

  useEffect(() => {
    let current = true;
    void (async () => {
      setLoading(true);
      await refresh();
      if (current) setLoading(false);
    })();
    return () => { current = false; };
  }, [systemId]);

  const onIssue = async (event: FormEvent) => {
    event.preventDefault();
    if (!canIssue) return;
    setIssuing(true);
    setError(null);
    const result = await issueSourceCredential(systemId, customerId.trim());
    if (!result.ok) {
      if (result.status === 403) setForbidden(true);
      else if (result.status === 404) setError('This system does not exist for your tenant.');
      else setError(result.errors?.length ? result.errors.join(' ') : result.refusal ? refusalText(result.refusal) : statusText(result.status, 'source credentials'));
      setIssuing(false);
      return;
    }
    const secret = result.data.secret ?? null;
    if (!secret) {
      await refresh();
      setError('The secret was not returned.');
      setIssuing(false);
      return;
    }
    setCopied(false);
    setCopyError(false);
    setDialogOpen(true);
    setIssuedSecret(secret);
    setCustomerId('');
    await refresh();
    setIssuing(false);
  };

  const closeDialog = () => {
    setDialogOpen(false);
    setIssuedSecret(null);
    setCopied(false);
    setCopyError(false);
  };

  const onCopy = async () => {
    if (!issuedSecret) return;
    setCopied(false);
    setCopyError(false);
    try {
      await navigator.clipboard.writeText(issuedSecret);
      setCopied(true);
      setIssuedSecret(null);
    } catch {
      setCopyError(true);
    }
  };

  const onRotate = async (credentialId: string) => {
    if (rotatingId || revokingId) return;
    setRotatingId(credentialId);
    try {
      const result = await rotateSourceCredential(credentialId);
      if (!result.ok) {
        if (result.status === 403) setForbidden(true);
        else if (result.status === 404) setError('This system does not exist for your tenant.');
        else setError(result.errors?.length ? result.errors.join(' ') : result.refusal ? refusalText(result.refusal) : statusText(result.status, 'source credentials'));
        setConfirm(null);
        return;
      }
      const secret = result.data.secret ?? null;
      if (!secret) {
        setError('The secret was not returned.');
        setConfirm(null);
        return;
      }
      setIssuedSecret(secret);
      setCopied(false);
      setCopyError(false);
      setDialogOpen(true);
      setConfirm(null);
      await refresh();
    } finally {
      setRotatingId(null);
    }
  };

  const onRevoke = async (credentialId: string) => {
    if (rotatingId || revokingId) return;
    setRevokingId(credentialId);
    try {
      const result = await revokeSourceCredential(credentialId);
      if (!result.ok) {
        if (result.status === 403) setForbidden(true);
        else if (result.status === 404) setError('This system does not exist for your tenant.');
        else setError(result.errors?.length ? result.errors.join(' ') : result.refusal ? refusalText(result.refusal) : statusText(result.status, 'source credentials'));
        setConfirm(null);
        return;
      }
      setConfirm(null);
      await refresh();
    } finally {
      setRevokingId(null);
    }
  };

  if (loading) return <p className="systems-muted">Loading source credentials...</p>;

  if (forbidden) {
    return (
      <section data-screen="no-access" role="alert">
        <p>You do not have access to source credentials for this system.</p>
      </section>
    );
  }

  return (
    <section data-section="source-credentials">
      <h3>Source credentials for {systemName}</h3>
      {!canIssue && (
        <p role="status" className="systems-alert" data-cf-notice="true">
          Ask your tenant admin to issue the source key.
        </p>
      )}

      <ul data-list="source-credentials">
        {credentials.map((cred) => (
          <li key={cred.id} data-credential-id={cred.id}>
            <span>{cred.clientId}</span>
            <span data-status={cred.status}>{cred.status}</span>
            {canIssue && (
              <>
                <button
                  data-action="rotate-source-credential"
                  disabled={cred.status !== 'ACTIVE' || rotatingId === cred.id}
                  aria-disabled={cred.status !== 'ACTIVE' || rotatingId === cred.id ? 'true' : undefined}
                  onClick={() => { if (rotatingId || revokingId) return; setConfirm({ kind: 'rotate', credentialId: cred.id }); }}
                >
                  Rotate
                </button>
                <button
                  data-action="revoke-source-credential"
                  disabled={revokingId === cred.id}
                  aria-disabled={revokingId === cred.id ? 'true' : undefined}
                  onClick={() => { if (rotatingId || revokingId) return; setConfirm({ kind: 'revoke', credentialId: cred.id }); }}
                >
                  Revoke
                </button>
              </>
            )}
          </li>
        ))}
      </ul>

      {canIssue && (
        <form data-form="issue-source-credential" onSubmit={onIssue}>
          <label>
            Customer
            <input
              data-field="source-credential-customer"
              value={customerId}
              onChange={(e) => setCustomerId(e.target.value)}
              placeholder="customer id (optional)"
            />
          </label>
          <button type="submit" data-action="issue-source-credential" disabled={issuing}>
            {issuing ? 'Issuing...' : 'Issue source credential'}
          </button>
        </form>
      )}

      {error && <p role="alert" className="systems-alert">{error}</p>}

      {dialogOpen && (
        <div role="dialog" aria-labelledby="sc-dialog-title" data-dialog="source-credential">
          <h4 id="sc-dialog-title">Source credential issued for {systemName}</h4>
          {dialogOpen && issuedSecret !== null && <p data-secret-display="true">Secret redacted for security.</p>}
          {issuedSecret === null && copied && <p role="status">Copied — secret cleared.</p>}
          {copyError && <p role="alert" className="systems-alert">Could not copy</p>}
          <button data-action="copy-secret" autoFocus onClick={onCopy}>Copy</button>
          <pre data-curl="true">curl -H "Authorization: Bearer &lt;SOURCE_KEY&gt;" https://example.com</pre>
          <button data-action="close-dialog" onClick={closeDialog}>Close</button>
        </div>
      )}

      {confirm && (
        <div role="dialog" aria-labelledby="sc-confirm-title" data-confirm={confirm.kind}>
          <h4 id="sc-confirm-title">
            {confirm.kind === 'rotate' ? `Rotate source credential for ${systemName}` : `Revoke source credential for ${systemName}`}
          </h4>
          <button
            data-action="confirm"
            autoFocus
            onClick={() =>
              confirm.kind === 'rotate' ? onRotate(confirm.credentialId) : onRevoke(confirm.credentialId)
            }
          >
            Confirm
          </button>
          <button data-action="cancel-confirm" onClick={() => setConfirm(null)}>Cancel</button>
        </div>
      )}
    </section>
  );
}

export default GoLiveSourceCredentials;
