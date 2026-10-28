// Row 575 S5 (guided flow, plan S5): the Approvals list rows - flow versions that are SUBMITTED and contracts that have a version
// waiting for approval, from the lists Home already reads. One row per flow and one per contract (a contract with two versions
// waiting is one row that says so), so the count equals Home's "Waiting for your approval" block. Approving is unchanged: each row
// links to the existing screen. No DOM, no fetch.

import { statusWord } from '../ui/status-words';

type FlowLike = { id: string; code?: string | null; name?: string | null; status?: string | null };
type ContractLike = {
  id?: string | null;
  contractId?: string | null;
  code?: string | null;
  name?: string | null;
  role?: string | null;
  system?: string | null;
  versions?: ReadonlyArray<{ status?: string | null; versionLabel?: string | null }> | null;
};

export type ApprovalRow = {
  kind: 'flow' | 'contract';
  key: string;
  what: string;
  name: string;
  detail: string;
  statusWord: string;
  href: string;
};

export function approvalRows(flows: readonly FlowLike[], contracts: readonly ContractLike[]): ApprovalRow[] {
  const rows: ApprovalRow[] = [];
  for (const f of flows) {
    if (f.status !== 'SUBMITTED') continue;
    rows.push({
      kind: 'flow',
      key: f.id,
      what: 'Flow version',
      name: f.name || f.code || f.id,
      detail: f.code ? `Flow ${f.code}` : 'Flow',
      statusWord: statusWord('flowVersion', 'SUBMITTED').word,
      href: `#/versions/${encodeURIComponent(f.id)}`
    });
  }
  contracts.forEach((c, index) => {
    const waiting = (c.versions ?? []).filter((v) => v.status === 'PENDING_APPROVAL');
    if (waiting.length === 0) return;
    const role = c.role ? statusWord('role', c.role).word : '';
    const which = waiting.length === 1 ? `version ${waiting[0].versionLabel ?? ''}`.trim() : `${waiting.length} versions`;
    rows.push({
      kind: 'contract',
      key: c.id ?? c.contractId ?? c.code ?? String(index),
      what: 'Contract',
      name: c.name || c.code || 'Contract',
      detail: [role, c.system, which].filter(Boolean).join(' - '),
      statusWord: statusWord('contractVersion', 'PENDING_APPROVAL').word,
      href: '#/contracts' // the Contracts screen takes no filter yet; the row names the contract and its version
    });
  });
  return rows;
}
