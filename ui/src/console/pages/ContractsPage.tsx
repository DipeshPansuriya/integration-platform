// Console container for the Contracts screen: wires screens/contracts/ContractsScreen to the real contracts API and
// keeps every behaviour of pages/ContractVersions.tsx. One table row per contract (system/role) showing its latest
// version; a row opens the detail drawer with that version's stored JSON Schema. The version lifecycle the screen does
// not draw (compare side by side, submit with a change note, approve, the harness approver, refusals) stays on the old
// ContractList + SideBySide, rendered below the screen. Import uses the old ImportContractForm in the shared Drawer:
// the design's import drawer offers formats and options (XML, EDIFACT, CSV, auto-generated rules) the API does not take.
// "Flows Using" and "Used By" have no backend source and stay empty - nothing here is sample data.

import { useEffect, useState } from 'react';
import { useContracts } from '../../api/hooks';
import {
  approveContractVersion,
  readContractVersion,
  schemaFields,
  submitContractVersion,
  type Contract,
  type ContractVersion,
  type ContractVersionSummary
} from '../../api/contracts';
import { hasHarnessLogin, loadHarnessConfig } from '../../api/harness-config';
import ContractsScreen, { type ContractDetailVM, type ContractRowVM } from '../../screens/contracts/ContractsScreen';
import { Drawer } from '../../ui/Drawer';
import { refusalWords } from '../../ui/refusal-words';
import { ContractList, type CardRefusal } from '../../pages/contracts/ContractList';
import { contractKey, roleWord } from '../../pages/contracts/contracts-view';
import { ImportContractForm } from '../../pages/contracts/ImportContractForm';
import { SideBySide, type Compared } from '../../pages/contracts/SideBySide';
import '../../pages/contracts/contracts.css';

/** Two panes side by side; a third compared version replaces the oldest. */
const MAX_COMPARED = 2;

type Row = ContractRowVM & { key: string; latestId: string | null };
type Detail = { key: string; loading: boolean; error: string | null; version: ContractVersion | null };

/** The latest version: the newest createdAt, else the last one in the API's order. */
function latestOf(contract: Contract): ContractVersionSummary | null {
  const versions = contract.versions ?? [];
  if (versions.length === 0) return null;
  return versions.reduce((best, version) => ((version.createdAt ?? '') > (best.createdAt ?? '') ? version : best), versions[versions.length - 1]);
}

const dateWord = (iso: string | undefined, withYear: boolean) => {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return date.toLocaleDateString('en-US', withYear ? { month: 'short', day: 'numeric', year: 'numeric' } : { month: 'short', day: 'numeric' });
};

function toRows(contracts: Contract[]): Row[] {
  const labels = contracts.map((contract) => contract.code || contract.name || contract.system || '');
  return contracts.map((contract, index) => {
    const latest = latestOf(contract);
    const label = labels.filter((other) => other === labels[index]).length > 1 && contract.role
      ? `${labels[index]} · ${roleWord(contract.role)}`
      : labels[index];
    return {
      key: contractKey(contract),
      latestId: latest?.id ?? null,
      name: label || contractKey(contract),
      version: latest?.versionLabel ?? '—',
      // the API stores every import as a canonical JSON Schema (OpenAPI documents are converted)
      format: 'JSON',
      // no backend source for the number of flows using a contract: left empty, never a count
      flowsUsing: undefined as unknown as number,
      updated: dateWord(latest?.createdAt, false)
    };
  });
}

function toDetail(row: Row, version: ContractVersion | null): ContractDetailVM {
  const schema = version?.jsonSchema ?? null;
  return {
    name: row.name,
    version: version?.versionLabel ?? row.version,
    format: 'JSON',
    updated: dateWord(version?.createdAt, true),
    usedBy: [],
    schemaCode: schema ? JSON.stringify(schema, null, 2) : '',
    schemaLanguage: 'json',
    validationRules: schemaFields(schema ?? undefined).map((field) => ({
      field: field.name,
      rule: field.required ? `Required, ${field.type}` : field.type
    }))
  };
}

