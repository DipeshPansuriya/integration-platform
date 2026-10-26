// T-160 (E5 lane D, E5-F2-S1): the contract version screens - import a version from an OpenAPI
// document or a JSON Schema (an unparsable document shows the parser's errors; nothing is
// stored), the versions listed and compared side by side, submit with a change note, and approve.
// The author approving their own version sees the API's AUTHOR_IS_APPROVER refusal on screen,
// never hidden; another approver succeeds. The route and the menu entry live in App.tsx.
// Row 310 lane C (T101, 10-screens §6): grouped list (one card per system/role), compare panes in
// a two-column grid, and "Import a contract version" as the single primary opening the shared Drawer.

import { useCallback, useEffect, useState } from 'react';
import {
  approveContractVersion,
  listContracts,
  submitContractVersion,
  type Contract
} from '../api/contracts';
import { hasHarnessLogin, loadHarnessConfig } from '../api/harness-config';
import { Drawer } from '../ui/Drawer';
import { refusalWords } from '../ui/refusal-words';
import { ContractList, type CardRefusal } from './contracts/ContractList';
import { contractKey } from './contracts/contracts-view';
import { ImportContractForm } from './contracts/ImportContractForm';
import { SideBySide, type Compared } from './contracts/SideBySide';
import './contracts/contracts.css';

/** Two panes side by side; a third compared version replaces the oldest. */
const MAX_COMPARED = 2;

export function ContractVersions() {
  const [contracts, setContracts] = useState<Contract[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [listRefusal, setListRefusal] = useState<number | null>(null);
  const [compared, setCompared] = useState<Compared[]>([]);
  const [refusal, setRefusal] = useState<CardRefusal | null>(null);
  const [harnessApprover, setHarnessApprover] = useState(false);
  const [importing, setImporting] = useState(false);

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

  /** The card (system/role) a version belongs to - where its refusal is worded. */
  const cardOf = (versionId: string) =>
    contractKey(contracts.find((contract) => (contract.versions ?? []).some((version) => version.id === versionId)) ?? {});

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
      setRefusal({ contractKey: cardOf(versionId), code: result.error ?? `submit-refused-${result.status}` });
      return;
    }
    await refresh();
  };

  const onApprove = async (versionId: string, asLogin?: string) => {
    setRefusal(null);
    const result = await approveContractVersion(versionId, asLogin);
    if (!result.ok) {
      const code =
        result.refusal === 'AUTHOR_IS_APPROVER'
          ? 'author-cannot-approve'
          : result.refusal === 'NOT_PENDING_APPROVAL'
            ? 'not-pending-approval'
            : (result.error ?? `approve-refused-${result.status}`);
      setRefusal({ contractKey: cardOf(versionId), code });
      return;
    }
    await refresh();
  };

  const comparedIds = new Set(compared.map((entry) => entry.id));

  return (
    <div data-page="contracts" data-contracts-loaded={String(loaded)}>
      <div className="contracts-page">
        <div className="contracts-head">
          <h1>Contract versions</h1>
          <button type="button" className="btn btn-primary" data-action="open-import" onClick={() => setImporting(true)}>
            Import a contract version
          </button>
        </div>
        {listRefusal !== null && (
          <p role="alert" className="contracts-alert contracts-alert-danger" data-refusal={`list-refused-${listRefusal}`}>
            {refusalWords(listRefusal, 'the contracts list')}
            {' '}
            <button type="button" className="btn" data-action="retry-load" onClick={() => void refresh()}>
              Retry
            </button>
          </p>
        )}
        {!loaded && (
          <p className="contracts-muted-cell" role="status" data-contracts-loading>
            Loading contract versions...
          </p>
        )}
        {loaded && contracts.length === 0 && listRefusal === null && (
          <div className="contracts-empty" data-contracts-empty>
            <p>Import a contract version to start comparing.</p>
            <button type="button" className="btn" data-action="import-from-empty" onClick={() => setImporting(true)}>
              Import a contract version
            </button>
          </div>
        )}
        <ContractList
          contracts={contracts}
          refusal={refusal}
          actions={{
            compared: comparedIds,
            onCompare,
            onSubmit: (id, note) => void onSubmit(id, note),
            onApprove: (id, asLogin) => void onApprove(id, asLogin),
            harnessApprover
          }}
        />
        <SideBySide compared={compared} />
        <Drawer open={importing} title="Import a contract version" onClose={() => setImporting(false)} id="import-contract">
          <ImportContractForm onImported={refresh} />
        </Drawer>
      </div>
    </div>
  );
}
