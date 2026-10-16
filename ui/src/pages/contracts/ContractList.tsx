// T-160 (E5 lane D, E5-F2-S1): every contract of the tenant with its versions, each version a
// row the configurator compares, submits with a change note (DRAFT) or approves (PENDING_APPROVAL).
// "Approve as the harness approver" renders only while the screen-test harness exposes its
// second binding (/console-config.json); the backend authorizes for real.
// Row 310 lane C (T101, 10-screens §6): one card per system/role (sorted), versions in the table
// recipe with worded status badges; the change note is no longer an empty field on every draft row -
// "Submit for approval" opens a small inline group (note + Submit); a refused action is worded at
// the top of the card it concerns.

import { useState } from 'react';
import type { Contract, ContractVersionSummary } from '../../api/contracts';
import { contractGroups, statusBadge } from './contracts-view';

export const versionKey = (contract: Contract, version: ContractVersionSummary) =>
  `${contract.system ?? ''}/${contract.role ?? ''}/${version.versionLabel ?? ''}`;

/** A refused submit or approve, kept with the card (system/role) whose version it concerns. */
export type CardRefusal = { contractKey: string; code: string };

export type VersionActions = {
  compared: Set<string>;
  onCompare: (versionId: string, key: string, checked: boolean) => void;
  onSubmit: (versionId: string, changeNote: string) => void;
  onApprove: (versionId: string, asLogin?: string) => void;
  harnessApprover: boolean;
};

function RefusalAlert({ code }: { code: string }) {
  if (code === 'author-cannot-approve') {
    return (
      <p role="alert" className="contracts-alert contracts-alert-danger" data-refusal="author-cannot-approve">
        Refused: the author cannot approve their own version (AUTHOR_IS_APPROVER).
      </p>
    );
  }
  if (code === 'not-pending-approval') {
    return (
      <p role="alert" className="contracts-alert contracts-alert-danger" data-refusal="not-pending-approval">
        Refused: only a submitted version can be approved (NOT_PENDING_APPROVAL).
      </p>
    );
  }
  return (
    <p role="alert" className="contracts-alert contracts-alert-danger" data-refusal="action-refused">
      The API refused this action: {code}
    </p>
  );
}

function VersionRow({ contract, version, actions }: { contract: Contract; version: ContractVersionSummary; actions: VersionActions }) {
  const [changeNote, setChangeNote] = useState('');
  const [composing, setComposing] = useState(false);
  const id = version.id ?? '';
  const key = versionKey(contract, version);
  const badge = statusBadge(version.status);
  const compared = actions.compared.has(id);
  return (
    <tr className={compared ? 'contracts-row contracts-row-compared' : 'contracts-row'} data-version-key={key} data-version-status={version.status}>
      <td className="contracts-check">
        <input
          type="checkbox"
          data-action="compare"
          aria-label={`Compare ${key}`}
          checked={compared}
          onChange={(e) => actions.onCompare(id, key, e.target.checked)}
        />
      </td>
      <td className="contracts-mono">{version.versionLabel}</td>
      <td>
        <span className={`badge badge-${badge.tone}`}>{badge.word}</span>
      </td>
      <td className="contracts-muted-cell">{version.createdAt ? new Date(version.createdAt).toLocaleString() : '-'}</td>
      <td className="contracts-actions">
        {version.status === 'DRAFT' && (
          <>
            <button
              type="button"
              className="btn"
              data-action="compose-submit"
              aria-expanded={composing}
              onClick={() => setComposing((open) => !open)}
            >
              Submit for approval
            </button>
            {composing && (
              <span className="contracts-note-group">
                <input
                  data-field="change-note"
                  aria-label={`Change note for ${key}`}
                  placeholder="Change note"
                  value={changeNote}
                  onChange={(e) => setChangeNote(e.target.value)}
                />
                <button type="button" className="btn" data-action="submit-version" onClick={() => actions.onSubmit(id, changeNote)}>
                  Submit
                </button>
              </span>
            )}
          </>
        )}
        {version.status === 'PENDING_APPROVAL' && (
          <>
            <button type="button" className="btn" data-action="approve-version" onClick={() => actions.onApprove(id)}>
              Approve
            </button>
            {actions.harnessApprover && (
              <button type="button" className="btn" data-action="approve-as-approver" onClick={() => actions.onApprove(id, 'approver')}>
                Approve as the harness approver
              </button>
            )}
          </>
        )}
      </td>
    </tr>
  );
}

export function ContractList({
  contracts,
  actions,
  refusal
}: {
  contracts: Contract[];
  actions: VersionActions;
  refusal: CardRefusal | null;
}) {
  return (
    <section className="contracts-list" data-contract-list>
      {contractGroups(contracts).map((group) => (
        <article key={group.contract.contractId ?? group.contract.id ?? group.key} className="contracts-card" data-contract={group.key}>
          <header className="contracts-card-head">
            <h3>
              <span className="contracts-mono">{group.contract.system}</span> - {group.contract.role}
            </h3>
            {group.contract.name && <span className="contracts-muted-cell">{group.contract.name}</span>}
          </header>
          {refusal?.contractKey === group.key && <RefusalAlert code={refusal.code} />}
          <div className="contracts-table-wrap">
            <table className="table">
              <caption className="contracts-sr-only">
                Versions of {group.contract.system} - {group.contract.role}
              </caption>
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
                {(group.contract.versions ?? []).map((version) => (
                  <VersionRow key={version.id} contract={group.contract} version={version} actions={actions} />
                ))}
              </tbody>
            </table>
          </div>
        </article>
      ))}
    </section>
  );
}
