// T-160 (E5 lane D, E5-F2-S1): the contract version screens - import a version from an OpenAPI
// document or a JSON Schema (an unparsable document shows the parser's errors; nothing is
// stored), the versions listed and compared side by side, submit with a change note, and approve.
// The author approving their own version sees the API's AUTHOR_IS_APPROVER refusal on screen,
// never hidden; another approver succeeds. The route and the menu entry live in App.tsx.

import { useCallback, useEffect, useState } from 'react';
import {
  approveContractVersion,
  listContracts,
  submitContractVersion,
  type Contract
} from '../api/contracts';
import { hasHarnessLogin, loadHarnessConfig } from '../api/harness-config';
import { ContractList } from './contracts/ContractList';
import { ImportContractForm } from './contracts/ImportContractForm';
import { SideBySide, type Compared } from './contracts/SideBySide';

/** Two panes side by side; a third compared version replaces the oldest. */
const MAX_COMPARED = 2;

export function ContractVersions() {
  const [contracts, setContracts] = useState<Contract[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [listRefusal, setListRefusal] = useState<number | null>(null);
  const [compared, setCompared] = useState<Compared[]>([]);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [harnessApprover, setHarnessApprover] = useState(false);

  const refresh = useCallback(async () => {
    const result = await listContracts();
    if (result.ok) {
      setContracts(result.data);
      setListRefusal(null);
    } else {
      setListRefusal(result.status);
    }
    setLoaded(true);
  }, []);

  useEffect(() => {
    void loadHarnessConfig().then(() => setHarnessApprover(hasHarnessLogin('approver')));
    void refresh();
  }, [refresh]);

  const onCompare = (id: string, key: string, checked: boolean) => {
    setCompared((current) => {
      const rest = current.filter((entry) => entry.id !== id);
      return checked ? [...rest, { id, key }].slice(-MAX_COMPARED) : rest;
    });
  };

  const onSubmit = async (versionId: string, changeNote: string) => {
    setRefusal(null);
    const result = await submitContractVersion(versionId, changeNote.trim());
    if (!result.ok) {
      setRefusal(result.error ?? `submit-refused-${result.status}`);
      return;
    }
    await refresh();
  };

  const onApprove = async (versionId: string, asLogin?: string) => {
    setRefusal(null);
    const result = await approveContractVersion(versionId, asLogin);
    if (!result.ok) {
      if (result.refusal === 'AUTHOR_IS_APPROVER') setRefusal('author-cannot-approve');
      else if (result.refusal === 'NOT_PENDING_APPROVAL') setRefusal('not-pending-approval');
      else setRefusal(result.error ?? `approve-refused-${result.status}`);
      return;
    }
    await refresh();
  };

  const comparedIds = new Set(compared.map((entry) => entry.id));

  return (
    <div data-page="contracts" data-contracts-loaded={String(loaded)}>
      <h2>Contract versions</h2>
      {listRefusal !== null && (
        <p role="alert" data-refusal={`list-refused-${listRefusal}`}>
          The API refused the contracts list (HTTP {listRefusal}).
        </p>
      )}
      {refusal === 'author-cannot-approve' && (
        <p role="alert" data-refusal="author-cannot-approve">
          Refused: the author cannot approve their own version (AUTHOR_IS_APPROVER).
        </p>
      )}
      {refusal === 'not-pending-approval' && (
        <p role="alert" data-refusal="not-pending-approval">
          Refused: only a submitted version can be approved (NOT_PENDING_APPROVAL).
        </p>
      )}
      {refusal !== null && !['author-cannot-approve', 'not-pending-approval'].includes(refusal) && (
        <p role="alert" data-refusal="action-refused">
          The API refused this action: {refusal}
        </p>
      )}
      {loaded && contracts.length === 0 && listRefusal === null && <p data-contracts-empty>No contracts yet.</p>}
      <ContractList
        contracts={contracts}
        actions={{
          compared: comparedIds,
          onCompare,
          onSubmit: (id, note) => void onSubmit(id, note),
          onApprove: (id, asLogin) => void onApprove(id, asLogin),
          harnessApprover
        }}
      />
      <SideBySide compared={compared} />
      <ImportContractForm onImported={refresh} />
    </div>
  );
}
