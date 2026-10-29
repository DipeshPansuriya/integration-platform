import { useEffect, useState, type FormEvent } from 'react';
import {
  approveApprovedTable,
  listApprovedTables,
  requestApprovedTable,
  revokeApprovedTable,
  type ApprovedTableView
} from '../../api/systems';
import { refusalWords as refusalText } from '../../api/refusal-words';
import { refusalWords as statusText } from '../../ui/refusal-words';

interface Props {
  systemId: string;
  systemName: string;
  canApprove: boolean;
}

export function GoLiveApprovedTables({ systemId, systemName, canApprove }: Props) {
  const [tables, setTables] = useState<ApprovedTableView[]>([]);
  const [loading, setLoading] = useState(true);
  const [tableName, setTableName] = useState('');
  const [requesting, setRequesting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState<{ approving?: string; revoking?: string }>({});

  const refresh = async () => {
    const result = await listApprovedTables(systemId);
    if (!result.ok) {
      if (result.status === 403) setForbidden(true);
      else if (result.status === 404) setError('This system does not exist for your tenant.');
      else setError(result.errors?.length ? result.errors.join(' ') : result.refusal ? refusalText(result.refusal) : statusText(result.status, 'approved tables'));
      return;
    }
    setTables(result.data ?? []);
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

  const onRequest = async (event: FormEvent) => {
    event.preventDefault();
    setRequesting(true);
    setError(null);
    setNotice(null);
    const result = await requestApprovedTable(systemId, tableName.trim());
    if (!result.ok) {
      if (result.status === 403) setForbidden(true);
      else if (result.status === 404) setError('This system does not exist for your tenant.');
      else setError(result.errors?.length ? result.errors.join(' ') : result.refusal ? refusalText(result.refusal) : statusText(result.status, 'approved tables'));
      setRequesting(false);
      return;
    }
    setTableName('');
    if (!canApprove) setNotice('Your request is pending approval.');
    await refresh();
    setRequesting(false);
  };

  const onApprove = async (id: string) => {
    setBusy((b) => ({ ...b, approving: id }));
    try {
      const result = await approveApprovedTable(systemId, id);
      if (!result.ok) {
        if (result.status === 403) setForbidden(true);
        else if (result.status === 404) setError('This system does not exist for your tenant.');
        else setError(result.errors?.length ? result.errors.join(' ') : result.refusal ? refusalText(result.refusal) : statusText(result.status, 'approved tables'));
        return;
      }
      await refresh();
    } finally {
      setBusy((b) => ({ ...b, approving: undefined }));
    }
  };

  const onRevoke = async (id: string) => {
    setBusy((b) => ({ ...b, revoking: id }));
    try {
      const result = await revokeApprovedTable(systemId, id);
      if (!result.ok) {
        if (result.status === 403) setForbidden(true);
        else if (result.status === 404) setError('This system does not exist for your tenant.');
        else setError(result.errors?.length ? result.errors.join(' ') : result.refusal ? refusalText(result.refusal) : statusText(result.status, 'approved tables'));
        return;
      }
      await refresh();
    } finally {
      setBusy((b) => ({ ...b, revoking: undefined }));
    }
  };

  if (loading) return <p className="systems-muted">Loading approved tables...</p>;

  if (forbidden) {
    return (
      <section data-screen="no-access" role="alert">
        <p>You do not have access to approved tables for this system.</p>
      </section>
    );
  }

  return (
    <section data-section="approved-tables">
      <h3>Approved tables for {systemName}</h3>

      <ul data-list="approved-tables">
        {tables.map((row) => (
          <li key={row.id} data-approved-table-id={row.id} data-table={row.table}>
            <span>{row.table}</span>
            <span data-status={row.status}>{row.status}</span>
            {canApprove ? (
              <>
                <button
                  data-action="approve-table"
                  aria-disabled={busy.approving === row.id ? 'true' : undefined}
                  onClick={() => { if (busy.approving || busy.revoking) return; void onApprove(row.id); }}
                >
                  Approve
                </button>
                <button
                  data-action="revoke-table"
                  aria-disabled={busy.revoking === row.id ? 'true' : undefined}
                  onClick={() => { if (busy.approving || busy.revoking) return; void onRevoke(row.id); }}
                >
                  Revoke
                </button>
              </>
            ) : (
              <span data-pending-notice="true">Pending approval</span>
            )}
          </li>
        ))}
      </ul>

      <form data-form="request-approved-table" onSubmit={onRequest}>
        <label>
          Table
          <input
            data-field="approved-table-request"
            value={tableName}
            onChange={(e) => setTableName(e.target.value)}
            placeholder="table name"
          />
        </label>
        <button type="submit" data-action="request-approved-table" disabled={requesting} aria-disabled={requesting ? 'true' : undefined}>
          {requesting ? 'Requesting...' : 'Submit'}
        </button>
      </form>

      {notice && <p role="status" className="systems-alert" data-pending-notice="true">{notice}</p>}
      {error && <p role="alert" className="systems-alert">{error}</p>}
    </section>
  );
}

export default GoLiveApprovedTables;