export function ContractsPage() {
  const contracts = useContracts();
  const [compared, setCompared] = useState<Compared[]>([]);
  const [refusal, setRefusal] = useState<CardRefusal | null>(null);
  const [harnessApprover, setHarnessApprover] = useState(false);
  const [importing, setImporting] = useState(false);
  const [detail, setDetail] = useState<Detail | null>(null);

  useEffect(() => {
    void loadHarnessConfig().then(() => setHarnessApprover(hasHarnessLogin('approver')));
  }, []);

  const list = contracts.data ?? [];
  const rows = toRows(list);
  const detailRow = detail ? rows.find((row) => row.key === detail.key) ?? null : null;

  const onRowSelect = (selected: ContractRowVM) => {
    const row = rows.find((candidate) => candidate.name === selected.name);
    if (!row) return;
    if (!row.latestId) {
      setDetail({ key: row.key, loading: false, error: null, version: null });
      return;
    }
    const versionId = row.latestId;
    setDetail({ key: row.key, loading: true, error: null, version: null });
    void readContractVersion(versionId).then((result) => {
      setDetail((current) =>
        current?.key !== row.key
          ? current
          : result.ok
            ? { key: row.key, loading: false, error: null, version: result.data }
            : { key: row.key, loading: false, error: refusalWords(result.status, 'this contract version'), version: null }
      );
    });
  };

  const onDownloadSchema = () => {
    const schema = detail?.version?.jsonSchema;
    if (!schema || !detailRow) return;
    const blob = new Blob([JSON.stringify(schema, null, 2)], { type: 'application/schema+json' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${detailRow.name}-${detail.version?.versionLabel ?? 'schema'}.json`.replace(/[^A-Za-z0-9._-]+/g, '_');
    link.click();
    URL.revokeObjectURL(url);
  };

  /** The card (system/role) a version belongs to - where its refusal is worded. */
  const cardOf = (versionId: string) =>
    contractKey(list.find((contract) => (contract.versions ?? []).some((version) => version.id === versionId)) ?? {});

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
    contracts.reload();
  };

  const onApprove = async (versionId: string, asLogin?: string) => {
    setRefusal(null);
    const result = await approveContractVersion(versionId, asLogin);
    if (!result.ok) {
      const code =
        result.refusal === 'NOT_PENDING_APPROVAL' ? 'not-pending-approval' : (result.error ?? `approve-refused-${result.status}`);
      setRefusal({ contractKey: cardOf(versionId), code });
      return;
    }
    contracts.reload();
  };

  const listStatus = contracts.status;

  return (
    <div
      data-page="contracts"
      data-contracts-loaded={String(!contracts.loading)}
      data-refusal={listStatus !== null ? `list-refused-${listStatus}` : undefined}
    >
      <ContractsScreen
        rows={rows}
        loading={contracts.loading}
        error={listStatus !== null ? refusalWords(listStatus, 'the contracts list') : null}
        onRetry={contracts.reload}
        onRowSelect={onRowSelect}
        importAction="open-import"
        emptyImportAction="import-from-empty"
        importOpen={false}
        onImportOpen={() => setImporting(true)}
        detailOpen={detail !== null}
        detail={detail && detailRow ? toDetail(detailRow, detail.version) : null}
        detailLoading={detail?.loading ?? false}
        detailError={detail?.error ?? null}
        onDetailClose={() => setDetail(null)}
        onDownloadSchema={onDownloadSchema}
        onEditContract={() => {
          setDetail(null);
          setImporting(true);
        }}
      />

      {list.length > 0 && (
        <section className="contracts-page" data-contract-versions aria-label="Contract versions">
          <h2>Contract versions</h2>
          <ContractList
            contracts={list}
            refusal={refusal}
            actions={{
              compared: new Set(compared.map((entry) => entry.id)),
              onCompare,
              onSubmit: (id, note) => void onSubmit(id, note),
              onApprove: (id, asLogin) => void onApprove(id, asLogin),
              harnessApprover
            }}
          />
          <SideBySide compared={compared} />
        </section>
      )}

      <Drawer open={importing} title="Import a contract version" onClose={() => setImporting(false)} id="import-contract">
        <ImportContractForm onImported={async () => contracts.reload()} />
      </Drawer>
    </div>
  );
}
