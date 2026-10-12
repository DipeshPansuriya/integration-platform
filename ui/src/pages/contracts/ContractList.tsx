// T-160 (E5 lane D, E5-F2-S1): every contract of the tenant with its versions, each version a
// row the configurator compares, submits with a change note (DRAFT) or approves (PENDING_APPROVAL).
// "Approve as the harness approver" renders only while the screen-test harness exposes its
// second binding (/console-config.json); the backend authorizes for real.

import { useState } from 'react';
import type { Contract, ContractVersionSummary } from '../../api/contracts';

export const versionKey = (contract: Contract, version: ContractVersionSummary) =>
  `${contract.system ?? ''}/${contract.role ?? ''}/${version.versionLabel ?? ''}`;

export type VersionActions = {
  compared: Set<string>;
  onCompare: (versionId: string, key: string, checked: boolean) => void;
  onSubmit: (versionId: string, changeNote: string) => void;
  onApprove: (versionId: string, asLogin?: string) => void;
  harnessApprover: boolean;
};

function VersionRow({ contract, version, actions }: { contract: Contract; version: ContractVersionSummary; actions: VersionActions }) {
  const [changeNote, setChangeNote] = useState('');
  const id = version.id ?? '';
  const key = versionKey(contract, version);
  return (
    <tr data-version-key={key} data-version-status={version.status}>
      <td>
        <input
          type="checkbox"
          data-action="compare"
          aria-label={`Compare ${key}`}
          checked={actions.compared.has(id)}
          onChange={(e) => actions.onCompare(id, key, e.target.checked)}
        />
      </td>
      <td>{version.versionLabel}</td>
      <td>{version.status}</td>
      <td>{version.createdAt ? new Date(version.createdAt).toLocaleString() : '-'}</td>
      <td>
        {version.status === 'DRAFT' && (
          <>
            <input
              data-field="change-note"
              aria-label={`Change note for ${key}`}
              placeholder="Change note"
              value={changeNote}
              onChange={(e) => setChangeNote(e.target.value)}
            />
            <button type="button" data-action="submit-version" onClick={() => actions.onSubmit(id, changeNote)}>
              Submit for approval
            </button>
          </>
        )}
        {version.status === 'PENDING_APPROVAL' && (
          <>
            <button type="button" data-action="approve-version" onClick={() => actions.onApprove(id)}>
              Approve
            </button>
            {actions.harnessApprover && (
              <button type="button" data-action="approve-as-approver" onClick={() => actions.onApprove(id, 'approver')}>
                Approve as the harness approver
              </button>
            )}
          </>
        )}
      </td>
    </tr>
  );
}

export function ContractList({ contracts, actions }: { contracts: Contract[]; actions: VersionActions }) {
  return (
    <section data-contract-list>
      {contracts.map((contract) => (
        <article key={contract.contractId ?? contract.id} data-contract={`${contract.system}/${contract.role}`}>
          <h3>
            {contract.system} - {contract.role} <small>{contract.name}</small>
          </h3>
          <table>
            <thead>
              <tr>
                <th>Compare</th>
                <th>Version</th>
                <th>Status</th>
                <th>Created</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {(contract.versions ?? []).map((version) => (
                <VersionRow key={version.id} contract={contract} version={version} actions={actions} />
              ))}
            </tbody>
          </table>
        </article>
      ))}
    </section>
  );
}
