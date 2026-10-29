// Console adapters (week 6 mount): thin glue between the presentational screens and the shared
// query hooks. App.tsx is intentionally untouched here.
import * as React from 'react';
import { Screen } from '../Screen';
import { useSystems, useContracts } from '../../api/hooks';
import type { SystemView } from '../../api/systems';
import type { Contract } from '../../api/contracts';
import type { SystemRow } from '../../screens/systems/types';
import SystemsScreen from '../../screens/systems/SystemsScreen';
import ContractsScreen from '../../screens/contracts/ContractsScreen';
import type { ContractRowVM } from '../../screens/contracts/ContractsScreen';

function systemRow(raw: SystemView): SystemRow {
  const candidate = raw as unknown as {
    id?: string;
    code?: string;
    name?: string;
    status?: string;
    type?: string | null;
    protocol?: string;
  };
  return {
    id: String(candidate.id ?? candidate.code ?? ''),
    name: String(candidate.name ?? candidate.code ?? ''),
    code: String(candidate.code ?? candidate.id ?? ''),
    status: (candidate.status ?? 'Unknown') as SystemRow['status'],
    protocol: String(candidate.protocol ?? candidate.type ?? 'REST API') as SystemRow['protocol']
  };
}

function contractRow(raw: Contract, index: number): ContractRowVM {
  const candidate = raw as unknown as {
    code?: string;
    contractId?: string;
    id?: string;
    name?: string;
    format?: string;
    versions?: { versionLabel?: string; createdAt?: string }[];
  };
  const first = (candidate.versions ?? [])[0];
  return {
    name: String(candidate.name ?? candidate.code ?? candidate.contractId ?? candidate.id ?? `contract-${index}`),
    version: String(first?.versionLabel ?? candidate.code ?? ''),
    format: (candidate.format ?? 'XML') as ContractRowVM['format'],
    flowsUsing: 0,
    updated: String(first?.createdAt ?? '')
  };
}

export function SystemsRoute(): React.ReactElement {
  const systems = useSystems();
  return (
    <Screen title="Systems">
      <SystemsScreen
        systems={systems.data ? systems.data.map(systemRow) : null}
        loading={systems.loading}
        error={systems.error ?? systems.refusal ?? ''}
        stale={systems.stale}
        onRetry={systems.reload}
      />
    </Screen>
  );
}

export function ContractsRoute(): React.ReactElement {
  const contracts = useContracts();
  return (
    <Screen title="Contracts">
      <ContractsScreen
        rows={contracts.data ? contracts.data.map(contractRow) : []}
        loading={contracts.loading}
        error={contracts.error ?? contracts.refusal ?? null}
        onRetry={contracts.reload}
      />
    </Screen>
  );
}

export default { SystemsRoute, ContractsRoute };
